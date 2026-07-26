package io.github.styx798.sillytavernmanager.data.stmcore

import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmCoreControllerStateGateTest {
    @Test
    fun `new process accepts low revision and rejects late old process after disconnect`() {
        val receiver = StmCoreSnapshotEpoch()

        assertTrue(receiver.accept(snapshot(processIdentity = "process-a", revision = 900)))
        receiver.disconnect()
        assertTrue(receiver.accept(snapshot(processIdentity = "process-b", revision = 1)))
        assertFalse(receiver.accept(snapshot(processIdentity = "process-a", revision = 901)))
        assertTrue(receiver.accept(snapshot(processIdentity = "process-b", revision = 2)))
    }

    @Test
    fun `same process cannot reconnect or replay until a new process identity arrives`() {
        val receiver = StmCoreSnapshotEpoch()

        assertTrue(receiver.accept(snapshot(processIdentity = "process-a", revision = 7)))
        receiver.disconnect()
        assertFalse(receiver.accept(snapshot(processIdentity = "process-a", revision = 8)))
        assertTrue(receiver.accept(snapshot(processIdentity = "process-b", revision = 1)))
        assertFalse(receiver.accept(snapshot(processIdentity = "process-b", revision = 1)))
    }

    @Test
    fun `resumed app task accepts a newer revision from the Core process still cleaning up`() {
        val receiver = StmCoreSnapshotEpoch()

        assertTrue(receiver.accept(snapshot(processIdentity = "process-a", revision = 7)))
        receiver.disconnect()
        receiver.resumeAppTask()
        assertFalse(receiver.accept(snapshot(processIdentity = "process-a", revision = 7)))
        assertTrue(receiver.accept(snapshot(processIdentity = "process-a", revision = 8)))
        assertFalse(receiver.accept(snapshot(processIdentity = "process-a", revision = 8)))
    }

    @Test
    fun `resumed app task also accepts a replacement Core process`() {
        val receiver = StmCoreSnapshotEpoch()

        assertTrue(receiver.accept(snapshot(processIdentity = "process-a", revision = 900)))
        receiver.disconnect()
        receiver.resumeAppTask()
        assertTrue(receiver.accept(snapshot(processIdentity = "process-b", revision = 1)))
        assertFalse(receiver.accept(snapshot(processIdentity = "process-a", revision = 901)))
        assertTrue(receiver.accept(snapshot(processIdentity = "process-b", revision = 2)))
    }

    @Test
    fun `command is rejected without delivery while connecting or disconnected`() {
        for (state in listOf(
            StmCoreConnectionState.CONNECTING,
            StmCoreConnectionState.DISCONNECTED,
        )) {
            var deliveryCalled = false
            val result = deliverConnectedCoreCommand(
                connectionState = state,
                unavailableReason = "unavailable",
                delivery = {
                    deliveryCalled = true
                    true
                },
                onDeliveryFailure = {},
            )

            assertEquals(StmCoreCommandResult.Rejected("unavailable"), result)
            assertFalse(deliveryCalled)
        }
    }

    @Test
    fun `connected command is accepted only when immediate delivery succeeds`() {
        var failureObserved = false
        val failed = deliverConnectedCoreCommand(
            connectionState = StmCoreConnectionState.CONNECTED,
            unavailableReason = "unavailable",
            delivery = { false },
            onDeliveryFailure = { failureObserved = true },
        )
        assertEquals(StmCoreCommandResult.Rejected("unavailable"), failed)
        assertTrue(failureObserved)

        val accepted = deliverConnectedCoreCommand(
            connectionState = StmCoreConnectionState.CONNECTED,
            unavailableReason = "unavailable",
            delivery = { true },
            onDeliveryFailure = { error("must not be called") },
        )
        assertEquals(StmCoreCommandResult.Accepted, accepted)
    }

    private fun snapshot(processIdentity: String, revision: Long): StmCoreState = StmCoreState(
        revision = revision,
        updatedAtEpochMs = 1,
        processIdentity = processIdentity,
        processId = 100,
    )
}
