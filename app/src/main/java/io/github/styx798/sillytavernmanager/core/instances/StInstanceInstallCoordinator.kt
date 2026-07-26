package io.github.styx798.sillytavernmanager.core.instances

import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadRepository
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadState
import io.github.styx798.sillytavernmanager.core.logging.LogLevel
import io.github.styx798.sillytavernmanager.core.logging.LogRepository
import io.github.styx798.sillytavernmanager.core.logging.LogSource
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreController
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSupportedVersions
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StInstanceInstallCoordinator(
    private val scope: CoroutineScope,
    private val downloadRepository: StDownloadRepository,
    private val instanceRepository: StInstanceRepository,
    private val stmCoreController: StmCoreController,
    private val logRepository: LogRepository,
) {
    private val mutableState = MutableStateFlow(StInstanceInstallState())
    private var submittedInstanceId: String? = null
    private var registeredInstanceId: String? = null
    private var activationRequestedInstanceId: String? = null
    private var recoveringPendingInstall = false

    val state = mutableState.asStateFlow()

    init {
        restorePendingInstall()
        scope.launch {
            stmCoreController.state.collect { coreState ->
                recoverPendingInstall(coreState)
                advanceFromCore(coreState)
            }
        }
        scope.launch {
            downloadRepository.state.collect(::advanceFromDownload)
        }
    }

    fun install(
        displayName: String,
        channel: StDownloadChannel = StDownloadChannel.STABLE,
    ) {
        if (mutableState.value.active) return
        val normalizedName = runCatching { requireValidInstanceName(displayName) }
            .getOrElse {
                mutableState.value = StInstanceInstallState(
                    phase = StInstanceInstallPhase.FAILED,
                    displayName = displayName,
                    channel = channel,
                    failure = StInstanceInstallFailure.INVALID_NAME,
                )
                return
            }
        val nameKey = instanceNameCollisionKey(normalizedName)
        if (instanceRepository.state.value.instances.any {
                instanceNameCollisionKey(it.displayName) == nameKey
            }
        ) {
            mutableState.value = StInstanceInstallState(
                phase = StInstanceInstallPhase.FAILED,
                displayName = normalizedName,
                channel = channel,
                failure = StInstanceInstallFailure.DUPLICATE_NAME,
            )
            return
        }
        val instanceId = UUID.randomUUID().toString()
        val installMode = if (channel == StDownloadChannel.PREVIEW) {
            StmCoreInstallMode.LOCAL_NPM_BUILD
        } else {
            StmCoreInstallMode.FAST_SIGNED_RUNTIME
        }
        val expectedCommit = if (channel == StDownloadChannel.STABLE) {
            StmCoreSupportedVersions.SIGNED_STABLE_COMMIT
        } else {
            null
        }
        val pending = StPendingInstanceInstall(
            instanceId = instanceId,
            displayName = normalizedName,
            slotId = "st-$instanceId",
            channel = channel,
            installMode = installMode,
            expectedCommitSha = expectedCommit,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        if (instanceRepository.beginInstall(pending).isFailure) {
            mutableState.value = StInstanceInstallState(
                phase = StInstanceInstallPhase.FAILED,
                instanceId = instanceId,
                displayName = normalizedName,
                slotId = pending.slotId,
                channel = channel,
                installMode = installMode,
                expectedCommitSha = expectedCommit,
                failure = StInstanceInstallFailure.INSTANCE_REGISTRY_FAILED,
            )
            return
        }
        submittedInstanceId = null
        registeredInstanceId = null
        activationRequestedInstanceId = null
        mutableState.value = StInstanceInstallState(
            phase = StInstanceInstallPhase.DOWNLOADING_SOURCE,
            instanceId = instanceId,
            displayName = normalizedName,
            slotId = pending.slotId,
            channel = channel,
            installMode = installMode,
            expectedCommitSha = expectedCommit,
        )
        downloadRepository.clearFailure()
        val existing = matchingArchive(mutableState.value, downloadRepository.state.value)
        if (existing != null) {
            submit(existing)
        } else {
            downloadRepository.start(channel, expectedCommit)
        }
    }

    fun cancel() {
        val install = mutableState.value
        if (!install.active) return
        if (install.phase == StInstanceInstallPhase.DOWNLOADING_SOURCE) {
            downloadRepository.cancel()
            clearPending(install)
            mutableState.value = install.copy(phase = StInstanceInstallPhase.CANCELLED)
            return
        }
        val slotId = install.slotId ?: return
        val activeJob = stmCoreController.state.value.jobs.singleOrNull {
            it.targetId == slotId &&
                it.type == StmCoreJobType.INSTALL &&
                it.state in ACTIVE_CORE_JOB_STATES
        } ?: return
        mutableState.value = install.copy(phase = StInstanceInstallPhase.CANCELLING)
        dispatch { stmCoreController.cancelJob(activeJob.operationId) }
    }

    fun dismiss() {
        if (!mutableState.value.terminal) return
        mutableState.value = StInstanceInstallState()
    }

    private fun advanceFromDownload(download: StDownloadState) {
        val install = mutableState.value
        if (recoveringPendingInstall ||
            install.phase != StInstanceInstallPhase.DOWNLOADING_SOURCE
        ) {
            return
        }
        val channel = install.channel ?: return
        download.failure?.takeIf { it.channel == null || it.channel == channel }?.let {
            fail(StInstanceInstallFailure.DOWNLOAD_FAILED, it.reason.name)
            return
        }
        download.active?.takeIf { it.channel == channel }?.let { active ->
            mutableState.value = install.copy(downloadProgress = active.progress)
            return
        }
        matchingArchive(install, download)?.let(::submit)
    }

    private fun submit(archive: DownloadedStArchive) {
        val install = mutableState.value
        val instanceId = install.instanceId ?: return
        if (submittedInstanceId == instanceId) return
        val slotId = install.slotId ?: return
        val installMode = install.installMode ?: return
        submittedInstanceId = instanceId
        mutableState.value = install.copy(
            phase = StInstanceInstallPhase.INSTALLING,
            downloadProgress = 1f,
        )
        scope.launch {
            when (
                val result = stmCoreController.installDownloadedArchive(
                    slotId,
                    archive,
                    installMode,
                )
            ) {
                StmCoreCommandResult.Accepted -> Unit
                is StmCoreCommandResult.Rejected ->
                    fail(StInstanceInstallFailure.CORE_REJECTED, result.reason)
            }
        }
    }

    private fun advanceFromCore(coreState: StmCoreState) {
        val install = mutableState.value
        if (install.phase !in CORE_DRIVEN_PHASES) return
        val instanceId = install.instanceId ?: return
        val slotId = install.slotId ?: return
        if (install.phase == StInstanceInstallPhase.ACTIVATING) {
            val slot = coreState.slots.singleOrNull { it.id == slotId }
            val active = coreState.activeSlot
            if (slot != null &&
                active?.slotId == slot.id &&
                active.slotRevision == slot.revision
            ) {
                instanceRepository.select(instanceId)
                    .onSuccess {
                        mutableState.value = install.copy(
                            phase = StInstanceInstallPhase.COMPLETE,
                            coreProgress = 1.0,
                        )
                    }
                    .onFailure {
                        fail(
                            StInstanceInstallFailure.INSTANCE_REGISTRY_FAILED,
                            it.message,
                        )
                    }
                return
            }
            coreState.jobs
                .filter { it.type == StmCoreJobType.ACTIVATE && it.targetId == slotId }
                .maxByOrNull { it.updatedAtEpochMs }
                ?.takeIf { it.state == StmCoreJobState.FAILED }
                ?.let {
                    fail(StInstanceInstallFailure.ACTIVATION_FAILED, it.error?.code)
                }
            return
        }
        val job = coreState.jobs
            .filter { it.type == StmCoreJobType.INSTALL && it.targetId == slotId }
            .maxByOrNull { it.updatedAtEpochMs }
        if (job != null) {
            if (install.phase == StInstanceInstallPhase.CANCELLING &&
                job.state == StmCoreJobState.CANCELLED
            ) {
                clearPending(install)
                mutableState.value = install.copy(
                    phase = StInstanceInstallPhase.CANCELLED,
                    corePhase = job.phase,
                    coreProgress = job.progress,
                )
                return
            }
            if (job.state == StmCoreJobState.FAILED) {
                fail(StInstanceInstallFailure.INSTALL_FAILED, job.error?.code)
                return
            }
            mutableState.value = install.copy(
                corePhase = job.phase,
                coreProgress = job.progress,
            )
        }
        val slot = coreState.slots.singleOrNull {
            it.id == slotId &&
                it.state == StmCoreSlotState.READY &&
                it.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
        } ?: return
        if (registeredInstanceId != instanceId) {
            val name = install.displayName ?: return
            val version = slot.artifact?.stVersion ?: return
            val wasFirstInstance = instanceRepository.state.value.instances.isEmpty()
            val added = instanceRepository.completeInstall(
                StInstance(
                    id = instanceId,
                    displayName = name,
                    slotId = slot.id,
                    slotRevision = slot.revision,
                    stVersion = version,
                    createdAtEpochMs = System.currentTimeMillis(),
                    dataMode = StInstanceDataMode.ISOLATED,
                ),
                makeActive = false,
            )
            if (added.isFailure) {
                fail(
                    StInstanceInstallFailure.INSTANCE_REGISTRY_FAILED,
                    added.exceptionOrNull()?.message,
                )
                return
            }
            registeredInstanceId = instanceId
            if (!wasFirstInstance) {
                mutableState.value = install.copy(
                    phase = StInstanceInstallPhase.COMPLETE,
                    corePhase = job?.phase,
                    coreProgress = 1.0,
                )
                return
            }
        }
        if (activationRequestedInstanceId == instanceId) return
        activationRequestedInstanceId = instanceId
        mutableState.value = install.copy(
            phase = StInstanceInstallPhase.ACTIVATING,
            coreProgress = 1.0,
        )
        scope.launch {
            when (val result = stmCoreController.activate(slotId)) {
                StmCoreCommandResult.Accepted -> Unit
                is StmCoreCommandResult.Rejected ->
                    fail(StInstanceInstallFailure.ACTIVATION_FAILED, result.reason)
            }
        }
    }

    private fun restorePendingInstall() {
        val pending = instanceRepository.state.value.pendingInstall ?: return
        recoveringPendingInstall = true
        mutableState.value = StInstanceInstallState(
            phase = StInstanceInstallPhase.DOWNLOADING_SOURCE,
            instanceId = pending.instanceId,
            displayName = pending.displayName,
            slotId = pending.slotId,
            channel = pending.channel,
            installMode = pending.installMode,
            expectedCommitSha = pending.expectedCommitSha,
        )
    }

    private fun recoverPendingInstall(coreState: StmCoreState) {
        if (!recoveringPendingInstall || !coreState.installerRecoveryComplete) return
        val install = mutableState.value
        val instanceId = install.instanceId ?: return
        val slotId = install.slotId ?: return
        val recovery = classifyPendingInstallRecovery(slotId, coreState)
        val job = recovery.job
        recoveringPendingInstall = false
        when (recovery.action) {
            StPendingInstallRecoveryAction.REGISTER_READY_SLOT -> {
                submittedInstanceId = instanceId
                mutableState.value = install.copy(
                    phase = StInstanceInstallPhase.INSTALLING,
                    corePhase = job?.phase,
                    coreProgress = 1.0,
                )
            }

            StPendingInstallRecoveryAction.MONITOR_INSTALL,
            StPendingInstallRecoveryAction.MONITOR_CANCELLATION,
            -> {
                submittedInstanceId = instanceId
                mutableState.value = install.copy(
                    phase = if (
                        recovery.action ==
                        StPendingInstallRecoveryAction.MONITOR_CANCELLATION
                    ) {
                        StInstanceInstallPhase.CANCELLING
                    } else {
                        StInstanceInstallPhase.INSTALLING
                    },
                    corePhase = job?.phase,
                    coreProgress = job?.progress,
                )
            }

            StPendingInstallRecoveryAction.REPORT_INSTALL_FAILURE ->
                fail(StInstanceInstallFailure.INSTALL_FAILED, job?.error?.code)

            StPendingInstallRecoveryAction.REPORT_CANCELLATION -> {
                clearPending(install)
                mutableState.value = install.copy(
                    phase = StInstanceInstallPhase.CANCELLED,
                    corePhase = job?.phase,
                    coreProgress = job?.progress,
                )
            }

            StPendingInstallRecoveryAction.RESUME_DOWNLOAD -> resumeDownload(install)
        }
    }

    private fun resumeDownload(install: StInstanceInstallState) {
        val channel = install.channel ?: return
        val archive = matchingArchive(install, downloadRepository.state.value)
        when {
            archive != null -> submit(archive)
            downloadRepository.state.value.active?.channel == channel ->
                advanceFromDownload(downloadRepository.state.value)
            else -> downloadRepository.start(channel, install.expectedCommitSha)
        }
    }

    private fun matchingArchive(
        install: StInstanceInstallState,
        download: StDownloadState,
    ): DownloadedStArchive? {
        val channel = install.channel ?: return null
        return download.archives
            .filter {
                it.channel == channel &&
                    (install.expectedCommitSha == null ||
                        it.identity.exactCommit == install.expectedCommitSha)
            }
            .maxByOrNull { it.downloadedAtEpochMillis ?: 0L }
    }

    private fun fail(failure: StInstanceInstallFailure, failureCode: String?) {
        clearPending(mutableState.value)
        mutableState.value = mutableState.value.copy(
            phase = StInstanceInstallPhase.FAILED,
            failure = failure,
            failureCode = failureCode?.lineSequence()?.firstOrNull()?.take(120),
        )
    }

    private fun clearPending(install: StInstanceInstallState) {
        install.instanceId?.let(instanceRepository::clearPendingInstall)
    }

    private fun dispatch(command: suspend () -> StmCoreCommandResult) {
        scope.launch {
            val result = command()
            if (result is StmCoreCommandResult.Rejected) {
                logRepository.append(
                    source = LogSource.APP,
                    level = LogLevel.WARNING,
                    message = result.reason,
                )
            }
        }
    }

    private companion object {
        val ACTIVE_CORE_JOB_STATES = setOf(
            StmCoreJobState.QUEUED,
            StmCoreJobState.RUNNING,
            StmCoreJobState.CANCELLING,
        )
        val CORE_DRIVEN_PHASES = setOf(
            StInstanceInstallPhase.INSTALLING,
            StInstanceInstallPhase.ACTIVATING,
            StInstanceInstallPhase.CANCELLING,
        )
    }
}
