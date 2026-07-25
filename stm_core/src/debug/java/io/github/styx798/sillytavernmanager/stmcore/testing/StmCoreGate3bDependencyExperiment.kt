package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.enums.V8RuntimeTerminationMode
import com.caoccao.javet.interop.NodeRuntime
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.StmNodeRuntimeFactory
import io.github.styx798.sillytavernmanager.stmcore.installer.StmBundledNpmToolchainFactory
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyCandidate
import io.github.styx798.sillytavernmanager.stmcore.installer.StmExtractionCancellation
import io.github.styx798.sillytavernmanager.stmcore.installer.StmPreparedNpmToolchain
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotMetadata
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotStore
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotVerificationResult
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Debug-only Stage 3B feasibility harness.
 *
 * It obtains npm from the production APK asset carrier, creates a fresh lockfile-only work
 * directory for every run, fixes registry/cache/temp/script/bin-link policy, and executes the npm
 * CLI or Arborist in a short-lived Javet runtime. It does not write production slots and cannot
 * promote SillyTavern to READY.
 */
internal interface StmCoreGate3bExperimentRunner {
    fun cancel()

    fun run(): Map<String, String>

    fun hasLiveResources(): Boolean = false

    fun finishTeardown(): Boolean {
        cancel()
        return !hasLiveResources()
    }
}

internal class Gate3bInstallerWorkspaceLifetime {
    @Volatile
    private var runtimeCreated = false

    @Volatile
    private var runtimeClosed = false

    @Volatile
    private var processStateRestored = false

    fun markRuntimeCreated() {
        runtimeCreated = true
        runtimeClosed = false
        processStateRestored = false
    }

    fun markRuntimeClosed(closed: Boolean) {
        runtimeClosed = closed
    }

    fun markProcessStateRestored(restored: Boolean) {
        processStateRestored = restored
    }

    fun isDeletionSafe(): Boolean =
        !runtimeCreated || (runtimeClosed && processStateRestored)
}

internal fun cleanupGate3bExperimentWorkspace(
    root: Path,
    ownedParent: Path,
    installerDeletionSafe: Boolean,
    runnableDeletionSafe: Boolean,
    currentProcessCwd: Path,
): String {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalizedCwd = currentProcessCwd.toAbsolutePath().normalize()
    val retentionReasons = buildList {
        if (!installerDeletionSafe) add("installer_runtime_or_process_state_not_released")
        if (!runnableDeletionSafe) add("runnable_runtime_port_or_cwd_not_released")
        if (normalizedCwd == normalizedRoot || normalizedCwd.startsWith(normalizedRoot)) {
            add("process_cwd_inside_experiment_root")
        }
    }
    if (retentionReasons.isNotEmpty()) {
        return "retained:${retentionReasons.joinToString(",")}"
    }
    return runCatching {
        deleteGate3bTreeNoFollow(normalizedRoot, ownedParent)
        "removed"
    }.getOrElse { error ->
        "retained:${error.safeGate3bDetail()}"
    }
}

internal fun deleteGate3bTreeNoFollow(root: Path, ownedParent: Path) {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalizedParent = ownedParent.toAbsolutePath().normalize()
    check(normalizedRoot.parent == normalizedParent && normalizedRoot != normalizedParent) {
        "Stage 3B cleanup target escaped its exact owned parent"
    }
    if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
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

internal data class Gate3bCommittedSlotIdentity(
    val entryName: String,
    val metadata: StmSlotMetadata,
    val contentManifestSha256: String,
)

internal fun captureGate3bCommittedSlotIdentity(context: Context): List<Gate3bCommittedSlotIdentity> =
    StmSlotStore(
        StmCorePaths.slotsRoot(context),
        StmCorePaths.stagingRoot(context),
    ).scanCommitted().map { entry ->
        val valid = entry.verification as? StmSlotVerificationResult.Valid
            ?: error("Committed slot ${entry.entryName} failed immutable verification")
        Gate3bCommittedSlotIdentity(
            entryName = entry.entryName,
            metadata = valid.slot.metadata,
            contentManifestSha256 = valid.slot.manifest.manifestSha256,
        )
    }

internal fun captureGate3bFileIdentity(file: File, label: String): String {
    val path = file.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "missing"
    check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        "$label is unsafe"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    val sha256 = digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "${file.length()}:$sha256"
}

internal class StmCoreGate3bDependencyExperiment(
    context: Context,
    private val candidate: StmDependencySupplyCandidate,
    private val installTimeoutMillis: Long = INSTALL_TIMEOUT_MILLIS,
    private val cancelAfterMillis: Long? = null,
    private val runnableAcceptance: Boolean = false,
    private val localBundleBuild: Boolean = false,
) : StmCoreGate3bExperimentRunner {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var runtime: NodeRuntime? = null

    @Volatile
    private var activeRunnableAcceptance: StmCoreGate3bRunnableAcceptance? = null

    @Volatile
    private var runFinished = false

    @Volatile
    private var processCwdOutsideExperimentRoot = true

    private val installerWorkspaceLifetime = Gate3bInstallerWorkspaceLifetime()
    private val teardownLock = Any()

    @Volatile
    private var experimentRootForTeardown: File? = null

    @Volatile
    private var processCwdBeforeExperiment: String? = null

    init {
        require(installTimeoutMillis in MIN_INSTALL_TIMEOUT_MILLIS..INSTALL_TIMEOUT_MILLIS) {
            "Stage 3B dependency experiment timeout is outside its debug bounds"
        }
        require(cancelAfterMillis == null || cancelAfterMillis in 1_000 until installTimeoutMillis) {
            "Stage 3B dependency cancellation delay is outside its debug bounds"
        }
        require(!runnableAcceptance || cancelAfterMillis == null) {
            "Runnable acceptance cannot be combined with the cancellation probe"
        }
        require(!localBundleBuild || runnableAcceptance) {
            "Local bundle construction requires runnable acceptance"
        }
        require(!localBundleBuild || candidate == StmDependencySupplyCandidate.NPM_CLI) {
            "The selected local bundle path is bound to npm CLI"
        }
    }

    override fun cancel() {
        cancelled.set(true)
        if (!runFinished) runtime?.let { active ->
            runCatching {
                active.terminateExecution(V8RuntimeTerminationMode.Synchronous)
            }
        }
        activeRunnableAcceptance?.cancel()
    }

    override fun hasLiveResources(): Boolean =
        runtime?.let { active ->
            !runCatching { active.isClosed }.getOrDefault(false)
        } == true ||
            activeRunnableAcceptance?.isWorkspaceDeletionSafe() == false ||
            (runFinished && !installerWorkspaceLifetime.isDeletionSafe()) ||
            (runFinished && !processCwdOutsideExperimentRoot) ||
            experimentRootForTeardown?.exists() == true

    override fun finishTeardown(): Boolean = synchronized(teardownLock) {
        cancelled.set(true)
        runtime?.let { active ->
            runCatching { active.cancelTerminateExecution() }
            val expectedCwd = processCwdBeforeExperiment
            if (expectedCwd != null) {
                val restored = runCatching {
                    val evidence = JSONObject(
                        active.getExecutor(processStateRestorationScript())
                            .executeString()
                            .orEmpty(),
                    )
                    evidence.getBoolean("environmentRestored") &&
                        evidence.getBoolean("cwdRestored") &&
                        evidence.getBoolean("processSurfaceRestored") &&
                        File(".").canonicalPath == expectedCwd
                }.getOrDefault(false)
                installerWorkspaceLifetime.markProcessStateRestored(restored)
            }
            runCatching { active.setStopping(true) }
            runCatching { active.close(true) }
            val closed = runCatching { active.isClosed }.getOrDefault(false)
            installerWorkspaceLifetime.markRuntimeClosed(closed)
            if (closed && runtime === active) runtime = null
        }

        val runnableSafe = activeRunnableAcceptance?.finishTeardown() != false
        if (runnableSafe) activeRunnableAcceptance = null
        experimentRootForTeardown?.let { root ->
            val currentCwd = runCatching { File(".").canonicalFile.toPath() }
                .getOrDefault(root.toPath())
            val normalizedRoot = root.toPath().toAbsolutePath().normalize()
            val normalizedCwd = currentCwd.toAbsolutePath().normalize()
            processCwdOutsideExperimentRoot =
                normalizedCwd != normalizedRoot && !normalizedCwd.startsWith(normalizedRoot)
            cleanupGate3bExperimentWorkspace(
                root = root.toPath(),
                ownedParent = requireNotNull(root.parentFile).toPath(),
                installerDeletionSafe = installerWorkspaceLifetime.isDeletionSafe(),
                runnableDeletionSafe = runnableSafe,
                currentProcessCwd = currentCwd,
            )
            if (!root.exists()) experimentRootForTeardown = null
        }
        !hasLiveResources()
    }

    override fun run(): Map<String, String> = try {
        runExperiment()
    } finally {
        runFinished = true
    }

    private fun runExperiment(): Map<String, String> {
        check(
            candidate == StmDependencySupplyCandidate.NPM_CLI ||
                candidate == StmDependencySupplyCandidate.ARBORIST,
        ) {
            "This harness only evaluates the two device-side dependency installers"
        }
        val startedElapsed = SystemClock.elapsedRealtime()
        val sourceProgram = if (runnableAcceptance) null else requireFixedSourceProgram()
        val npmToolchain = requireFixedNpmToolchain()
        val npmRoot = npmToolchain.npmDirectory
        val slotsBefore = captureGate3bCommittedSlotIdentity(appContext)
        val activePointerBefore = captureGate3bFileIdentity(
            StmCorePaths.activeSlotFile(appContext),
            "Stage 3B active-slot pointer",
        )
        val experimentRoot = prepareExperimentRoot().also { experimentRootForTeardown = it }
        val runnable = if (runnableAcceptance) {
            StmCoreGate3bRunnableAcceptance(
                appContext,
                candidate,
                cancelled,
                localBundleBuild,
            ).also {
                activeRunnableAcceptance = it
            }
        } else {
            null
        }
        val runnableSource = try {
            runnable?.prepareSource(experimentRoot)
        } catch (error: Exception) {
            activeRunnableAcceptance = null
            runCatching {
                deleteGate3bTreeNoFollow(
                    experimentRoot.toPath(),
                    requireNotNull(experimentRoot.parentFile).toPath(),
                )
            }
            if (!experimentRoot.exists()) experimentRootForTeardown = null
            throw error
        }
        val workRoot = File(experimentRoot, "work").also { require(it.mkdir()) }
        val cacheRoot = File(experimentRoot, "npm-cache").also { require(it.mkdir()) }
        val tempRoot = File(experimentRoot, "tmp").also { require(it.mkdir()) }
        val networkProxy = androidSystemProxyUrl()
        try {
            copyRequiredSourceInputs(
                runnableSource?.programRoot ?: requireNotNull(sourceProgram),
                workRoot,
            )
        } catch (error: Exception) {
            activeRunnableAcceptance = null
            runCatching {
                deleteGate3bTreeNoFollow(
                    experimentRoot.toPath(),
                    requireNotNull(experimentRoot.parentFile).toPath(),
                )
            }
            if (!experimentRoot.exists()) experimentRootForTeardown = null
            throw error
        }
        val operationNonce = UUID.randomUUID().toString()
        val memorySampler = Gate3bMemorySampler().also(Gate3bMemorySampler::start)
        val processCwdBefore = File(".").canonicalPath.also {
            processCwdBeforeExperiment = it
        }
        var nodeVersion = ""
        var processExecPath = ""
        var processExitAttempted = ""
        var processExitCode = ""
        var npmPromiseSettled = false
        var npmOutput = ""
        var javascriptResult = ""
        var javascriptError = ""
        var executionFailure = ""
        var postInstallPackageLockSha256 = EXPECTED_PACKAGE_LOCK_SHA256
        var cleanup = "not_attempted"
        var tree = Gate3bTreeFingerprint.EMPTY
        var treeManifestSha256 = ""
        var treeManifestBytes = 0L
        var hiddenLockSha256 = ""
        var hiddenLockBytes = 0L
        var timedOut = false
        var cancellationThread: Thread? = null
        var cache = Gate3bTreeFingerprint.EMPTY
        var processEnvironmentRestored = false
        var processCwdRestored = false
        var processSurfaceRestored = false
        var processListenerNamesRestored = false
        var processListenersRestored = false
        var processExecPathRestored = false
        var npmInstanceCaptured = false
        var npmUnloadAttempted = false
        var npmUnloadSucceeded = false
        var npmUnloadCalls = 0
        var npmUnrefPromisesSettled = false
        var installerRuntimeNonce = ""
        var processStateRestoration = "not_attempted"
        var installerRuntimeClosure = "not_attempted"
        var dependencyPhaseElapsedMillis = 0L
        var rssAfterInstallerRuntimeCloseKilobytes = 0L
        var peakRssBeforeLocalBundleKilobytes = 0L
        var rssAfterLocalBundleKilobytes = 0L
        var peakRssAfterLocalBundleKilobytes = 0L
        var npmRuntimeClosedBeforeLocalBuild = false
        var localBundleBuildResult: Gate3bLocalBundleBuildResult? = null
        var runnableValues: Map<String, String> = if (runnableAcceptance) {
            mapOf("result" to "not_run")
        } else {
            mapOf("result" to "not_requested")
        }

        try {
            val active = StmNodeRuntimeFactory.create(
                arrayOf("stm-core-gate3b-${candidate.name.lowercase()}"),
            )
            runtime = active
            installerWorkspaceLifetime.markRuntimeCreated()
            nodeVersion = active.getExecutor("process.version").executeString().orEmpty()
            processExecPath = active.getExecutor("process.execPath").executeString().orEmpty()
            active.getExecutor(
                bootstrapScript(
                    npmRoot,
                    workRoot,
                    cacheRoot,
                    tempRoot,
                    networkProxy,
                    operationNonce,
                ),
            ).executeVoid()
            cancellationThread = cancelAfterMillis?.let { delayMillis ->
                Thread(
                    {
                        try {
                            Thread.sleep(delayMillis)
                            cancel()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    },
                    "STM-Gate3B-${candidate.name}-Cancellation",
                ).apply {
                    isDaemon = true
                    start()
                }
            }
            val deadline = SystemClock.elapsedRealtime() + installTimeoutMillis
            while (SystemClock.elapsedRealtime() < deadline) {
                check(!cancelled.get()) { "Stage 3B dependency experiment was cancelled" }
                // npm/Arborist can retain timers or sockets after their result Promise settles.
                // RunOnce may block on those handles and hide the completed result from Kotlin.
                active.await(V8AwaitMode.RunNoWait)
                val completed = active.getExecutor(
                    "Boolean(globalThis.__stmGate3b?.done)",
                ).executeBoolean()
                if (completed) break
                Thread.sleep(EVENT_LOOP_POLL_MILLIS)
            }
            val completed = active.getExecutor(
                "Boolean(globalThis.__stmGate3b?.done)",
            ).executeBoolean()
            timedOut = !completed
            check(completed) {
                "Stage 3B dependency installer exceeded its time budget"
            }
            javascriptResult = active.getExecutor(
                "String(globalThis.__stmGate3b?.result || '')",
            ).executeString().orEmpty()
            javascriptError = active.getExecutor(
                "String(globalThis.__stmGate3b?.error || '')",
            ).executeString().orEmpty()
            processExitAttempted = active.getExecutor(
                "String(Boolean(globalThis.__stmGate3b?.exitAttempted))",
            ).executeString().orEmpty()
            processExitCode = active.getExecutor(
                "String(globalThis.__stmGate3b?.exitCode ?? '')",
            ).executeString().orEmpty()
            npmPromiseSettled = active.getExecutor(
                "Boolean(globalThis.__stmGate3b?.npmPromiseSettled)",
            ).executeBoolean()
            npmOutput = active.getExecutor(
                "String(globalThis.__stmGate3b?.output || '')",
            ).executeString().orEmpty()
            installerRuntimeNonce = active.getExecutor(
                "String(globalThis.__stmGate3b?.runtimeNonce || '')",
            ).executeString().orEmpty()
            check(npmPromiseSettled) {
                "Stage 3B dependency installer reported completion before its Promise settled"
            }
            if (javascriptError.isBlank()) {
                postInstallPackageLockSha256 = sha256(File(workRoot, "package-lock.json"))
                check(postInstallPackageLockSha256 == EXPECTED_PACKAGE_LOCK_SHA256) {
                    "Dependency installer changed the fixed package-lock identity"
                }
                check(File(workRoot, "node_modules").isDirectory) {
                    "Dependency installer completed without creating node_modules"
                }
                val scan = Gate3bTreeScanner.scan(
                    File(workRoot, "node_modules").toPath(),
                    includeManifest = true,
                )
                tree = scan.fingerprint
                cache = fingerprintTree(cacheRoot.toPath())
                validateInstalledTree(tree)
                val evidence = Gate3bTreeEvidenceStore.persist(
                    appContext,
                    candidate,
                    requireNotNull(scan.manifestBytes),
                )
                treeManifestSha256 = evidence.sha256
                treeManifestBytes = evidence.bytes
                val hiddenLockEvidence = Gate3bTreeEvidenceStore.persistHiddenLock(
                    appContext,
                    candidate,
                    requireRegular(File(workRoot, "node_modules/.package-lock.json"), workRoot),
                )
                hiddenLockSha256 = hiddenLockEvidence.sha256
                hiddenLockBytes = hiddenLockEvidence.bytes
                dependencyPhaseElapsedMillis = SystemClock.elapsedRealtime() - startedElapsed
                if (localBundleBuild) {
                    val restored = JSONObject(
                        active.getExecutor(processStateRestorationScript())
                            .executeString()
                            .orEmpty(),
                    )
                    processEnvironmentRestored = restored.getBoolean("environmentRestored")
                    processCwdRestored = restored.getBoolean("cwdRestored") &&
                        File(".").canonicalPath == processCwdBefore
                    processSurfaceRestored = restored.getBoolean("processSurfaceRestored")
                    processListenerNamesRestored = restored.getBoolean("listenerNamesRestored")
                    processListenersRestored = restored.getBoolean("listenersRestored")
                    processExecPathRestored = restored.getBoolean("execPathRestored")
                    npmInstanceCaptured = restored.getBoolean("npmInstanceCaptured")
                    npmUnloadAttempted = restored.getBoolean("npmUnloadAttempted")
                    npmUnloadSucceeded = restored.getBoolean("npmUnloadSucceeded")
                    npmUnloadCalls = restored.getInt("npmUnloadCalls")
                    npmUnrefPromisesSettled = restored.getBoolean("npmUnrefPromisesSettled")
                    check(
                        processEnvironmentRestored &&
                            processCwdRestored &&
                            processSurfaceRestored &&
                            processListenerNamesRestored &&
                            processListenersRestored &&
                            processExecPathRestored &&
                            npmInstanceCaptured &&
                            npmUnloadAttempted &&
                            npmUnloadSucceeded &&
                            npmUnloadCalls == 1 &&
                            npmUnrefPromisesSettled
                    ) {
                        "npm runtime did not complete its lifecycle before local bundle construction"
                    }
                }
            } else {
                executionFailure = javascriptError
                File(workRoot, "node_modules").takeIf(File::isDirectory)?.let { directory ->
                    tree = fingerprintTree(directory.toPath())
                }
                cacheRoot.takeIf(File::isDirectory)?.let { directory ->
                    cache = fingerprintTree(directory.toPath())
                }
            }
        } catch (error: Exception) {
            executionFailure = error.safeGate3bDetail()
            File(workRoot, "package-lock.json").takeIf(File::isFile)?.let { lock ->
                postInstallPackageLockSha256 = runCatching { sha256(lock) }
                    .getOrDefault("unreadable")
            }
            File(workRoot, "node_modules").takeIf(File::isDirectory)?.let { directory ->
                tree = runCatching { fingerprintTree(directory.toPath()) }
                    .getOrDefault(Gate3bTreeFingerprint.EMPTY)
            }
            cacheRoot.takeIf(File::isDirectory)?.let { directory ->
                cache = runCatching { fingerprintTree(directory.toPath()) }
                    .getOrDefault(Gate3bTreeFingerprint.EMPTY)
            }
        } finally {
            cancellationThread?.interrupt()
            cancellationThread?.join(2_000)
            runtime?.let { active ->
                runCatching { active.cancelTerminateExecution() }
                processStateRestoration = runCatching {
                    val restored = JSONObject(
                        active.getExecutor(processStateRestorationScript())
                            .executeString()
                            .orEmpty(),
                    )
                    processEnvironmentRestored = restored.getBoolean("environmentRestored")
                    processCwdRestored = restored.getBoolean("cwdRestored") &&
                        File(".").canonicalPath == processCwdBefore
                    processSurfaceRestored = restored.getBoolean("processSurfaceRestored")
                    processListenerNamesRestored = restored.getBoolean("listenerNamesRestored")
                    processListenersRestored = restored.getBoolean("listenersRestored")
                    processExecPathRestored = restored.getBoolean("execPathRestored")
                    npmInstanceCaptured = restored.getBoolean("npmInstanceCaptured")
                    npmUnloadAttempted = restored.getBoolean("npmUnloadAttempted")
                    npmUnloadSucceeded = restored.getBoolean("npmUnloadSucceeded")
                    npmUnloadCalls = restored.getInt("npmUnloadCalls")
                    npmUnrefPromisesSettled = restored.getBoolean("npmUnrefPromisesSettled")
                    val npmLifecycleRestored =
                        candidate != StmDependencySupplyCandidate.NPM_CLI ||
                            (
                                npmInstanceCaptured &&
                                    npmUnloadAttempted &&
                                    npmUnloadSucceeded &&
                                    npmUnloadCalls == 1
                                )
                    check(
                        processEnvironmentRestored &&
                            processCwdRestored &&
                            processSurfaceRestored &&
                            processListenerNamesRestored &&
                            processListenersRestored &&
                            processExecPathRestored &&
                            npmLifecycleRestored
                    ) {
                        "Stage 3B dependency experiment did not restore process state"
                    }
                    installerWorkspaceLifetime.markProcessStateRestored(true)
                    "restored"
                }.getOrElse { error ->
                    installerWorkspaceLifetime.markProcessStateRestored(false)
                    "failed:${error.safeGate3bDetail()}"
                }
                val closeErrors = mutableListOf<String>()
                runCatching { active.setStopping(true) }
                    .onFailure { error -> closeErrors += "setStopping:${error.safeGate3bDetail()}" }
                runCatching { active.close(true) }
                    .onFailure { error -> closeErrors += "close:${error.safeGate3bDetail()}" }
                val runtimeClosed = runCatching { active.isClosed }.getOrDefault(false)
                installerWorkspaceLifetime.markRuntimeClosed(runtimeClosed)
                if (!runtimeClosed) closeErrors += "runtime_remained_open"
                installerRuntimeClosure = if (closeErrors.isEmpty()) {
                    "closed"
                } else {
                    "failed:${closeErrors.joinToString(",")}"
                }
                if (runtimeClosed && runtime === active) runtime = null
            }
            if (dependencyPhaseElapsedMillis == 0L) {
                dependencyPhaseElapsedMillis = SystemClock.elapsedRealtime() - startedElapsed
            }
            if (
                runnable != null &&
                runnableSource != null &&
                executionFailure.isBlank() &&
                javascriptError.isBlank() &&
                processStateRestoration == "restored" &&
                installerRuntimeClosure == "closed" &&
                !cancelled.get()
            ) {
                runnableValues = runCatching {
                    if (localBundleBuild) {
                        npmRuntimeClosedBeforeLocalBuild =
                            runtime == null &&
                            installerRuntimeClosure == "closed" &&
                            installerWorkspaceLifetime.isDeletionSafe()
                        check(npmRuntimeClosedBeforeLocalBuild) {
                            "npm runtime remained live or unrestored before local bundle construction"
                        }
                        rssAfterInstallerRuntimeCloseKilobytes =
                            Gate3bMemorySampler.currentRssKilobytes()
                        peakRssBeforeLocalBundleKilobytes =
                            memorySampler.peakRssKilobytes.get()
                        localBundleBuildResult = runnable.buildLocalBundleInFreshRuntime(
                            candidateWorkRoot = workRoot,
                            source = runnableSource,
                            expectedDependencyTree = tree,
                            operationNonce = operationNonce,
                            installerRuntimeNonce = installerRuntimeNonce,
                        )
                        rssAfterLocalBundleKilobytes = Gate3bMemorySampler.currentRssKilobytes()
                        peakRssAfterLocalBundleKilobytes = memorySampler.peakRssKilobytes.get()
                    }
                    runnable.run(
                        experimentRoot,
                        workRoot,
                        runnableSource,
                        tree,
                        localBundleBuildResult,
                        npmRuntimeClosedBeforeLocalBuild,
                    )
                }.getOrElse { error ->
                    val detail = error.safeGate3bDetail()
                    executionFailure = listOf(
                        executionFailure,
                        "Runnable acceptance failed: $detail",
                    ).filter(String::isNotBlank).joinToString("; ")
                    linkedMapOf(
                        "result" to "failed",
                        "failure" to detail,
                    ).apply { putAll(runnable.teardownEvidence()) }
                }
            }
            memorySampler.close()
            val currentProcessCwd = runCatching { File(".").canonicalFile.toPath() }
                .getOrDefault(experimentRoot.toPath())
            val normalizedExperimentRoot = experimentRoot.toPath().toAbsolutePath().normalize()
            val normalizedProcessCwd = currentProcessCwd.toAbsolutePath().normalize()
            processCwdOutsideExperimentRoot =
                normalizedProcessCwd != normalizedExperimentRoot &&
                !normalizedProcessCwd.startsWith(normalizedExperimentRoot)
            cleanup = cleanupGate3bExperimentWorkspace(
                root = experimentRoot.toPath(),
                ownedParent = requireNotNull(experimentRoot.parentFile).toPath(),
                installerDeletionSafe = installerWorkspaceLifetime.isDeletionSafe(),
                runnableDeletionSafe = runnable?.isWorkspaceDeletionSafe() != false,
                currentProcessCwd = currentProcessCwd,
            )
            if (!experimentRoot.exists()) experimentRootForTeardown = null
            if (runnable?.isWorkspaceDeletionSafe() != false) {
                activeRunnableAcceptance = null
            }
        }

        if (processStateRestoration.startsWith("failed:")) {
            executionFailure = listOf(
                executionFailure,
                "Process-state restoration failed: $processStateRestoration",
            ).filter(String::isNotBlank).joinToString("; ")
        }
        if (installerRuntimeClosure.startsWith("failed:")) {
            executionFailure = listOf(
                executionFailure,
                "Installer runtime closure failed: $installerRuntimeClosure",
            ).filter(String::isNotBlank).joinToString("; ")
        }
        if (runnableAcceptance && cleanup != "removed") {
            executionFailure = listOf(
                executionFailure,
                "Runnable workspace cleanup failed: $cleanup",
            ).filter(String::isNotBlank).joinToString("; ")
        }

        val slotsAfter = captureGate3bCommittedSlotIdentity(appContext)
        val activePointerAfter = captureGate3bFileIdentity(
            StmCorePaths.activeSlotFile(appContext),
            "Stage 3B active-slot pointer",
        )

        return linkedMapOf(
            "result" to if (executionFailure.isBlank()) "passed" else "failed",
            "candidate" to candidate.name,
            "st_commit" to ST_COMMIT,
            "package_lock_sha256" to EXPECTED_PACKAGE_LOCK_SHA256,
            "post_install_package_lock_sha256" to postInstallPackageLockSha256,
            "npm_version" to NPM_VERSION,
            "npm_toolchain_source" to "apk_asset",
            "npm_toolchain_archive_sha256" to npmToolchain.archiveSha256,
            "npm_toolchain_tree_sha256" to npmToolchain.treeSha256,
            "npm_toolchain_manifest_sha256" to npmToolchain.manifestSha256,
            "npm_toolchain_files" to npmToolchain.fileCount.toString(),
            "npm_toolchain_directories" to npmToolchain.directoryCount.toString(),
            "npm_toolchain_bytes" to npmToolchain.totalFileBytes.toString(),
            "npm_toolchain_reused" to npmToolchain.reused.toString(),
            "npm_toolchain_license_gap_count" to npmToolchain.licenseGapCount.toString(),
            "arborist_version" to ARBORIST_VERSION,
            "node_version" to nodeVersion,
            "process_exec_path" to processExecPath,
            "process_exit_attempted" to processExitAttempted,
            "process_exit_code" to processExitCode,
            "npm_promise_settled" to npmPromiseSettled.toString(),
            "npm_output" to npmOutput.take(MAX_RESULT_CHARS),
            "javascript_result" to javascriptResult.take(MAX_RESULT_CHARS),
            "failure" to executionFailure.take(MAX_RESULT_CHARS),
            "network_proxy" to (networkProxy ?: "direct"),
            "time_budget_ms" to installTimeoutMillis.toString(),
            "cancel_after_ms" to cancelAfterMillis?.toString().orEmpty(),
            "timed_out" to timedOut.toString(),
            "cancel_requested" to cancelled.get().toString(),
            "dependency_phase_elapsed_ms" to dependencyPhaseElapsedMillis.toString(),
            "elapsed_ms" to (SystemClock.elapsedRealtime() - startedElapsed).toString(),
            "peak_rss_kb" to memorySampler.peakRssKilobytes.get().toString(),
            "vm_hwm_kb" to memorySampler.maximumVmHwmKilobytes.get().toString(),
            "memory_scope" to if (runnableAcceptance) {
                "combined_dependency_install_and_runnable_core_process"
            } else {
                "dependency_install_core_process"
            },
            "node_modules_sha256" to tree.sha256,
            "node_modules_files" to tree.files.toString(),
            "node_modules_directories" to tree.directories.toString(),
            "node_modules_symlinks" to tree.symlinks.toString(),
            "node_modules_bytes" to tree.bytes.toString(),
            "tree_manifest_sha256" to treeManifestSha256,
            "tree_manifest_bytes" to treeManifestBytes.toString(),
            "tree_manifest_persisted" to (treeManifestSha256.isNotBlank()).toString(),
            "hidden_lock_sha256" to hiddenLockSha256,
            "hidden_lock_bytes" to hiddenLockBytes.toString(),
            "hidden_lock_persisted" to (hiddenLockSha256.isNotBlank()).toString(),
            "npm_cache_sha256" to cache.sha256,
            "npm_cache_files" to cache.files.toString(),
            "npm_cache_bytes" to cache.bytes.toString(),
            "process_state_restoration" to processStateRestoration,
            "process_environment_restored" to processEnvironmentRestored.toString(),
            "process_cwd_restored" to processCwdRestored.toString(),
            "process_surface_restored" to processSurfaceRestored.toString(),
            "process_listener_names_restored" to processListenerNamesRestored.toString(),
            "process_listeners_restored" to processListenersRestored.toString(),
            "process_exec_path_restored" to processExecPathRestored.toString(),
            "npm_instance_captured" to npmInstanceCaptured.toString(),
            "npm_unload_attempted" to npmUnloadAttempted.toString(),
            "npm_unload_succeeded" to npmUnloadSucceeded.toString(),
            "npm_unload_calls" to npmUnloadCalls.toString(),
            "npm_unref_promises_settled" to npmUnrefPromisesSettled.toString(),
            "installer_runtime_nonce_sha256" to installerRuntimeNonce
                .takeIf(String::isNotBlank)
                ?.let(::sha256)
                .orEmpty(),
            "installer_runtime_closure" to installerRuntimeClosure,
            "rss_after_installer_runtime_close_kb" to
                rssAfterInstallerRuntimeCloseKilobytes.toString(),
            "peak_rss_before_local_bundle_kb" to peakRssBeforeLocalBundleKilobytes.toString(),
            "rss_after_local_bundle_kb" to rssAfterLocalBundleKilobytes.toString(),
            "peak_rss_after_local_bundle_kb" to peakRssAfterLocalBundleKilobytes.toString(),
            "cleanup" to cleanup,
            "committed_slots_unchanged" to (slotsBefore == slotsAfter).toString(),
            "active_slot_pointer_unchanged" to
                (activePointerBefore == activePointerAfter).toString(),
            "runnable_requested" to runnableAcceptance.toString(),
            "local_bundle_build_requested" to localBundleBuild.toString(),
        ).apply {
            runnableValues.forEach { (key, value) -> put("runnable_$key", value) }
        }
    }

    private fun bootstrapScript(
        npmRoot: File,
        workRoot: File,
        cacheRoot: File,
        tempRoot: File,
        networkProxy: String?,
        operationNonce: String,
    ): String {
        val proxyLiteral = networkProxy?.let(::jsString) ?: "null"
        val common =
            """
            (() => {
              const crypto = require('node:crypto');
              const state = globalThis.__stmGate3b = {
                done: false,
                npmPromiseSettled: false,
                result: '',
                error: '',
                output: '',
                exitAttempted: false,
                exitCode: null,
                operationNonce: ${jsString(operationNonce)},
                runtimeNonce: crypto.randomUUID(),
                npmInstance: null,
                npmInstanceCaptured: false,
                npmUnloadAttempted: false,
                npmUnloadSucceeded: false,
                npmUnloadCalls: 0,
                npmUnrefPromisesSettled: false,
              };
              Object.defineProperties(state, {
                operationNonce: { value: state.operationNonce, writable: false, configurable: false },
                runtimeNonce: { value: state.runtimeNonce, writable: false, configurable: false },
              });
              const format = error => String(error && (error.stack || error));
              const work = ${jsString(workRoot.absolutePath)};
              const cache = ${jsString(cacheRoot.absolutePath)};
              const temp = ${jsString(tempRoot.absolutePath)};
              const networkProxy = $proxyLiteral;
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
              process.env.HOME = temp;
              process.env.TMPDIR = temp;
              process.env.TMP = temp;
              process.env.TEMP = temp;
              process.env.npm_config_registry = ${jsString(REGISTRY)};
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
                process.env.HTTP_PROXY = networkProxy;
                process.env.HTTPS_PROXY = networkProxy;
                process.env.http_proxy = networkProxy;
                process.env.https_proxy = networkProxy;
                process.env.npm_config_proxy = networkProxy;
                process.env.npm_config_https_proxy = networkProxy;
              } else {
                for (const name of [
                  'HTTP_PROXY',
                  'HTTPS_PROXY',
                  'http_proxy',
                  'https_proxy',
                  'npm_config_proxy',
                  'npm_config_https_proxy',
                ]) delete process.env[name];
              }
              process.chdir(work);
            """.trimIndent()
        val body = when (candidate) {
            StmDependencySupplyCandidate.NPM_CLI ->
                """
                  const npmCli = ${jsString(File(npmRoot, "lib/cli.js").absolutePath)};
                  const Npm = require(${jsString(File(npmRoot, "lib/npm.js").absolutePath)});
                  state.npmPrototype = Npm.prototype;
                  state.originalNpmLoad = Npm.prototype.load;
                  state.originalNpmUnload = Npm.prototype.unload;
                  state.wrappedNpmLoad = function(...args) {
                    state.npmInstance = this;
                    state.npmInstanceCaptured = true;
                    return Reflect.apply(state.originalNpmLoad, this, args);
                  };
                  state.wrappedNpmUnload = function(...args) {
                    state.npmUnloadAttempted = true;
                    state.npmUnloadCalls += 1;
                    try {
                      const result = Reflect.apply(state.originalNpmUnload, this, args);
                      state.npmUnloadSucceeded = true;
                      return result;
                    } catch (error) {
                      state.npmUnloadError = format(error);
                      throw error;
                    }
                  };
                  Npm.prototype.load = state.wrappedNpmLoad;
                  Npm.prototype.unload = state.wrappedNpmUnload;
                  const outputChunks = [];
                  let outputCharacters = 0;
                  const captureOutput = (chunk, encoding, callback) => {
                    const text = String(chunk);
                    outputChunks.push(text);
                    outputCharacters += text.length;
                    while (outputCharacters > 32768 && outputChunks.length > 1) {
                      outputCharacters -= outputChunks.shift().length;
                    }
                    const completion = typeof encoding === 'function' ? encoding : callback;
                    if (typeof completion === 'function') queueMicrotask(completion);
                    return true;
                  };
                  process.stdout.write = captureOutput;
                  process.stderr.write = captureOutput;
                  process.argv.splice(
                    0,
                    process.argv.length,
                    process.execPath,
                    ${jsString(File(npmRoot, "bin/npm-cli.js").absolutePath)},
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
                    '--registry=${REGISTRY}',
                    '--cache=' + cache,
                    '--userconfig=' + temp + '/user.npmrc',
                    '--globalconfig=' + temp + '/global.npmrc'
                  );
                  if (networkProxy) {
                    process.argv.push('--proxy=' + networkProxy, '--https-proxy=' + networkProxy);
                  }
                  process.exit = code => {
                    const normalizedCode = Number(code ?? process.exitCode ?? 0);
                    state.exitAttempted = true;
                    state.exitCode = normalizedCode;
                    state.output = outputChunks.join('').slice(-32768);
                    if (normalizedCode !== 0) {
                      state.error =
                        'npm CLI exited with code ' + normalizedCode + '\n' + state.output;
                    }
                    state.result = 'npm_cli_exit_intercepted';
                  };
                  const settleUnrefPromises = () => {
                    const pending = Array.isArray(state.npmInstance?.unrefPromises)
                      ? Array.from(state.npmInstance.unrefPromises)
                      : [];
                    return Promise.allSettled(pending).then(() => {
                      state.npmUnrefPromisesSettled = true;
                    });
                  };
                  Promise.resolve()
                    .then(() => require(npmCli)(process))
                    .then(async () => {
                      state.npmPromiseSettled = true;
                      await settleUnrefPromises();
                      setImmediate(() => {
                        state.output = outputChunks.join('').slice(-32768);
                        if (!state.result) state.result = 'npm_cli_returned_without_exit';
                        if (Number(state.exitCode ?? 0) !== 0 && !state.error) {
                          state.error =
                            'npm CLI exited with code ' + String(state.exitCode) + '\n' + state.output;
                        }
                        state.done = true;
                      });
                    })
                    .catch(async error => {
                      state.npmPromiseSettled = true;
                      state.error = format(error);
                      await settleUnrefPromises();
                      state.done = true;
                    });
                """.trimIndent()

            StmDependencySupplyCandidate.ARBORIST ->
                """
                  const Arborist = require(
                    ${jsString(
                        File(npmRoot, "node_modules/@npmcli/arborist").absolutePath,
                    )}
                  );
                  const installOptions = {
                    path: work,
                    cache,
                    registry: ${jsString(REGISTRY)},
                    omit: ['dev'],
                    ignoreScripts: true,
                    binLinks: false,
                    audit: false,
                    fund: false,
                    packageLock: true,
                    save: false,
                    maxSockets: 4,
                    fetchRetries: 5,
                    fetchRetryMintimeout: 1000,
                    fetchRetryMaxtimeout: 15000,
                    fetchTimeout: 120000,
                    proxy: networkProxy || undefined,
                    httpsProxy: networkProxy || undefined,
                  };
                  const arborist = new Arborist(installOptions);
                  Promise.resolve(arborist.reify(installOptions))
                    .then(() => {
                      state.npmPromiseSettled = true;
                      state.result = 'arborist_reify_completed';
                      state.done = true;
                    })
                    .catch(error => {
                      state.npmPromiseSettled = true;
                      state.error = format(error);
                      state.done = true;
                    });
                """.trimIndent()

            StmDependencySupplyCandidate.SIGNED_PREBUILT ->
                error("Signed prebuilt is not a device-side JavaScript installer")
        }
        return "$common\n$body\n})();"
    }

    private fun processStateRestorationScript(): String =
        """
        (() => {
          const state = globalThis.__stmGate3b;
          if (!state || !state.originalCwd || !state.originalEnv ||
              !Array.isArray(state.originalArgv) || typeof state.originalExit !== 'function' ||
              typeof state.originalExecPath !== 'string' ||
              typeof state.originalStdoutWrite !== 'function' ||
              typeof state.originalStderrWrite !== 'function' ||
              !Array.isArray(state.listenerNames) || !Array.isArray(state.originalListeners)) {
            throw new Error('Stage 3B process-state snapshot is unavailable');
          }
          const bundleState = globalThis.__stmGate3bLocalBundle;
          if (bundleState) {
            if (
              bundleState.compilerPrototype &&
              typeof bundleState.originalCompilerRun === 'function'
            ) {
              bundleState.compilerPrototype.run = bundleState.originalCompilerRun;
            }
            if (
              bundleState.compilerPrototype &&
              typeof bundleState.originalCompilerClose === 'function'
            ) {
              bundleState.compilerPrototype.close = bundleState.originalCompilerClose;
            }
            if (bundleState.originalConsole) {
              for (const level of ['log', 'info', 'warn', 'error']) {
                if (typeof bundleState.originalConsole[level] === 'function') {
                  console[level] = bundleState.originalConsole[level];
                }
              }
            }
            if (bundleState.originalCwd) process.chdir(bundleState.originalCwd);
          }
          let npmPrototypeRestored = true;
          if (state.npmPrototype) {
            if (state.npmInstanceCaptured && state.npmUnloadCalls === 0) {
              try {
                state.npmInstance.unload();
              } catch (error) {
                state.npmUnloadError = String(error && (error.stack || error));
              }
            }
            if (typeof state.originalNpmLoad === 'function') {
              state.npmPrototype.load = state.originalNpmLoad;
            }
            if (typeof state.originalNpmUnload === 'function') {
              state.npmPrototype.unload = state.originalNpmUnload;
            }
            npmPrototypeRestored =
              state.npmPrototype.load === state.originalNpmLoad &&
              state.npmPrototype.unload === state.originalNpmUnload;
          }
          for (const name of Object.keys(process.env)) delete process.env[name];
          for (const [name, value] of Object.entries(state.originalEnv)) {
            process.env[name] = value;
          }
          process.argv.splice(0, process.argv.length, ...state.originalArgv);
          process.execPath = state.originalExecPath;
          process.exit = state.originalExit;
          if (state.originalExitCode === undefined) {
            process.exitCode = undefined;
          } else {
            process.exitCode = state.originalExitCode;
          }
          process.stdout.write = state.originalStdoutWrite;
          process.stderr.write = state.originalStderrWrite;
          process.title = state.originalTitle;
          for (const name of process.eventNames()) process.removeAllListeners(name);
          for (const [name, listeners] of state.originalListeners) {
            for (const listener of listeners) process.on(name, listener);
          }
          process.chdir(state.originalCwd);
          const environmentNames = Object.keys(process.env);
          const originalEnvironmentNames = Object.keys(state.originalEnv);
          const environmentRestored =
            environmentNames.length === originalEnvironmentNames.length &&
            originalEnvironmentNames.every(name => process.env[name] === state.originalEnv[name]);
          const cwdRestored = process.cwd() === state.originalCwd;
          const argvRestored = process.argv.length === state.originalArgv.length &&
            process.argv.every((value, index) => value === state.originalArgv[index]);
          const execPathRestored = process.execPath === state.originalExecPath;
          const currentListenerNames = process.eventNames();
          const listenerNamesRestored =
            currentListenerNames.length === state.listenerNames.length &&
            state.listenerNames.every(name => currentListenerNames.includes(name));
          const listenersRestored = listenerNamesRestored && state.originalListeners.every(
            ([name, expected]) => {
            const actual = process.rawListeners(name);
            return actual.length === expected.length &&
              actual.every((listener, index) => listener === expected[index]);
            }
          );
          const npmLifecycleRestored = !state.npmPrototype ||
            (state.npmInstanceCaptured && state.npmUnloadAttempted &&
              state.npmUnloadSucceeded && state.npmUnloadCalls === 1 && npmPrototypeRestored);
          const processSurfaceRestored = argvRestored && execPathRestored &&
            process.exit === state.originalExit &&
            process.exitCode === state.originalExitCode &&
            process.stdout.write === state.originalStdoutWrite &&
            process.stderr.write === state.originalStderrWrite &&
            process.title === state.originalTitle && npmLifecycleRestored &&
            listenersRestored;
          state.processSurfaceRestored = processSurfaceRestored;
          return JSON.stringify({
            environmentRestored,
            cwdRestored,
            argvRestored,
            listenerNamesRestored,
            listenersRestored,
            execPathRestored,
            npmInstanceCaptured: Boolean(state.npmInstanceCaptured),
            npmUnloadAttempted: Boolean(state.npmUnloadAttempted),
            npmUnloadSucceeded: Boolean(state.npmUnloadSucceeded),
            npmUnloadCalls: Number(state.npmUnloadCalls || 0),
            npmUnrefPromisesSettled: Boolean(state.npmUnrefPromisesSettled),
            npmPrototypeRestored,
            processSurfaceRestored,
          });
        })();
        """.trimIndent()

    private fun requireFixedSourceProgram(): File {
        val expectedParent = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/gate3a/$ST_COMMIT",
        ).canonicalFile
        val program = File(expectedParent, "program").canonicalFile
        check(program.parentFile == expectedParent && program.isDirectory) {
            "Gate 3A fixed source program is unavailable"
        }
        check(!File(program, ".git").exists()) { "Fixed source program contains Git metadata" }
        val packageJson = requireRegular(File(program, "package.json"), program)
        val packageLock = requireRegular(File(program, "package-lock.json"), program)
        check(JSONObject(packageJson.readText()).getString("version") == ST_VERSION) {
            "Fixed source package version is not $ST_VERSION"
        }
        check(sha256(packageLock) == EXPECTED_PACKAGE_LOCK_SHA256) {
            "Fixed source package-lock SHA-256 did not match"
        }
        return program
    }

    private fun requireFixedNpmToolchain(): StmPreparedNpmToolchain {
        val prepared = StmBundledNpmToolchainFactory.create(appContext).prepare(
            StmExtractionCancellation { cancelled.get() },
        )
        val npm = prepared.npmDirectory.canonicalFile
        check(
            prepared.npmVersion == NPM_VERSION &&
                prepared.fileCount == EXPECTED_PRODUCTION_NPM_FILES &&
                prepared.directoryCount == EXPECTED_PRODUCTION_NPM_DIRECTORIES &&
                prepared.totalFileBytes == EXPECTED_PRODUCTION_NPM_BYTES &&
                prepared.treeSha256 == EXPECTED_PRODUCTION_NPM_TREE_SHA256 &&
                npm.isDirectory,
        ) {
            "APK npm toolchain identity did not match the fixed production asset"
        }
        val packageJson = requireRegular(File(npm, "package.json"), npm)
        val arboristPackage = requireRegular(
            File(npm, "node_modules/@npmcli/arborist/package.json"),
            npm,
        )
        requireRegular(File(npm, "lib/cli.js"), npm)
        requireRegular(File(npm, "bin/npm-cli.js"), npm)
        check(
            packageJson.length() == EXPECTED_NPM_PACKAGE_JSON_BYTES &&
                sha256(packageJson) == EXPECTED_NPM_PACKAGE_JSON_SHA256 &&
                JSONObject(packageJson.readText()).getString("version") == NPM_VERSION,
        ) {
            "Staged npm package identity did not match the fixed experiment input"
        }
        check(
            arboristPackage.length() == EXPECTED_ARBORIST_PACKAGE_JSON_BYTES &&
                sha256(arboristPackage) == EXPECTED_ARBORIST_PACKAGE_JSON_SHA256 &&
                JSONObject(arboristPackage.readText()).getString("version") == ARBORIST_VERSION,
        ) {
            "Staged Arborist package identity did not match the fixed experiment input"
        }
        return prepared
    }

    private fun prepareExperimentRoot(): File {
        val parent = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/gate3b/work",
        ).canonicalFile
        Files.createDirectories(parent.toPath())
        check(!Files.isSymbolicLink(parent.toPath()) && parent.isDirectory) {
            "Stage 3B experiment root is unsafe"
        }
        val root = File(
            parent,
            "${candidate.name.lowercase()}-${UUID.randomUUID()}",
        )
        require(root.mkdir()) { "Could not create Stage 3B experiment directory" }
        check(root.canonicalFile.parentFile == parent) { "Stage 3B work root escaped" }
        return root
    }

    private fun copyRequiredSourceInputs(sourceProgram: File, workRoot: File) {
        listOf("package.json", "package-lock.json").forEach { name ->
            val source = requireRegular(File(sourceProgram, name), sourceProgram)
            val target = File(workRoot, name)
            Files.copy(source.toPath(), target.toPath())
            check(
                target.setReadable(true, true) &&
                    target.setWritable(true, true) &&
                    target.setExecutable(false, false) &&
                target.isFile &&
                    target.canWrite() &&
                    !Files.isSymbolicLink(target.toPath()),
            ) {
                "Stage 3B source input copy failed"
            }
        }
    }

    private fun requireRegular(file: File, root: File): File {
        val path = file.toPath()
        check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Required experiment input is not a regular file: ${file.name}"
        }
        val canonical = file.canonicalFile
        check(canonical.toPath().startsWith(root.canonicalFile.toPath())) {
            "Required experiment input escaped its owned root"
        }
        return canonical
    }

    private fun validateInstalledTree(tree: Gate3bTreeFingerprint) {
        check(tree.files > 0 && tree.directories > 0 && tree.bytes > 0) {
            "Installed dependency tree is empty"
        }
        check(tree.symlinks == 0L) {
            "Fixed --no-bin-links policy produced symbolic links"
        }
        check(tree.special == 0L) {
            "Dependency tree contains a special filesystem entry"
        }
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

    private fun fingerprintTree(root: Path): Gate3bTreeFingerprint =
        Gate3bTreeScanner.scan(root, includeManifest = false).fingerprint

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

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

    private companion object {
        const val ST_COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val ST_VERSION = "1.18.0"
        const val EXPECTED_PACKAGE_LOCK_SHA256 =
            "7484f87e7dc6e99044ad532b80111c3e93463aaf1d5dbe377b3a4486bfe65f6f"
        const val NPM_VERSION = "11.6.2"
        const val ARBORIST_VERSION = "9.1.6"
        const val EXPECTED_NPM_PACKAGE_JSON_BYTES = 6_535L
        const val EXPECTED_NPM_PACKAGE_JSON_SHA256 =
            "7cbb6a7c030b398a7591992750cfb3e2479ef4f8aaa40316e3deb98af3b8184c"
        const val EXPECTED_ARBORIST_PACKAGE_JSON_BYTES = 2_754L
        const val EXPECTED_ARBORIST_PACKAGE_JSON_SHA256 =
            "c4c6c72fce623a67a3d92c6940a14143b1f576ae78736c7117a4a722eae94c4f"
        const val EXPECTED_PRODUCTION_NPM_FILES = 2_133
        const val EXPECTED_PRODUCTION_NPM_DIRECTORIES = 549
        const val EXPECTED_PRODUCTION_NPM_BYTES = 11_785_613L
        const val EXPECTED_PRODUCTION_NPM_TREE_SHA256 =
            "86fe906883080018691b6d7ff9648394171d92c66a1261611430393a15810e03"
        const val REGISTRY = "https://registry.npmjs.org/"
        const val INSTALL_TIMEOUT_MILLIS = 30L * 60L * 1000L
        const val MIN_INSTALL_TIMEOUT_MILLIS = 5_000L
        const val EVENT_LOOP_POLL_MILLIS = 10L
        const val MAX_RESULT_CHARS = 2_000
        val PROXY_HOST_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$")
    }
}

internal class Gate3bMemorySampler : AutoCloseable {
    val peakRssKilobytes = AtomicLong(0)
    val maximumVmHwmKilobytes = AtomicLong(0)
    private val stopped = AtomicBoolean(false)
    private val thread = Thread(
        {
            while (!stopped.get()) {
                sample()
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            sample()
        },
        "STM-Gate3B-Memory-Sampler",
    ).apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    override fun close() {
        stopped.set(true)
        thread.interrupt()
        thread.join(2_000)
    }

    private fun sample() {
        val values = readProcessMemoryKilobytes()
        peakRssKilobytes.accumulateAndGet(values["VmRSS"] ?: 0L, ::maxOf)
        maximumVmHwmKilobytes.accumulateAndGet(values["VmHWM"] ?: 0L, ::maxOf)
    }

    companion object {
        fun currentRssKilobytes(): Long = readProcessMemoryKilobytes()["VmRSS"] ?: 0L

        private fun readProcessMemoryKilobytes(): Map<String, Long> = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.filter { line ->
                    line.startsWith("VmRSS:") || line.startsWith("VmHWM:")
                }.associate { line ->
                    line.substringBefore(':') to
                        (line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
                }
            }
        }.getOrDefault(emptyMap())
    }
}

private fun Throwable.safeGate3bDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)
