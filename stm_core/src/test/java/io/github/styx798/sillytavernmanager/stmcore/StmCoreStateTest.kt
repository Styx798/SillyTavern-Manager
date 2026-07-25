package io.github.styx798.sillytavernmanager.stmcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class StmCoreStateTest {
    @Test
    fun `stopped diagnostic Core can start but cannot open SillyTavern`() {
        val state = validState(runState = StmCoreRunState.STOPPED)

        assertTrue(state.canStart)
        assertFalse(state.canStop)
        assertFalse(state.canOpenTavern)
    }

    @Test
    fun `stopped Core cannot start before installer recovery completes`() {
        val state = validState(runState = StmCoreRunState.STOPPED).copy(
            installerRecoveryComplete = false,
        )

        assertFalse(state.canStart)
    }

    @Test
    fun `running state requires real session endpoint and health evidence`() {
        val invalid = validState(runState = StmCoreRunState.STOPPED).copy(
            runState = StmCoreRunState.RUNNING,
            sessionId = "session-1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            invalid.requireValidCoreSnapshot()
        }
    }

    @Test
    fun `diagnostic health never unlocks the SillyTavern WebView`() {
        val state = validState(runState = StmCoreRunState.RUNNING).copy(
            sessionId = "session-1",
            localBaseUrl = "http://127.0.0.1:32123",
            port = 32123,
            lastHealthyAtEpochMs = 1_000,
        ).requireValidCoreSnapshot()

        assertTrue(state.isDiagnosticReady)
        assertFalse(state.canOpenTavern)
    }

    @Test
    fun `active pointer may reference only the matching ready slot revision`() {
        val invalid = validState(runState = StmCoreRunState.STOPPED).copy(
            slots = listOf(StmCoreSlot("slot-a", StmCoreSlotState.BROKEN, revision = 7)),
            activeSlot = StmCoreActiveSlot("slot-a", slotRevision = 7, activeRevision = 2),
        )

        assertThrows(IllegalArgumentException::class.java) {
            invalid.requireValidCoreSnapshot()
        }
    }

    @Test
    fun `crashed state requires structured error and exposes no endpoint`() {
        val invalid = validState(runState = StmCoreRunState.CRASHED)

        assertThrows(IllegalArgumentException::class.java) {
            invalid.requireValidCoreSnapshot()
        }
    }

    @Test
    fun `ready slot requires separate integrity trust and manifest evidence`() {
        val invalid = validState(runState = StmCoreRunState.STOPPED).copy(
            slots = listOf(
                StmCoreSlot(
                    id = "slot-a",
                    state = StmCoreSlotState.READY,
                    revision = 1,
                    artifact = artifact().copy(integrity = StmCoreArtifactIntegrity.FAILED),
                    manifestSha256 = "b".repeat(64),
                    manifestFileCount = 1,
                    manifestTotalBytes = 10,
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            invalid.requireValidCoreSnapshot()
        }
    }

    @Test
    fun `maintenance failure remains orthogonal to stopped run state`() {
        val state = validState(runState = StmCoreRunState.STOPPED).copy(
            jobs = listOf(
                StmCoreJob(
                    operationId = UUID.randomUUID().toString(),
                    type = StmCoreJobType.INSTALL,
                    targetId = "slot-a",
                    phase = StmCoreJobPhase.CLEANING_UP,
                    state = StmCoreJobState.FAILED,
                    startedAtEpochMs = 900,
                    updatedAtEpochMs = 1_000,
                    error = StmCoreError("installer", "ZIP_PATH_REJECTED", "Archive rejected"),
                ),
            ),
        ).requireValidCoreSnapshot()

        assertTrue(state.runState == StmCoreRunState.STOPPED)
        assertTrue(state.jobs.single().state == StmCoreJobState.FAILED)
    }

    private fun validState(runState: StmCoreRunState) = StmCoreState(
        revision = 1,
        updatedAtEpochMs = 1_000,
        processIdentity = "process-1",
        processId = 123,
        installerRecoveryComplete = true,
        runState = runState,
    )

    private fun artifact(): StmCoreArtifact {
        val commit = "a".repeat(40)
        return StmCoreArtifact(
            kind = StmCoreArtifactKind.GATE2_SYNTHETIC,
            repository = "https://github.com/example/fixture.git",
            channel = "gate2",
            commitSha = commit,
            downloadUrl = "https://github.com/example/fixture/archive/$commit.zip",
            downloadedAtEpochMs = 1_000,
            archiveLength = 10,
            archiveSha256 = "a".repeat(64),
            integrity = StmCoreArtifactIntegrity.VERIFIED,
            trust = StmCoreArtifactTrust.TRUSTED_CATALOG,
        )
    }
}
