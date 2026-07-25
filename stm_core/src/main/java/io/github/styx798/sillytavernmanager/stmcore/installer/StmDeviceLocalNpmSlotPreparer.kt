package io.github.styx798.sillytavernmanager.stmcore.installer

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.enums.V8RuntimeTerminationMode
import com.caoccao.javet.interop.NodeRuntime
import io.github.styx798.sillytavernmanager.stmcore.FeatherEngine
import io.github.styx798.sillytavernmanager.stmcore.LoopbackHealthProbe
import io.github.styx798.sillytavernmanager.stmcore.LoopbackProbeResult
import io.github.styx798.sillytavernmanager.stmcore.StmNodeRuntimeFactory
import io.github.styx798.sillytavernmanager.stmcore.StmSillyTavernLaunchFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal enum class StmRuntimeSlotPreparationPhase {
    PREPARING_TOOLCHAIN,
    INSTALLING_DEPENDENCIES,
    BUILDING_BUNDLE,
    ASSEMBLING_RUNTIME,
    RUNNABLE_ACCEPTANCE,
}

internal data class StmRuntimeSlotPreparationRequest(
    val operationId: String,
    val operationRoot: File,
    val payloadDirectory: File,
    val archiveRoot: String,
    val repository: String,
    val commitSha: String,
    val stVersion: String,
    val packageLockSha256: String,
)

internal enum class StmRuntimeSlotPreparationErrorCode {
    OPERATION_CANCELLED,
    TOOLCHAIN_FAILED,
    DEPENDENCY_INSTALL_FAILED,
    DEPENDENCY_TREE_REJECTED,
    BUNDLE_BUILD_FAILED,
    RUNTIME_ASSEMBLY_FAILED,
    RUNNABLE_ACCEPTANCE_FAILED,
}

internal class StmRuntimeSlotPreparationException(
    val code: StmRuntimeSlotPreparationErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun interface StmRuntimeSlotPreparer {
    @Throws(StmRuntimeSlotPreparationException::class)
    fun prepare(
        request: StmRuntimeSlotPreparationRequest,
        cancellation: StmExtractionCancellation,
        onPhase: (StmRuntimeSlotPreparationPhase) -> Unit,
    ): StmRuntimeSlotAdmissionEvidence
}

private val STM_WEBPACK_CACHE_VERSION_PATTERN = Regex("^[0-9a-f]{16}$")

internal fun stmIsSafeWebpackCacheVersion(value: String): Boolean =
    STM_WEBPACK_CACHE_VERSION_PATTERN.matches(value)

/**
 * Production Stage 3B path. Everything it writes remains in the caller-owned operation staging
 * until [StmSlotStore] performs the single READY admission and atomic slot move.
 */
internal class StmDeviceLocalNpmSlotPreparer(
    private val appContext: Context,
    private val toolchain: StmBundledNpmToolchain =
        StmBundledNpmToolchainFactory.create(appContext.applicationContext),
    private val npmTimeoutMillis: Long = NPM_TIMEOUT_MILLIS,
    private val bundleTimeoutMillis: Long = BUNDLE_TIMEOUT_MILLIS,
) : StmRuntimeSlotPreparer {
    override fun prepare(
        request: StmRuntimeSlotPreparationRequest,
        cancellation: StmExtractionCancellation,
        onPhase: (StmRuntimeSlotPreparationPhase) -> Unit,
    ): StmRuntimeSlotAdmissionEvidence {
        val operation = requireDirectory(request.operationRoot.toPath(), "installer operation")
        val payload = requireDirectory(request.payloadDirectory.toPath(), "source payload")
        check(payload.parent == operation && payload.fileName.toString() == PAYLOAD_DIRECTORY) {
            "Source payload is not the direct payload child of its installer operation"
        }
        check(SAFE_ARCHIVE_ROOT.matches(request.archiveRoot)) { "Source archive root is unsafe" }
        val program = requireDirectory(payload.resolve(request.archiveRoot), "SillyTavern program")
        check(program.parent == payload) { "SillyTavern program escaped its source payload" }
        check(!Files.exists(program.resolve(NODE_MODULES), LinkOption.NOFOLLOW_LINKS)) {
            "Verified source payload already contains node_modules"
        }
        requireRegular(program.resolve("package.json"), "SillyTavern package.json")
        val packageLock = requireRegular(
            program.resolve("package-lock.json"),
            "SillyTavern package-lock.json",
        )
        check(sha256(packageLock) == request.packageLockSha256) {
            "Verified package-lock identity changed before dependency installation"
        }
        throwIfCancelled(cancellation)

        onPhase(StmRuntimeSlotPreparationPhase.PREPARING_TOOLCHAIN)
        val preparedToolchain = try {
            toolchain.prepare(cancellation)
        } catch (error: Exception) {
            throw mappedFailure(
                cancellation,
                StmRuntimeSlotPreparationErrorCode.TOOLCHAIN_FAILED,
                "Bundled npm toolchain preparation failed",
                error,
            )
        }

        val cache = freshDirectory(operation, NPM_CACHE_DIRECTORY)
        val temporary = freshDirectory(operation, NPM_TEMP_DIRECTORY)
        val npmResult = try {
            onPhase(StmRuntimeSlotPreparationPhase.INSTALLING_DEPENDENCIES)
            runNpmCli(
                npmRoot = preparedToolchain.npmDirectory.toPath(),
                program = program,
                cache = cache,
                temporary = temporary,
                cancellation = cancellation,
            )
        } catch (error: Exception) {
            throw mappedFailure(
                cancellation,
                StmRuntimeSlotPreparationErrorCode.DEPENDENCY_INSTALL_FAILED,
                "npm CLI dependency installation failed",
                error,
            )
        }
        check(sha256(packageLock) == request.packageLockSha256) {
            "npm CLI changed the verified package-lock identity"
        }
        val dependencyRoot = requireDirectory(program.resolve(NODE_MODULES), "installed node_modules")
        val dependencyTree = try {
            scanTree(
                root = dependencyRoot,
                includeRoot = true,
                rootName = NODE_MODULES,
                cancellation = cancellation,
            )
        } catch (error: Exception) {
            throw mappedFailure(
                cancellation,
                StmRuntimeSlotPreparationErrorCode.DEPENDENCY_TREE_REJECTED,
                "Installed dependency tree was rejected",
                error,
            )
        }

        val bundle = try {
            onPhase(StmRuntimeSlotPreparationPhase.BUILDING_BUNDLE)
            buildBundle(
                program = program,
                operationId = request.operationId,
                npmRuntimeNonce = npmResult.runtimeNonce,
                cancellation = cancellation,
            )
        } catch (error: Exception) {
            throw mappedFailure(
                cancellation,
                StmRuntimeSlotPreparationErrorCode.BUNDLE_BUILD_FAILED,
                "Device-local lib.js build failed",
                error,
            )
        }

        val runtime = try {
            onPhase(StmRuntimeSlotPreparationPhase.ASSEMBLING_RUNTIME)
            assembleRuntime(
                request = request,
                payload = payload,
                program = program,
                dependencyTree = dependencyTree,
                toolchain = preparedToolchain,
                npmResult = npmResult,
                bundle = bundle,
                cancellation = cancellation,
            )
        } catch (error: Exception) {
            throw mappedFailure(
                cancellation,
                StmRuntimeSlotPreparationErrorCode.RUNTIME_ASSEMBLY_FAILED,
                "Device-local runtime assembly failed",
                error,
            )
        } finally {
            runCatching { removeBundleBuildTree(bundle) }
        }

        val acceptance = try {
            onPhase(StmRuntimeSlotPreparationPhase.RUNNABLE_ACCEPTANCE)
            acceptRunnable(
                request = request,
                payload = payload,
                program = program,
                operation = operation,
                expectedBundle = runtime.bundleBinding,
                cancellation = cancellation,
            )
        } catch (error: Exception) {
            throw mappedFailure(
                cancellation,
                StmRuntimeSlotPreparationErrorCode.RUNNABLE_ACCEPTANCE_FAILED,
                "Staged SillyTavern runnable acceptance failed",
                error,
            )
        }

        try {
            return finalizeEvidence(
                request = request,
                program = program,
                runtime = runtime,
                dependencyTree = dependencyTree,
                toolchain = preparedToolchain,
                npmResult = npmResult,
                bundle = bundle,
                acceptance = acceptance,
                cancellation = cancellation,
            )
        } catch (error: Exception) {
            throw mappedFailure(
                cancellation,
                StmRuntimeSlotPreparationErrorCode.RUNTIME_ASSEMBLY_FAILED,
                "Device-local runtime evidence finalization failed",
                error,
            )
        }
    }

    private fun runNpmCli(
        npmRoot: Path,
        program: Path,
        cache: Path,
        temporary: Path,
        cancellation: StmExtractionCancellation,
    ): NpmInstallResult {
        requireRegular(npmRoot.resolve("bin/npm-cli.js"), "npm CLI executable entry")
        requireRegular(npmRoot.resolve("lib/cli.js"), "npm CLI JavaScript entry")
        requireRegular(npmRoot.resolve("lib/npm.js"), "npm lifecycle entry")
        val runtime = StmNodeRuntimeFactory.create(arrayOf("stm-core-installer-npm"))
        val started = SystemClock.elapsedRealtime()
        var primaryFailure: Throwable? = null
        try {
            runtime.getExecutor(
                npmBootstrap(
                    npmRoot = npmRoot,
                    program = program,
                    cache = cache,
                    temporary = temporary,
                    proxy = androidSystemProxyUrl(),
                ),
            ).executeVoid()
            awaitJavascriptCompletion(
                runtime = runtime,
                doneExpression = "Boolean(globalThis.__stmNpmInstall?.done)",
                timeoutMillis = npmTimeoutMillis,
                cancellation = cancellation,
                timeoutMessage = "npm CLI exceeded its installation time budget",
            )
            val error = runtime.getExecutor(
                "String(globalThis.__stmNpmInstall?.error || '')",
            ).executeString().orEmpty()
            check(error.isBlank()) { error.take(MAX_ERROR_CHARS) }
            check(
                runtime.getExecutor(
                    "Boolean(globalThis.__stmNpmInstall?.promiseSettled)",
                ).executeBoolean(),
            ) {
                "npm CLI reported completion before its Promise settled"
            }
            val nonce = runtime.getExecutor(
                "String(globalThis.__stmNpmInstall?.runtimeNonce || '')",
            ).executeString().orEmpty()
            check(nonce.isNotBlank()) { "npm installer runtime identity is missing" }
            val output = runtime.getExecutor(
                "String(globalThis.__stmNpmInstall?.output || '')",
            ).executeString().orEmpty()
            return NpmInstallResult(
                runtimeNonce = nonce,
                elapsedMillis = SystemClock.elapsedRealtime() - started,
                outputSha256 = sha256(output.toByteArray()),
                outputCharacters = output.length,
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            closeInstallerRuntime(runtime, npmRestorationScript(), primaryFailure)
        }
    }

    private fun buildBundle(
        program: Path,
        operationId: String,
        npmRuntimeNonce: String,
        cancellation: StmExtractionCancellation,
    ): LocalBundleResult {
        val publicLib = requireRegular(program.resolve("public/lib.js"), "public/lib.js")
        val webpackConfig = requireRegular(program.resolve("webpack.config.js"), "webpack.config.js")
        val buildEntry = requireRegular(program.resolve("docker/build-lib.js"), "docker/build-lib.js")
        val packageLock = requireRegular(program.resolve("package-lock.json"), "package-lock.json")
        val webpackPackage = requireRegular(
            program.resolve("node_modules/webpack/package.json"),
            "installed Webpack package",
        )
        val webpackVersion = JSONObject(webpackPackage.toFile().readText()).getString("version")
        check(VERSION_PATTERN.matches(webpackVersion)) { "Installed Webpack version is invalid" }
        val dist = program.resolve("dist")
        val distExistedBefore = Files.exists(dist, LinkOption.NOFOLLOW_LINKS)
        if (distExistedBefore) {
            requireDirectory(dist, "SillyTavern dist")
        }
        val webpackRoot = dist.resolve("_webpack")
        check(!Files.exists(webpackRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Local bundle build requires a fresh dist/_webpack directory"
        }

        val runtime = StmNodeRuntimeFactory.create(arrayOf("stm-core-installer-bundle"))
        val started = SystemClock.elapsedRealtime()
        var primaryFailure: Throwable? = null
        try {
            val nodeVersion = runtime.getExecutor("process.version").executeString().orEmpty()
            runtime.getExecutor(
                bundleBootstrap(
                    program = program,
                    buildEntry = buildEntry,
                    operationId = operationId,
                    npmRuntimeNonce = npmRuntimeNonce,
                ),
            ).executeVoid()
            awaitJavascriptCompletion(
                runtime = runtime,
                doneExpression = "Boolean(globalThis.__stmLocalBundle?.done)",
                timeoutMillis = bundleTimeoutMillis,
                cancellation = cancellation,
                timeoutMessage = "Device-local lib.js build exceeded its time budget",
            )
            val error = runtime.getExecutor(
                "String(globalThis.__stmLocalBundle?.error || '')",
            ).executeString().orEmpty()
            val output = runtime.getExecutor(
                "String(globalThis.__stmLocalBundle?.output || '')",
            ).executeString().orEmpty()
            check(error.isBlank()) { error.take(MAX_ERROR_CHARS) }
            val compiler = JSONObject(
                runtime.getExecutor(
                    """
                    JSON.stringify({
                      runCalls: Number(globalThis.__stmLocalBundle?.compilerRunCalls || 0),
                      callbackCalls: Number(globalThis.__stmLocalBundle?.compilerCallbackCalls || 0),
                      statsPresent: Boolean(globalThis.__stmLocalBundle?.compilerStatsPresent),
                      statsHasErrors: Boolean(globalThis.__stmLocalBundle?.compilerStatsHasErrors),
                      statsErrorCount: Number(globalThis.__stmLocalBundle?.compilerStatsErrorCount ?? -1),
                      libAssetPresent: Boolean(globalThis.__stmLocalBundle?.compilerLibAssetPresent),
                      closeCalls: Number(globalThis.__stmLocalBundle?.compilerCloseCalls || 0),
                      closeCallbackCalls: Number(globalThis.__stmLocalBundle?.compilerCloseCallbackCalls || 0),
                      closeError: String(globalThis.__stmLocalBundle?.compilerCloseError || ''),
                      closeSameInstance: Boolean(globalThis.__stmLocalBundle?.compilerCloseSameInstance),
                      runtimeNonce: String(globalThis.__stmLocalBundle?.runtimeNonce || ''),
                      npmStateAbsent: Boolean(globalThis.__stmLocalBundle?.npmStateAbsent),
                    })
                    """.trimIndent(),
                ).executeString().orEmpty(),
            )
            check(
                compiler.getInt("runCalls") == 1 &&
                    compiler.getInt("callbackCalls") == 1 &&
                    compiler.getBoolean("statsPresent") &&
                    !compiler.getBoolean("statsHasErrors") &&
                    compiler.getInt("statsErrorCount") == 0 &&
                    compiler.getBoolean("libAssetPresent") &&
                    compiler.getInt("closeCalls") == 1 &&
                    compiler.getInt("closeCallbackCalls") == 1 &&
                    compiler.getString("closeError").isBlank() &&
                    compiler.getBoolean("closeSameInstance") &&
                    compiler.getBoolean("npmStateAbsent") &&
                    compiler.getString("runtimeNonce").let {
                        it.isNotBlank() && it != npmRuntimeNonce
                    }
            ) {
                "Webpack compiler lifecycle did not prove one error-free closed lib.js build"
            }
            val outputRoot = locateBundleOutput(program)
            val bundle = requireRegular(outputRoot.resolve("lib.js"), "locally generated lib.js")
            val license = requireRegular(
                outputRoot.resolve("lib.js.LICENSE.txt"),
                "locally generated lib.js license",
            )
            check(Files.size(bundle) in 1..MAX_BUNDLE_BYTES) {
                "Locally generated lib.js length is invalid"
            }
            check(Files.size(license) in 1..MAX_SIDECAR_BYTES) {
                "Locally generated lib.js license length is invalid"
            }
            return LocalBundleResult(
                webpackRoot = webpackRoot,
                distExistedBefore = distExistedBefore,
                bundle = bundle,
                bundleLicense = license,
                bundleBinding = binding(bundle),
                bundleLicenseBinding = binding(license),
                nodeVersion = nodeVersion,
                webpackVersion = webpackVersion,
                elapsedMillis = SystemClock.elapsedRealtime() - started,
                logSha256 = sha256(output.toByteArray()),
                logCharacters = output.length,
                inputSha256 = linkedMapOf(
                    "public_lib" to sha256(publicLib),
                    "webpack_config" to sha256(webpackConfig),
                    "build_lib" to sha256(buildEntry),
                    "package_lock" to sha256(packageLock),
                ),
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            closeInstallerRuntime(runtime, bundleRestorationScript(), primaryFailure)
        }
    }

    private fun assembleRuntime(
        request: StmRuntimeSlotPreparationRequest,
        payload: Path,
        program: Path,
        dependencyTree: TreeScan,
        toolchain: StmPreparedNpmToolchain,
        npmResult: NpmInstallResult,
        bundle: LocalBundleResult,
        cancellation: StmExtractionCancellation,
    ): AssembledRuntime {
        throwIfCancelled(cancellation)
        val runtime = payload.resolve(StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY)
        check(!Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) {
            "Source payload already contains reserved runtime evidence"
        }
        Files.createDirectory(runtime)
        val adapter = runtime.resolve(StmRuntimeSlotAdmissionEvidence.ADAPTER_FILE)
        writeSynced(adapter, RUNTIME_ADAPTER.toByteArray())
        copyRegular(bundle.bundle, runtime.resolve(StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE))
        copyRegular(
            bundle.bundleLicense,
            runtime.resolve(StmRuntimeSlotAdmissionEvidence.BUNDLE_LICENSE_FILE),
        )
        // The Webpack cache is installation scratch, not part of the immutable runtime program.
        // Copy the two frozen outputs first, then remove the whole build tree before identity scan.
        removeBundleBuildTree(bundle)
        replaceProgramAdapter(
            program = program,
            source = adapter,
        )
        val programTree = scanTree(
            root = program,
            includeRoot = false,
            rootName = "",
            cancellation = cancellation,
        )
        val bundleBinding = binding(
            runtime.resolve(StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE),
        )
        check(bundleBinding == bundle.bundleBinding) {
            "Frozen bundle changed while entering the staged runtime"
        }
        return AssembledRuntime(
            runtimeDirectory = runtime,
            dependencyTreeSha256 = dependencyTree.sha256,
            programTreeSha256 = programTree.sha256,
            bundleBinding = bundleBinding,
            initialManifestValues = linkedMapOf(
                "format_version" to "1",
                "provenance_kind" to "device-local-upstream-build",
                "repository" to request.repository,
                "commit_sha" to request.commitSha,
                "st_version" to request.stVersion,
                "package_lock_sha256" to request.packageLockSha256,
                "dependency_tree_sha256" to dependencyTree.sha256,
                "post_adapter_program_tree_sha256" to programTree.sha256,
                "npm_version" to toolchain.npmVersion,
                "npm_toolchain_manifest_sha256" to toolchain.manifestSha256,
                "npm_toolchain_archive_sha256" to toolchain.archiveSha256,
                "npm_toolchain_tree_sha256" to toolchain.treeSha256,
                "npm_toolchain_reused" to toolchain.reused.toString(),
                "node_version" to bundle.nodeVersion,
                "webpack_version" to bundle.webpackVersion,
                "npm_elapsed_ms" to npmResult.elapsedMillis.toString(),
                "npm_output_sha256" to npmResult.outputSha256,
                "npm_output_characters" to npmResult.outputCharacters.toString(),
                "bundle_elapsed_ms" to bundle.elapsedMillis.toString(),
                "bundle_log_sha256" to bundle.logSha256,
                "bundle_log_characters" to bundle.logCharacters.toString(),
                "bundle_sha256" to bundle.bundleBinding.sha256,
                "bundle_bytes" to bundle.bundleBinding.bytes.toString(),
                "bundle_license_sha256" to bundle.bundleLicenseBinding.sha256,
                "bundle_license_bytes" to bundle.bundleLicenseBinding.bytes.toString(),
            ).apply {
                bundle.inputSha256.forEach { (name, value) ->
                    put("input_${name}_sha256", value)
                }
            },
        )
    }

    private fun acceptRunnable(
        request: StmRuntimeSlotPreparationRequest,
        payload: Path,
        program: Path,
        operation: Path,
        expectedBundle: StmRuntimeFileBinding,
        cancellation: StmExtractionCancellation,
    ): RunnableAcceptance {
        throwIfCancelled(cancellation)
        val data = freshDirectory(operation, ACCEPTANCE_DATA_DIRECTORY)
        val session = freshDirectory(operation, ACCEPTANCE_SESSION_DIRECTORY)
        val logs = freshDirectory(operation, ACCEPTANCE_LOGS_DIRECTORY)
        val programBefore = scanTree(program, false, "", cancellation).sha256
        val prepared = StmSillyTavernLaunchFactory.prepare(
            slotRoot = payload.toFile(),
            archiveRoot = request.archiveRoot,
            dataRoot = data.toFile(),
            sessionDirectory = session.toFile(),
            logsRoot = logs.toFile(),
            expectedVersion = request.stVersion,
        )
        val callback = AcceptanceCallback()
        val engine = FeatherEngine(callback)
        val sessionId = "install-${request.operationId}"
        val signal = callback.register(sessionId)
        val started = SystemClock.elapsedRealtime()
        var port = 0
        var stopElapsed = 0L
        try {
            engine.start(sessionId, session.toFile(), prepared.launchSpec)
            awaitLatch(signal.ready, START_TIMEOUT_MILLIS, cancellation) {
                "Timed out waiting for staged SillyTavern READY"
            }
            signal.failure?.let { error(it) }
            port = signal.port
            check(port == prepared.selectedPort) {
                "Staged SillyTavern listened on $port instead of ${prepared.selectedPort}"
            }
            val base = "http://127.0.0.1:$port"
            val version = LoopbackHealthProbe.capture(base, "/version")
            check(version is LoopbackProbeResult.Healthy) {
                "Staged SillyTavern /version acceptance failed"
            }
            val home = httpGet("$base/")
            check(
                home.code == 200 &&
                    home.body.toString(Charsets.UTF_8).contains("<title>SillyTavern</title>"),
            ) {
                "Staged SillyTavern homepage acceptance failed"
            }
            val servedBundle = httpGet("$base/lib.js")
            check(
                servedBundle.code == 200 &&
                    servedBundle.body.size.toLong() == expectedBundle.bytes &&
                    sha256(servedBundle.body) == expectedBundle.sha256
            ) {
                "Staged SillyTavern /lib.js acceptance failed"
            }
            throwIfCancelled(cancellation)
            val stopStarted = SystemClock.elapsedRealtime()
            check(engine.requestGracefulStop()) {
                "Feather Engine rejected staged SillyTavern stop"
            }
            awaitLatch(signal.stopped, STOP_TIMEOUT_MILLIS, cancellation) {
                "Timed out stopping staged SillyTavern"
            }
            signal.failure?.let { error(it) }
            check(!signal.terminationUsed) {
                "Staged SillyTavern required forced termination"
            }
            stopElapsed = SystemClock.elapsedRealtime() - stopStarted
            return RunnableAcceptance(
                nodeVersion = requireNotNull(signal.nodeVersion),
                port = port,
                startElapsedMillis = stopStarted - started,
                stopElapsedMillis = stopElapsed,
                homepageBytes = home.body.size,
                bundleSha256 = expectedBundle.sha256,
                bundleBytes = expectedBundle.bytes,
            )
        } finally {
            callback.cancelAll("Runnable acceptance ended")
            check(engine.destroyAndAwait(ENGINE_DESTROY_TIMEOUT_SECONDS)) {
                "Staged Feather Engine teardown timed out"
            }
            if (port in 1..65_535) {
                check(awaitPortReleased(port)) {
                    "Staged SillyTavern loopback port remained open"
                }
            }
            check(scanTree(program, false, "", StmExtractionCancellation.NONE).sha256 == programBefore) {
                "Staged program changed during runnable acceptance"
            }
            check(!Files.exists(data.resolve("_webpack"), LinkOption.NOFOLLOW_LINKS)) {
                "Runtime created a forbidden Webpack cache"
            }
        }
    }

    private fun finalizeEvidence(
        request: StmRuntimeSlotPreparationRequest,
        program: Path,
        runtime: AssembledRuntime,
        dependencyTree: TreeScan,
        toolchain: StmPreparedNpmToolchain,
        npmResult: NpmInstallResult,
        bundle: LocalBundleResult,
        acceptance: RunnableAcceptance,
        cancellation: StmExtractionCancellation,
    ): StmRuntimeSlotAdmissionEvidence {
        val root = runtime.runtimeDirectory
        val treeManifest = root.resolve(StmRuntimeSlotAdmissionEvidence.TREE_MANIFEST_FILE)
        writeSynced(treeManifest, encodeTreeManifest(dependencyTree.entries))
        val packageEvidence = collectPackageEvidence(program.resolve(NODE_MODULES), cancellation)
        val sbom = root.resolve(StmRuntimeSlotAdmissionEvidence.SBOM_FILE)
        writeSynced(sbom, encodeSbom(request, packageEvidence))
        val licenses = root.resolve(StmRuntimeSlotAdmissionEvidence.LICENSE_MANIFEST_FILE)
        writeSynced(licenses, encodeLicenses(packageEvidence))
        val prune = root.resolve(StmRuntimeSlotAdmissionEvidence.PRUNE_POLICY_FILE)
        writeSynced(prune, "LOCKFILE_COMPLETE\n".toByteArray())
        val acceptanceFile = root.resolve(
            StmRuntimeSlotAdmissionEvidence.RUNNABLE_ACCEPTANCE_FILE,
        )
        writeSynced(
            acceptanceFile,
            buildString {
                appendLine("format_version=1")
                appendLine("repository=${request.repository}")
                appendLine("commit_sha=${request.commitSha}")
                appendLine("st_version=${request.stVersion}")
                appendLine("node_version=${acceptance.nodeVersion}")
                appendLine("loopback_port=${acceptance.port}")
                appendLine("start_elapsed_ms=${acceptance.startElapsedMillis}")
                appendLine("stop_elapsed_ms=${acceptance.stopElapsedMillis}")
                appendLine("homepage_bytes=${acceptance.homepageBytes}")
                appendLine("bundle_sha256=${acceptance.bundleSha256}")
                appendLine("bundle_bytes=${acceptance.bundleBytes}")
                appendLine("graceful_stop=true")
                appendLine("port_released=true")
                appendLine("program_tree_unchanged=true")
                appendLine("runtime_webpack_loads=0")
            }.toByteArray(),
        )
        val manifest = root.resolve(StmRuntimeSlotAdmissionEvidence.MANIFEST_FILE)
        val manifestValues = runtime.initialManifestValues.toMutableMap().apply {
            put("tree_manifest_sha256", sha256(treeManifest))
            put("sbom_sha256", sha256(sbom))
            put("license_manifest_sha256", sha256(licenses))
            put("prune_policy_sha256", sha256(prune))
            put("runnable_acceptance_sha256", sha256(acceptanceFile))
            put("package_count", packageEvidence.size.toString())
            put(
                "package_license_gap_count",
                packageEvidence.count { it.licenseFiles.isEmpty() }.toString(),
            )
            put("npm_toolchain_license_gap_count", toolchain.licenseGapCount.toString())
            put("npm_runtime_nonce_sha256", sha256(npmResult.runtimeNonce.toByteArray()))
            put("bundle_input_count", bundle.inputSha256.size.toString())
        }
        writeSynced(
            manifest,
            buildString {
                appendLine("STM_DEVICE_LOCAL_RUNTIME_MANIFEST_V1")
                manifestValues.toSortedMap().forEach { (name, value) ->
                    append(name).append('=').append(value).append('\n')
                }
            }.toByteArray(),
        )
        throwIfCancelled(cancellation)

        val required = StmRuntimeSlotAdmissionEvidence.DEVICE_LOCAL_BUILD_RUNTIME_FILES
        val names = Files.list(root).use { stream ->
            stream.iterator().asSequence().map { it.fileName.toString() }.toSet()
        }
        check(names == required) { "Device-local runtime sidecars are incomplete: $names" }
        val bindings = required.sorted().associateWith { name ->
            binding(requireRegular(root.resolve(name), "runtime sidecar $name"))
        }
        val finalProgramTree = scanTree(program, false, "", cancellation).sha256
        check(finalProgramTree == runtime.programTreeSha256) {
            "Program identity changed after runnable acceptance"
        }
        return StmRuntimeSlotAdmissionEvidence(
            supplyKind = StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD,
            repository = request.repository,
            commitSha = request.commitSha,
            packageLockSha256 = request.packageLockSha256,
            dependencyTreeSha256 = runtime.dependencyTreeSha256,
            postAdapterProgramTreeSha256 = runtime.programTreeSha256,
            runtimeFiles = bindings,
        )
    }

    private fun awaitJavascriptCompletion(
        runtime: NodeRuntime,
        doneExpression: String,
        timeoutMillis: Long,
        cancellation: StmExtractionCancellation,
        timeoutMessage: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancellation.isCancelled()) {
                runCatching {
                    runtime.terminateExecution(V8RuntimeTerminationMode.Synchronous)
                }
                throw StmRuntimeSlotPreparationException(
                    StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED,
                    "Device-local runtime preparation was cancelled",
                )
            }
            runtime.await(V8AwaitMode.RunNoWait)
            if (runtime.getExecutor(doneExpression).executeBoolean()) return
            Thread.sleep(EVENT_LOOP_POLL_MILLIS)
        }
        runCatching { runtime.terminateExecution(V8RuntimeTerminationMode.Synchronous) }
        error(timeoutMessage)
    }

    private fun closeInstallerRuntime(
        runtime: NodeRuntime,
        restorationScript: String,
        primaryFailure: Throwable?,
    ) {
        val failures = mutableListOf<Throwable>()
        runCatching { runtime.cancelTerminateExecution() }.onFailure(failures::add)
        if (!runtime.isClosed) {
            runCatching {
                check(runtime.getExecutor(restorationScript).executeBoolean()) {
                    "Installer runtime did not restore process state"
                }
            }.onFailure(failures::add)
            runCatching { runtime.setStopping(true) }.onFailure(failures::add)
            runCatching { runtime.close(true) }.onFailure(failures::add)
        }
        if (!runCatching { runtime.isClosed }.getOrDefault(false)) {
            failures += IllegalStateException("Installer Node runtime remained open")
        }
        if (failures.isNotEmpty()) {
            if (primaryFailure != null) {
                failures.forEach(primaryFailure::addSuppressed)
            } else {
                val failure = IllegalStateException("Installer runtime teardown failed")
                failures.forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun scanTree(
        root: Path,
        includeRoot: Boolean,
        rootName: String,
        cancellation: StmExtractionCancellation,
    ): TreeScan {
        val realRoot = requireDirectory(root, "tree scan root")
        val entries = mutableListOf<StmZipManifestEntry>()
        var files = 0
        var directories = 0
        var totalBytes = 0L
        if (includeRoot) {
            entries += StmZipManifestEntry(
                relativePath = rootName,
                type = StmZipManifestEntryType.DIRECTORY,
                sizeBytes = 0,
                sha256 = null,
            )
            directories += 1
        }
        Files.walkFileTree(realRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                throwIfCancelled(cancellation)
                if (directory == realRoot) return FileVisitResult.CONTINUE
                check(attributes.isDirectory && !attributes.isSymbolicLink) {
                    "Tree contains an unsafe directory"
                }
                val relative = manifestPath(realRoot.relativize(directory))
                entries += StmZipManifestEntry(
                    relativePath = if (includeRoot) "$rootName/$relative" else relative,
                    type = StmZipManifestEntryType.DIRECTORY,
                    sizeBytes = 0,
                    sha256 = null,
                )
                directories += 1
                check(files + directories <= MAX_TREE_NODES) { "Tree node limit exceeded" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                throwIfCancelled(cancellation)
                check(attributes.isRegularFile && !attributes.isSymbolicLink) {
                    "Tree contains a symbolic link or special file"
                }
                val relative = manifestPath(realRoot.relativize(file))
                entries += StmZipManifestEntry(
                    relativePath = if (includeRoot) "$rootName/$relative" else relative,
                    type = StmZipManifestEntryType.FILE,
                    sizeBytes = attributes.size(),
                    sha256 = sha256(file),
                )
                files += 1
                totalBytes = Math.addExact(totalBytes, attributes.size())
                check(files + directories <= MAX_TREE_NODES) { "Tree node limit exceeded" }
                check(totalBytes <= MAX_TREE_BYTES) { "Tree byte limit exceeded" }
                return FileVisitResult.CONTINUE
            }
        })
        val sorted = entries.sortedBy(StmZipManifestEntry::relativePath)
        return TreeScan(
            entries = sorted,
            sha256 = stmTreeIdentitySha256(sorted),
            fileCount = files,
            directoryCount = directories,
            totalBytes = totalBytes,
        )
    }

    private fun collectPackageEvidence(
        nodeModules: Path,
        cancellation: StmExtractionCancellation,
    ): List<PackageEvidence> {
        val root = requireDirectory(nodeModules, "node_modules license scan")
        val packages = mutableListOf<PackageEvidence>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                throwIfCancelled(cancellation)
                if (attributes.isRegularFile &&
                    !attributes.isSymbolicLink &&
                    file.fileName.toString() == "package.json"
                ) {
                    val packageRoot = requireNotNull(file.parent)
                    val json = JSONObject(file.toFile().readText())
                    val name = json.optString("name").takeIf(String::isNotBlank)
                        ?: return FileVisitResult.CONTINUE
                    val version = json.optString("version").takeIf(String::isNotBlank)
                        ?: return FileVisitResult.CONTINUE
                    val relativeRoot = manifestPath(root.relativize(packageRoot))
                    val license = when (val value = json.opt("license")) {
                        is String -> value
                        is JSONObject -> value.optString("type")
                        else -> ""
                    }
                    val licenseFiles = Files.list(packageRoot).use { stream ->
                        stream.iterator().asSequence()
                            .filter { candidate ->
                                Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
                                    !Files.isSymbolicLink(candidate) &&
                                    LICENSE_FILE_PATTERN.matches(
                                        candidate.fileName.toString(),
                                    )
                            }
                            .sortedBy { it.fileName.toString() }
                            .map { candidate ->
                                FileEvidence(
                                    path = candidate.fileName.toString(),
                                    bytes = Files.size(candidate),
                                    sha256 = sha256(candidate),
                                )
                            }
                            .toList()
                    }
                    packages += PackageEvidence(
                        path = relativeRoot,
                        name = name,
                        version = version,
                        licenseExpression = license,
                        licenseFiles = licenseFiles,
                    )
                }
                return FileVisitResult.CONTINUE
            }
        })
        return packages.distinctBy { it.path }.sortedBy { it.path }
    }

    private fun encodeSbom(
        request: StmRuntimeSlotPreparationRequest,
        packages: List<PackageEvidence>,
    ): ByteArray {
        val components = JSONArray()
        packages.forEach { item ->
            components.put(
                JSONObject()
                    .put("type", "library")
                    .put("name", item.name)
                    .put("version", item.version)
                    .put(
                        "bom-ref",
                        "pkg:npm/${URLEncoder.encode(item.name, Charsets.UTF_8.name())}" +
                            "@${URLEncoder.encode(item.version, Charsets.UTF_8.name())}" +
                            "?path=${URLEncoder.encode(item.path, Charsets.UTF_8.name())}",
                    )
                    .put(
                        "licenses",
                        JSONArray().apply {
                            if (item.licenseExpression.isNotBlank()) {
                                put(
                                    JSONObject().put(
                                        "expression",
                                        item.licenseExpression,
                                    ),
                                )
                            }
                        },
                    ),
            )
        }
        return JSONObject()
            .put("bomFormat", "CycloneDX")
            .put("specVersion", "1.5")
            .put("version", 1)
            .put(
                "metadata",
                JSONObject().put(
                    "component",
                    JSONObject()
                        .put("type", "application")
                        .put("name", "SillyTavern")
                        .put("version", request.stVersion)
                        .put("bom-ref", "${request.repository}@${request.commitSha}"),
                ),
            )
            .put("components", components)
            .toString(2)
            .plus("\n")
            .toByteArray()
    }

    private fun encodeLicenses(packages: List<PackageEvidence>): ByteArray =
        JSONObject()
            .put("format_version", 1)
            .put("release_cleared", packages.none { it.licenseFiles.isEmpty() })
            .put("package_count", packages.size)
            .put("missing_package_local_text_count", packages.count { it.licenseFiles.isEmpty() })
            .put(
                "packages",
                JSONArray().apply {
                    packages.forEach { item ->
                        put(
                            JSONObject()
                                .put("path", item.path)
                                .put("name", item.name)
                                .put("version", item.version)
                                .put("license", item.licenseExpression)
                                .put(
                                    "license_files",
                                    JSONArray().apply {
                                        item.licenseFiles.forEach { file ->
                                            put(
                                                JSONObject()
                                                    .put("path", file.path)
                                                    .put("bytes", file.bytes)
                                                    .put("sha256", file.sha256),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
            .toString(2)
            .plus("\n")
            .toByteArray()

    private fun locateBundleOutput(program: Path): Path {
        val webpackRoot = requireDirectory(program.resolve("dist/_webpack"), "Webpack output root")
        val versions = Files.list(webpackRoot).use { stream ->
            stream.iterator().asSequence().toList()
        }
        check(versions.size == 1) { "Webpack output root must contain one cache version" }
        val version = requireDirectory(versions.single(), "Webpack cache version")
        check(stmIsSafeWebpackCacheVersion(version.fileName.toString())) {
            "Webpack cache version is unsafe"
        }
        val output = requireDirectory(version.resolve("output"), "Webpack bundle output")
        val names = Files.list(output).use { stream ->
            stream.iterator().asSequence().map { it.fileName.toString() }.sorted().toList()
        }
        check(names == listOf("lib.js", "lib.js.LICENSE.txt")) {
            "Webpack bundle output has missing or unexpected files: $names"
        }
        return output
    }

    private fun removeBundleBuildTree(bundle: LocalBundleResult) {
        if (!Files.exists(bundle.webpackRoot, LinkOption.NOFOLLOW_LINKS)) return
        deleteTree(bundle.webpackRoot)
        val dist = requireNotNull(bundle.webpackRoot.parent)
        if (!bundle.distExistedBefore && Files.isDirectory(dist, LinkOption.NOFOLLOW_LINKS)) {
            val empty = Files.list(dist).use { stream -> !stream.iterator().hasNext() }
            if (empty) Files.delete(dist)
        }
    }

    private fun replaceProgramAdapter(program: Path, source: Path) {
        val target = requireRegular(
            program.resolve("src/middleware/webpack-serve.js"),
            "SillyTavern Webpack middleware",
        )
        val temporary = target.resolveSibling("${target.fileName}.stm-part")
        check(!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            "Webpack adapter temporary file already exists"
        }
        copyRegular(source, temporary)
        Files.delete(target)
        check(temporary.toFile().renameTo(target.toFile())) {
            "Frozen Webpack adapter could not replace the staged source module"
        }
    }

    private fun npmBootstrap(
        npmRoot: Path,
        program: Path,
        cache: Path,
        temporary: Path,
        proxy: String?,
    ): String {
        val proxyLiteral = proxy?.let(::jsString) ?: "null"
        return """
            (() => {
              const crypto = require('node:crypto');
              const state = globalThis.__stmNpmInstall = {
                done: false,
                promiseSettled: false,
                error: '',
                output: '',
                exitCode: null,
                runtimeNonce: crypto.randomUUID(),
              };
              state.originalCwd = process.cwd();
              state.originalEnv = Object.fromEntries(Object.entries(process.env));
              state.originalArgv = Array.from(process.argv);
              state.originalExecPath = process.execPath;
              state.originalExit = process.exit;
              state.originalExitCode = process.exitCode;
              state.originalStdoutWrite = process.stdout.write;
              state.originalStderrWrite = process.stderr.write;
              state.originalTitle = process.title;
              state.listenerNames = Array.from(process.eventNames());
              state.originalListeners = state.listenerNames.map(
                name => [name, process.rawListeners(name)]
              );
              const work = ${jsString(program.toString())};
              const cache = ${jsString(cache.toString())};
              const temp = ${jsString(temporary.toString())};
              const networkProxy = $proxyLiteral;
              const format = error => String(error && (error.stack || error));
              process.env.HOME = temp;
              process.env.TMPDIR = temp;
              process.env.TMP = temp;
              process.env.TEMP = temp;
              process.env.npm_config_registry = ${jsString(NPM_REGISTRY)};
              process.env.npm_config_cache = cache;
              process.env.npm_config_ignore_scripts = 'true';
              process.env.npm_config_audit = 'false';
              process.env.npm_config_fund = 'false';
              process.env.npm_config_bin_links = 'false';
              process.env.npm_config_update_notifier = 'false';
              process.env.npm_config_package_lock = 'true';
              process.env.npm_config_save = 'false';
              process.env.npm_config_maxsockets = '4';
              process.env.npm_config_fetch_retries = '5';
              process.env.npm_config_fetch_retry_mintimeout = '1000';
              process.env.npm_config_fetch_retry_maxtimeout = '15000';
              process.env.npm_config_fetch_timeout = '120000';
              if (networkProxy) {
                for (const name of ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy']) {
                  process.env[name] = networkProxy;
                }
                process.env.npm_config_proxy = networkProxy;
                process.env.npm_config_https_proxy = networkProxy;
              }
              process.chdir(work);
              const Npm = require(${jsString(npmRoot.resolve("lib/npm.js").toString())});
              state.npmPrototype = Npm.prototype;
              state.originalNpmLoad = Npm.prototype.load;
              state.originalNpmUnload = Npm.prototype.unload;
              state.npmPrototype.load = function(...args) {
                state.npmInstance = this;
                return Reflect.apply(state.originalNpmLoad, this, args);
              };
              const output = [];
              let outputCharacters = 0;
              const capture = (chunk, encoding, callback) => {
                const text = String(chunk);
                output.push(text);
                outputCharacters += text.length;
                while (outputCharacters > 32768 && output.length > 1) {
                  outputCharacters -= output.shift().length;
                }
                const completion = typeof encoding === 'function' ? encoding : callback;
                if (typeof completion === 'function') queueMicrotask(completion);
                return true;
              };
              process.stdout.write = capture;
              process.stderr.write = capture;
              process.argv.splice(
                0,
                process.argv.length,
                process.execPath,
                ${jsString(npmRoot.resolve("bin/npm-cli.js").toString())},
                'ci',
                '--omit=dev',
                '--ignore-scripts',
                '--no-bin-links',
                '--no-audit',
                '--no-fund',
                '--no-update-notifier',
                '--maxsockets=4',
                '--fetch-retries=5',
                '--fetch-retry-mintimeout=1000',
                '--fetch-retry-maxtimeout=15000',
                '--fetch-timeout=120000',
                '--registry=${NPM_REGISTRY}',
                '--cache=' + cache,
                '--userconfig=' + temp + '/user.npmrc',
                '--globalconfig=' + temp + '/global.npmrc'
              );
              if (networkProxy) {
                process.argv.push('--proxy=' + networkProxy, '--https-proxy=' + networkProxy);
              }
              process.exit = code => {
                state.exitCode = Number(code ?? process.exitCode ?? 0);
                state.output = output.join('').slice(-32768);
                if (state.exitCode !== 0) {
                  state.error = 'npm CLI exited with code ' + state.exitCode + '\n' + state.output;
                }
              };
              const settle = () => Promise.allSettled(
                Array.isArray(state.npmInstance?.unrefPromises)
                  ? Array.from(state.npmInstance.unrefPromises)
                  : []
              );
              Promise.resolve()
                .then(() => require(${jsString(npmRoot.resolve("lib/cli.js").toString())})(process))
                .then(async () => {
                  state.promiseSettled = true;
                  await settle();
                  setImmediate(() => {
                    state.output = output.join('').slice(-32768);
                    if (Number(state.exitCode ?? 0) !== 0 && !state.error) {
                      state.error = 'npm CLI exited with code ' + String(state.exitCode);
                    }
                    state.done = true;
                  });
                })
                .catch(async error => {
                  state.promiseSettled = true;
                  state.error = format(error);
                  await settle();
                  state.done = true;
                });
            })();
        """.trimIndent()
    }

    private fun npmRestorationScript(): String =
        """
        (() => {
          const state = globalThis.__stmNpmInstall;
          if (!state || !state.originalCwd || !state.originalEnv) return false;
          if (state.npmPrototype) {
            if (state.npmInstance && typeof state.npmInstance.unload === 'function') {
              try { state.npmInstance.unload(); } catch (_) {}
            }
            state.npmPrototype.load = state.originalNpmLoad;
            state.npmPrototype.unload = state.originalNpmUnload;
          }
          for (const name of Object.keys(process.env)) delete process.env[name];
          for (const [name, value] of Object.entries(state.originalEnv)) process.env[name] = value;
          process.argv.splice(0, process.argv.length, ...state.originalArgv);
          process.execPath = state.originalExecPath;
          process.exit = state.originalExit;
          process.exitCode = state.originalExitCode;
          process.stdout.write = state.originalStdoutWrite;
          process.stderr.write = state.originalStderrWrite;
          process.title = state.originalTitle;
          for (const name of process.eventNames()) process.removeAllListeners(name);
          for (const [name, listeners] of state.originalListeners) {
            for (const listener of listeners) process.on(name, listener);
          }
          process.chdir(state.originalCwd);
          return process.cwd() === state.originalCwd &&
            process.argv.length === state.originalArgv.length &&
            process.argv.every((value, index) => value === state.originalArgv[index]) &&
            process.execPath === state.originalExecPath &&
            process.exit === state.originalExit &&
            process.stdout.write === state.originalStdoutWrite &&
            process.stderr.write === state.originalStderrWrite;
        })();
        """.trimIndent()

    private fun bundleBootstrap(
        program: Path,
        buildEntry: Path,
        operationId: String,
        npmRuntimeNonce: String,
    ): String {
        val importExpression = "import(${jsString(buildEntry.toUri().toString())})"
        return """
            (() => {
              const util = require('node:util');
              const vm = require('node:vm');
              const crypto = require('node:crypto');
              const webpack = require(${jsString(program.resolve("node_modules/webpack").toString())});
              const state = globalThis.__stmLocalBundle = {
                done: false,
                error: '',
                output: '',
                logs: [],
                originalCwd: process.cwd(),
                originalConsole: {},
                operationId: ${jsString(operationId)},
                runtimeNonce: crypto.randomUUID(),
                npmStateAbsent: globalThis.__stmNpmInstall === undefined,
                compilerRunCalls: 0,
                compilerCallbackCalls: 0,
                compilerStatsPresent: false,
                compilerStatsHasErrors: true,
                compilerStatsErrorCount: -1,
                compilerLibAssetPresent: false,
                compilerCloseCalls: 0,
                compilerCloseCallbackCalls: 0,
                compilerCloseError: '',
                compilerCloseSameInstance: false,
              };
              if (!state.npmStateAbsent || state.runtimeNonce === ${jsString(npmRuntimeNonce)}) {
                throw new Error('Bundle build did not receive a fresh Node runtime');
              }
              const format = value => value instanceof Error
                ? String(value.stack || value.message || value)
                : (typeof value === 'string' ? value : util.inspect(value));
              for (const level of ['log', 'info', 'warn', 'error']) {
                const original = console[level];
                state.originalConsole[level] = original;
                console[level] = (...values) => {
                  state.logs.push(values.map(format).join(' '));
                  if (state.logs.length > 200) state.logs.shift();
                  original.apply(console, values);
                };
              }
              const prototype = webpack?.Compiler?.prototype;
              if (!prototype || typeof prototype.run !== 'function' ||
                  typeof prototype.close !== 'function') {
                throw new Error('Webpack Compiler lifecycle is unavailable');
              }
              state.compilerPrototype = prototype;
              state.originalCompilerRun = prototype.run;
              state.originalCompilerClose = prototype.close;
              prototype.run = function(callback) {
                state.compilerRunCalls += 1;
                state.compilerInstance = this;
                return state.originalCompilerRun.call(this, (error, stats) => {
                  state.compilerCallbackCalls += 1;
                  state.compilerStatsPresent = Boolean(stats);
                  state.compilerStatsHasErrors = Boolean(stats?.hasErrors?.());
                  state.compilerStatsErrorCount = Number(stats?.compilation?.errors?.length ?? -1);
                  state.compilerLibAssetPresent = Boolean(stats?.compilation?.getAsset?.('lib.js'));
                  if (error) state.error = format(error);
                  return callback(error, stats);
                });
              };
              prototype.close = function(callback) {
                state.compilerCloseCalls += 1;
                state.compilerCloseSameInstance = this === state.compilerInstance;
                return state.originalCompilerClose.call(this, error => {
                  state.compilerCloseCallbackCalls += 1;
                  state.compilerCloseError = error ? format(error) : '';
                  return callback(error);
                });
              };
              process.chdir(${jsString(program.toString())});
              const loader = new vm.Script(${jsString(importExpression)}, {
                filename: ${jsString(program.resolve(".stm-local-bundle-loader.js").toString())},
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

    private fun bundleRestorationScript(): String =
        """
        (() => {
          const state = globalThis.__stmLocalBundle;
          if (!state || !state.originalCwd || !state.originalConsole) return false;
          if (state.compilerPrototype) {
            state.compilerPrototype.run = state.originalCompilerRun;
            state.compilerPrototype.close = state.originalCompilerClose;
          }
          for (const level of ['log', 'info', 'warn', 'error']) {
            if (typeof state.originalConsole[level] === 'function') {
              console[level] = state.originalConsole[level];
            }
          }
          process.chdir(state.originalCwd);
          return process.cwd() === state.originalCwd &&
            (!state.compilerPrototype ||
              (state.compilerPrototype.run === state.originalCompilerRun &&
               state.compilerPrototype.close === state.originalCompilerClose));
        })();
        """.trimIndent()

    private fun httpGet(url: String): HttpEvidence {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MILLIS
        connection.readTimeout = HTTP_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        return try {
            val code = connection.responseCode
            val input = if (code >= 400) connection.errorStream else connection.inputStream
            val body = input?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_HTTP_BODY_BYTES) {
                        "Runnable acceptance HTTP body exceeded its limit"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            HttpEvidence(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitLatch(
        latch: CountDownLatch,
        timeoutMillis: Long,
        cancellation: StmExtractionCancellation,
        timeoutMessage: () -> String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            throwIfCancelled(cancellation)
            if (latch.await(100, TimeUnit.MILLISECONDS)) return
        }
        error(timeoutMessage())
    }

    private fun awaitPortReleased(port: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PORT_RELEASE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val open = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                }
                true
            }.getOrDefault(false)
            if (!open) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun androidSystemProxyUrl(): String? {
        val raw = Settings.Global.getString(
            appContext.contentResolver,
            Settings.Global.HTTP_PROXY,
        )?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val hostAndPort = raw.substringBefore(',').trim()
        val uri = runCatching { URI("http://$hostAndPort") }.getOrNull() ?: return null
        if (
            uri.scheme != "http" ||
            uri.userInfo != null ||
            uri.query != null ||
            uri.fragment != null ||
            uri.path.orEmpty().isNotEmpty() ||
            uri.host.isNullOrBlank() ||
            uri.port !in 1..65_535
        ) {
            return null
        }
        val host = requireNotNull(uri.host)
        if (!PROXY_HOST_PATTERN.matches(host)) return null
        return "http://$host:${uri.port}"
    }

    private fun mappedFailure(
        cancellation: StmExtractionCancellation,
        code: StmRuntimeSlotPreparationErrorCode,
        message: String,
        error: Exception,
    ): StmRuntimeSlotPreparationException {
        if (cancellation.isCancelled() ||
            (error as? StmRuntimeSlotPreparationException)?.code ==
            StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED
        ) {
            return StmRuntimeSlotPreparationException(
                StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED,
                "Device-local runtime preparation was cancelled",
                error,
            )
        }
        return StmRuntimeSlotPreparationException(
            code,
            "$message: ${error.safePreparationDetail()}",
            error,
        )
    }

    private fun throwIfCancelled(cancellation: StmExtractionCancellation) {
        if (cancellation.isCancelled()) {
            throw StmRuntimeSlotPreparationException(
                StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED,
                "Device-local runtime preparation was cancelled",
            )
        }
    }

    private fun freshDirectory(parent: Path, name: String): Path {
        val child = parent.resolve(name).normalize()
        check(child.parent == parent && !Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            "Installer staging child is not fresh: $name"
        }
        return Files.createDirectory(child)
    }

    private fun requireDirectory(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        check(
            !Files.isSymbolicLink(absolute) &&
                Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
        ) {
            "$label is unavailable or unsafe"
        }
        return absolute
    }

    private fun requireRegular(path: Path, label: String): Path {
        val absolute = path.toAbsolutePath().normalize()
        check(
            !Files.isSymbolicLink(absolute) &&
                Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)
        ) {
            "$label is unavailable or unsafe"
        }
        return absolute
    }

    private fun copyRegular(source: Path, destination: Path) {
        val input = requireRegular(source, "runtime input")
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Runtime destination already exists: ${destination.fileName}"
        }
        Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS).use { stream ->
            FileOutputStream(destination.toFile()).use { output ->
                stream.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        check(binding(input) == binding(destination)) {
            "Runtime input changed while being copied"
        }
    }

    private fun writeSynced(destination: Path, bytes: ByteArray) {
        check(bytes.isNotEmpty() && bytes.size <= MAX_SIDECAR_BYTES) {
            "Runtime sidecar length is invalid"
        }
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Runtime sidecar already exists: ${destination.fileName}"
        }
        FileOutputStream(destination.toFile()).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        requireRegular(destination, "runtime sidecar")
    }

    private fun binding(path: Path): StmRuntimeFileBinding =
        StmRuntimeFileBinding(Files.size(path), sha256(path))

    private fun encodeTreeManifest(entries: List<StmZipManifestEntry>): ByteArray =
        buildString {
            appendLine("STM_DEPENDENCY_TREE_MANIFEST_V1")
            entries.forEach { entry ->
                when (entry.type) {
                    StmZipManifestEntryType.DIRECTORY ->
                        append("D\t").append(entry.relativePath).append('\n')

                    StmZipManifestEntryType.FILE ->
                        append("F\t")
                            .append(entry.relativePath)
                            .append('\t')
                            .append(entry.sizeBytes)
                            .append('\t')
                            .append(entry.sha256)
                            .append('\n')
                }
            }
        }.toByteArray()

    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                error?.let { throw it }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun manifestPath(path: Path): String =
        (0 until path.nameCount).joinToString("/") { path.getName(it).toString() }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance(SHA256)
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
        MessageDigest.getInstance(SHA256).digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
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

    private data class NpmInstallResult(
        val runtimeNonce: String,
        val elapsedMillis: Long,
        val outputSha256: String,
        val outputCharacters: Int,
    )

    private data class LocalBundleResult(
        val webpackRoot: Path,
        val distExistedBefore: Boolean,
        val bundle: Path,
        val bundleLicense: Path,
        val bundleBinding: StmRuntimeFileBinding,
        val bundleLicenseBinding: StmRuntimeFileBinding,
        val nodeVersion: String,
        val webpackVersion: String,
        val elapsedMillis: Long,
        val logSha256: String,
        val logCharacters: Int,
        val inputSha256: Map<String, String>,
    )

    private data class TreeScan(
        val entries: List<StmZipManifestEntry>,
        val sha256: String,
        val fileCount: Int,
        val directoryCount: Int,
        val totalBytes: Long,
    )

    private data class AssembledRuntime(
        val runtimeDirectory: Path,
        val dependencyTreeSha256: String,
        val programTreeSha256: String,
        val bundleBinding: StmRuntimeFileBinding,
        val initialManifestValues: Map<String, String>,
    )

    private data class RunnableAcceptance(
        val nodeVersion: String,
        val port: Int,
        val startElapsedMillis: Long,
        val stopElapsedMillis: Long,
        val homepageBytes: Int,
        val bundleSha256: String,
        val bundleBytes: Long,
    )

    private data class FileEvidence(
        val path: String,
        val bytes: Long,
        val sha256: String,
    )

    private data class PackageEvidence(
        val path: String,
        val name: String,
        val version: String,
        val licenseExpression: String,
        val licenseFiles: List<FileEvidence>,
    )

    private data class HttpEvidence(val code: Int, val body: ByteArray)

    private companion object {
        const val PAYLOAD_DIRECTORY = "payload"
        const val NODE_MODULES = "node_modules"
        const val NPM_CACHE_DIRECTORY = "npm-cache"
        const val NPM_TEMP_DIRECTORY = "npm-temp"
        const val ACCEPTANCE_DATA_DIRECTORY = "acceptance-data"
        const val ACCEPTANCE_SESSION_DIRECTORY = "acceptance-session"
        const val ACCEPTANCE_LOGS_DIRECTORY = "acceptance-logs"
        const val NPM_REGISTRY = "https://registry.npmjs.org/"
        const val NPM_TIMEOUT_MILLIS = 20L * 60L * 1000L
        const val BUNDLE_TIMEOUT_MILLIS = 15L * 60L * 1000L
        const val START_TIMEOUT_MILLIS = 4L * 60L * 1000L
        const val STOP_TIMEOUT_MILLIS = 20_000L
        const val ENGINE_DESTROY_TIMEOUT_SECONDS = 15L
        const val EVENT_LOOP_POLL_MILLIS = 10L
        const val PORT_RELEASE_TIMEOUT_MILLIS = 5_000L
        const val HTTP_TIMEOUT_MILLIS = 10_000
        const val MAX_HTTP_BODY_BYTES = 32 * 1024 * 1024
        const val MAX_BUNDLE_BYTES = 128L * 1024 * 1024
        const val MAX_SIDECAR_BYTES = 64 * 1024 * 1024
        const val MAX_TREE_NODES = 250_000
        const val MAX_TREE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_ERROR_CHARS = 8_000
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val SHA256 = "SHA-256"

        val SAFE_ARCHIVE_ROOT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
        val VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")
        val LICENSE_FILE_PATTERN = Regex(
            "(?i)(license|licence|copying|notice)(?:\\.[A-Za-z0-9._-]+)?",
        )
        val PROXY_HOST_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9._:-]{0,252}[A-Za-z0-9])?")

        val RUNTIME_ADAPTER =
            """
            import fs from 'node:fs';
            import path from 'node:path';

            export default function getWebpackServeMiddleware() {
                const bundlePath = process.env.STM_PREBUILT_LIB_JS;
                if (!bundlePath || !path.isAbsolute(bundlePath)) {
                    throw new Error('STM_PREBUILT_LIB_JS must identify an absolute frozen bundle');
                }
                const bundleRoot = path.dirname(bundlePath);
                const bundleName = path.basename(bundlePath);
                function middleware(req, res, next) {
                    const parsed = path.parse(req.path);
                    if (req.method === 'GET' && parsed.dir === '/' && parsed.base === bundleName) {
                        return res.sendFile(bundleName, { root: bundleRoot });
                    }
                    next();
                }
                middleware.runWebpackCompiler = async () => {
                    const stat = await fs.promises.stat(bundlePath);
                    if (!stat.isFile()) throw new Error('STM frozen lib.js is not a regular file');
                };
                return middleware;
            }
            """.trimIndent() + "\n"
    }
}

private class AcceptanceCallback : FeatherEngine.Callback {
    private val signals = ConcurrentHashMap<String, AcceptanceSignal>()

    fun register(sessionId: String): AcceptanceSignal =
        AcceptanceSignal().also { signals[sessionId] = it }

    fun cancelAll(detail: String) {
        signals.values.forEach { it.cancel(detail) }
        signals.clear()
    }

    override fun onNodeCreated(sessionId: String, nodeVersion: String) {
        signals[sessionId]?.nodeVersion = nodeVersion
    }

    override fun onReady(sessionId: String, port: Int, nodeVersion: String) {
        signals[sessionId]?.let {
            it.nodeVersion = nodeVersion
            it.port = port
            it.ready.countDown()
        }
    }

    override fun onStopped(sessionId: String, terminationUsed: Boolean) {
        signals.remove(sessionId)?.let {
            it.terminationUsed = terminationUsed
            it.stopped.countDown()
        }
    }

    override fun onFailure(sessionId: String, detail: String) {
        signals[sessionId]?.let {
            it.failure = detail
            it.ready.countDown()
            it.stopped.countDown()
        }
    }
}

private class AcceptanceSignal {
    val ready = CountDownLatch(1)
    val stopped = CountDownLatch(1)

    @Volatile
    var nodeVersion: String? = null

    @Volatile
    var port: Int = 0

    @Volatile
    var terminationUsed: Boolean = false

    @Volatile
    var failure: String? = null

    fun cancel(detail: String) {
        failure = failure ?: detail
        ready.countDown()
        stopped.countDown()
    }
}

private fun Throwable.safePreparationDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)
