package io.github.styx798.sillytavernmanager.stmcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDeviceLocalNpmSlotPreparer
import io.github.styx798.sillytavernmanager.stmcore.installer.StmGitHubPrebuiltSlotPreparer
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
    private val clientDeaths = linkedMapOf<IBinder, IBinder.DeathRecipient>()
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
    private var shutdownMode: CoreShutdownMode? = null
    private var foregroundActive = false

    override fun onCreate() {
        super.onCreate()
        checkpointStore = StmCoreCheckpointStore(this)
        engine = FeatherEngine(this)
        initializeCoreStateAsync()
    }

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MARK_APP_TASK_OWNED -> Unit
            ACTION_PREPARE_SILLY_TAVERN -> ensureSessionForeground()
            ACTION_PREPARE_CORE_SHUTDOWN -> Unit
            ACTION_RELEASE_SILLY_TAVERN_FOREGROUND -> {
                if (!initializationComplete ||
                    state.runState == StmCoreRunState.STOPPED ||
                    state.runState == StmCoreRunState.CRASHED
                ) {
                    releaseSessionForeground()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        broadcastAppTaskRemoved()
        beginOwnerLossShutdown("The STM task was removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        cancelWatchdog()
        releaseSessionForeground()
        runtimeVerificationExecutor.shutdownNow()
        if (::installerCoordinator.isInitialized) installerCoordinator.close()
        checkpointExecutor.shutdownNow()
        if (::engine.isInitialized) engine.destroy()
        clientDeaths.forEach { (binder, recipient) ->
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        clientDeaths.clear()
        clients.clear()
        super.onDestroy()
    }

    private fun initializeCoreStateAsync() {
        checkpointExecutor.execute {
            try {
                StmCorePaths.initializeCoreLayout(this)
                val recovered = recoverState(checkpointStore.read())
                checkpointStore.write(recovered)
                val localRuntimePreparer = StmDeviceLocalNpmSlotPreparer(this)
                val coordinator = StmInstallerCoordinator(
                    installerCacheRoot = StmCorePaths.installerCacheRoot(this),
                    stagingRoot = StmCorePaths.stagingRoot(this),
                    slotsRoot = StmCorePaths.slotsRoot(this),
                    activeFile = StmCorePaths.activeSlotFile(this),
                    journalRoot = StmCorePaths.installerJournalRoot(this),
                    eventSink = { event -> mainHandler.post { applyInstallerEvent(event) } },
                    runtimeSlotPreparer = StmGitHubPrebuiltSlotPreparer(
                        localPreparer = localRuntimePreparer,
                        runnableAcceptor = localRuntimePreparer,
                    ),
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
            if (state.workload == StmCoreWorkload.SILLY_TAVERN) {
                updateSessionForeground(state.runningSillyTavernVersion())
            }
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
                    waitPrompt = null,
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
                inspectStoppedSillyTavernSlot(sessionId, running, terminationUsed)
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

    private fun inspectStoppedSillyTavernSlot(
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
            copy(summary = "SillyTavern stopped; checking its slot lease metadata")
        }
        runtimeVerificationExecutor.execute {
            val verification = runCatching {
                installerCoordinator.readCommittedSlot(running.slotId)
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
        publish(afterDurableCommit = {
            releaseSessionForeground()
            maybeFinishCoreShutdown()
            if (shutdownMode == null) stopSelf()
        }) {
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
                    "SillyTavern stopped cleanly; its slot lease metadata still matches"
                } else {
                    "Feather Engine session stopped cleanly"
                },
                error = null,
                waitPrompt = null,
            )
        }
    }

    private fun startCore(operationId: String) {
        if (processTerminationScheduled) return
        if (!installerRecoveryComplete) {
            releaseSessionForeground()
            publish { copy(summary = "Start ignored while Core recovery is pending") }
            return
        }
        if (!state.canStart) {
            releaseSessionForeground()
            publish { copy(summary = "Start ignored while Core is ${runState.name.lowercase()}") }
            return
        }
        if (state.jobs.any { it.state in ACTIVE_JOB_STATES }) {
            releaseSessionForeground()
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
        if (runsSillyTavern) {
            ensureSessionForeground(requireNotNull(activeSlot).artifact?.stVersion)
        }
        publish(afterDurableCommit = { committed ->
            if (state.sessionId != sessionId ||
                state.runState != StmCoreRunState.STARTING ||
                committed.sessionId != sessionId
            ) {
                return@publish
            }
            if (runsSillyTavern) {
                scheduleStartReminder(operationId, SILLY_TAVERN_START_TIMEOUT_MILLIS)
            } else {
                scheduleWatchdog(START_TIMEOUT_MILLIS) {
                    crashAndTerminate(
                        code = "START_TIMEOUT",
                        summary = "STM Core diagnostic did not become healthy in time",
                        detail = "Synthetic Node health did not pass within $START_TIMEOUT_MILLIS ms",
                    )
                }
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
                releaseSessionForeground()
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
                waitPrompt = null,
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
                        waitPrompt = null,
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
        releaseSessionForeground()
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

    /**
     * Broadcast-only state for live progress and wait prompts. The next durable transition uses a
     * higher revision, while the checkpoint codec deliberately omits these ephemeral fields.
     */
    private fun publishEphemeral(transform: StmCoreState.() -> StmCoreState) {
        if (!initializationComplete || checkpointPersistenceFailed.get() || serviceDestroyed) return
        val nextRevision = nextCoreRevisionOrNull(state.revision) ?: return
        val next = state.transform().copy(
            revision = nextRevision,
            updatedAtEpochMs = System.currentTimeMillis(),
            processIdentity = processIdentity,
            processId = Process.myPid(),
        ).requireValidCoreSnapshot()
        state = next
        broadcastState(next)
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
        broadcastState(state)
    }

    private fun broadcastState(snapshot: StmCoreState) {
        val deadClients = mutableListOf<IBinder>()
        clients.forEach { (binder, client) ->
            try {
                client.send(StmCoreProtocol.stateMessage(snapshot))
            } catch (_: RemoteException) {
                deadClients += binder
            }
        }
        deadClients.forEach(clients::remove)
        deadClients.forEach(::removeClientDeathRecipient)
        if (deadClients.isNotEmpty() && clients.isEmpty()) {
            beginOwnerLossShutdown("The STM app process disconnected")
        }
    }

    private fun broadcastAppTaskRemoved() {
        val deadClients = mutableListOf<IBinder>()
        clients.forEach { (binder, client) ->
            try {
                client.send(StmCoreProtocol.appTaskRemovedMessage())
            } catch (_: RemoteException) {
                deadClients += binder
            }
        }
        deadClients.forEach(clients::remove)
        deadClients.forEach(::removeClientDeathRecipient)
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
        val binder = client.binder
        val recipient = IBinder.DeathRecipient {
            mainHandler.post {
                clients.remove(binder)
                removeClientDeathRecipient(binder)
                if (clients.isEmpty()) {
                    beginOwnerLossShutdown("The STM app process ended")
                }
            }
        }
        removeClientDeathRecipient(binder)
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: RemoteException) {
            beginOwnerLossShutdown("The STM app process ended before Core registration")
            return
        }
        clientDeaths[binder] = recipient
        clients[binder] = client
        if (!initializationComplete || checkpointPersistenceFailed.get()) return
        try {
            client.send(StmCoreProtocol.stateMessage(state))
        } catch (_: RemoteException) {
            clients.remove(binder)
            removeClientDeathRecipient(binder)
            if (clients.isEmpty()) beginOwnerLossShutdown("The STM app process disconnected")
        }
    }

    private fun unregisterClient(client: Messenger?) {
        client ?: return
        clients.remove(client.binder)
        removeClientDeathRecipient(client.binder)
        if (clients.isEmpty()) {
            beginOwnerLossShutdown("The STM app released its Core connection")
        }
    }

    private fun removeClientDeathRecipient(binder: IBinder) {
        val recipient = clientDeaths.remove(binder) ?: return
        runCatching { binder.unlinkToDeath(recipient, 0) }
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

            StmCoreProtocol.MESSAGE_VERIFY_SLOT -> {
                handleVerifySlotMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_CONTINUE_WAITING -> {
                handleContinueWaitingMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_RESTART_CORE -> {
                handleRestartCoreMessage(message)
                true
            }

            StmCoreProtocol.MESSAGE_CLOSE_CORE -> {
                handleCloseCoreMessage(message)
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
                installMode = request.installMode,
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
                    installMode = request.installMode,
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

    private fun handleContinueWaitingMessage(message: Message) {
        val targetOperationId = StmCoreProtocol.targetIdFrom(message) ?: return
        val prompt = state.waitPrompt ?: return
        if (prompt.operationId != targetOperationId) return
        if (prompt.kind == StmCoreWaitKind.SILLY_TAVERN_START) {
            if (state.operationId != targetOperationId ||
                state.runState != StmCoreRunState.STARTING
            ) {
                return
            }
            publishEphemeral { copy(waitPrompt = null) }
            scheduleStartReminder(targetOperationId, prompt.intervalMillis)
            return
        }
        installerCoordinator.continueWaiting(targetOperationId)
    }

    private fun handleRestartCoreMessage(message: Message) {
        val operationId = StmCoreProtocol.operationIdFrom(message) ?: return
        beginCoreShutdown(operationId, CoreShutdownMode.RESTART)
    }

    private fun handleCloseCoreMessage(message: Message) {
        val operationId = StmCoreProtocol.operationIdFrom(message) ?: return
        beginCoreShutdown(operationId, CoreShutdownMode.CLOSE)
    }

    private fun beginCoreShutdown(operationId: String, mode: CoreShutdownMode) {
        if (shutdownMode != null || processTerminationScheduled) return
        shutdownMode = mode
        state.jobs.firstOrNull { it.state in ACTIVE_JOB_STATES }?.let { active ->
            installerCoordinator.cancel(active.operationId)
        }
        publish(afterDurableCommit = {
            if (state.runState == StmCoreRunState.STOPPED ||
                state.runState == StmCoreRunState.CRASHED
            ) {
                maybeFinishCoreShutdown()
            }
        }) {
            copy(
                waitPrompt = null,
                runtimeTransfer = null,
                summary = if (mode == CoreShutdownMode.RESTART) {
                    "Restarting STM Core; SillyTavern will remain stopped"
                } else {
                    "Closing STM Core"
                },
            )
        }
        when (state.runState) {
            StmCoreRunState.STARTING,
            StmCoreRunState.RUNNING,
            -> stopCore(operationId)

            StmCoreRunState.DRAINING -> Unit
            StmCoreRunState.STOPPED,
            StmCoreRunState.CRASHED,
            -> Unit
        }
    }

    private fun beginOwnerLossShutdown(reason: String) {
        if (!initializationComplete || processTerminationScheduled || shutdownMode != null) return
        val hasMaintenance = ::installerCoordinator.isInitialized &&
            installerCoordinator.hasActiveOperation()
        val hasLiveSession = state.runState == StmCoreRunState.STARTING ||
            state.runState == StmCoreRunState.RUNNING ||
            state.runState == StmCoreRunState.DRAINING
        if (!hasMaintenance && !hasLiveSession) {
            releaseSessionForeground()
            stopSelf()
            scheduleProcessTermination()
            return
        }
        Log.i(TAG, "owner_lost; shutting down Core: $reason")
        beginCoreShutdown(UUID.randomUUID().toString(), CoreShutdownMode.CLOSE)
    }

    private fun maybeFinishCoreShutdown() {
        if (shutdownMode == null ||
            processTerminationScheduled ||
            (::installerCoordinator.isInitialized && installerCoordinator.hasActiveOperation()) ||
            state.runState == StmCoreRunState.STARTING ||
            state.runState == StmCoreRunState.RUNNING ||
            state.runState == StmCoreRunState.DRAINING
        ) {
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
        stopSelf()
        scheduleProcessTermination()
    }

    private fun ensureSessionForeground(version: String? = null) {
        createNotificationChannel()
        val notification = sessionNotification(version)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundActive = true
    }

    private fun updateSessionForeground(version: String?) {
        if (!foregroundActive) {
            ensureSessionForeground(version)
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, sessionNotification(version))
    }

    private fun releaseSessionForeground() {
        if (!foregroundActive) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SillyTavern runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Visible while the user keeps SillyTavern running locally"
                setShowBadge(false)
            },
        )
    }

    private fun sessionNotification(version: String?): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val text = version?.takeIf(String::isNotBlank)?.let {
            "Running SillyTavern $it"
        } ?: "Starting SillyTavern"
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stm_core_notification)
            .setContentTitle("SillyTavern Manager")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun StmCoreState.runningSillyTavernVersion(): String? {
        val running = runningSlot ?: return null
        return slots.singleOrNull {
            it.id == running.slotId && it.revision == running.slotRevision
        }?.artifact?.stVersion
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

    private fun handleVerifySlotMessage(message: Message) {
        val operationId = StmCoreProtocol.operationIdFrom(message) ?: return
        val targetId = StmCoreProtocol.targetIdFrom(message) ?: REQUEST_TARGET
        if (!maintenanceAllowed(operationId, StmCoreJobType.VERIFY, targetId)) return
        if (state.runState != StmCoreRunState.STOPPED) {
            publishMaintenanceRejection(
                operationId,
                StmCoreJobType.VERIFY,
                targetId,
                "CORE_NOT_STOPPED",
                "Full slot verification requires SillyTavern to be stopped",
            )
            return
        }
        val target = state.slots.singleOrNull { it.id == targetId }
        if (target == null) {
            publishMaintenanceRejection(
                operationId,
                StmCoreJobType.VERIFY,
                targetId,
                "SLOT_MISSING",
                "The requested slot is not present in the Core snapshot",
            )
            return
        }
        handleSubmission(
            operationId,
            StmCoreJobType.VERIFY,
            targetId,
            installerCoordinator.verifySlot(
                operationId = operationId,
                target = target,
                markBrokenOnFailure = state.activeSlot?.slotId != targetId,
            ),
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
            is StmInstallerEvent.JobChanged -> publish(
                afterDurableCommit = {
                    if (event.job.state !in ACTIVE_JOB_STATES) maybeFinishCoreShutdown()
                },
            ) {
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

            is StmInstallerEvent.WaitPromptChanged -> publishEphemeral {
                copy(waitPrompt = event.prompt)
            }

            is StmInstallerEvent.RuntimeTransferChanged -> publishEphemeral {
                copy(runtimeTransfer = event.progress)
            }

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

            is StmInstallerEvent.WaitPromptChanged -> Log.i(
                TAG,
                "wait_prompt operation=${event.prompt?.operationId.orEmpty()} " +
                    "kind=${event.prompt?.kind?.name.orEmpty()}",
            )

            is StmInstallerEvent.RuntimeTransferChanged -> {
                val progress = event.progress
                if (progress == null || progress.transferredBytes == progress.totalBytes) {
                    Log.i(
                        TAG,
                        "runtime_transfer operation=${progress?.operationId.orEmpty()} " +
                            "bytes=${progress?.transferredBytes ?: 0}",
                    )
                }
            }

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

    private fun scheduleStartReminder(operationId: String, intervalMillis: Long) {
        cancelWatchdog()
        watchdog = Runnable {
            watchdog = null
            if (state.operationId != operationId ||
                state.runState != StmCoreRunState.STARTING ||
                state.workload != StmCoreWorkload.SILLY_TAVERN
            ) {
                return@Runnable
            }
            publishEphemeral {
                copy(
                    waitPrompt = StmCoreWaitPrompt(
                        operationId = operationId,
                        kind = StmCoreWaitKind.SILLY_TAVERN_START,
                        intervalMillis = intervalMillis,
                        triggeredAtEpochMs = System.currentTimeMillis(),
                        summary = "SillyTavern has not passed its local health check. Plugins, configuration, or a busy device may be delaying startup.",
                    ),
                )
            }
        }.also { mainHandler.postDelayed(it, intervalMillis) }
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

    private enum class CoreShutdownMode {
        RESTART,
        CLOSE,
    }

    internal companion object {
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
        const val SILLY_TAVERN_START_TIMEOUT_MILLIS = 15_000L
        const val GRACEFUL_STOP_TIMEOUT_MILLIS = 15_000L
        const val TERMINATE_TIMEOUT_MILLIS = 3_000L
        const val PROCESS_EXIT_GRACE_MILLIS = 200L
        const val ACTION_PREPARE_SILLY_TAVERN =
            "io.github.styx798.sillytavernmanager.stmcore.action.PREPARE_SILLY_TAVERN"
        const val ACTION_MARK_APP_TASK_OWNED =
            "io.github.styx798.sillytavernmanager.stmcore.action.MARK_APP_TASK_OWNED"
        const val ACTION_PREPARE_CORE_SHUTDOWN =
            "io.github.styx798.sillytavernmanager.stmcore.action.PREPARE_CORE_SHUTDOWN"
        const val ACTION_RELEASE_SILLY_TAVERN_FOREGROUND =
            "io.github.styx798.sillytavernmanager.stmcore.action.RELEASE_SILLY_TAVERN_FOREGROUND"
        const val NOTIFICATION_CHANNEL_ID = "stm_silly_tavern_runtime"
        const val NOTIFICATION_ID = 0x53544d
        val ACTIVE_JOB_STATES = setOf(
            StmCoreJobState.QUEUED,
            StmCoreJobState.RUNNING,
            StmCoreJobState.CANCELLING,
        )

        fun serviceIntent(context: Context, action: String): Intent =
            Intent(context, StmCoreService::class.java).setAction(action)
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
