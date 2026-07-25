package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.enums.V8RuntimeTerminationMode
import com.caoccao.javet.interop.NodeRuntime
import io.github.styx798.sillytavernmanager.stmcore.FeatherEngine
import io.github.styx798.sillytavernmanager.stmcore.FeatherEngineLaunchSpec
import io.github.styx798.sillytavernmanager.stmcore.StmNodeRuntimeFactory
import io.github.styx798.sillytavernmanager.stmcore.StmSillyTavernLaunchFactory
import io.github.styx798.sillytavernmanager.stmcore.installer.ArtifactIdentity
import io.github.styx798.sillytavernmanager.stmcore.installer.ArtifactIntegrityResult
import io.github.styx798.sillytavernmanager.stmcore.installer.ArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.installer.StmArtifactVerifier
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyCandidate
import io.github.styx798.sillytavernmanager.stmcore.installer.StmExtractionCancellation
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSafeZipExtractor
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSillyTavernSourceInspectionResult
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSillyTavernSourceInspector
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal data class Gate3bRunnableSource(
    val payloadRoot: File,
    val programRoot: File,
    val archiveRoot: String,
    val sourceArchiveSha256: String,
    val sourceArchiveBytes: Long,
    val sourceEntries: Int,
    val sourceBytes: Long,
    val programFingerprint: Gate3bTreeFingerprint,
)

internal data class Gate3bRunnableRuntimeEvidence(
    val adapterSha256: String,
    val adapterBytes: Long,
    val bundleSha256: String,
    val bundleBytes: Long,
    val bundleLicenseSha256: String,
    val bundleLicenseBytes: Long,
    val provenanceSha256: String? = null,
    val provenanceBytes: Long = 0,
)

internal data class Gate3bLocalBundleOutput(
    val webpackRoot: Path,
    val outputRoot: Path,
    val bundle: Path,
    val bundleLicense: Path,
    val cacheVersion: String,
    val bundleSha256: String,
    val bundleBytes: Long,
    val bundleLicenseSha256: String,
    val bundleLicenseBytes: Long,
    val distExistedBefore: Boolean,
)

internal data class Gate3bLocalBundleBuildResult(
    val output: Gate3bLocalBundleOutput,
    val nodeVersion: String,
    val webpackVersion: String,
    val elapsedMillis: Long,
    val logSha256: String,
    val logCharacters: Int,
    val inputSha256: Map<String, String>,
    val dependencyTreeSha256: String,
    val processCwdRestored: Boolean,
    val buildRuntimeNonce: String,
    val installerStateAbsent: Boolean,
    val distinctFromInstallerRuntime: Boolean,
    val compilerRunCalls: Int,
    val compilerCallbackCalls: Int,
    val compilerErrorAbsent: Boolean,
    val compilerStatsPresent: Boolean,
    val compilerStatsHasErrors: Boolean,
    val compilerStatsErrorCount: Int,
    val compilerLibAssetPresent: Boolean,
    val compilerCloseCalls: Int,
    val compilerCloseCallbackCalls: Int,
    val compilerCloseErrorAbsent: Boolean,
    val compilerCloseSameInstance: Boolean,
    val buildRuntimeClosed: Boolean,
)

internal fun requireSuccessfulGate3bWebpackCompiler(
    runCalls: Int,
    callbackCalls: Int,
    errorAbsent: Boolean,
    statsPresent: Boolean,
    statsHasErrors: Boolean,
    statsErrorCount: Int,
    libAssetPresent: Boolean,
    closeCalls: Int,
    closeCallbackCalls: Int,
    closeErrorAbsent: Boolean,
    closeSameInstance: Boolean,
) {
    check(
        runCalls == 1 &&
            callbackCalls == 1 &&
            errorAbsent &&
            statsPresent &&
            !statsHasErrors &&
            statsErrorCount == 0 &&
            libAssetPresent &&
            closeCalls == 1 &&
            closeCallbackCalls == 1 &&
            closeErrorAbsent &&
            closeSameInstance
    ) {
        "Webpack compiler lifecycle did not prove one error-free closed lib.js build"
    }
}

internal fun requireGate3bNotCancelled(cancelled: AtomicBoolean, operation: String) {
    check(!cancelled.get()) { "$operation was cancelled" }
}

internal class Gate3bBundleTerminationGate(
    private val terminateExecution: () -> Unit,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val completed = CountDownLatch(1)

    @Volatile
    private var requested = false

    @Volatile
    private var closed = false

    fun request(): Boolean {
        synchronized(lifecycleLock) {
            if (requested || closed) return false
            requested = true
        }
        try {
            terminateExecution()
        } finally {
            completed.countDown()
        }
        return true
    }

    fun wasRequested(): Boolean = requested

    override fun close() {
        val shouldAwait = synchronized(lifecycleLock) {
            closed = true
            requested
        }
        if (shouldAwait) awaitGate3bLatchUninterruptibly(completed)
    }
}

internal class Gate3bBundleBuildWatchdog(
    private val timeoutMillis: Long,
    private val cancelled: () -> Boolean,
    private val terminationGate: Gate3bBundleTerminationGate,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val completed = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val timedOut = AtomicBoolean(false)

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    @Volatile
    private var terminationFailure: Throwable? = null

    private val thread = Thread(
        {
            try {
                if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS) && !cancelled()) {
                    timedOut.set(true)
                    runCatching { terminationGate.request() }
                        .onFailure { error -> terminationFailure = error }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                stopped.countDown()
            }
        },
        "STM-Gate3B-LocalBundle-Watchdog",
    ).apply { isDaemon = true }

    init {
        require(timeoutMillis > 0) { "Local bundle watchdog timeout must be positive" }
    }

    fun start(): Boolean = synchronized(lifecycleLock) {
        if (started || closed) return@synchronized false
        started = true
        thread.start()
        true
    }

    fun hasTimedOut(): Boolean = timedOut.get()

    fun terminationFailure(): Throwable? = terminationFailure

    fun isStopped(): Boolean = stopped.count == 0L

    override fun close() {
        val shouldAwait = synchronized(lifecycleLock) {
            if (!closed) {
                closed = true
                completed.countDown()
            }
            started
        }
        if (shouldAwait && Thread.currentThread() !== thread) {
            awaitGate3bLatchUninterruptibly(stopped)
        }
    }
}

private fun awaitGate3bLatchUninterruptibly(latch: CountDownLatch) {
    var interrupted = false
    while (true) {
        try {
            latch.await()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

internal object Gate3bLocalBundleOutputInspector {
    fun requireFresh(programRoot: Path): Boolean {
        val program = requireDirectory(programRoot, "local bundle program root")
        listOf(
            "public/lib.js",
            "webpack.config.js",
            "docker/build-lib.js",
            "package-lock.json",
            "node_modules/webpack/package.json",
        ).forEach { relative ->
            requireRegular(program.resolve(relative), "local bundle input $relative")
        }
        val dist = program.resolve(DIST_DIRECTORY)
        if (Files.exists(dist, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectDirectory(program, dist, "local bundle dist root")
        }
        val webpackRoot = dist.resolve(WEBPACK_DIRECTORY)
        check(!Files.exists(webpackRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Local bundle build requires a fresh dist/_webpack root"
        }
        return Files.exists(dist, LinkOption.NOFOLLOW_LINKS)
    }

    fun inspect(programRoot: Path, distExistedBefore: Boolean): Gate3bLocalBundleOutput {
        val program = requireDirectory(programRoot, "local bundle program root")
        val dist = requireDirectDirectory(
            program,
            program.resolve(DIST_DIRECTORY),
            "local bundle dist root",
        )
        val webpackRoot = requireDirectDirectory(
            dist,
            dist.resolve(WEBPACK_DIRECTORY),
            "local bundle Webpack root",
        )
        val cacheRoots = directEntries(webpackRoot)
        check(cacheRoots.size == 1) {
            "Local bundle Webpack root must contain exactly one cache version"
        }
        val cacheRoot = requireDirectory(cacheRoots.single(), "local bundle cache version")
        val cacheVersion = cacheRoot.fileName.toString()
        check(CACHE_VERSION_PATTERN.matches(cacheVersion)) {
            "Local bundle cache version is unsafe: $cacheVersion"
        }
        val outputRoot = requireDirectory(cacheRoot.resolve(OUTPUT_DIRECTORY), "local bundle output")
        val outputs = directEntries(outputRoot)
        check(outputs.map { it.fileName.toString() }.sorted() == REQUIRED_OUTPUT_FILES) {
            "Local bundle output has missing or unexpected files"
        }
        val bundle = requireRegular(outputRoot.resolve(BUNDLE_FILE), "locally generated lib.js")
        val bundleLicense = requireRegular(
            outputRoot.resolve(BUNDLE_LICENSE_FILE),
            "locally generated lib.js license",
        )
        val bundleBytes = Files.size(bundle)
        val bundleLicenseBytes = Files.size(bundleLicense)
        check(bundleBytes in 1..MAX_BUNDLE_BYTES) { "Locally generated lib.js length is invalid" }
        check(bundleLicenseBytes in 1..MAX_LICENSE_BYTES) {
            "Locally generated lib.js license length is invalid"
        }
        return Gate3bLocalBundleOutput(
            webpackRoot = webpackRoot,
            outputRoot = outputRoot,
            bundle = bundle,
            bundleLicense = bundleLicense,
            cacheVersion = cacheVersion,
            bundleSha256 = sha256(bundle),
            bundleBytes = bundleBytes,
            bundleLicenseSha256 = sha256(bundleLicense),
            bundleLicenseBytes = bundleLicenseBytes,
            distExistedBefore = distExistedBefore,
        )
    }

    fun removeBuildTree(output: Gate3bLocalBundleOutput) {
        val dist = output.webpackRoot.parent
        deleteGate3bTreeNoFollow(output.webpackRoot, dist)
        check(!Files.exists(output.webpackRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Local bundle Webpack tree remained after cleanup"
        }
        if (!output.distExistedBefore && directEntries(dist).isEmpty()) Files.delete(dist)
    }

    private fun directEntries(root: Path): List<Path> =
        Files.newDirectoryStream(root).use { stream -> stream.toList() }

    private fun requireDirectory(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(absolute) && Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "$label is unavailable or unsafe"
        }
        return absolute.toRealPath()
    }

    private fun requireDirectDirectory(parent: Path, path: Path, label: String): Path {
        val realParent = requireDirectory(parent, "$label parent")
        val absolute = path.toAbsolutePath().normalize()
        check(absolute.parent == realParent) { "$label escaped its direct parent" }
        val real = requireDirectory(absolute, label)
        check(real.parent == realParent) { "$label resolved outside its direct parent" }
        return real
    }

    private fun requireRegular(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(absolute) && Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "$label is unavailable or unsafe"
        }
        return absolute.toRealPath()
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private const val DIST_DIRECTORY = "dist"
    private const val WEBPACK_DIRECTORY = "_webpack"
    private const val OUTPUT_DIRECTORY = "output"
    private const val BUNDLE_FILE = "lib.js"
    private const val BUNDLE_LICENSE_FILE = "lib.js.LICENSE.txt"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_BUNDLE_BYTES = 128L * 1024 * 1024
    private const val MAX_LICENSE_BYTES = 16L * 1024 * 1024
    private val CACHE_VERSION_PATTERN = Regex("[0-9a-f]{16}")
    private val REQUIRED_OUTPUT_FILES = listOf(BUNDLE_FILE, BUNDLE_LICENSE_FILE)
}

internal object Gate3bLocalBundleBuilder {
    fun build(
        runtime: NodeRuntime,
        source: Gate3bRunnableSource,
        dependencyTree: Gate3bTreeFingerprint,
        cancelled: AtomicBoolean,
        terminationGate: Gate3bBundleTerminationGate,
        operationNonce: String,
        installerRuntimeNonce: String,
    ): Gate3bLocalBundleBuildResult {
        check(!runtime.isClosed) { "Local bundle build requires a live short-lived Node runtime" }
        check(!cancelled.get()) { "Stage 3B local bundle build was cancelled" }
        check(operationNonce.isNotBlank() && installerRuntimeNonce.isNotBlank()) {
            "Local bundle build requires its staging job and npm runtime identities"
        }
        val program = source.programRoot.canonicalFile.toPath()
        val distExistedBefore = Gate3bLocalBundleOutputInspector.requireFresh(program)
        val dependencyRoot = program.resolve(NODE_MODULES)
        check(
            Gate3bTreeScanner.scan(dependencyRoot, includeManifest = false).fingerprint ==
                dependencyTree,
        ) {
            "Candidate dependencies changed before local bundle construction"
        }
        val inputPaths = linkedMapOf(
            "public_lib" to program.resolve("public/lib.js"),
            "webpack_config" to program.resolve("webpack.config.js"),
            "build_lib" to program.resolve("docker/build-lib.js"),
            "package_lock" to program.resolve("package-lock.json"),
        )
        val inputSha256 = inputPaths.mapValues { (_, path) -> sha256(path) }
        val webpackPackage = program.resolve("node_modules/webpack/package.json")
        val webpackVersion = JSONObject(webpackPackage.toFile().readText(Charsets.UTF_8))
            .getString("version")
        check(VERSION_PATTERN.matches(webpackVersion)) { "Installed Webpack version is invalid" }
        val nodeVersion = runtime.getExecutor("process.version").executeString().orEmpty()
        check(nodeVersion.isNotBlank()) { "Installer runtime did not report its Node version" }
        val processCwdBefore = File(".").canonicalPath
        val started = SystemClock.elapsedRealtime()
        var log = ""
        var failure: Throwable? = null
        var output: Gate3bLocalBundleOutput? = null
        var buildRuntimeNonce = ""
        var installerStateAbsent = false
        var distinctFromInstallerRuntime = false
        var compilerRunCalls = 0
        var compilerCallbackCalls = 0
        var compilerErrorAbsent = false
        var compilerStatsPresent = false
        var compilerStatsHasErrors = true
        var compilerStatsErrorCount = -1
        var compilerLibAssetPresent = false
        var compilerCloseCalls = 0
        var compilerCloseCallbackCalls = 0
        var compilerCloseErrorAbsent = false
        var compilerCloseSameInstance = false
        val watchdog = Gate3bBundleBuildWatchdog(
            timeoutMillis = BUILD_TIMEOUT_MILLIS,
            cancelled = cancelled::get,
            terminationGate = terminationGate,
        )
        check(watchdog.start()) { "Local bundle watchdog did not start" }
        val deadline = started + BUILD_TIMEOUT_MILLIS
        try {
            runtime.getExecutor(
                bootstrapScript(
                    programRoot = program,
                    buildEntry = inputPaths.getValue("build_lib"),
                    operationNonce = operationNonce,
                    installerRuntimeNonce = installerRuntimeNonce,
                ),
            ).executeVoid()
            while (SystemClock.elapsedRealtime() < deadline) {
                check(!cancelled.get()) { "Stage 3B local bundle build was cancelled" }
                runtime.await(V8AwaitMode.RunNoWait)
                if (
                    runtime.getExecutor("Boolean(globalThis.__stmGate3bLocalBundle?.done)")
                        .executeBoolean()
                ) {
                    break
                }
                Thread.sleep(EVENT_LOOP_POLL_MILLIS)
            }
            val completed = runtime.getExecutor(
                "Boolean(globalThis.__stmGate3bLocalBundle?.done)",
            ).executeBoolean()
            check(completed) { "Stage 3B local bundle build exceeded its time budget" }
            val javascriptError = runtime.getExecutor(
                "String(globalThis.__stmGate3bLocalBundle?.error || '')",
            ).executeString().orEmpty()
            log = runtime.getExecutor(
                "String(globalThis.__stmGate3bLocalBundle?.output || '')",
            ).executeString().orEmpty()
            val compiler = JSONObject(
                runtime.getExecutor(
                    """
                    JSON.stringify({
                      runCalls: Number(globalThis.__stmGate3bLocalBundle?.compilerRunCalls || 0),
                      callbackCalls: Number(
                        globalThis.__stmGate3bLocalBundle?.compilerCallbackCalls || 0
                      ),
                      errorAbsent: !globalThis.__stmGate3bLocalBundle?.compilerError,
                      statsPresent: Boolean(
                        globalThis.__stmGate3bLocalBundle?.compilerStatsPresent
                      ),
                      statsHasErrors: Boolean(
                        globalThis.__stmGate3bLocalBundle?.compilerStatsHasErrors
                      ),
                      statsErrorCount: Number(
                        globalThis.__stmGate3bLocalBundle?.compilerStatsErrorCount ?? -1
                      ),
                      libAssetPresent: Boolean(
                        globalThis.__stmGate3bLocalBundle?.compilerLibAssetPresent
                      ),
                      closeCalls: Number(
                        globalThis.__stmGate3bLocalBundle?.compilerCloseCalls || 0
                      ),
                      closeCallbackCalls: Number(
                        globalThis.__stmGate3bLocalBundle?.compilerCloseCallbackCalls || 0
                      ),
                      closeErrorAbsent: !globalThis.__stmGate3bLocalBundle?.compilerCloseError,
                      closeSameInstance: Boolean(
                        globalThis.__stmGate3bLocalBundle?.compilerCloseSameInstance
                      ),
                      buildRuntimeNonce: String(
                        globalThis.__stmGate3bLocalBundle?.runtimeNonce || ''
                      ),
                      installerStateAbsent: Boolean(
                        globalThis.__stmGate3bLocalBundle?.installerStateAbsent
                      ),
                      distinctFromInstallerRuntime: Boolean(
                        globalThis.__stmGate3bLocalBundle?.distinctFromInstallerRuntime
                      ),
                    })
                    """.trimIndent(),
                ).executeString().orEmpty(),
            )
            compilerRunCalls = compiler.getInt("runCalls")
            compilerCallbackCalls = compiler.getInt("callbackCalls")
            compilerErrorAbsent = compiler.getBoolean("errorAbsent")
            compilerStatsPresent = compiler.getBoolean("statsPresent")
            compilerStatsHasErrors = compiler.getBoolean("statsHasErrors")
            compilerStatsErrorCount = compiler.getInt("statsErrorCount")
            compilerLibAssetPresent = compiler.getBoolean("libAssetPresent")
            compilerCloseCalls = compiler.getInt("closeCalls")
            compilerCloseCallbackCalls = compiler.getInt("closeCallbackCalls")
            compilerCloseErrorAbsent = compiler.getBoolean("closeErrorAbsent")
            compilerCloseSameInstance = compiler.getBoolean("closeSameInstance")
            buildRuntimeNonce = compiler.getString("buildRuntimeNonce")
            installerStateAbsent = compiler.getBoolean("installerStateAbsent")
            distinctFromInstallerRuntime = compiler.getBoolean("distinctFromInstallerRuntime")
            check(javascriptError.isBlank()) { "Local bundle entry failed: $javascriptError" }
            check(
                buildRuntimeNonce.isNotBlank() &&
                    installerStateAbsent &&
                    distinctFromInstallerRuntime &&
                    buildRuntimeNonce != installerRuntimeNonce
            ) {
                "Local bundle construction did not run in a fresh isolated Node runtime"
            }
            requireSuccessfulGate3bWebpackCompiler(
                runCalls = compilerRunCalls,
                callbackCalls = compilerCallbackCalls,
                errorAbsent = compilerErrorAbsent,
                statsPresent = compilerStatsPresent,
                statsHasErrors = compilerStatsHasErrors,
                statsErrorCount = compilerStatsErrorCount,
                libAssetPresent = compilerLibAssetPresent,
                closeCalls = compilerCloseCalls,
                closeCallbackCalls = compilerCloseCallbackCalls,
                closeErrorAbsent = compilerCloseErrorAbsent,
                closeSameInstance = compilerCloseSameInstance,
            )
            output = Gate3bLocalBundleOutputInspector.inspect(program, distExistedBefore)
        } catch (error: Throwable) {
            failure = error
        } finally {
            watchdog.close()
        }
        if (watchdog.hasTimedOut()) {
            val timeoutFailure = IllegalStateException(
                "Stage 3B local bundle build exceeded its hard time budget",
                failure,
            )
            watchdog.terminationFailure()?.let(timeoutFailure::addSuppressed)
            failure = timeoutFailure
        }
        runCatching { runtime.cancelTerminateExecution() }
        val processCwdRestored = runCatching {
            restoreRuntime(runtime) &&
                File(".").canonicalPath == processCwdBefore
        }.getOrDefault(false)
        if (!processCwdRestored) {
            throw IllegalStateException(
                "Local bundle build did not restore the Installer process CWD and console",
                failure,
            )
        }
        failure?.let { throw it }
        requireGate3bNotCancelled(cancelled, "Stage 3B local bundle build")
        val verifiedOutput = requireNotNull(output)
        return Gate3bLocalBundleBuildResult(
            output = verifiedOutput,
            nodeVersion = nodeVersion,
            webpackVersion = webpackVersion,
            elapsedMillis = SystemClock.elapsedRealtime() - started,
            logSha256 = sha256(log.toByteArray()),
            logCharacters = log.length,
            inputSha256 = inputSha256,
            dependencyTreeSha256 = dependencyTree.sha256,
            processCwdRestored = processCwdRestored,
            buildRuntimeNonce = buildRuntimeNonce,
            installerStateAbsent = installerStateAbsent,
            distinctFromInstallerRuntime = distinctFromInstallerRuntime,
            compilerRunCalls = compilerRunCalls,
            compilerCallbackCalls = compilerCallbackCalls,
            compilerErrorAbsent = compilerErrorAbsent,
            compilerStatsPresent = compilerStatsPresent,
            compilerStatsHasErrors = compilerStatsHasErrors,
            compilerStatsErrorCount = compilerStatsErrorCount,
            compilerLibAssetPresent = compilerLibAssetPresent,
            compilerCloseCalls = compilerCloseCalls,
            compilerCloseCallbackCalls = compilerCloseCallbackCalls,
            compilerCloseErrorAbsent = compilerCloseErrorAbsent,
            compilerCloseSameInstance = compilerCloseSameInstance,
            buildRuntimeClosed = false,
        )
    }

    private fun bootstrapScript(
        programRoot: Path,
        buildEntry: Path,
        operationNonce: String,
        installerRuntimeNonce: String,
    ): String {
        val importExpression = "import(${jsString(buildEntry.toUri().toString())})"
        return """
            (() => {
              if (globalThis.__stmGate3bLocalBundle) {
                throw new Error('Stage 3B local bundle state already exists');
              }
              const util = require('node:util');
              const vm = require('node:vm');
              const crypto = require('node:crypto');
              const installerStateAbsent = globalThis.__stmGate3b === undefined;
              const webpack = require(
                ${jsString(programRoot.resolve("node_modules/webpack").toString())}
              );
              const state = globalThis.__stmGate3bLocalBundle = {
                done: false,
                error: '',
                output: '',
                logs: [],
                originalCwd: process.cwd(),
                originalConsole: {},
                operationNonce: ${jsString(operationNonce)},
                runtimeNonce: crypto.randomUUID(),
                installerStateAbsent,
                distinctFromInstallerRuntime: false,
                compilerRunCalls: 0,
                compilerCallbackCalls: 0,
                compilerError: '',
                compilerStatsPresent: false,
                compilerStatsHasErrors: true,
                compilerStatsErrorCount: -1,
                compilerLibAssetPresent: false,
                compilerCloseCalls: 0,
                compilerCloseCallbackCalls: 0,
                compilerCloseError: '',
                compilerCloseSameInstance: false,
              };
              Object.defineProperties(state, {
                operationNonce: { value: state.operationNonce, writable: false, configurable: false },
                runtimeNonce: { value: state.runtimeNonce, writable: false, configurable: false },
              });
              state.distinctFromInstallerRuntime =
                state.runtimeNonce !== ${jsString(installerRuntimeNonce)};
              if (!state.installerStateAbsent || !state.distinctFromInstallerRuntime) {
                throw new Error('Local bundle runtime was not isolated from the npm runtime');
              }
              const format = value => {
                if (value instanceof Error) return String(value.stack || value.message || value);
                if (typeof value === 'string') return value;
                return util.inspect(value, { depth: 3, maxArrayLength: 50, breakLength: 160 });
              };
              for (const level of ['log', 'info', 'warn', 'error']) {
                const original = console[level];
                state.originalConsole[level] = original;
                console[level] = (...values) => {
                  state.logs.push(values.map(format).join(' '));
                  if (state.logs.length > 200) state.logs.shift();
                  original.apply(console, values);
                };
              }
              const compilerPrototype = webpack?.Compiler?.prototype;
              if (!compilerPrototype || typeof compilerPrototype.run !== 'function' ||
                  typeof compilerPrototype.close !== 'function') {
                throw new Error('Webpack Compiler lifecycle is unavailable for observation');
              }
              state.compilerPrototype = compilerPrototype;
              state.originalCompilerRun = compilerPrototype.run;
              state.originalCompilerClose = compilerPrototype.close;
              compilerPrototype.run = function(callback) {
                state.compilerRunCalls += 1;
                state.compilerInstance = this;
                return state.originalCompilerRun.call(this, (error, stats) => {
                  state.compilerCallbackCalls += 1;
                  state.compilerError = error ? format(error) : '';
                  state.compilerStatsPresent = Boolean(stats);
                  try {
                    state.compilerStatsHasErrors = Boolean(stats?.hasErrors?.());
                    state.compilerStatsErrorCount = Number(stats?.compilation?.errors?.length ?? -1);
                    state.compilerLibAssetPresent = Boolean(
                      stats?.compilation?.getAsset?.('lib.js')
                    );
                  } catch (probeError) {
                    state.compilerError = state.compilerError || format(probeError);
                  }
                  return callback(error, stats);
                });
              };
              compilerPrototype.close = function(callback) {
                state.compilerCloseCalls += 1;
                state.compilerCloseSameInstance = this === state.compilerInstance;
                return state.originalCompilerClose.call(this, error => {
                  state.compilerCloseCallbackCalls += 1;
                  state.compilerCloseError = error ? format(error) : '';
                  return callback(error);
                });
              };
              process.chdir(${jsString(programRoot.toString())});
              const loader = new vm.Script(${jsString(importExpression)}, {
                filename: ${jsString(programRoot.resolve(".stm-local-bundle-loader.js").toString())},
                importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER,
              });
              Promise.resolve(loader.runInThisContext()).then(
                () => {
                  state.output = state.logs.join('\n').slice(-32768);
                  state.done = true;
                },
                error => {
                  state.error = format(error);
                  state.output = state.logs.join('\n').slice(-32768);
                  state.done = true;
                },
              );
            })();
            """.trimIndent()
    }

    fun restoreRuntime(runtime: NodeRuntime): Boolean =
        runtime.getExecutor(restorationScript()).executeBoolean()

    private fun restorationScript(): String =
        """
        (() => {
          const state = globalThis.__stmGate3bLocalBundle;
          if (!state || !state.originalCwd || !state.originalConsole) return false;
          if (state.compilerPrototype && typeof state.originalCompilerRun === 'function') {
            state.compilerPrototype.run = state.originalCompilerRun;
          }
          if (state.compilerPrototype && typeof state.originalCompilerClose === 'function') {
            state.compilerPrototype.close = state.originalCompilerClose;
          }
          for (const level of ['log', 'info', 'warn', 'error']) {
            if (typeof state.originalConsole[level] === 'function') {
              console[level] = state.originalConsole[level];
            }
          }
          process.chdir(state.originalCwd);
          state.restored = process.cwd() === state.originalCwd &&
            ['log', 'info', 'warn', 'error'].every(
              level => console[level] === state.originalConsole[level]
            ) &&
            (!state.compilerPrototype ||
              (state.compilerPrototype.run === state.originalCompilerRun &&
                state.compilerPrototype.close === state.originalCompilerClose));
          return state.restored;
        })();
        """.trimIndent()

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
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

    private const val NODE_MODULES = "node_modules"
    private const val BUILD_TIMEOUT_MILLIS = 15L * 60L * 1000L
    private const val EVENT_LOOP_POLL_MILLIS = 10L
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private val VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")
}

internal object Gate3bLocalRuntimeAdapter {
    fun materialize(experimentRoot: Path): Path {
        val root = experimentRoot.toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Local runtime adapter root is unavailable or unsafe"
        }
        val destination = root.resolve(ADAPTER_FILE)
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Local runtime adapter destination already exists"
        }
        FileOutputStream(destination.toFile()).use { output ->
            output.write(SOURCE.toByteArray())
            output.fd.sync()
        }
        check(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Local runtime adapter was not materialized as a regular file"
        }
        return destination
    }

    private const val ADAPTER_FILE = "local-webpack-serve.adapter.js"
    private val SOURCE =
        """
        import fs from 'node:fs';
        import path from 'node:path';

        export default function getWebpackServeMiddleware() {
            const bundlePath = process.env.STM_PREBUILT_LIB_JS;
            if (!bundlePath || !path.isAbsolute(bundlePath)) {
                throw new Error('STM_PREBUILT_LIB_JS must identify an absolute frozen bundle path');
            }
            const bundleRoot = path.dirname(bundlePath);
            const bundleName = path.basename(bundlePath);

            function devMiddleware(req, res, next) {
                const parsedPath = path.parse(req.path);
                if (req.method === 'GET' && parsedPath.dir === '/' && parsedPath.base === bundleName) {
                    return res.sendFile(bundleName, { root: bundleRoot });
                }
                next();
            }

            devMiddleware.runWebpackCompiler = async () => {
                const stat = await fs.promises.stat(bundlePath);
                if (!stat.isFile()) throw new Error('STM frozen lib.js is not a regular file');
            };

            return devMiddleware;
        }
        """.trimIndent() + "\n"
}

internal object Gate3bLocalBundleProvenance {
    fun materialize(
        experimentRoot: Path,
        source: Gate3bRunnableSource,
        build: Gate3bLocalBundleBuildResult,
        adapterSource: Path,
        npmVersion: String,
    ): Path {
        val root = experimentRoot.toAbsolutePath().normalize()
        val destination = root.resolve(PROVENANCE_FILE)
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Local bundle provenance destination already exists"
        }
        val adapterSha256 = sha256(adapterSource)
        val text = buildString {
            appendLine("format_version=1")
            appendLine("provenance_kind=device-local-upstream-build")
            appendLine("repository=$ST_REPOSITORY")
            appendLine("commit_sha=$ST_COMMIT")
            appendLine("source_archive_sha256=${source.sourceArchiveSha256}")
            appendLine("package_lock_sha256=${build.inputSha256.getValue("package_lock")}")
            appendLine("dependency_tree_sha256=${build.dependencyTreeSha256}")
            appendLine("node_version=${build.nodeVersion}")
            appendLine("npm_version=$npmVersion")
            appendLine("webpack_version=${build.webpackVersion}")
            build.inputSha256.forEach { (name, sha256) ->
                appendLine("input_${name}_sha256=$sha256")
            }
            appendLine("adapter_sha256=$adapterSha256")
            appendLine("bundle_sha256=${build.output.bundleSha256}")
            appendLine("bundle_bytes=${build.output.bundleBytes}")
            appendLine("bundle_license_sha256=${build.output.bundleLicenseSha256}")
            appendLine("bundle_license_bytes=${build.output.bundleLicenseBytes}")
            appendLine("build_log_sha256=${build.logSha256}")
            appendLine("build_log_characters=${build.logCharacters}")
            appendLine("build_elapsed_ms=${build.elapsedMillis}")
            appendLine("build_runtime_nonce_sha256=${sha256(build.buildRuntimeNonce)}")
            appendLine("installer_state_absent=${build.installerStateAbsent}")
            appendLine("distinct_from_installer_runtime=${build.distinctFromInstallerRuntime}")
            appendLine("webpack_compiler_run_calls=${build.compilerRunCalls}")
            appendLine("webpack_compiler_callback_calls=${build.compilerCallbackCalls}")
            appendLine("webpack_compiler_error_absent=${build.compilerErrorAbsent}")
            appendLine("webpack_compiler_stats_present=${build.compilerStatsPresent}")
            appendLine("webpack_compiler_stats_has_errors=${build.compilerStatsHasErrors}")
            appendLine("webpack_compiler_stats_error_count=${build.compilerStatsErrorCount}")
            appendLine("webpack_compiler_lib_asset_present=${build.compilerLibAssetPresent}")
            appendLine("webpack_compiler_close_calls=${build.compilerCloseCalls}")
            appendLine(
                "webpack_compiler_close_callback_calls=${build.compilerCloseCallbackCalls}",
            )
            appendLine("webpack_compiler_close_error_absent=${build.compilerCloseErrorAbsent}")
            appendLine("webpack_compiler_close_same_instance=${build.compilerCloseSameInstance}")
            appendLine("build_runtime_closed=${build.buildRuntimeClosed}")
        }
        FileOutputStream(destination.toFile()).use { output ->
            output.write(text.toByteArray())
            output.fd.sync()
        }
        check(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Local bundle provenance was not materialized as a regular file"
        }
        return destination
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private const val ST_REPOSITORY = "SillyTavern/SillyTavern"
    private const val ST_COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
    private const val PROVENANCE_FILE = "local-build.provenance.stm"
    private const val COPY_BUFFER_SIZE = 64 * 1024
}

internal class Gate3bRunnableWorkspaceLifetime {
    @Volatile
    private var engineDeletionSafe = true

    @Volatile
    private var buildRuntimeDeletionSafe = true

    fun markEngineStarted() {
        engineDeletionSafe = false
    }

    fun markBuildRuntimeCreated() {
        buildRuntimeDeletionSafe = false
    }

    fun markBuildRuntimeClosed(closed: Boolean) {
        buildRuntimeDeletionSafe = closed
    }

    fun markTeardownCompleted(
        engineDestroyed: Boolean,
        portReleased: Boolean,
        processCwdRestored: Boolean,
    ) {
        engineDeletionSafe = engineDestroyed && portReleased && processCwdRestored
    }

    fun isDeletionSafe(): Boolean = engineDeletionSafe && buildRuntimeDeletionSafe
}

internal fun selectGate3bTeardownPorts(
    actualPort: Int,
    signalPort: Int,
    selectedPort: Int,
): List<Int> = listOf(actualPort, signalPort, selectedPort)
    .filter { port -> port in 1..65_535 }
    .distinct()

internal fun selectGate3bTeardownPort(
    actualPort: Int,
    signalPort: Int,
    selectedPort: Int,
): Int = selectGate3bTeardownPorts(actualPort, signalPort, selectedPort)
    .firstOrNull()
    ?: throw IllegalArgumentException("Prepared runnable port is invalid")

private fun FeatherEngineLaunchSpec.withGate3bForbiddenModuleInvariant(): FeatherEngineLaunchSpec =
    copy(
        startupErrorExpression =
            """
            (() => {
              const existing = String(($startupErrorExpression) || '');
              if (existing) return existing;
              const count = Number(globalThis.__stmCore?.forbiddenModuleLoads || 0);
              return count === 0 ? '' : 'Forbidden runtime module loads observed: ' + count;
            })();
            """.trimIndent(),
        closedExpression =
            """
            (() => {
              const count = Number(globalThis.__stmCore?.forbiddenModuleLoads || 0);
              if (count !== 0) {
                throw new Error('Forbidden runtime module loads observed: ' + count);
              }
              return Boolean(($closedExpression));
            })();
            """.trimIndent(),
    )

/** Assembles only the fixed runtime sidecars around a candidate-owned dependency tree. */
internal object Gate3bRunnableWorkspaceAssembler {
    fun attachCandidateDependencies(
        workRoot: Path,
        programRoot: Path,
        expectedProgramFingerprint: Gate3bTreeFingerprint,
        expectedDependencyFingerprint: Gate3bTreeFingerprint,
    ) {
        val work = requireDirectory(workRoot, "candidate dependency work root")
        val program = requireDirectory(programRoot, "fixed runnable program")
        check(
            Gate3bTreeScanner.scan(program, includeManifest = false).fingerprint ==
                expectedProgramFingerprint,
        ) {
            "Fixed runnable source changed before candidate dependency attachment"
        }
        val candidateDependencies = requireDirectory(
            work.resolve(NODE_MODULES),
            "candidate dependency tree",
        )
        check(candidateDependencies.parent == work) {
            "Candidate dependency tree escaped its lockfile-only work root"
        }
        check(
            Gate3bTreeScanner.scan(candidateDependencies, includeManifest = false).fingerprint ==
                expectedDependencyFingerprint,
        ) {
            "Candidate dependency tree changed before attachment"
        }
        val installedDependencies = program.resolve(NODE_MODULES)
        check(!Files.exists(installedDependencies, LinkOption.NOFOLLOW_LINKS)) {
            "Fixed runnable source already contains node_modules"
        }
        check(candidateDependencies.toFile().renameTo(installedDependencies.toFile())) {
            "Candidate dependency tree could not be atomically moved into fixed source"
        }
        check(!Files.exists(candidateDependencies, LinkOption.NOFOLLOW_LINKS)) {
            "Candidate dependency tree remained in its lockfile-only work root"
        }
        check(
            Gate3bTreeScanner.scan(installedDependencies, includeManifest = false).fingerprint ==
                expectedDependencyFingerprint,
        ) {
            "Candidate dependency tree changed during atomic attachment"
        }
    }

    fun assemble(
        payloadRoot: Path,
        programRoot: Path,
        adapterSource: Path,
        bundleSource: Path,
        bundleLicenseSource: Path,
        expectedAdapterSha256: String,
        expectedBundleSha256: String,
        expectedBundleBytes: Long,
        expectedBundleLicenseSha256: String,
        expectedBundleLicenseBytes: Long,
        provenanceSource: Path? = null,
    ): Gate3bRunnableRuntimeEvidence {
        val payload = requireDirectory(payloadRoot, "temporary runnable payload")
        val program = requireDirectory(programRoot, "temporary runnable program")
        check(program.parent == payload) { "Runnable program must be one direct payload child" }
        val adapter = requireRegular(adapterSource, "fixed Webpack adapter")
        val bundle = requireRegular(bundleSource, "frozen lib.js")
        val bundleLicense = requireRegular(bundleLicenseSource, "frozen lib.js license")
        val provenance = provenanceSource?.let { source ->
            requireRegular(source, "local bundle provenance")
        }
        val adapterSha256 = sha256(adapter)
        val bundleSha256 = sha256(bundle)
        val bundleLicenseSha256 = sha256(bundleLicense)
        check(adapterSha256 == expectedAdapterSha256) {
            "Fixed Webpack adapter identity changed before runnable assembly"
        }
        check(Files.size(bundle) == expectedBundleBytes && bundleSha256 == expectedBundleSha256) {
            "Frozen lib.js identity changed before runnable assembly"
        }
        check(
            Files.size(bundleLicense) == expectedBundleLicenseBytes &&
                bundleLicenseSha256 == expectedBundleLicenseSha256,
        ) {
            "Frozen lib.js license identity changed before runnable assembly"
        }

        val targetAdapter = requireRegular(
            program.resolve(ADAPTER_RELATIVE_PATH),
            "upstream Webpack middleware",
        )
        val runtimeRoot = payload.resolve(RUNTIME_DIRECTORY)
        check(!Files.exists(runtimeRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Temporary runnable payload already contains runtime sidecars"
        }
        Files.createDirectory(runtimeRoot)

        val temporaryAdapter = targetAdapter.resolveSibling("${targetAdapter.fileName}.stm-part")
        check(!Files.exists(temporaryAdapter, LinkOption.NOFOLLOW_LINKS)) {
            "Temporary runnable adapter target already exists"
        }
        copyRegular(adapter, temporaryAdapter)
        Files.delete(targetAdapter)
        check(temporaryAdapter.toFile().renameTo(targetAdapter.toFile())) {
            "Fixed Webpack adapter could not replace the temporary source module"
        }
        copyRegular(adapter, runtimeRoot.resolve(ADAPTER_FILE))
        copyRegular(bundle, runtimeRoot.resolve(BUNDLE_FILE))
        copyRegular(bundleLicense, runtimeRoot.resolve(BUNDLE_LICENSE_FILE))
        provenance?.let { source ->
            copyRegular(source, runtimeRoot.resolve(LOCAL_BUILD_PROVENANCE_FILE))
        }

        check(sha256(targetAdapter) == adapterSha256) {
            "Installed runnable Webpack adapter changed during assembly"
        }
        check(sha256(runtimeRoot.resolve(ADAPTER_FILE)) == adapterSha256) {
            "Runnable adapter sidecar changed during assembly"
        }
        check(
            Files.size(runtimeRoot.resolve(BUNDLE_FILE)) == expectedBundleBytes &&
                sha256(runtimeRoot.resolve(BUNDLE_FILE)) == bundleSha256,
        ) {
            "Runnable lib.js sidecar changed during assembly"
        }
        check(
            Files.size(runtimeRoot.resolve(BUNDLE_LICENSE_FILE)) == expectedBundleLicenseBytes &&
                sha256(runtimeRoot.resolve(BUNDLE_LICENSE_FILE)) == bundleLicenseSha256,
        ) {
            "Runnable lib.js license sidecar changed during assembly"
        }
        return Gate3bRunnableRuntimeEvidence(
            adapterSha256 = adapterSha256,
            adapterBytes = Files.size(adapter),
            bundleSha256 = bundleSha256,
            bundleBytes = Files.size(bundle),
            bundleLicenseSha256 = bundleLicenseSha256,
            bundleLicenseBytes = Files.size(bundleLicense),
            provenanceSha256 = provenance?.let(::sha256),
            provenanceBytes = provenance?.let(Files::size) ?: 0,
        )
    }

    private fun requireDirectory(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(absolute) && Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "$label is unavailable or unsafe"
        }
        return absolute.toRealPath()
    }

    private fun requireRegular(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(absolute) && Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "$label is unavailable or unsafe"
        }
        return absolute.toRealPath()
    }

    private fun copyRegular(source: Path, destination: Path) {
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Runnable destination already exists: ${destination.fileName}"
        }
        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileOutputStream(destination.toFile()).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        check(
            Files.size(source) == Files.size(destination) &&
                MessageDigest.isEqual(sha256Bytes(source), sha256Bytes(destination)),
        ) {
            "Runnable file changed while being copied: ${destination.fileName}"
        }
    }

    private fun sha256(path: Path): String = sha256Bytes(path).toHex()

    private fun sha256Bytes(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private const val ADAPTER_RELATIVE_PATH = "src/middleware/webpack-serve.js"
    private const val NODE_MODULES = "node_modules"
    private const val RUNTIME_DIRECTORY = ".stm-runtime"
    private const val ADAPTER_FILE = "webpack-serve.adapter.js"
    private const val BUNDLE_FILE = "lib.js"
    private const val BUNDLE_LICENSE_FILE = "lib.js.LICENSE.txt"
    private const val LOCAL_BUILD_PROVENANCE_FILE = "local-build.provenance.stm"
    private const val COPY_BUFFER_SIZE = 64 * 1024
}

/** Debug-only real-ST acceptance for an npm CLI or Arborist dependency tree. */
internal class StmCoreGate3bRunnableAcceptance(
    context: Context,
    private val candidate: StmDependencySupplyCandidate,
    private val cancelled: AtomicBoolean,
    private val localBundleBuild: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val teardownLock = Any()

    @Volatile
    private var activeCallback: Gate3bRunnableCallback? = null

    @Volatile
    private var activeEngine: FeatherEngine? = null

    @Volatile
    private var activeBuildRuntime: NodeRuntime? = null

    @Volatile
    private var activeBuildTerminationGate: Gate3bBundleTerminationGate? = null

    private val workspaceLifetime = Gate3bRunnableWorkspaceLifetime()
    private val buildRuntimeCloseLock = Any()

    @Volatile
    private var engineStartAttempted = false

    @Volatile
    private var teardownEngineDestroyed = false

    @Volatile
    private var teardownCheckedPort = 0

    @Volatile
    private var teardownCheckedPorts: List<Int> = emptyList()

    @Volatile
    private var teardownPortReleased = false

    @Volatile
    private var teardownProcessCwdRestored = false

    @Volatile
    private var teardownActualPort = 0

    @Volatile
    private var teardownSignalPort = 0

    @Volatile
    private var teardownSelectedPort = 0

    @Volatile
    private var teardownExpectedProcessCwd: String? = null

    @Volatile
    private var teardownCompleted = false

    @Volatile
    private var buildRuntimeClosure = "not_requested"

    fun cancel() {
        activeBuildTerminationGate?.let { gate -> runCatching { gate.request() } }
        activeCallback?.cancelAll("Stage 3B runnable acceptance was cancelled")
    }

    fun finishTeardown(): Boolean = synchronized(teardownLock) {
        if (teardownCompleted) return@synchronized true
        activeBuildTerminationGate?.let { gate -> runCatching { gate.request() } }
        activeBuildRuntime?.let(::closeBuildRuntime)
        val engine = activeEngine
        val engineDestroyed = when {
            engine != null -> runCatching {
                engine.destroyAndAwait(ENGINE_DESTROY_TIMEOUT_SECONDS)
            }.getOrDefault(false)

            !engineStartAttempted -> true
            else -> teardownEngineDestroyed
        }
        if (engineDestroyed && (engine == null || activeEngine === engine)) {
            activeEngine = null
            activeCallback = null
        }

        val checkedPorts = selectGate3bTeardownPorts(
            actualPort = teardownActualPort,
            signalPort = teardownSignalPort,
            selectedPort = teardownSelectedPort,
        )
        val portsReleased = checkedPorts.isEmpty() || awaitPortsReleased(checkedPorts)
        val expectedProcessCwd = teardownExpectedProcessCwd
        val processCwdRestored = expectedProcessCwd == null || runCatching {
            File(".").canonicalPath == expectedProcessCwd
        }.getOrDefault(false)

        teardownEngineDestroyed = engineDestroyed
        teardownCheckedPorts = checkedPorts
        teardownCheckedPort = checkedPorts.firstOrNull() ?: 0
        teardownPortReleased = portsReleased
        teardownProcessCwdRestored = processCwdRestored
        workspaceLifetime.markTeardownCompleted(
            engineDestroyed = engineDestroyed,
            portReleased = portsReleased,
            processCwdRestored = processCwdRestored,
        )
        workspaceLifetime.isDeletionSafe().also { deletionSafe ->
            if (deletionSafe && engineStartAttempted) teardownCompleted = true
        }
    }

    fun isWorkspaceDeletionSafe(): Boolean = workspaceLifetime.isDeletionSafe()

    fun buildLocalBundleInFreshRuntime(
        candidateWorkRoot: File,
        source: Gate3bRunnableSource,
        expectedDependencyTree: Gate3bTreeFingerprint,
        operationNonce: String,
        installerRuntimeNonce: String,
    ): Gate3bLocalBundleBuildResult {
        check(localBundleBuild) { "Local bundle construction was not requested" }
        check(!cancelled.get()) { "Stage 3B local bundle build was cancelled" }
        Gate3bRunnableWorkspaceAssembler.attachCandidateDependencies(
            workRoot = candidateWorkRoot.toPath(),
            programRoot = source.programRoot.toPath(),
            expectedProgramFingerprint = source.programFingerprint,
            expectedDependencyFingerprint = expectedDependencyTree,
        )
        val runtime = StmNodeRuntimeFactory.create(arrayOf("stm-core-gate3b-local-bundle"))
        val terminationGate = Gate3bBundleTerminationGate {
            runtime.terminateExecution(V8RuntimeTerminationMode.Synchronous)
        }
        workspaceLifetime.markBuildRuntimeCreated()
        activeBuildTerminationGate = terminationGate
        activeBuildRuntime = runtime
        var result: Gate3bLocalBundleBuildResult? = null
        var failure: Throwable? = null
        var runtimeClosed = false
        try {
            if (cancelled.get()) {
                val cancellationFailure = IllegalStateException(
                    "Stage 3B local bundle runtime was cancelled",
                )
                runCatching { terminationGate.request() }
                    .exceptionOrNull()
                    ?.let(cancellationFailure::addSuppressed)
                throw cancellationFailure
            }
            result = Gate3bLocalBundleBuilder.build(
                runtime = runtime,
                source = source,
                dependencyTree = expectedDependencyTree,
                cancelled = cancelled,
                terminationGate = terminationGate,
                operationNonce = operationNonce,
                installerRuntimeNonce = installerRuntimeNonce,
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            runtimeClosed = closeBuildRuntime(runtime)
            if (!runtimeClosed) {
                val closeFailure = IllegalStateException("Local bundle Node runtime did not close")
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
        requireGate3bNotCancelled(cancelled, "Stage 3B local bundle runtime")
        return requireNotNull(result).copy(buildRuntimeClosed = runtimeClosed)
    }

    fun teardownEvidence(): Map<String, String> = linkedMapOf(
        "engine_start_attempted" to engineStartAttempted.toString(),
        "engine_destroyed" to teardownEngineDestroyed.toString(),
        "checked_port" to teardownCheckedPort.toString(),
        "checked_ports" to teardownCheckedPorts.joinToString(","),
        "port_released" to teardownPortReleased.toString(),
        "process_cwd_restored" to teardownProcessCwdRestored.toString(),
        "build_runtime_closure" to buildRuntimeClosure,
    )

    private fun closeBuildRuntime(runtime: NodeRuntime): Boolean = synchronized(buildRuntimeCloseLock) {
        val terminationGate = if (activeBuildRuntime === runtime) {
            activeBuildTerminationGate
        } else {
            null
        }
        terminationGate?.close()
        if (runCatching { runtime.isClosed }.getOrDefault(false)) {
            workspaceLifetime.markBuildRuntimeClosed(true)
            if (activeBuildRuntime === runtime) {
                activeBuildRuntime = null
                activeBuildTerminationGate = null
            }
            if (buildRuntimeClosure == "not_requested") buildRuntimeClosure = "closed"
            return@synchronized true
        }
        val errors = mutableListOf<String>()
        runCatching { runtime.cancelTerminateExecution() }
        runCatching { Gate3bLocalBundleBuilder.restoreRuntime(runtime) }
            .onSuccess { restored -> if (!restored) errors += "restore:false" }
            .onFailure { error -> errors += "restore:${error.message.orEmpty()}" }
        runCatching { runtime.setStopping(true) }
            .onFailure { error -> errors += "setStopping:${error.message.orEmpty()}" }
        runCatching { runtime.close(true) }
            .onFailure { error -> errors += "close:${error.message.orEmpty()}" }
        val closed = runCatching { runtime.isClosed }.getOrDefault(false)
        if (!closed) errors += "runtime_remained_open"
        workspaceLifetime.markBuildRuntimeClosed(closed)
        if (closed && activeBuildRuntime === runtime) {
            activeBuildRuntime = null
            activeBuildTerminationGate = null
        }
        buildRuntimeClosure = if (errors.isEmpty()) "closed" else "failed:${errors.joinToString(",")}"
        errors.isEmpty()
    }

    fun prepareSource(experimentRoot: File): Gate3bRunnableSource {
        check(!cancelled.get()) { "Stage 3B runnable acceptance was cancelled" }
        val sourceArchive = requireSourceArchive()
        val verifiedCopy = File(experimentRoot, "fixed-source.verified.zip")
        val identity = ArtifactIdentity(
            repository = ST_REPOSITORY,
            commitSha = ST_COMMIT,
            archiveSha256 = SOURCE_ARCHIVE_SHA256,
            archiveLength = SOURCE_ARCHIVE_BYTES,
            downloadUrl = SOURCE_DOWNLOAD_URL,
            kind = ArtifactKind.UPSTREAM_SOURCE_ARCHIVE,
        )
        val verified = sourceArchive.inputStream().use { input ->
            StmArtifactVerifier().verifyAndCopy(identity, input, verifiedCopy)
        }
        check(verified is ArtifactIntegrityResult.Verified) {
            val rejected = verified as ArtifactIntegrityResult.Rejected
            "${rejected.code}:${rejected.detail}"
        }
        val extraction = StmSafeZipExtractor().extract(
            artifact = verified.protectedTemporaryFile,
            operationStagingRoot = File(experimentRoot, "source-extraction"),
            cancellation = StmExtractionCancellation(cancelled::get),
        )
        Files.delete(verified.protectedTemporaryFile.toPath())
        val inspection = StmSillyTavernSourceInspector().inspect(
            payloadDirectory = extraction.payloadDirectory,
            expectedExactCommit = ST_COMMIT,
        )
        check(inspection is StmSillyTavernSourceInspectionResult.Accepted) {
            val rejected = inspection as StmSillyTavernSourceInspectionResult.Rejected
            "${rejected.code}:${rejected.detail}"
        }
        val source = inspection.evidence
        check(source.stVersion == ST_VERSION && source.packageLockSha256 == PACKAGE_LOCK_SHA256) {
            "Fixed runnable source did not match the Stage 3B version and lockfile"
        }
        val program = File(extraction.payloadDirectory, source.archiveRoot).canonicalFile
        check(program.parentFile == extraction.payloadDirectory.canonicalFile && program.isDirectory) {
            "Fixed runnable source program escaped its temporary payload"
        }
        check(!File(program, "node_modules").exists()) {
            "Fixed runnable source archive unexpectedly contains node_modules"
        }
        return Gate3bRunnableSource(
            payloadRoot = extraction.payloadDirectory.canonicalFile,
            programRoot = program,
            archiveRoot = source.archiveRoot,
            sourceArchiveSha256 = verified.archiveSha256,
            sourceArchiveBytes = verified.archiveLength,
            sourceEntries = extraction.fileCount + extraction.directoryCount,
            sourceBytes = extraction.totalFileBytes,
            programFingerprint = Gate3bTreeScanner.scan(
                program.toPath(),
                includeManifest = false,
            ).fingerprint,
        )
    }

    fun run(
        experimentRoot: File,
        candidateWorkRoot: File,
        source: Gate3bRunnableSource,
        expectedDependencyTree: Gate3bTreeFingerprint,
        localBuild: Gate3bLocalBundleBuildResult? = null,
        npmRuntimeClosedBeforeLocalBuild: Boolean = false,
    ): Map<String, String> {
        check(!cancelled.get()) { "Stage 3B runnable acceptance was cancelled" }
        val started = SystemClock.elapsedRealtime()
        if (localBundleBuild) {
            check(localBuild != null) {
                "The local bundle result was not produced by the npm Installer runtime"
            }
            check(npmRuntimeClosedBeforeLocalBuild) {
                "The npm runtime was not closed before the fresh local bundle runtime"
            }
            check(!File(candidateWorkRoot, "node_modules").exists()) {
                "Candidate dependencies remained in work after local bundle construction"
            }
        } else {
            check(localBuild == null) { "Unexpected local bundle result for fixed-fixture acceptance" }
            Gate3bRunnableWorkspaceAssembler.attachCandidateDependencies(
                workRoot = candidateWorkRoot.toPath(),
                programRoot = source.programRoot.toPath(),
                expectedProgramFingerprint = source.programFingerprint,
                expectedDependencyFingerprint = expectedDependencyTree,
            )
        }
        check(!cancelled.get()) { "Stage 3B runnable acceptance was cancelled" }
        var fixedSupplyManifestSha256 = ""
        var buildCacheRemoved = false
        var fixedSourceOracleMatched = false
        val runtimeEvidence = if (localBundleBuild) {
            val build = requireNotNull(localBuild)
            check(
                build.dependencyTreeSha256 == expectedDependencyTree.sha256 &&
                    build.inputSha256.getValue("package_lock") == PACKAGE_LOCK_SHA256 &&
                    build.processCwdRestored
            ) {
                "Local bundle evidence did not bind the candidate dependency inputs"
            }
            fixedSourceOracleMatched =
                build.output.bundleSha256 == HISTORICAL_BUNDLE_SHA256 &&
                    build.output.bundleBytes == HISTORICAL_BUNDLE_BYTES &&
                    build.output.bundleLicenseSha256 == HISTORICAL_BUNDLE_LICENSE_SHA256 &&
                    build.output.bundleLicenseBytes == HISTORICAL_BUNDLE_LICENSE_BYTES
            check(fixedSourceOracleMatched) {
                "Fixed-source local bundle did not match its established output oracle"
            }
            check(
                build.installerStateAbsent &&
                    build.distinctFromInstallerRuntime &&
                    build.buildRuntimeClosed
            ) {
                "Local bundle was not produced and released by its fresh isolated runtime"
            }
            val adapterSource = Gate3bLocalRuntimeAdapter.materialize(experimentRoot.toPath())
            val provenanceSource = Gate3bLocalBundleProvenance.materialize(
                experimentRoot = experimentRoot.toPath(),
                source = source,
                build = build,
                adapterSource = adapterSource,
                npmVersion = NPM_VERSION,
            )
            val assembled = Gate3bRunnableWorkspaceAssembler.assemble(
                payloadRoot = source.payloadRoot.toPath(),
                programRoot = source.programRoot.toPath(),
                adapterSource = adapterSource,
                bundleSource = build.output.bundle,
                bundleLicenseSource = build.output.bundleLicense,
                expectedAdapterSha256 = sha256(Files.readAllBytes(adapterSource)),
                expectedBundleSha256 = build.output.bundleSha256,
                expectedBundleBytes = build.output.bundleBytes,
                expectedBundleLicenseSha256 = build.output.bundleLicenseSha256,
                expectedBundleLicenseBytes = build.output.bundleLicenseBytes,
                provenanceSource = provenanceSource,
            )
            Gate3bLocalBundleOutputInspector.removeBuildTree(build.output)
            buildCacheRemoved = !Files.exists(
                build.output.webpackRoot,
                LinkOption.NOFOLLOW_LINKS,
            )
            check(buildCacheRemoved) { "Local Webpack build tree remained in the candidate program" }
            assembled
        } else {
            val supply = StmCoreGate3bPrebuiltExperiment(appContext).verifySupply()
            check(
                supply.manifest.stCommitSha == ST_COMMIT &&
                    supply.manifest.packageLockSha256 == PACKAGE_LOCK_SHA256,
            ) {
                "Fixed runnable sidecars were bound to a different SillyTavern source"
            }
            fixedSupplyManifestSha256 = supply.manifestSha256
            Gate3bRunnableWorkspaceAssembler.assemble(
                payloadRoot = source.payloadRoot.toPath(),
                programRoot = source.programRoot.toPath(),
                adapterSource = File(
                    supply.root,
                    StmCoreGate3bPrebuiltExperiment.ADAPTER_FILE,
                ).toPath(),
                bundleSource = File(
                    supply.root,
                    StmCoreGate3bPrebuiltExperiment.BUNDLE_FILE,
                ).toPath(),
                bundleLicenseSource = File(
                    supply.root,
                    StmCoreGate3bPrebuiltExperiment.BUNDLE_LICENSE_FILE,
                ).toPath(),
                expectedAdapterSha256 = supply.manifest.adapterSha256,
                expectedBundleSha256 = supply.manifest.bundleSha256,
                expectedBundleBytes = supply.manifest.bundleBytes,
                expectedBundleLicenseSha256 = supply.manifest.bundleLicenseSha256,
                expectedBundleLicenseBytes = supply.manifest.bundleLicenseBytes,
            )
        }
        val dependencyRoot = File(source.programRoot, "node_modules").toPath()
        val dependencyBefore = Gate3bTreeScanner.scan(
            dependencyRoot,
            includeManifest = false,
        ).fingerprint
        check(dependencyBefore == expectedDependencyTree) {
            "Candidate dependency tree changed before runnable startup"
        }
        val programBefore = Gate3bTreeScanner.scan(
            source.programRoot.toPath(),
            includeManifest = false,
        ).fingerprint
        val rootBefore = Gate3bTreeScanner.scan(
            source.payloadRoot.toPath(),
            includeManifest = false,
        ).fingerprint
        val processCwdBefore = File(".").canonicalPath
        teardownExpectedProcessCwd = processCwdBefore
        val dataRoot = File(experimentRoot, "runnable-data")
        val sessionRoot = File(experimentRoot, "runnable-session")
        val logsRoot = File(experimentRoot, "runnable-logs")
        val prepared = StmSillyTavernLaunchFactory.prepare(
            slotRoot = source.payloadRoot,
            archiveRoot = source.archiveRoot,
            dataRoot = dataRoot,
            sessionDirectory = sessionRoot,
            logsRoot = logsRoot,
            expectedVersion = ST_VERSION,
        )
        teardownSelectedPort = prepared.selectedPort
        check(!cancelled.get()) { "Stage 3B runnable acceptance was cancelled" }

        val callback = Gate3bRunnableCallback()
        val engine = FeatherEngine(callback)
        activeEngine = engine
        val sessionId = "gate3b-${candidate.name.lowercase()}-${UUID.randomUUID()}"
        val signal = callback.register(sessionId)
        activeCallback = callback
        workspaceLifetime.markEngineStarted()

        var nodeVersion = ""
        var actualPort = 0
        var startMillis = 0L
        var stopMillis = 0L
        var versionEvidence = ""
        var homeEvidence = ""
        var bundleEvidence = ""
        var portReleased = false
        var terminationUsed = false
        var processCwdRestoredAfterTeardown = false
        try {
            check(!cancelled.get()) { "Stage 3B runnable acceptance was cancelled" }
            val startAt = SystemClock.elapsedRealtime()
            engineStartAttempted = true
            engine.start(
                sessionId,
                sessionRoot,
                prepared.launchSpec.withGate3bForbiddenModuleInvariant(),
            )
            check(signal.ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for candidate SillyTavern READY"
            }
            signal.failure?.let { error(it) }
            check(!cancelled.get()) { "Stage 3B runnable acceptance was cancelled" }
            nodeVersion = requireNotNull(signal.nodeVersion)
            actualPort = signal.port
            teardownActualPort = actualPort
            teardownSignalPort = signal.port
            check(actualPort == prepared.selectedPort) {
                "Candidate SillyTavern listened on $actualPort instead of ${prepared.selectedPort}"
            }
            startMillis = SystemClock.elapsedRealtime() - startAt
            val baseUrl = "http://127.0.0.1:$actualPort"
            val version = httpGet("$baseUrl/version")
            check(
                version.code == 200 &&
                    JSONObject(version.body.toString(Charsets.UTF_8)).getString("pkgVersion") == ST_VERSION,
            ) {
                "Candidate SillyTavern /version acceptance failed"
            }
            versionEvidence = "200:${version.body.size}:$ST_VERSION"
            val home = httpGet("$baseUrl/")
            check(
                home.code == 200 &&
                    home.body.toString(Charsets.UTF_8).contains("<title>SillyTavern</title>"),
            ) {
                "Candidate SillyTavern homepage acceptance failed"
            }
            homeEvidence = "200:${home.body.size}"
            val bundle = httpGet("$baseUrl/lib.js")
            val bundleSha256 = sha256(bundle.body)
            check(
                bundle.code == 200 &&
                    bundle.body.size.toLong() == runtimeEvidence.bundleBytes &&
                    bundleSha256 == runtimeEvidence.bundleSha256,
            ) {
                "Candidate SillyTavern /lib.js acceptance failed"
            }
            bundleEvidence = "200:${bundle.body.size}:$bundleSha256"
            check(!cancelled.get()) { "Stage 3B runnable acceptance was cancelled" }

            val stopAt = SystemClock.elapsedRealtime()
            check(engine.requestGracefulStop()) {
                "Feather Engine rejected the candidate SillyTavern stop request"
            }
            check(signal.stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out stopping candidate SillyTavern"
            }
            signal.failure?.let { error(it) }
            stopMillis = SystemClock.elapsedRealtime() - stopAt
            terminationUsed = signal.terminationUsed
            check(!terminationUsed) {
                "Candidate SillyTavern required forced termination"
            }
        } finally {
            teardownActualPort = actualPort
            teardownSignalPort = signal.port
            finishTeardown()
            portReleased = teardownPortReleased
            processCwdRestoredAfterTeardown = teardownProcessCwdRestored
            check(teardownEngineDestroyed) { "Candidate Feather Engine teardown timed out" }
            check(portReleased) { "Candidate SillyTavern loopback port remained open" }
            check(processCwdRestoredAfterTeardown) {
                "Candidate SillyTavern teardown did not restore the Core process CWD"
            }
        }

        val processCwdAfter = File(".").canonicalPath
        val dependencyAfter = Gate3bTreeScanner.scan(
            dependencyRoot,
            includeManifest = false,
        ).fingerprint
        val programAfter = Gate3bTreeScanner.scan(
            source.programRoot.toPath(),
            includeManifest = false,
        ).fingerprint
        val rootAfter = Gate3bTreeScanner.scan(
            source.payloadRoot.toPath(),
            includeManifest = false,
        ).fingerprint
        check(dependencyAfter == dependencyBefore) {
            "Candidate dependency tree changed while running SillyTavern"
        }
        check(programAfter == programBefore) {
            "Candidate program tree changed while running SillyTavern"
        }
        check(rootAfter == rootBefore) {
            "Candidate temporary slot-like root changed while running SillyTavern"
        }
        check(processCwdRestoredAfterTeardown && processCwdAfter == processCwdBefore) {
            "Candidate SillyTavern launch leaked the Core process CWD"
        }
        check(!File(dataRoot, "_webpack").exists()) {
            "Candidate SillyTavern created a Webpack cache despite the fixed adapter"
        }
        val freshBuildRuntimeProved = localBuild?.let { build ->
            build.installerStateAbsent &&
                build.distinctFromInstallerRuntime &&
                build.buildRuntimeClosed
        } == true

        return linkedMapOf(
            "result" to "passed",
            "candidate" to candidate.name,
            "server_ready" to "true",
            "node_version" to nodeVersion,
            "port" to actualPort.toString(),
            "start_ms" to startMillis.toString(),
            "stop_ms" to stopMillis.toString(),
            "version" to versionEvidence,
            "home" to homeEvidence,
            "lib_js" to bundleEvidence,
            "port_released" to portReleased.toString(),
            "engine_start_attempted" to engineStartAttempted.toString(),
            "engine_destroyed" to teardownEngineDestroyed.toString(),
            "build_runtime_closure" to buildRuntimeClosure,
            "checked_port" to teardownCheckedPort.toString(),
            "checked_ports" to teardownCheckedPorts.joinToString(","),
            "termination_used" to terminationUsed.toString(),
            "dependency_tree_unchanged" to (dependencyAfter == dependencyBefore).toString(),
            "program_tree_unchanged" to (programAfter == programBefore).toString(),
            "root_tree_unchanged" to (rootAfter == rootBefore).toString(),
            "process_cwd_restored" to (processCwdAfter == processCwdBefore).toString(),
            "webpack_cache_absent" to (!File(dataRoot, "_webpack").exists()).toString(),
            "source_archive_sha256" to source.sourceArchiveSha256,
            "source_archive_bytes" to source.sourceArchiveBytes.toString(),
            "source_entries" to source.sourceEntries.toString(),
            "source_bytes" to source.sourceBytes.toString(),
            "fixed_supply_manifest_sha256" to fixedSupplyManifestSha256,
            "fixed_adapter_sha256" to if (localBundleBuild) "" else runtimeEvidence.adapterSha256,
            "fixed_adapter_bytes" to if (localBundleBuild) "0" else runtimeEvidence.adapterBytes.toString(),
            "fixed_bundle_sha256" to if (localBundleBuild) "" else runtimeEvidence.bundleSha256,
            "fixed_bundle_bytes" to if (localBundleBuild) "0" else runtimeEvidence.bundleBytes.toString(),
            "fixed_bundle_license_sha256" to if (localBundleBuild) {
                ""
            } else {
                runtimeEvidence.bundleLicenseSha256
            },
            "fixed_bundle_license_bytes" to if (localBundleBuild) {
                "0"
            } else {
                runtimeEvidence.bundleLicenseBytes.toString()
            },
            "adapter_sha256" to runtimeEvidence.adapterSha256,
            "adapter_bytes" to runtimeEvidence.adapterBytes.toString(),
            "bundle_sha256" to runtimeEvidence.bundleSha256,
            "bundle_bytes" to runtimeEvidence.bundleBytes.toString(),
            "bundle_license_sha256" to runtimeEvidence.bundleLicenseSha256,
            "bundle_license_bytes" to runtimeEvidence.bundleLicenseBytes.toString(),
            "bundle_origin" to if (localBundleBuild) {
                "device_local_upstream_build"
            } else {
                "common_fixed_signed_fixture"
            },
            "runtime_fixture" to if (localBundleBuild) {
                "device_local_upstream_bundle_plus_debug_adapter"
            } else {
                "common_fixed_signed_fixture_not_candidate_dependency"
            },
            "root_kind" to "writable_temporary_root",
            "observed_root_identity" to "unchanged_before_after_runtime",
            "immutability_basis" to "pre_post_full_tree_manifest_observation_only",
            "slot_admission" to "not_requested",
            "candidate_dependency_tree_signed" to "false",
            "candidate_dependency_attachment" to if (localBundleBuild) {
                "atomic_move_after_npm_runtime_closed_before_fresh_local_build_runtime"
            } else {
                "atomic_move_after_installer_runtime_closed"
            },
            "runtime_kit_dependency_tree_claim_used" to "false",
            "signed_prebuilt_bundle_used" to (!localBundleBuild).toString(),
            "local_bundle_build" to localBundleBuild.toString(),
            "local_build_same_installer_runtime" to
                (localBuild != null && !localBuild.distinctFromInstallerRuntime).toString(),
            "local_build_fresh_runtime" to freshBuildRuntimeProved.toString(),
            "npm_runtime_closed_before_local_build" to
                npmRuntimeClosedBeforeLocalBuild.toString(),
            "local_build_cache_removed" to buildCacheRemoved.toString(),
            "local_build_fixed_source_oracle_matched" to fixedSourceOracleMatched.toString(),
            "runtime_forbidden_module_invariant" to
                "startup_and_shutdown_hard_gate",
            "local_provenance_sha256" to runtimeEvidence.provenanceSha256.orEmpty(),
            "local_provenance_bytes" to runtimeEvidence.provenanceBytes.toString(),
            "workspace_capability" to "debug_only_not_a_committed_slot",
            "meaning" to "candidate_runnable_acceptance_only_not_gate_passed_not_ready",
            "elapsed_ms" to (SystemClock.elapsedRealtime() - started).toString(),
        ).apply {
            localBuild?.let { build ->
                put("local_build_node_version", build.nodeVersion)
                put("local_build_webpack_version", build.webpackVersion)
                put("local_build_elapsed_ms", build.elapsedMillis.toString())
                put("local_build_log_sha256", build.logSha256)
                put("local_build_log_characters", build.logCharacters.toString())
                put("local_build_cache_version", build.output.cacheVersion)
                put("local_build_process_cwd_restored", build.processCwdRestored.toString())
                put("local_build_fresh_runtime_closed", build.buildRuntimeClosed.toString())
                put("local_build_installer_state_absent", build.installerStateAbsent.toString())
                put(
                    "local_build_distinct_from_installer_runtime",
                    build.distinctFromInstallerRuntime.toString(),
                )
                put("local_build_runtime_nonce_sha256", sha256(build.buildRuntimeNonce))
                put("local_build_compiler_run_calls", build.compilerRunCalls.toString())
                put(
                    "local_build_compiler_callback_calls",
                    build.compilerCallbackCalls.toString(),
                )
                put("local_build_compiler_error_absent", build.compilerErrorAbsent.toString())
                put("local_build_compiler_stats_present", build.compilerStatsPresent.toString())
                put(
                    "local_build_compiler_stats_has_errors",
                    build.compilerStatsHasErrors.toString(),
                )
                put(
                    "local_build_compiler_stats_error_count",
                    build.compilerStatsErrorCount.toString(),
                )
                put(
                    "local_build_compiler_lib_asset_present",
                    build.compilerLibAssetPresent.toString(),
                )
                put("local_build_compiler_close_calls", build.compilerCloseCalls.toString())
                put(
                    "local_build_compiler_close_callback_calls",
                    build.compilerCloseCallbackCalls.toString(),
                )
                put(
                    "local_build_compiler_close_error_absent",
                    build.compilerCloseErrorAbsent.toString(),
                )
                put(
                    "local_build_compiler_close_same_instance",
                    build.compilerCloseSameInstance.toString(),
                )
                put(
                    "local_bundle_matches_historical_fixture",
                    (build.output.bundleSha256 == HISTORICAL_BUNDLE_SHA256).toString(),
                )
                build.inputSha256.forEach { (name, sha256) ->
                    put("local_build_input_${name}_sha256", sha256)
                }
            }
        }.also {
            requireGate3bNotCancelled(cancelled, "Stage 3B runnable acceptance")
        }
    }

    private fun requireSourceArchive(): File {
        val downloads = requireNotNull(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        ) {
            "App external Download directory is unavailable"
        }.canonicalFile
        val source = File(downloads, SOURCE_ARCHIVE_FILE).absoluteFile
        check(
            !Files.isSymbolicLink(source.toPath()) &&
                source.canonicalFile.parentFile == downloads &&
                Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                source.length() == SOURCE_ARCHIVE_BYTES,
        ) {
            "The fixed exact-commit source archive is missing or unsafe"
        }
        return source
    }

    private fun httpGet(url: String): Gate3bRunnableHttpEvidence {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MILLIS
            connection.readTimeout = HTTP_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            val code = connection.responseCode
            val source = if (code in 200..399) connection.inputStream else connection.errorStream
            Gate3bRunnableHttpEvidence(code, source?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitPortsReleased(ports: List<Int>): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PORT_RELEASE_TIMEOUT_MILLIS
        while (
            ports.any(::isPortOpen) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(PORT_POLL_MILLIS)
        }
        return ports.none(::isPortOpen)
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PORT_CONNECT_TIMEOUT_MILLIS)
        }
    }.isSuccess

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val ST_REPOSITORY = StmCoreGate3bPrebuiltExperiment.ST_REPOSITORY
        const val ST_COMMIT = StmCoreGate3bPrebuiltExperiment.ST_COMMIT
        const val ST_VERSION = StmCoreGate3bPrebuiltExperiment.ST_VERSION
        const val PACKAGE_LOCK_SHA256 = StmCoreGate3bPrebuiltExperiment.PACKAGE_LOCK_SHA256
        const val NPM_VERSION = "11.6.2"
        const val SOURCE_ARCHIVE_FILE = "sillytavern-release-$ST_COMMIT.zip"
        const val SOURCE_DOWNLOAD_URL =
            "https://github.com/SillyTavern/SillyTavern/archive/$ST_COMMIT.zip"
        const val SOURCE_ARCHIVE_BYTES = 38_459_064L
        const val SOURCE_ARCHIVE_SHA256 =
            "92ce95bd95f277e73c8aa6efb57f34821136262076a756efd19ffbaa58773b03"
        const val HISTORICAL_BUNDLE_SHA256 =
            "2d5fb1eedcbefe7062421e8ca54b90a23312f64df8d480c16538714c5157e0bf"
        const val HISTORICAL_BUNDLE_BYTES = 1_947_206L
        const val HISTORICAL_BUNDLE_LICENSE_SHA256 =
            "7d9c6fd5c043071752d853a02c63fbb9a7828157265ff1a90b75edaf6f5a9fc0"
        const val HISTORICAL_BUNDLE_LICENSE_BYTES = 1_283L
        const val START_TIMEOUT_SECONDS = 240L
        const val STOP_TIMEOUT_SECONDS = 15L
        const val ENGINE_DESTROY_TIMEOUT_SECONDS = 12L
        const val HTTP_TIMEOUT_MILLIS = 10_000
        const val PORT_RELEASE_TIMEOUT_MILLIS = 5_000L
        const val PORT_CONNECT_TIMEOUT_MILLIS = 200
        const val PORT_POLL_MILLIS = 50L
    }
}

private class Gate3bRunnableCallback : FeatherEngine.Callback {
    private val sessions = ConcurrentHashMap<String, Gate3bRunnableSignal>()
    private val cancellationLock = Any()
    private var cancellationDetail: String? = null

    fun register(sessionId: String): Gate3bRunnableSignal = Gate3bRunnableSignal().also { signal ->
        synchronized(cancellationLock) {
            check(sessions.putIfAbsent(sessionId, signal) == null) {
                "Duplicate Stage 3B runnable session"
            }
            cancellationDetail?.let(signal::cancel)
        }
    }

    fun cancelAll(detail: String) {
        synchronized(cancellationLock) {
            cancellationDetail = detail
            sessions.values.forEach { signal -> signal.cancel(detail) }
        }
    }

    override fun onNodeCreated(sessionId: String, nodeVersion: String) {
        sessions[sessionId]?.nodeVersion = nodeVersion
    }

    override fun onReady(sessionId: String, port: Int, nodeVersion: String) {
        sessions[sessionId]?.let { signal ->
            signal.nodeVersion = nodeVersion
            signal.port = port
            signal.ready.countDown()
        }
    }

    override fun onStopped(sessionId: String, terminationUsed: Boolean) {
        sessions[sessionId]?.let { signal ->
            signal.terminationUsed = terminationUsed
            signal.stopped.countDown()
        }
    }

    override fun onFailure(sessionId: String, detail: String) {
        sessions[sessionId]?.let { signal ->
            signal.failure = detail
            signal.ready.countDown()
            signal.stopped.countDown()
        }
    }
}

private class Gate3bRunnableSignal {
    val ready = CountDownLatch(1)
    val stopped = CountDownLatch(1)

    @Volatile
    var nodeVersion: String? = null

    @Volatile
    var port: Int = 0

    @Volatile
    var failure: String? = null

    @Volatile
    var terminationUsed: Boolean = false

    fun cancel(detail: String) {
        failure = detail
        ready.countDown()
        stopped.countDown()
    }
}

private data class Gate3bRunnableHttpEvidence(
    val code: Int,
    val body: ByteArray,
)
