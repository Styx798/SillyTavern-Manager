package io.github.styx798.sillytavernmanager.stmcore

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDeviceLocalNpmSlotPreparer
import io.github.styx798.sillytavernmanager.stmcore.installer.StmInstallerCoordinator
import io.github.styx798.sillytavernmanager.stmcore.installer.StmInstallerCoordinatorFailpoint
import io.github.styx798.sillytavernmanager.stmcore.installer.StmInstallerCoordinatorFaultInjector
import io.github.styx798.sillytavernmanager.stmcore.installer.StmInstallerEvent
import io.github.styx798.sillytavernmanager.stmcore.installer.StmInstallerSubmission
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotVerificationResult
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class StmCoreService : Service(), FeatherEngine.Callback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clients = linkedMapOf<IBinder, Messenger>()
    private val incomingMessenger = Messenger(
        Handler(Looper.getMainLooper()) { message -> handleIncomingMessage(message) },
    )
    private val processIdentity = UUID.randomUUID().toString()
    private lateinit var checkpointStore: StmCoreCheckpointStore
    private lateinit var engine: FeatherEngine
    private lateinit var installerCoordinator: StmInstallerCoordinator
    private lateinit var state: StmCoreState
    private lateinit var committedState: StmCoreState
    private val checkpointExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "STM-Core-Checkpoint").apply { isDaemon = true }
    }
    private val runtimeVerificationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "STM-Core-Runtime-Verify").apply { isDaemon = true }
    }
    private val checkpointPersistenceFailed = AtomicBoolean(false)
    private var watchdog: Runnable? = null
    private var processTerminationScheduled = false
    private var initializationComplete = false
    private var installerRecoveryComplete = false
    private var recoveryEvidenceCount = 0
    private var serviceDestroyed = false

    override fun onCreate() {
        super.onCreate()
        checkpointStore = StmCoreCheckpointStore(this)
        engine = FeatherEngine(this)
        initializeCoreStateAsync()
    }

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onDestroy() {
        serviceDestroyed = true
        cancelWatchdog()
        runtimeVerificationExecutor.shutdownNow()
        if (::installerCoordinator.isInitialized) installerCoordinator.close()
        checkpointExecutor.shutdownNow()
        if (::engine.isInitialized) engine.destroy()
        clients.clear()
        super.onDestroy()
    }

    private fun initializeCoreStateAsync() {
        checkpointExecutor.execute {
            try {
                StmCorePaths.initializeCoreLayout(this)
                val recovered = recoverState(checkpointStore.read())
                checkpointStore.write(recovered)
                val coordinator = StmInstallerCoordinator(
                    installerCacheRoot = StmCorePaths.installerCacheRoot(this),
                    stagingRoot = StmCorePaths.stagingRoot(this),
                    slotsRoot = StmCorePaths.slotsRoot(this),
                    activeFile = StmCorePaths.activeSlotFile(this),
                    journalRoot = StmCorePaths.installerJournalRoot(this),
                    eventSink = { event -> mainHandler.post { applyInstallerEvent(event) } },
                    runtimeSlotPreparer = StmDeviceLocalNpmSlotPreparer(this),
                    faultInjector = debugInstallerFaultInjector(),
                    checkpointTerminalOperationIds = recovered.jobs
                        .filter { it.state !in ACTIVE_JOB_STATES }
                        .map(StmCoreJob::operationId)
                        .toSet(),
                )
                mainHandler.post {
                    if (serviceDestroyed) {
                        coordinator.close()
                        return@post
                    }
                    state = recovered
                    committedState = recovered
                    installerCoordinator = coordinator
                    initializationComplete = true
                    logState(recovered)
                    broadcastCommittedState()
                    coordinator.recoverAsync()
                }
            } catch (error: Throwable) {
                checkpointPersistenceFailed.set(true)
                mainHandler.post {
                    Log.e(TAG, "core_initialization_failed; terminating private Core process", error)
                    if (!processTerminationScheduled) scheduleProcessTermination()
                }
            }
        }
    }

    override fun onNodeCreated(sessionId: String, nodeVersion: String) {
        mainHandler.post {
            if (!isCurrentSession(sessionId) || state.runState != StmCoreRunState.STARTING) {
                return@post
            }
            publish {
                copy(
                    nodeVersion = nodeVersion,
                    summary = "Node.js $nodeVersion created; waiting for loopback health",
                )
            }
        }
    }

    override fun onReady(sessionId: String, port: Int, nodeVersion: String) {
        mainHandler.post {
            if (!isCurrentSession(sessionId) || state.runState != StmCoreRunState.STARTING) {
                return@post
            }
            cancelWatchdog()
            val healthyAt = System.currentTimeMillis()
            publish {
                copy(
                    runState = StmCoreRunState.RUNNING,
                    localBaseUrl = "http://127.0.0.1:$port",
                    port = port,
                    lastHealthyAtEpochMs = healthyAt,
                    nodeVersion = nodeVersion,
                    summary = if (workload == StmCoreWorkload.SILLY_TAVERN) {
                        "SillyTavern /version health check passed"
                    } else {
                        "Feather Engine health check passed"
                    },
                    error = null,
                )
            }
        }
    }

    override fun onStopped(sessionId: String, terminationUsed: Boolean) {
        mainHandler.post {
            if (!isCurrentSession(sessionId)) return@post
            if (state.runState != StmCoreRunState.DRAINING) return@post
            cancelWatchdog()
            val running = state.runningSlot
            if (state.workload == StmCoreWorkload.SILLY_TAVERN && running != null) {
                verifyStoppedSillyTavernSlot(sessionId, running, terminationUsed)
            } else {
                publishStopped(terminationUsed)
            }
        }
    }

    override fun onFailure(sessionId: String, detail: String) {
        mainHandler.post {
            if (!isCurrentSession(sessionId) || processTerminationScheduled) return@post
            crashAndTerminate(
                code = "FEATHER_ENGINE_FAILURE",
                summary = "Feather Engine failed",
                detail = detail,
            )
        }
    }

    private fun verifyStoppedSillyTavernSlot(
        sessionId: String,
        running: StmCoreActiveSlot,
        terminationUsed: Boolean,
    ) {
        val expected = state.slots.singleOrNull { slot ->
            slot.id == running.slotId && slot.revision == running.slotRevision
        }
        if (expected == null) {
            publishCrash(
                code = "RUNNING_SLOT_SNAPSHOT_MISSING",
                summary = "The stopped SillyTavern slot lost its snapshot evidence",
                detail = "No slot matched ${running.slotId} revision ${running.slotRevision}",
            )
            return
        }
        publish {
            copy(summary = "SillyTavern stopped; verifying its immutable slot")
        }
        runtimeVerificationExecutor.execute {
            val verification = runCatching {
                installerCoordinator.verifyCommittedSlot(running.slotId)
            }.getOrElse { error ->
                StmSlotVerificationResult.Invalid(error.safeMessage())
            }
            mainHandler.post {
                if (serviceDestroyed ||
                    state.sessionId != sessionId ||
                    state.runState != StmCoreRunState.DRAINING ||
                    state.runningSlot != running
                ) {
                    return@post
                }
                val valid = verification as? StmSlotVerificationResult.Valid
                val matchesSnapshot = valid?.slot?.let { committed ->
                    committed.metadata.slotRevision == running.slotRevision &&
                        committed.metadata.manifestSha256 == expected.manifestSha256
                } == true
                if (!matchesSnapshot) {
                    val detail = when (verification) {
                        is StmSlotVerificationResult.Invalid -> verification.detail
                        StmSlotVerificationResult.Missing -> "The committed slot is missing"
                        is StmSlotVerificationResult.Valid ->
                            "The committed slot identity no longer matches the frozen running lease"
                    }
                    publishCrash(
                        code = "RUNNING_SLOT_IMMUTABILITY_FAILED",
                        summary = "SillyTavern changed its immutable program slot",
                        detail = detail,
                    )
                    return@post
                }
                publishStopped(terminationUsed)
            }
        }
    }

    private fun publishStopped(terminationUsed: Boolean) {
        publish {
            copy(
                operationId = null,
                sessionId = null,
                runState = StmCoreRunState.STOPPED,
                runningSlot = null,
                localBaseUrl = null,
                port = null,
                summary = if (terminationUsed) {
                    "Feather Engine stopped after terminateExecution escalation"
                } else if (workload == StmCoreWorkload.SILLY_TAVERN) {
                    "SillyTavern stopped cleanly; its immutable slot was verified"
                } else {
                    "Feather Engine session stopped cleanly"
                },
                error = null,
            )
        }
    }

    private fun startCore(operationId: String) {
        if (processTerminationScheduled) return
        if (!installerRecoveryComplete) {
            publish { copy(summary = "Start ignored while Core recovery is pending") }
            return
        }
        if (!state.canStart) {
            publish { copy(summary = "Start ignored while Core is ${runState.name.lowercase()}") }
            return
        }
        if (state.jobs.any { it.state in ACTIVE_JOB_STATES }) {
            publish { copy(summary = "Start ignored while Core maintenance is active") }
            return
        }
        cancelWatchdog()
        val sessionId = UUID.randomUUID().toString()
        val active = state.activeSlot
        val activeSlot = active?.let { pointer ->
            state.slots.singleOrNull { slot ->
                slot.id == pointer.slotId &&
                    slot.revision == pointer.slotRevision &&
                    slot.state == StmCoreSlotState.READY
            }
        }
        val runsSillyTavern =
            active != null && activeSlot?.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
        publish(afterDurableCommit = { committed ->
            if (state.sessionId != sessionId ||
                state.runState != StmCoreRunState.STARTING ||
                committed.sessionId != sessionId
            ) {
                return@publish
            }
            val startTimeout = if (runsSillyTavern) {
                SILLY_TAVERN_START_TIMEOUT_MILLIS
            } else {
                START_TIMEOUT_MILLIS
            }
            scheduleWatchdog(startTimeout) {
                crashAndTerminate(
                    code = "START_TIMEOUT",
                    summary = "STM Core did not become healthy in time",
                    detail = if (runsSillyTavern) {
                        "SillyTavern /version did not pass within $startTimeout ms"
                    } else {
                        "Synthetic Node health did not pass within $startTimeout ms"
                    },
                )
            }
            val sessionDirectory = File(
                StmCorePaths.sessionsRoot(this),
                sessionId,
            ).absoluteFile
            try {
                if (runsSillyTavern) {
                    val selected = requireNotNull(activeSlot)
                    val artifact = requireNotNull(selected.artifact)
                    val version = requireNotNull(artifact.stVersion) {
                        "The READY SillyTavern slot has no version evidence"
                    }
                    val archiveRoot = requireNotNull(artifact.archiveRoot) {
                        "The READY SillyTavern slot has no archive-root evidence"
                    }
                    val slotRoot = File(
                        StmCorePaths.slotsRoot(this),
                        selected.id,
                    ).absoluteFile
                    val prepared = StmSillyTavernLaunchFactory.prepare(
                        slotRoot = slotRoot,
                        archiveRoot = archiveRoot,
                        dataRoot = StmCorePaths.dataRoot(this),
                        sessionDirectory = sessionDirectory,
                        logsRoot = StmCorePaths.logsRoot(this),
                        expectedVersion = version,
                    )
                    engine.start(sessionId, sessionDirectory, prepared.launchSpec)
                } else {
                    engine.start(sessionId, sessionDirectory)
                }
            } catch (error: Throwable) {
                crashAndTerminate(
                    code = "SESSION_CREATE_FAILED",
                    summary = "STM Core could not create a Feather Engine session",
                    detail = error.safeMessage(),
                )
            }
        }) {
            copy(
                operationId = operationId,
                sessionId = sessionId,
                runState = StmCoreRunState.STARTING,
                workload = if (runsSillyTavern) {
                    StmCoreWorkload.SILLY_TAVERN
                } else {
                    StmCoreWorkload.DIAGNOSTIC
                },
                runningSlot = active.takeIf { runsSillyTavern },
                localBaseUrl = null,
                port = null,
                summary = if (runsSillyTavern) {
                    "Starting SillyTavern from immutable slot ${activeSlot.id}"
                } else {
                    "Creating a one-shot Feather Engine session"
                },
                error = null,
                nodeVersion = null,
            )
        }
    }

    private fun stopCore(operationId: String) {
        if (processTerminationScheduled) return
        when (state.runState) {
            StmCoreRunState.STARTING,
            StmCoreRunState.RUNNING,
            -> {
                publish(afterDurableCommit = { committed ->
                    if (state.runState != StmCoreRunState.DRAINING ||
                        committed.operationId != operationId
                    ) {
                        return@publish
                    }
                    scheduleWatchdog(
                        GRACEFUL_STOP_TIMEOUT_MILLIS,
                        ::terminateAfterGracefulTimeout,
                    )
                    if (!engine.requestGracefulStop()) {
                        crashAndTerminate(
                            code = "SESSION_MISSING",
                            summary = "STM Core lost its active Feather Engine session",
                            detail = "The state was active but the Engine had no session to stop",
                        )
                    }
                }) {
                    copy(
                        operationId = operationId,
                        runState = StmCoreRunState.DRAINING,
                        summary = if (workload == StmCoreWorkload.SILLY_TAVERN) {
                            "Draining the SillyTavern server"
                        } else {
                            "Draining the synthetic Node server"
                        },
                        error = null,
                    )
                }
            }

            StmCoreRunState.STOPPED,
            StmCoreRunState.CRASHED,
            StmCoreRunState.DRAINING,
            -> Unit
        }
    }

    private fun terminateAfterGracefulTimeout() {
        if (state.runState != StmCoreRunState.DRAINING || processTerminationScheduled) return
        val result = engine.terminateExecutionFromWatchdog()
        if (result.isFailure) {
            crashAndKill(
                code = "TERMINATE_EXECUTION_FAILED",
                summary = "Feather Engine did not accept terminateExecution",
                detail = result.exceptionOrNull()?.safeMessage().orEmpty(),
            )
            return
        }
        publish {
            copy(summary = "Graceful shutdown timed out; terminateExecution was requested")
        }
        scheduleWatchdog(TERMINATE_TIMEOUT_MILLIS) {
            crashAndKill(
                code = "TERMINATE_TIMEOUT",
                summary = "Feather Engine did not stop after terminateExecution",
                detail = "The Core private process will be terminated",
            )
        }
    }

    private fun crashAndTerminate(code: String, summary: String, detail: String) {
        if (processTerminationScheduled) return
        cancelWatchdog()
        publishCrash(code, summary, detail)
        engine.terminateExecutionFromWatchdog()
        scheduleWatchdog(TERMINATE_TIMEOUT_MILLIS) {
            scheduleProcessTermination()
        }
    }

    private fun crashAndKill(code: String, summary: String, detail: String) {
        if (processTerminationScheduled) return
        cancelWatchdog()
        publishCrash(code, summary, detail)
        scheduleProcessTermination()
    }

    private fun publishCrash(code: String, summary: String, detail: String) {
        if (state.runState == StmCoreRunState.CRASHED && state.error?.code == code) return
        publish {
            copy(
                runState = StmCoreRunState.CRASHED,
                localBaseUrl = null,
                port = null,
                summary = summary,
                error = StmCoreError(
                    domain = "feather_engine",
                    code = code,
                    summary = summary,
                    diagnosticDetail = detail,
                ),
            )
        }
    }

    private fun publish(
        afterDurableCommit: ((StmCoreState) -> Unit)? = null,
        transform: StmCoreState.() -> StmCoreState,
    ) {
        if (!initializationComplete || checkpointPersistenceFailed.get() || serviceDestroyed) return
        val nextRevision = nextCoreRevisionOrNull(state.revision)
        if (nextRevision == null) {
            Log.e(TAG, "core_revision_exhausted; restarting into a new process epoch")
            scheduleProcessTermination()
            return
        }
        val next = state.transform().copy(
            revision = nextRevision,
            updatedAtEpochMs = System.currentTimeMillis(),
            processIdentity = processIdentity,
            processId = Process.myPid(),
        ).requireValidCoreSnapshot()
        state = next
        checkpointExecutor.execute {
            if (checkpointPersistenceFailed.get()) return@execute
            try {
                checkpointStore.write(next)
            } catch (error: Throwable) {
                if (checkpointPersistenceFailed.compareAndSet(false, true)) {
                    mainHandler.post { handleCheckpointPersistenceFailure(next.revision, error) }
                }
                return@execute
            }
            mainHandler.post {
                if (commitPublishedState(next)) afterDurableCommit?.invoke(next)
            }
        }
    }

    private fun commitPublishedState(next: StmCoreState): Boolean {
        if (checkpointPersistenceFailed.get() || serviceDestroyed) return false
        if (next.revision <= committedState.revision) return false
        committedState = next
        logState(next)
        broadcastCommittedState()
        return true
    }

    private fun broadcastCommittedState() {
        if (!initializationComplete || checkpointPersistenceFailed.get()) return
        val deadClients = mutableListOf<IBinder>()
        clients.forEach { (binder, client) ->
            try {
                client.send(StmCoreProtocol.stateMessage(committedState))
            } catch (_: RemoteException) {
                deadClients += binder
            }
        }
        deadClients.forEach(clients::remove)
    }

    private fun handleCheckpointPersistenceFailure(revision: Long, error: Throwable) {
        Log.e(
            TAG,
            "checkpoint_persistence_failed revision=$revision; terminating private Core process",
            error,
        )
        if (!processTerminationScheduled) scheduleProcessTermination()
    }

    private fun registerClient(client: Messenger?) {
        if (client == null) return
        clients[client.binder] = client
        if (!initializationComplete || checkpointPersistenceFailed.get()) return
        try {
            client.send(StmCoreProtocol.stateMessage(committedState))
        } catch (_: RemoteException) {
            clients.remove(client.binder)
        }
    }

    private fun unregisterClient(client: Messenger?) {
        client ?: return
        clients.remove(client.binder)
    }

    private fun handleIncomingMessage(message: Message): Boolean {
        if (message.sendingUid != applicationInfo.uid) {
            if (message.what == StmCoreProtocol.MESSAGE_IMPORT_ARTIFACT ||
                message.what == StmCoreProtocol.MESSAGE_INSTALL_IMPORTED_ARTIFACT
            ) {
                StmCoreProtocol.closeImportDescriptor(message)
            }
            Log.w(TAG, "Rejected Core IPC from UID ${message.sendingUid}")
            return true
        }
        if (!initializationComplete &&
            message.what != StmCoreProtocol.MESSAGE_REGISTER_CLIENT &&
            message.what != StmCoreProtocol.MESSAGE_UNREGISTER_CLIENT
        ) {
            if (message.what == StmCoreProtocol.MESSAGE_IMPORT_ARTIFACT) {
                StmCoreProtocol.closeImportDescriptor(message)
            }
            Log.w(TAG, "Rejected Core command while durable initialization is pending")
            return true
        }
        return when (message.what) {
            StmCoreProtocol.MESSAGE_REGISTER_CLIENT -> {
                registerClient(message.replyTo)
                true
            }

            StmCoreProtocol.MESSAGE_UNREGISTER_CLIENT -> {
                unregisterClient(message.replyTo)
                true
            }

            StmCoreProtocol.MESSAGE_START -> {
                StmCoreProtocol.operationIdFrom(message)?.let(::startCore)
                true
            }

            StmCoreProtocol.MESSAGE_STOP -> {
                StmCoreProtocol.operationIdFrom(message)?.let(::stopCore)
                true
            }

            StmCoreProtocol.MESSAGE_INSTALL_CACHED_ARTIFACT -> {
                handleInstallMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_CANCEL_JOB -> {
                handleCancelMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_ACTIVATE_SLOT -> {
                handleActivateMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_ROLLBACK_SLOT -> {
                handleRollbackMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_REMOVE_SLOT -> {
                handleRemoveMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_IMPORT_ARTIFACT -> {
                handleImportMessage(message, install = false)
                true
            }

            StmCoreProtocol.MESSAGE_INSTALL_IMPORTED_ARTIFACT -> {
                handleImportMessage(message, install = true)
                true
            }

            else -> false
        }
    }

    private fun handleInstallMessage(message: Message) {
        val operationId = StmCoreProtocol.operationIdFrom(message) ?: return
        val request = runCatching { StmCoreProtocol.installRequestFrom(message) }.getOrNull()
        if (request == null) {
            publishMaintenanceRejection(
                operationId,
                StmCoreJobType.INSTALL,
                StmCoreProtocol.targetIdFrom(message) ?: REQUEST_TARGET,
                "INVALID_INSTALL_REQUEST",
                "The install request did not pass the Core IPC schema",
            )
            return
        }
        if (!maintenanceAllowed(operationId, StmCoreJobType.INSTALL, request.slotId)) return
        val nextSlotRevision = nextSlotRevision(operationId, request.slotId) ?: return
        handleSubmission(
            operationId = operationId,
            type = StmCoreJobType.INSTALL,
            targetId = request.slotId,
            submission = installerCoordinator.installCachedArtifact(
                operationId = request.operationId,
                slotId = request.slotId,
                slotRevision = nextSlotRevision,
                cacheFileName = request.cacheFileName,
                requestedArtifact = request.artifact,
            ),
        )
    }

    private fun handleImportMessage(message: Message, install: Boolean) {
        val operationId = StmCoreProtocol.operationIdFrom(message)
        val request = runCatching { StmCoreProtocol.importRequestFrom(message) }.getOrNull()
        val jobType = if (install) StmCoreJobType.INSTALL else StmCoreJobType.VERIFY
        if (request == null) {
            if (operationId == null) return
            publishMaintenanceRejection(
                operationId,
                jobType,
                StmCoreProtocol.targetIdFrom(message) ?: REQUEST_TARGET,
                if (install) "INVALID_INSTALL_REQUEST" else "INVALID_IMPORT_REQUEST",
                "The artifact descriptor request did not pass the Core IPC schema",
            )
            return
        }
        val validatedOperationId = request.operationId
        if (!maintenanceAllowed(validatedOperationId, jobType, request.slotId)) {
            request.sourceDescriptor.close()
            return
        }
        val descriptorProblem = validateImportDescriptor(request)
        if (descriptorProblem != null) {
            request.sourceDescriptor.close()
            publishMaintenanceRejection(
                validatedOperationId,
                jobType,
                request.slotId,
                "INVALID_IMPORT_DESCRIPTOR",
                descriptorProblem,
            )
            return
        }
        if (request.artifact.kind != StmCoreArtifactKind.SILLY_TAVERN_SOURCE) {
            request.sourceDescriptor.close()
            publishMaintenanceRejection(
                validatedOperationId,
                jobType,
                request.slotId,
                "IMPORT_KIND_REJECTED",
                "Production descriptor imports accept only exact SillyTavern source artifacts",
            )
            return
        }
        val source = ParcelFileDescriptor.AutoCloseInputStream(request.sourceDescriptor)
        val slotRevision = if (install) {
            nextSlotRevision(validatedOperationId, request.slotId) ?: run {
                source.close()
                return
            }
        } else {
            1L
        }
        handleSubmission(
            operationId = validatedOperationId,
            type = jobType,
            targetId = request.slotId,
            submission = if (install) {
                installerCoordinator.installImportedArtifact(
                    operationId = validatedOperationId,
                    slotId = request.slotId,
                    slotRevision = slotRevision,
                    source = source,
                    requestedArtifact = request.artifact,
                )
            } else {
                installerCoordinator.verifyImportedArtifact(
                    operationId = validatedOperationId,
                    targetId = request.slotId,
                    source = source,
                    requestedArtifact = request.artifact,
                )
            },
        )
    }

    private fun validateImportDescriptor(request: StmCoreImportRequest): String? = try {
        val stat = Os.fstat(request.sourceDescriptor.fileDescriptor)
        when {
            !OsConstants.S_ISREG(stat.st_mode) -> "Artifact import descriptor is not a regular file"
            stat.st_size <= 0 -> "Artifact import descriptor is empty"
            stat.st_size != request.artifact.archiveLength ->
                "Artifact import descriptor length does not match the recorded identity"

            else -> null
        }
    } catch (error: Exception) {
        "Artifact import descriptor could not be inspected: ${error.safeMessage()}"
    }

    private fun nextSlotRevision(operationId: String, slotId: String): Long? {
        val highestSlotRevision = state.slots.maxOfOrNull(StmCoreSlot::revision) ?: 0
        if (highestSlotRevision != Long.MAX_VALUE) return highestSlotRevision + 1
        publishMaintenanceRejection(
            operationId,
            StmCoreJobType.INSTALL,
            slotId,
            "SLOT_REVISION_EXHAUSTED",
            "The Core slot revision counter cannot advance",
        )
        return null
    }

    private fun handleCancelMessage(message: Message) {
        val targetOperationId = StmCoreProtocol.targetIdFrom(message) ?: return
        val current = state.jobs.singleOrNull { it.operationId == targetOperationId } ?: return
        if (current.state !in ACTIVE_JOB_STATES || !installerCoordinator.cancel(targetOperationId)) {
            return
        }
        publish {
            copy(
                jobs = jobs.upsertJob(
                    current.copy(
                        state = StmCoreJobState.CANCELLING,
                        updatedAtEpochMs = maxOf(System.currentTimeMillis(), current.updatedAtEpochMs),
                    ),
                ),
                summary = "Cancelling Core maintenance operation ${current.operationId}",
            )
        }
    }

    private fun handleActivateMessage(message: Message) {
        val operationId = StmCoreProtocol.operationIdFrom(message) ?: return
        val targetId = StmCoreProtocol.targetIdFrom(message) ?: REQUEST_TARGET
        if (!maintenanceAllowed(operationId, StmCoreJobType.ACTIVATE, targetId)) return
        val target = state.slots.singleOrNull { it.id == targetId }
        if (target == null) {
            publishMaintenanceRejection(
                operationId,
                StmCoreJobType.ACTIVATE,
                targetId,
                "SLOT_MISSING",
                "The requested slot is not present in the Core snapshot",
            )
            return
        }
        handleSubmission(
            operationId,
            StmCoreJobType.ACTIVATE,
            targetId,
            installerCoordinator.activate(operationId, target, state.activeSlot),
        )
    }

    private fun handleRollbackMessage(message: Message) {
        val operationId = StmCoreProtocol.operationIdFrom(message) ?: return
        if (!maintenanceAllowed(operationId, StmCoreJobType.ROLLBACK, ACTIVE_TARGET)) return
        handleSubmission(
            operationId,
            StmCoreJobType.ROLLBACK,
            ACTIVE_TARGET,
            installerCoordinator.rollback(operationId, state.slots, state.activeSlot),
        )
    }

    private fun handleRemoveMessage(message: Message) {
        val operationId = StmCoreProtocol.operationIdFrom(message) ?: return
        val targetId = StmCoreProtocol.targetIdFrom(message) ?: REQUEST_TARGET
        if (!maintenanceAllowed(operationId, StmCoreJobType.REMOVE, targetId)) return
        val target = state.slots.singleOrNull { it.id == targetId }
        if (target == null) {
            publishMaintenanceRejection(
                operationId,
                StmCoreJobType.REMOVE,
                targetId,
                "SLOT_MISSING",
                "The requested slot is not present in the Core snapshot",
            )
            return
        }
        handleSubmission(
            operationId,
            StmCoreJobType.REMOVE,
            targetId,
            installerCoordinator.remove(operationId, target, state.activeSlot, state.runningSlot),
        )
    }

    private fun maintenanceAllowed(
        operationId: String,
        type: StmCoreJobType,
        targetId: String,
    ): Boolean {
        if (!installerRecoveryComplete) {
            publishMaintenanceRejection(
                operationId,
                type,
                targetId,
                "CORE_RECOVERING",
                "Core maintenance is unavailable until installer recovery is durably reconciled",
            )
            return false
        }
        val engineStateAllowsOperation = when (type) {
            StmCoreJobType.INSTALL,
            StmCoreJobType.VERIFY,
            -> state.runState == StmCoreRunState.STOPPED ||
                state.runState == StmCoreRunState.RUNNING

            else -> state.runState == StmCoreRunState.STOPPED
        }
        if (engineStateAllowsOperation) return true
        publishMaintenanceRejection(
            operationId,
            type,
            targetId,
            "CORE_NOT_STOPPED",
            if (type == StmCoreJobType.INSTALL || type == StmCoreJobType.VERIFY) {
                "Install and verification require the Feather Engine to be stable or stopped"
            } else {
                "This Core maintenance operation requires the Feather Engine to be stopped"
            },
        )
        return false
    }

    private fun handleSubmission(
        operationId: String,
        type: StmCoreJobType,
        targetId: String,
        submission: StmInstallerSubmission,
    ) {
        if (submission is StmInstallerSubmission.Rejected) {
            publishMaintenanceRejection(
                operationId,
                type,
                targetId,
                submission.code,
                submission.detail,
            )
        }
    }

    private fun publishMaintenanceRejection(
        operationId: String,
        type: StmCoreJobType,
        targetId: String,
        code: String,
        detail: String,
    ) {
        val now = System.currentTimeMillis()
        val error = StmCoreError(
            domain = "installer",
            code = code,
            summary = detail.take(MAX_ERROR_SUMMARY_LENGTH),
            diagnosticDetail = detail.take(MAX_ERROR_DETAIL_LENGTH),
        )
        publish {
            copy(
                jobs = jobs.upsertJob(
                    StmCoreJob(
                        operationId = operationId,
                        type = type,
                        targetId = targetId,
                        phase = StmCoreJobPhase.COMPLETE,
                        state = StmCoreJobState.FAILED,
                        startedAtEpochMs = now,
                        updatedAtEpochMs = now,
                        error = error,
                    ),
                ),
                summary = "Core maintenance request rejected: $code",
            )
        }
    }

    private fun applyInstallerEvent(event: StmInstallerEvent) {
        logInstallerEvent(event)
        when (event) {
            is StmInstallerEvent.JobChanged -> publish {
                copy(
                    jobs = jobs.upsertJob(event.job),
                    summary = "Core ${event.job.type.name.lowercase()} job is " +
                        event.job.state.name.lowercase(),
                )
            }

            is StmInstallerEvent.RecoveredTerminalJob -> {
                val existing = state.jobs.singleOrNull {
                    it.operationId == event.job.operationId
                }
                if (shouldApplyRecoveredTerminalJob(existing)) {
                    publish {
                        copy(
                            jobs = jobs.upsertJob(event.job),
                            summary = "Recovered Core ${event.job.type.name.lowercase()} job is " +
                                event.job.state.name.lowercase(),
                        )
                    }
                }
            }

            is StmInstallerEvent.SlotChanged -> publish {
                copy(
                    slots = slots.upsertSlot(event.slot),
                    summary = "Slot ${event.slot.id} is ${event.slot.state.name.lowercase()}",
                )
            }

            is StmInstallerEvent.SlotRemoved -> publish {
                copy(
                    slots = slots.filterNot { it.id == event.slotId },
                    summary = "Slot ${event.slotId} was removed",
                )
            }

            is StmInstallerEvent.SlotsReconciled -> publish {
                val reconciled = event.slots.sortedBy(StmCoreSlot::id)
                copy(
                    slots = reconciled,
                    activeSlot = activeSlot?.takeIf { active -> reconciled.containsReference(active) },
                    runningSlot = runningSlot?.takeIf { running -> reconciled.containsReference(running) },
                    summary = "Core slot state reconciled from disk",
                )
            }

            is StmInstallerEvent.ActiveChanged -> {
                val requested = event.active
                if (requested != null && !state.slots.containsReference(requested)) {
                    recordRecoveryEvidence(
                        StmCoreError(
                            "active_slot",
                            "ACTIVE_SLOT_TARGET_INVALID",
                            "Recovered active pointer does not reference a READY slot",
                        ),
                    )
                } else {
                    publish {
                        copy(
                            activeSlot = requested,
                            summary = requested?.let { "Slot ${it.slotId} is active" }
                                ?: "No active slot is selected",
                        )
                    }
                }
            }

            is StmInstallerEvent.RecoveryEvidence -> recordRecoveryEvidence(event.error)

            is StmInstallerEvent.RecoveryComplete -> completeInstallerRecovery(event.successful)

            is StmInstallerEvent.ArtifactVerified -> Unit
        }
    }

    private fun logInstallerEvent(event: StmInstallerEvent) {
        when (event) {
            is StmInstallerEvent.JobChanged -> Log.i(
                TAG,
                "maintenance operation=${event.job.operationId} type=${event.job.type} " +
                    "target=${event.job.targetId} phase=${event.job.phase} state=${event.job.state} " +
                    "error=${event.job.error?.code.orEmpty()}",
            )

            is StmInstallerEvent.RecoveredTerminalJob -> Log.i(
                TAG,
                "recovered maintenance operation=${event.job.operationId} " +
                    "type=${event.job.type} target=${event.job.targetId} " +
                    "phase=${event.job.phase} state=${event.job.state} " +
                    "error=${event.job.error?.code.orEmpty()}",
            )

            is StmInstallerEvent.SlotChanged -> {
                val slot = event.slot
                Log.i(
                    TAG,
                    "slot id=${slot.id} revision=${slot.revision} state=${slot.state} " +
                        "manifest=${slot.manifestSha256.orEmpty()}",
                )
                slot.artifact?.let { artifact ->
                    Log.i(
                        TAG,
                        "artifact_identity slot=${slot.id} repository=${artifact.repository.safeLogValue()} " +
                            "channel=${artifact.channel.safeLogValue()} commit=${artifact.commitSha}",
                    )
                    Log.i(
                        TAG,
                        "artifact_integrity slot=${slot.id} status=${artifact.integrity} " +
                            "length=${artifact.archiveLength} sha256=${artifact.archiveSha256}",
                    )
                    Log.i(
                        TAG,
                        "artifact_trust slot=${slot.id} status=${artifact.trust} " +
                            "catalog=${artifact.catalogVersion?.safeLogValue().orEmpty()}",
                    )
                }
            }

            is StmInstallerEvent.ActiveChanged -> Log.i(
                TAG,
                "active_slot id=${event.active?.slotId.orEmpty()} " +
                    "slot_revision=${event.active?.slotRevision ?: 0} " +
                    "active_revision=${event.active?.activeRevision ?: 0}",
            )

            is StmInstallerEvent.SlotRemoved ->
                Log.i(TAG, "slot_removed id=${event.slotId}")

            is StmInstallerEvent.SlotsReconciled ->
                Log.i(TAG, "slots_reconciled count=${event.slots.size}")

            is StmInstallerEvent.RecoveryEvidence -> Log.w(
                TAG,
                "installer_recovery domain=${event.error.domain.safeLogValue()} " +
                    "code=${event.error.code.safeLogValue()} " +
                    "summary=${event.error.summary.safeLogValue()}",
            )

            is StmInstallerEvent.RecoveryComplete -> Log.i(
                TAG,
                "installer_recovery_complete successful=${event.successful}",
            )

            is StmInstallerEvent.ArtifactVerified -> {
                val artifact = event.artifact
                Log.i(
                    TAG,
                    "artifact_identity target=${event.targetId} " +
                        "repository=${artifact.repository.safeLogValue()} " +
                        "channel=${artifact.channel.safeLogValue()} commit=${artifact.commitSha}",
                )
                Log.i(
                    TAG,
                    "artifact_integrity target=${event.targetId} status=${artifact.integrity} " +
                        "length=${artifact.archiveLength} sha256=${artifact.archiveSha256}",
                )
                Log.i(
                    TAG,
                    "artifact_trust target=${event.targetId} status=${artifact.trust} " +
                        "catalog=${artifact.catalogVersion?.safeLogValue().orEmpty()}",
                )
                Log.i(
                    TAG,
                    "artifact_source_evidence target=${event.targetId} " +
                        "archive_root=${artifact.archiveRoot?.safeLogValue().orEmpty()} " +
                        "st_version=${artifact.stVersion?.safeLogValue().orEmpty()} " +
                        "node=${artifact.nodeRequirement?.safeLogValue().orEmpty()} " +
                        "package_lock_sha256=${artifact.packageLockSha256.orEmpty()} " +
                        "license=${artifact.licenseStatus?.safeLogValue().orEmpty()}",
                )
            }
        }
    }

    private fun String.safeLogValue(): String =
        filterNot(Char::isISOControl).take(MAX_LOG_VALUE_LENGTH)

    private fun recordRecoveryEvidence(error: StmCoreError) {
        recoveryEvidenceCount += 1
    }

    private fun completeInstallerRecovery(successful: Boolean) {
        if (!successful) {
            publish {
                copy(
                    summary = "Core installer recovery is blocked; inspect StmCore logs",
                )
            }
            return
        }
        val now = System.currentTimeMillis()
        publish(afterDurableCommit = {
            installerRecoveryComplete = true
        }) {
            val reconciledJobs = jobs.map { job ->
                if (job.state in ACTIVE_JOB_STATES) {
                    job.copy(
                        phase = StmCoreJobPhase.CLEANING_UP,
                        state = StmCoreJobState.FAILED,
                        updatedAtEpochMs = maxOf(now, job.updatedAtEpochMs),
                        progress = null,
                        error = StmCoreError(
                            domain = "installer",
                            code = "CORE_PROCESS_INTERRUPTED",
                            summary = "The maintenance operation ended before its durable outcome was recorded",
                            diagnosticDetail = "Recovered ${job.operationId} during Core startup",
                        ),
                    )
                } else {
                    job
                }
            }
            copy(
                jobs = reconciledJobs,
                installerRecoveryComplete = true,
                summary = if (recoveryEvidenceCount == 0) {
                    "Core installer recovery completed"
                } else {
                    "Core installer recovery completed with $recoveryEvidenceCount logged notice(s)"
                },
            )
        }
    }

    private fun List<StmCoreSlot>.upsertSlot(slot: StmCoreSlot): List<StmCoreSlot> =
        (filterNot { it.id == slot.id } + slot).sortedBy(StmCoreSlot::id)

    private fun List<StmCoreJob>.upsertJob(job: StmCoreJob): List<StmCoreJob> {
        val updated = filterNot { it.operationId == job.operationId } + job
        val active = updated.filter { it.state in ACTIVE_JOB_STATES }
        val terminal = updated.filterNot { it.state in ACTIVE_JOB_STATES }
            .sortedByDescending(StmCoreJob::updatedAtEpochMs)
            .take(MAX_TERMINAL_JOBS)
        return (active + terminal).sortedBy(StmCoreJob::startedAtEpochMs)
    }

    private fun List<StmCoreSlot>.containsReference(reference: StmCoreActiveSlot): Boolean =
        any {
            it.id == reference.slotId &&
                it.revision == reference.slotRevision &&
                it.state == StmCoreSlotState.READY
        }

    private fun debugInstallerFaultInjector(): StmInstallerCoordinatorFaultInjector =
        StmInstallerCoordinatorFaultInjector { failpoint ->
            if (!BuildConfig.DEBUG) return@StmInstallerCoordinatorFaultInjector
            invokeDebugInstallerFaultBridge(failpoint)
        }

    private fun invokeDebugInstallerFaultBridge(failpoint: StmInstallerCoordinatorFailpoint) {
        try {
            Class.forName(DEBUG_INSTALLER_FAULT_BRIDGE)
                .getMethod("hit", String::class.java)
                .invoke(null, failpoint.name)
        } catch (_: ClassNotFoundException) {
            // The bridge exists only in debug builds used by instrumentation.
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Debug installer fault bridge failed", error)
        }
    }

    private fun recoverState(result: CheckpointReadResult): StmCoreState {
        val now = System.currentTimeMillis()
        val pid = Process.myPid()
        return when (result) {
            CheckpointReadResult.Missing -> StmCoreState(
                revision = 1,
                updatedAtEpochMs = now,
                processIdentity = processIdentity,
                processId = pid,
                runState = StmCoreRunState.STOPPED,
                summary = "STM Core initialized",
            )

            is CheckpointReadResult.Corrupt -> StmCoreState(
                revision = result.revisionHint?.let { hint ->
                    recoveredCoreRevision(hint)
                } ?: 1,
                updatedAtEpochMs = now,
                processIdentity = processIdentity,
                processId = pid,
                runState = StmCoreRunState.CRASHED,
                summary = "STM Core checkpoint is corrupt",
                error = StmCoreError(
                    domain = "checkpoint",
                    code = "CHECKPOINT_CORRUPT",
                    summary = "STM Core could not recover its checkpoint",
                    diagnosticDetail = result.detail,
                ),
            )

            is CheckpointReadResult.Loaded -> {
                val previous = result.state
                val revisionEpochReset = previous.revision == Long.MAX_VALUE
                val interrupted = previous.runState == StmCoreRunState.STARTING ||
                    previous.runState == StmCoreRunState.RUNNING ||
                    previous.runState == StmCoreRunState.DRAINING
                previous.copy(
                    revision = recoveredCoreRevision(previous.revision),
                    operationId = if (interrupted) previous.operationId else null,
                    updatedAtEpochMs = now,
                    processIdentity = processIdentity,
                    processId = pid,
                    installerRecoveryComplete = false,
                    runState = if (interrupted || revisionEpochReset) {
                        StmCoreRunState.CRASHED
                    } else {
                        previous.runState
                    },
                    localBaseUrl = null,
                    port = null,
                    sessionId = if (interrupted) previous.sessionId else null,
                    summary = if (revisionEpochReset) {
                        "STM Core revision counter started a new process epoch"
                    } else if (interrupted) {
                        "STM Core process restarted during an active session"
                    } else {
                        "STM Core checkpoint restored; installer recovery is pending"
                    },
                    error = if (revisionEpochReset) {
                        StmCoreError(
                            domain = "checkpoint",
                            code = "REVISION_EPOCH_RESET",
                            summary = "The prior process exhausted its snapshot revision counter",
                            diagnosticDetail = "Recovered revision ${previous.revision} into a new process identity",
                        )
                    } else if (interrupted) {
                        StmCoreError(
                            domain = "process",
                            code = "CORE_PROCESS_RESTARTED",
                            summary = "The STM Core process ended during an active session",
                            diagnosticDetail = "Recovered from revision ${previous.revision}",
                        )
                    } else {
                        previous.error
                    },
                ).let { recovered ->
                    if (recovered.runState == StmCoreRunState.STOPPED) {
                        recovered.copy(operationId = null, sessionId = null, error = null)
                    } else {
                        recovered
                    }
                }
            }
        }.requireValidCoreSnapshot()
    }

    private fun scheduleWatchdog(delayMillis: Long, action: () -> Unit) {
        cancelWatchdog()
        watchdog = Runnable(action).also { mainHandler.postDelayed(it, delayMillis) }
    }

    private fun cancelWatchdog() {
        watchdog?.let(mainHandler::removeCallbacks)
        watchdog = null
    }

    private fun scheduleProcessTermination() {
        if (processTerminationScheduled) return
        processTerminationScheduled = true
        mainHandler.postDelayed(
            { Process.killProcess(Process.myPid()) },
            PROCESS_EXIT_GRACE_MILLIS,
        )
    }

    private fun isCurrentSession(sessionId: String): Boolean = state.sessionId == sessionId

    private fun logState(snapshot: StmCoreState) {
        val message =
            "revision=${snapshot.revision} state=${snapshot.runState} " +
                "operation=${snapshot.operationId.orEmpty()} summary=${snapshot.summary.orEmpty()}"
        if (snapshot.runState == StmCoreRunState.CRASHED) Log.e(TAG, message) else Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "StmCore"
        const val REQUEST_TARGET = "request"
        const val ACTIVE_TARGET = "active"
        const val RECOVERY_TARGET = "recovery"
        const val MAX_TERMINAL_JOBS = 64
        const val MAX_ERROR_SUMMARY_LENGTH = 200
        const val MAX_ERROR_DETAIL_LENGTH = 500
        const val MAX_LOG_VALUE_LENGTH = 256
        const val DEBUG_INSTALLER_FAULT_BRIDGE =
            "io.github.styx798.sillytavernmanager.stmcore.testing.StmInstallerDebugFaultBridge"
        const val START_TIMEOUT_MILLIS = 15_000L
        const val SILLY_TAVERN_START_TIMEOUT_MILLIS = 30_000L
        const val GRACEFUL_STOP_TIMEOUT_MILLIS = 15_000L
        const val TERMINATE_TIMEOUT_MILLIS = 1_500L
        const val PROCESS_EXIT_GRACE_MILLIS = 200L
        val ACTIVE_JOB_STATES = setOf(
            StmCoreJobState.QUEUED,
            StmCoreJobState.RUNNING,
            StmCoreJobState.CANCELLING,
        )
    }
}

internal fun shouldApplyRecoveredTerminalJob(existing: StmCoreJob?): Boolean =
    existing == null || existing.state !in setOf(
        StmCoreJobState.SUCCEEDED,
        StmCoreJobState.FAILED,
        StmCoreJobState.CANCELLED,
    )

internal fun nextCoreRevisionOrNull(current: Long): Long? {
    require(current > 0) { "Core revision must be positive" }
    return if (current == Long.MAX_VALUE) null else current + 1L
}

internal fun recoveredCoreRevision(previous: Long): Long =
    nextCoreRevisionOrNull(previous) ?: 1L
