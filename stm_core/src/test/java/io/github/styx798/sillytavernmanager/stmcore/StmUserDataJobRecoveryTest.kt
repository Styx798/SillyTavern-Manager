package io.github.styx798.sillytavernmanager.stmcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StmUserDataJobRecoveryTest {
    @Test
    fun `active user data job becomes terminal failure after Core process death`() {
        val job = StmCoreJob(
            operationId = "00000000-0000-4000-8000-000000000001",
            type = StmCoreJobType.USER_DATA_IMPORT,
            targetId = "00000000-0000-4000-8000-000000000002",
            phase = StmCoreJobPhase.EXTRACTING,
            state = StmCoreJobState.RUNNING,
            startedAtEpochMs = 100,
            updatedAtEpochMs = 120,
            progress = 0.5,
        )

        val recovered = recoverInterruptedUserDataJobs(listOf(job), 200).single()

        assertEquals(StmCoreJobState.FAILED, recovered.state)
        assertEquals(StmCoreJobPhase.CLEANING_UP, recovered.phase)
        assertEquals("CORE_PROCESS_INTERRUPTED", recovered.error?.code)
        assertEquals(200, recovered.updatedAtEpochMs)
        assertNull(recovered.progress)
    }

    @Test
    fun `installer jobs and terminal user data jobs are left for their own recovery`() {
        val installer = job(
            type = StmCoreJobType.INSTALL,
            state = StmCoreJobState.RUNNING,
            operationId = "00000000-0000-4000-8000-000000000003",
        )
        val completed = job(
            type = StmCoreJobType.USER_DATA_BACKUP,
            state = StmCoreJobState.SUCCEEDED,
            operationId = "00000000-0000-4000-8000-000000000004",
        )

        val recovered = recoverInterruptedUserDataJobs(listOf(installer, completed), 200)

        assertEquals(installer, recovered[0])
        assertEquals(completed, recovered[1])
    }

    private fun job(
        type: StmCoreJobType,
        state: StmCoreJobState,
        operationId: String,
    ) = StmCoreJob(
        operationId = operationId,
        type = type,
        targetId = "00000000-0000-4000-8000-000000000002",
        phase = StmCoreJobPhase.COMPLETE,
        state = state,
        startedAtEpochMs = 100,
        updatedAtEpochMs = 120,
    )
}
