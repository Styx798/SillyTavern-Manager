package io.github.styx798.sillytavernmanager.core.instances

import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJob
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import org.junit.Assert.assertEquals
import org.junit.Test

class StPendingInstallRecoveryTest {
    @Test
    fun `ready slot wins over an old failed job`() {
        val recovery = classifyPendingInstallRecovery(
            SLOT_ID,
            StmCoreState(
                slots = listOf(readySlot()),
                jobs = listOf(installJob(StmCoreJobState.FAILED)),
            ),
        )

        assertEquals(
            StPendingInstallRecoveryAction.REGISTER_READY_SLOT,
            recovery.action,
        )
    }

    @Test
    fun `running and cancelling installs remain monitored after app process death`() {
        assertEquals(
            StPendingInstallRecoveryAction.MONITOR_INSTALL,
            recoveryFor(StmCoreJobState.RUNNING),
        )
        assertEquals(
            StPendingInstallRecoveryAction.MONITOR_CANCELLATION,
            recoveryFor(StmCoreJobState.CANCELLING),
        )
    }

    @Test
    fun `terminal job state is reported instead of resubmitting the slot`() {
        assertEquals(
            StPendingInstallRecoveryAction.REPORT_INSTALL_FAILURE,
            recoveryFor(StmCoreJobState.FAILED),
        )
        assertEquals(
            StPendingInstallRecoveryAction.REPORT_CANCELLATION,
            recoveryFor(StmCoreJobState.CANCELLED),
        )
    }

    @Test
    fun `missing Core work resumes the exact source download`() {
        assertEquals(
            StPendingInstallRecoveryAction.RESUME_DOWNLOAD,
            classifyPendingInstallRecovery(SLOT_ID, StmCoreState()).action,
        )
    }

    private fun recoveryFor(state: StmCoreJobState): StPendingInstallRecoveryAction =
        classifyPendingInstallRecovery(
            SLOT_ID,
            StmCoreState(jobs = listOf(installJob(state))),
        ).action

    private fun installJob(state: StmCoreJobState) = StmCoreJob(
        operationId = "operation",
        type = StmCoreJobType.INSTALL,
        targetId = SLOT_ID,
        phase = StmCoreJobPhase.PREFLIGHT,
        state = state,
        startedAtEpochMs = 1,
        updatedAtEpochMs = 2,
    )

    private fun readySlot() = StmCoreSlot(
        id = SLOT_ID,
        state = StmCoreSlotState.READY,
        revision = 1,
        artifact = StmCoreArtifact(
            kind = StmCoreArtifactKind.SILLY_TAVERN_SOURCE,
            repository = "https://github.com/SillyTavern/SillyTavern",
            channel = "release",
            commitSha = "a".repeat(40),
            downloadUrl = "https://github.com/SillyTavern/SillyTavern/archive/${"a".repeat(40)}.zip",
            downloadedAtEpochMs = 1,
            archiveLength = 1,
            archiveSha256 = "b".repeat(64),
            integrity = StmCoreArtifactIntegrity.VERIFIED,
            trust = StmCoreArtifactTrust.TRUSTED_CATALOG,
            stVersion = "1.18.0",
        ),
    )

    private companion object {
        const val SLOT_ID = "st-08f9bb2b-60f5-4d45-916c-ece2dc1acd40"
    }
}
