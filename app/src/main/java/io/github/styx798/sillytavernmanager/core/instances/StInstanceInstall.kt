package io.github.styx798.sillytavernmanager.core.instances

import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJob
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState

enum class StInstanceInstallPhase {
    IDLE,
    DOWNLOADING_SOURCE,
    INSTALLING,
    ACTIVATING,
    CANCELLING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

enum class StInstanceInstallFailure {
    INVALID_NAME,
    DUPLICATE_NAME,
    DOWNLOAD_FAILED,
    CORE_REJECTED,
    INSTALL_FAILED,
    INSTANCE_REGISTRY_FAILED,
    ACTIVATION_FAILED,
}

data class StInstanceInstallState(
    val phase: StInstanceInstallPhase = StInstanceInstallPhase.IDLE,
    val instanceId: String? = null,
    val displayName: String? = null,
    val slotId: String? = null,
    val channel: StDownloadChannel? = null,
    val installMode: StmCoreInstallMode? = null,
    val expectedCommitSha: String? = null,
    val downloadProgress: Float? = null,
    val corePhase: StmCoreJobPhase? = null,
    val coreProgress: Double? = null,
    val failure: StInstanceInstallFailure? = null,
    val failureCode: String? = null,
) {
    val active: Boolean
        get() = phase in setOf(
            StInstanceInstallPhase.DOWNLOADING_SOURCE,
            StInstanceInstallPhase.INSTALLING,
            StInstanceInstallPhase.ACTIVATING,
            StInstanceInstallPhase.CANCELLING,
        )

    val terminal: Boolean
        get() = phase in setOf(
            StInstanceInstallPhase.COMPLETE,
            StInstanceInstallPhase.FAILED,
            StInstanceInstallPhase.CANCELLED,
        )
}

internal enum class StPendingInstallRecoveryAction {
    REGISTER_READY_SLOT,
    MONITOR_INSTALL,
    MONITOR_CANCELLATION,
    REPORT_INSTALL_FAILURE,
    REPORT_CANCELLATION,
    RESUME_DOWNLOAD,
}

internal data class StPendingInstallRecovery(
    val action: StPendingInstallRecoveryAction,
    val slot: StmCoreSlot? = null,
    val job: StmCoreJob? = null,
)

internal fun classifyPendingInstallRecovery(
    slotId: String,
    coreState: StmCoreState,
): StPendingInstallRecovery {
    val readySlot = coreState.slots.singleOrNull {
        it.id == slotId &&
            it.state == StmCoreSlotState.READY &&
            it.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
    }
    val job = coreState.jobs
        .filter { it.type == StmCoreJobType.INSTALL && it.targetId == slotId }
        .maxByOrNull { it.updatedAtEpochMs }
    val action = when {
        readySlot != null -> StPendingInstallRecoveryAction.REGISTER_READY_SLOT
        job?.state == StmCoreJobState.CANCELLING ->
            StPendingInstallRecoveryAction.MONITOR_CANCELLATION
        job?.state == StmCoreJobState.QUEUED || job?.state == StmCoreJobState.RUNNING ->
            StPendingInstallRecoveryAction.MONITOR_INSTALL
        job?.state == StmCoreJobState.FAILED ->
            StPendingInstallRecoveryAction.REPORT_INSTALL_FAILURE
        job?.state == StmCoreJobState.CANCELLED ->
            StPendingInstallRecoveryAction.REPORT_CANCELLATION
        else -> StPendingInstallRecoveryAction.RESUME_DOWNLOAD
    }
    return StPendingInstallRecovery(
        action = action,
        slot = readySlot,
        job = job,
    )
}
