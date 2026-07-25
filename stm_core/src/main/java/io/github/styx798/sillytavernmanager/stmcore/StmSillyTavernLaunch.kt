package io.github.styx798.sillytavernmanager.stmcore

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class StmSillyTavernPreparedLaunch(
    val launchSpec: FeatherEngineLaunchSpec,
    val selectedPort: Int,
    val programRoot: File,
    val dataRoot: File,
)

/**
 * Builds the exact real-SillyTavern launch contract without granting the runtime any mutable
 * program path. The program slot is read-only by policy; config, data, temp files, and logs are
 * assigned outside it.
 */
internal object StmSillyTavernLaunchFactory {
    fun prepare(
        slotRoot: File,
        archiveRoot: String,
        dataRoot: File,
        sessionDirectory: File,
        logsRoot: File,
        expectedVersion: String,
    ): StmSillyTavernPreparedLaunch {
        require(expectedVersion.isNotBlank()) { "A real SillyTavern launch requires a version" }
        require(archiveRoot.isNotBlank()) { "A real SillyTavern launch requires an archive root" }
        val slot = requireRealDirectory(slotRoot.toPath(), "SillyTavern immutable slot")
        val programCandidate = slot.resolve(archiveRoot).normalize()
        require(programCandidate.parent == slot) {
            "SillyTavern archive root must be one direct slot child"
        }
        val program = requireRealDirectory(programCandidate, "SillyTavern program root")
        val data = ensureRealDirectory(dataRoot.toPath(), "SillyTavern data root")
        val session = ensureRealDirectory(sessionDirectory.toPath(), "Feather Engine session root")
        val logs = ensureRealDirectory(logsRoot.toPath(), "STM Core logs root")
        val temp = ensureRealDirectory(session.resolve(TEMP_DIRECTORY), "SillyTavern temp root")

        val serverFile = requireRegularFile(program.resolve(SERVER_FILE), "SillyTavern server.js")
        val defaultConfig = requireRegularFile(
            program.resolve(DEFAULT_CONFIG_FILE),
            "SillyTavern default config",
        )
        val adapterFile = requireRegularFile(
            program.resolve(WEBPACK_ADAPTER_TARGET),
            "SillyTavern Webpack adapter",
        )
        val runtime = requireRealDirectory(
            slot.resolve(RUNTIME_DIRECTORY),
            "STM runtime evidence",
        )
        val signedAdapter = requireRegularFile(
            runtime.resolve(RUNTIME_ADAPTER_FILE),
            "signed Webpack adapter sidecar",
        )
        val prebuiltBundle = requireRegularFile(
            runtime.resolve(RUNTIME_BUNDLE_FILE),
            "signed prebuilt lib.js",
        )
        val configFile = prepareConfig(defaultConfig, data.resolve(CONFIG_FILE))
        val logFile = prepareLogFile(logs.resolve(NODE_LOG_FILE))
        val selectedPort = reservePreferredLoopbackPort()
        val loaderFile = session.resolve(LOADER_FILE)

        return StmSillyTavernPreparedLaunch(
            launchSpec = createLaunchSpec(
                programRoot = program,
                dataRoot = data,
                tempRoot = temp,
                configFile = configFile,
                logFile = logFile,
                serverFile = serverFile,
                loaderFile = loaderFile,
                adapterFile = adapterFile,
                signedAdapter = signedAdapter,
                prebuiltBundle = prebuiltBundle,
                selectedPort = selectedPort,
                expectedVersion = expectedVersion,
            ),
            selectedPort = selectedPort,
            programRoot = program.toFile(),
            dataRoot = data.toFile(),
        )
    }

    private fun createLaunchSpec(
        programRoot: Path,
        dataRoot: Path,
        tempRoot: Path,
        configFile: Path,
        logFile: Path,
        serverFile: Path,
        loaderFile: Path,
        adapterFile: Path,
        signedAdapter: Path,
        prebuiltBundle: Path,
        selectedPort: Int,
        expectedVersion: String,
    ): FeatherEngineLaunchSpec {
        val bootstrap =
            """
            (() => {
              const fs = require('node:fs');
              const http = require('node:http');
              const util = require('node:util');
              const vm = require('node:vm');
              const crypto = require('node:crypto');
              const { registerHooks } = require('node:module');
              const { pathToFileURL } = require('node:url');
              const lexicalValue = 40;
              const evalProbe = eval('lexicalValue + 2');
              const functionProbe = Function('left', 'right', 'return left + right;')(20, 22);
              const nativeFunctionUnchanged = Function.prototype.toString
                .call(Function)
                .includes('[native code]');
              if (evalProbe !== 42 || functionProbe !== 42 || !nativeFunctionUnchanged) {
                throw new Error('Feather Engine code-generation policy failed its ST preflight');
              }
              const state = globalThis.__stmCore = {
                servers: [],
                server: null,
                port: 0,
                closed: false,
                error: '',
                requestCount: 0,
                lastRequest: '',
                importSettled: false,
                forbiddenModuleLoads: 0,
                logs: [],
                originalCwd: process.cwd(),
                originalCreateServer: http.createServer,
                originalConsole: {},
                originalEnv: {
                  NODE_ENV: process.env.NODE_ENV,
                  TMPDIR: process.env.TMPDIR,
                  TMP: process.env.TMP,
                  TEMP: process.env.TEMP,
                  STM_PREBUILT_LIB_JS: process.env.STM_PREBUILT_LIB_JS,
                },
              };
              const logFile = ${jsString(logFile.toString())};
              const format = value => {
                if (value instanceof Error) return String(value.stack || value.message || value);
                if (typeof value === 'string') return value;
                return util.inspect(value, { depth: 3, maxArrayLength: 50, breakLength: 160 });
              };
              const append = (level, values) => {
                const line = '[' + new Date().toISOString() + '] [' + level + '] ' +
                  values.map(format).join(' ');
                state.logs.push(line);
                if (state.logs.length > 200) state.logs.shift();
                try {
                  if (fs.existsSync(logFile) && fs.statSync(logFile).size < $MAX_NODE_LOG_BYTES) {
                    fs.appendFileSync(logFile, line + '\n', 'utf8');
                  }
                } catch (_) {}
                if (level === 'error' &&
                    line.includes('A critical error has occurred while starting the server')) {
                  state.error = line;
                }
              };
              for (const level of ['log', 'info', 'warn', 'error']) {
                const original = console[level].bind(console);
                state.originalConsole[level] = original;
                console[level] = (...values) => {
                  append(level, values);
                  original(...values);
                };
              }
              state.onUncaughtException = error => {
                state.error = 'uncaughtException: ' + format(error);
                append('error', [state.error]);
              };
              state.onUnhandledRejection = error => {
                state.error = 'unhandledRejection: ' + format(error);
                append('error', [state.error]);
              };
              process.prependListener('uncaughtException', state.onUncaughtException);
              process.prependListener('unhandledRejection', state.onUnhandledRejection);

              const adapterPath = fs.realpathSync(${jsString(adapterFile.toString())});
              const signedAdapterPath = fs.realpathSync(${jsString(signedAdapter.toString())});
              const adapterBytes = fs.readFileSync(adapterPath);
              const signedAdapterBytes = fs.readFileSync(signedAdapterPath);
              if (adapterBytes.length !== signedAdapterBytes.length ||
                  !crypto.timingSafeEqual(adapterBytes, signedAdapterBytes)) {
                throw new Error('Installed Webpack adapter does not match its signed sidecar');
              }
              const bundlePath = fs.realpathSync(${jsString(prebuiltBundle.toString())});
              if (!fs.statSync(bundlePath).isFile()) {
                throw new Error('Signed prebuilt lib.js is not a regular file');
              }
              const webpackConfigUrl = pathToFileURL(
                fs.realpathSync(${jsString(programRoot.resolve(WEBPACK_CONFIG_FILE).toString())})
              ).href;
              registerHooks({
                load(url, context, nextLoad) {
                  if (url === webpackConfigUrl ||
                      url.includes('/node_modules/webpack/') ||
                      url.includes('/node_modules/terser-webpack-plugin/') ||
                      url.includes('/node_modules/terser/')) {
                    state.forbiddenModuleLoads += 1;
                    throw new Error('Forbidden runtime Webpack module load: ' + url);
                  }
                  return nextLoad(url, context);
                },
              });

              http.createServer = function(...args) {
                const server = state.originalCreateServer.apply(this, args);
                state.servers.push(server);
                state.server = server;
                server.on('request', request => {
                  state.requestCount += 1;
                  state.lastRequest = String(request.method) + ' ' + String(request.url);
                });
                server.on('listening', () => {
                  const address = server.address();
                  if (address && typeof address === 'object') state.port = Number(address.port || 0);
                });
                server.on('close', () => {
                  if (state.servers.every(item => !item.listening)) state.closed = true;
                });
                server.on('error', error => {
                  state.error = 'serverError: ' + format(error);
                  append('error', [state.error]);
                });
                return server;
              };
              process.env.NODE_ENV = 'production';
              process.env.TMPDIR = ${jsString(tempRoot.toString())};
              process.env.TMP = ${jsString(tempRoot.toString())};
              process.env.TEMP = ${jsString(tempRoot.toString())};
              process.env.STM_PREBUILT_LIB_JS = bundlePath;
              process.chdir(${jsString(programRoot.toString())});
              append('info', ['STM real SillyTavern bootstrap', JSON.stringify(process.argv)]);
              const importExpression = ${jsString("import(${jsString(serverFile.toUri().toString())})")};
              const loader = new vm.Script(importExpression, {
                filename: ${jsString(loaderFile.toString())},
                importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER,
              });
              Promise.resolve(loader.runInThisContext()).then(
                () => { state.importSettled = true; },
                error => {
                  state.error = 'serverImport: ' + format(error);
                  append('error', [state.error]);
                },
              );
            })();
            """.trimIndent()
        val stopScript =
            """
            (() => {
              const state = globalThis.__stmCore;
              if (!state || !Array.isArray(state.servers) || state.servers.length === 0) {
                if (state) state.closed = true;
                return;
              }
              let pending = 0;
              const completed = () => {
                pending -= 1;
                if (pending <= 0) state.closed = true;
              };
              for (const server of state.servers) {
                if (server && server.listening) {
                  pending += 1;
                  server.close(completed);
                }
              }
              if (pending === 0) state.closed = true;
            })();
            """.trimIndent()
        val cleanupScript =
            """
            (() => {
              const state = globalThis.__stmCore;
              if (!state) return;
              const http = require('node:http');
              if (state.originalCreateServer) http.createServer = state.originalCreateServer;
              if (state.onUncaughtException) {
                process.removeListener('uncaughtException', state.onUncaughtException);
              }
              if (state.onUnhandledRejection) {
                process.removeListener('unhandledRejection', state.onUnhandledRejection);
              }
              for (const level of ['log', 'info', 'warn', 'error']) {
                if (state.originalConsole[level]) console[level] = state.originalConsole[level];
              }
              for (const name of ['NODE_ENV', 'TMPDIR', 'TMP', 'TEMP', 'STM_PREBUILT_LIB_JS']) {
                const value = state.originalEnv[name];
                if (value === undefined) delete process.env[name]; else process.env[name] = value;
              }
              if (state.originalCwd) process.chdir(state.originalCwd);
            })();
            """.trimIndent()
        return FeatherEngineLaunchSpec(
            consoleArguments = arrayOf(
                serverFile.toString(),
                "--dataRoot",
                dataRoot.toString(),
                "--configPath",
                configFile.toString(),
                "--port",
                selectedPort.toString(),
                "--listen",
                "false",
                "--browserLaunchEnabled",
                "false",
                "--enableIPv4",
                "true",
                "--enableIPv6",
                "false",
            ),
            bootstrapScript = bootstrap,
            startupErrorExpression = "globalThis.__stmCore?.error || ''",
            readinessPortExpression = "globalThis.__stmCore?.port || 0",
            stopScript = stopScript,
            closedExpression = "Boolean(globalThis.__stmCore?.closed)",
            diagnosticsExpression =
                """
                (() => {
                  const state = globalThis.__stmCore;
                  return 'requests=' + String(state?.requestCount || 0) +
                    ', last=' + String(state?.lastRequest || '') +
                    ', importSettled=' + String(state?.importSettled || false) +
                    ', forbiddenModuleLoads=' + String(state?.forbiddenModuleLoads || 0) +
                    ', error=' + String(state?.error || '') +
                    ', logs=' + String((state?.logs || []).slice(-8).join(' || '));
                })();
                """.trimIndent(),
            cleanupScript = cleanupScript,
            readinessProbe = StmSillyTavernVersionProbe(expectedVersion)::execute,
        )
    }

    private fun prepareConfig(defaultConfig: Path, configFile: Path): Path {
        if (Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) {
            return requireRegularFile(configFile, "SillyTavern config")
        }
        val source = Files.readAllBytes(defaultConfig).toString(Charsets.UTF_8)
        val expected = "git:\n  backend: auto"
        require(source.windowed(expected.length).count { it == expected } == 1) {
            "SillyTavern default config has an unexpected git backend shape"
        }
        val configured = source.replace(expected, "git:\n  backend: builtin")
        val temporary = configFile.resolveSibling("${configFile.fileName}.stm-part")
        require(!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            "A stale SillyTavern config temporary file is present"
        }
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(configured.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE)
            } catch (error: Exception) {
                throw IllegalStateException("SillyTavern config could not be atomically created", error)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return requireRegularFile(configFile, "SillyTavern config")
    }

    private fun prepareLogFile(logFile: Path): Path {
        if (!Files.exists(logFile, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(logFile)
        }
        return requireRegularFile(logFile, "SillyTavern Core log")
    }

    private fun reservePreferredLoopbackPort(): Int =
        runCatching { reserveLoopbackPort(PREFERRED_PORT) }
            .getOrElse { reserveLoopbackPort(0) }

    private fun reserveLoopbackPort(port: Int): Int = ServerSocket().use { socket ->
        // Node enables address reuse for its HTTP listener. Match that behavior so a clean prior
        // session's TIME_WAIT sockets do not force an origin change; an actual listener still
        // makes bind() fail and triggers the random-port fallback.
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        socket.localPort
    }

    private fun ensureRealDirectory(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            val parent = requireNotNull(absolute.parent) { "$label has no parent" }
            requireRealDirectory(parent, "$label parent")
            Files.createDirectory(absolute)
        }
        return requireRealDirectory(absolute, label)
    }

    private fun requireRealDirectory(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        require(
            Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(absolute) &&
                Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS),
        ) {
            "$label must be a real no-follow directory"
        }
        return absolute
    }

    private fun requireRegularFile(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        require(
            Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(absolute) &&
                Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS),
        ) {
            "$label must be a regular no-follow file"
        }
        return absolute
    }

    private fun jsString(value: String): String = buildString(value.length + 2) {
        append('\'')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
        append('\'')
    }

    private const val SERVER_FILE = "server.js"
    private const val DEFAULT_CONFIG_FILE = "default/config.yaml"
    private const val CONFIG_FILE = "config.yaml"
    private const val WEBPACK_CONFIG_FILE = "webpack.config.js"
    private const val WEBPACK_ADAPTER_TARGET = "src/middleware/webpack-serve.js"
    private const val RUNTIME_DIRECTORY = ".stm-runtime"
    private const val RUNTIME_ADAPTER_FILE = "webpack-serve.adapter.js"
    private const val RUNTIME_BUNDLE_FILE = "lib.js"
    private const val TEMP_DIRECTORY = "tmp"
    private const val LOADER_FILE = "stm-sillytavern-loader.cjs"
    private const val NODE_LOG_FILE = "sillytavern-node.log"
    private const val PREFERRED_PORT = 8000
    private const val MAX_NODE_LOG_BYTES = 2_000_000
}

private class StmSillyTavernVersionProbe(
    private val expectedVersion: String,
) {
    fun execute(baseUrl: String): LoopbackProbeResult {
        val response = when (val result = LoopbackHealthProbe.capture(baseUrl, "/version")) {
            is LoopbackProbeResult.Failed -> return result
            is LoopbackProbeResult.Healthy -> result.response
        }
        if (response.statusCode != 200) {
            return LoopbackProbeResult.Failed(
                "SillyTavern /version returned HTTP ${response.statusCode}",
                response,
            )
        }
        val version = runCatching {
            JSONObject(response.bodyUtf8()).getString("pkgVersion")
        }.getOrElse { error ->
            return LoopbackProbeResult.Failed(
                "SillyTavern /version returned invalid JSON: ${error.message}",
                response,
            )
        }
        return if (version == expectedVersion) {
            LoopbackProbeResult.Healthy(response)
        } else {
            LoopbackProbeResult.Failed(
                "SillyTavern /version returned unexpected pkgVersion=$version",
                response,
            )
        }
    }
}
