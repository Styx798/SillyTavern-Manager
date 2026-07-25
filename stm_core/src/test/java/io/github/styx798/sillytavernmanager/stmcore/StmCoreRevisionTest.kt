package io.github.styx798.sillytavernmanager.stmcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StmCoreRevisionTest {
    @Test
    fun `normal revision increments without overflow`() {
        assertEquals(2L, nextCoreRevisionOrNull(1L))
        assertEquals(Long.MAX_VALUE, nextCoreRevisionOrNull(Long.MAX_VALUE - 1L))
    }

    @Test
    fun `exhausted live epoch refuses a negative revision`() {
        assertNull(nextCoreRevisionOrNull(Long.MAX_VALUE))
    }

    @Test
    fun `a restarted process resets only an exhausted revision epoch`() {
        assertEquals(2L, recoveredCoreRevision(1L))
        assertEquals(1L, recoveredCoreRevision(Long.MAX_VALUE))
    }

    @Test
    fun `recovered terminal replay replaces only missing or active checkpoint jobs`() {
        assertTrue(shouldApplyRecoveredTerminalJob(null))
        assertTrue(shouldApplyRecoveredTerminalJob(job(StmCoreJobState.RUNNING)))
        assertTrue(shouldApplyRecoveredTerminalJob(job(StmCoreJobState.CANCELLING)))
        assertFalse(shouldApplyRecoveredTerminalJob(job(StmCoreJobState.SUCCEEDED)))
        assertFalse(shouldApplyRecoveredTerminalJob(job(StmCoreJobState.FAILED)))
        assertFalse(shouldApplyRecoveredTerminalJob(job(StmCoreJobState.CANCELLED)))
    }

    private fun job(state: StmCoreJobState) = StmCoreJob(
        operationId = "00000000-0000-4000-8000-000000000001",
        type = StmCoreJobType.INSTALL,
        targetId = "slot-a",
        phase = if (state == StmCoreJobState.SUCCEEDED) {
            StmCoreJobPhase.COMPLETE
        } else {
            StmCoreJobPhase.CLEANING_UP
        },
        state = state,
        startedAtEpochMs = 1,
        updatedAtEpochMs = 2,
        error = if (state == StmCoreJobState.FAILED) {
            StmCoreError("test", "FAILED", "failed")
        } else {
            null
        },
    )
}
