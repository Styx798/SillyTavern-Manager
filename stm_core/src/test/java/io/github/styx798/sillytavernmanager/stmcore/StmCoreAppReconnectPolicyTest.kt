package io.github.styx798.sillytavernmanager.stmcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmCoreAppReconnectPolicyTest {
    @Test
    fun `retains foreground SillyTavern while starting or running`() {
        listOf(
            StmCoreRunState.STARTING,
            StmCoreRunState.RUNNING,
        ).forEach { runState ->
            assertTrue(
                shouldRetainCoreForAppReconnect(
                    state = StmCoreState(
                        runState = runState,
                        workload = StmCoreWorkload.SILLY_TAVERN,
                    ),
                    foregroundActive = true,
                ),
            )
        }
    }

    @Test
    fun `does not retain idle draining crashed or non ST Core`() {
        listOf(
            StmCoreRunState.STOPPED,
            StmCoreRunState.DRAINING,
            StmCoreRunState.CRASHED,
        ).forEach { runState ->
            assertFalse(
                shouldRetainCoreForAppReconnect(
                    state = StmCoreState(
                        runState = runState,
                        workload = StmCoreWorkload.SILLY_TAVERN,
                    ),
                    foregroundActive = true,
                ),
            )
        }
        assertFalse(
            shouldRetainCoreForAppReconnect(
                state = StmCoreState(
                    runState = StmCoreRunState.RUNNING,
                    workload = StmCoreWorkload.DIAGNOSTIC,
                ),
                foregroundActive = true,
            ),
        )
        assertFalse(
            shouldRetainCoreForAppReconnect(
                state = StmCoreState(
                    runState = StmCoreRunState.RUNNING,
                    workload = StmCoreWorkload.SILLY_TAVERN,
                ),
                foregroundActive = false,
            ),
        )
    }
}
