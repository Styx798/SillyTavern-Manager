package io.github.styx798.sillytavernmanager.stmcore.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StmCoreExperimentProtocolTest {
    @Test
    fun `cancellation before runner registration remains observable`() {
        val state = StmCoreExperimentRequestCancellationState()
        val cancellation = state.begin("early-cancel-request")

        assertTrue(state.requestCancellation("early-cancel-request"))
        assertTrue(cancellation.isRequested("early-cancel-request"))
        assertTrue(state.isCancellationRequested("early-cancel-request"))
    }

    @Test
    fun `matching cancellation before delivery fails a passed result closed`() {
        val state = StmCoreExperimentRequestCancellationState()
        val cancellation = state.begin("late-cancel-request")
        assertTrue(state.requestCancellation("late-cancel-request"))

        val result = failClosedExperimentResult(
            "late-cancel-request",
            cancellation,
            mapOf("result" to "passed", "evidence" to "must-not-be-delivered"),
        )

        assertEquals("cancelled", result["result"])
        assertEquals("Experiment was cancelled before result delivery", result["failure"])
        assertFalse(result.containsKey("evidence"))
    }

    @Test
    fun `stale request cannot cancel the active request`() {
        val state = StmCoreExperimentRequestCancellationState()
        state.begin("fresh-request")

        assertFalse(state.requestCancellation("stale-request"))
        assertFalse(state.isCancellationRequested("fresh-request"))
        assertEquals("fresh-request", state.activeRequestId())
    }

    @Test
    fun `clearing an old request preserves a newer request`() {
        val state = StmCoreExperimentRequestCancellationState()
        state.begin("old-request")
        assertTrue(state.requestCancellation("old-request"))
        state.begin("fresh-request")

        assertFalse(state.clear("old-request"))
        assertEquals("fresh-request", state.activeRequestId())
        assertFalse(state.isCancellationRequested("fresh-request"))
        assertTrue(state.requestCancellation("fresh-request"))
    }

    @Test
    fun `timeout budget adds nonnegative phases`() {
        assertEquals(
            54L * 60L * 1000L,
            saturatingTimeoutBudgetMillis(
                30L * 60L * 1000L,
                15L * 60L * 1000L,
                4L * 60L * 1000L,
                5L * 60L * 1000L,
            ),
        )
    }

    @Test
    fun `timeout budget saturates instead of overflowing`() {
        assertEquals(
            Long.MAX_VALUE,
            saturatingTimeoutBudgetMillis(Long.MAX_VALUE - 10L, 11L),
        )
    }

    @Test
    fun `timeout budget rejects a negative phase`() {
        assertThrows(IllegalArgumentException::class.java) {
            saturatingTimeoutBudgetMillis(Long.MAX_VALUE, 1L, -1L)
        }
    }

    @Test
    fun `process lease remains exclusive until the owning teardown releases it`() {
        val lease = StmCoreExperimentProcessLease()

        assertTrue(lease.tryAcquire("npm-request"))
        assertEquals("npm-request", lease.ownerRequestId())
        assertFalse(lease.tryAcquire("arborist-request"))
        assertFalse(lease.release("arborist-request"))
        assertFalse(lease.tryAcquire("arborist-request"))

        assertTrue(lease.release("npm-request"))
        assertNull(lease.ownerRequestId())
        assertTrue(lease.tryAcquire("arborist-request"))
    }

    @Test
    fun `request ownership rejects a result from another request or candidate`() {
        val request = StmCoreExperimentRequest(
            requestId = "arborist-request",
            experiment = StmCoreExperiment.GATE3B_ARBORIST_RUNNABLE,
        )

        assertTrue(
            request.matches(
                "arborist-request",
                StmCoreExperiment.GATE3B_ARBORIST_RUNNABLE,
            ),
        )
        assertFalse(
            request.matches(
                "npm-request",
                StmCoreExperiment.GATE3B_ARBORIST_RUNNABLE,
            ),
        )
        assertFalse(
            request.matches(
                "arborist-request",
                StmCoreExperiment.GATE3B_NPM_CLI_RUNNABLE,
            ),
        )
    }

    @Test
    fun `incomplete result keeps request cancellable until matching teardown`() {
        val state = StmCoreExperimentClientProtocolState()
        val request = request("npm-request")
        state.begin(request)

        val transition = requireNotNull(
            state.acceptResult(
                request.requestId,
                request.experiment,
                teardownComplete = false,
            ),
        )

        assertTrue(transition.deliverResult)
        assertFalse(transition.teardownComplete)
        assertEquals(request, state.activeRequest())
        assertTrue(state.hasActiveRequest())
        assertFalse(state.acceptTeardown("stale-request"))
        assertEquals(request, state.activeRequest())
        assertTrue(state.acceptTeardown(request.requestId))
        assertFalse(state.hasActiveRequest())
    }

    @Test
    fun `timeout cancellation before incomplete result retains ownership until teardown`() {
        val state = StmCoreExperimentClientProtocolState()
        val request = request("arborist-request")
        state.begin(request)

        assertEquals(request, state.activeRequest())
        val transition = requireNotNull(
            state.acceptResult(
                request.requestId,
                request.experiment,
                teardownComplete = false,
            ),
        )

        assertTrue(transition.deliverResult)
        assertEquals(request, state.activeRequest())
        assertTrue(state.acceptTeardown(request.requestId))
        assertNull(state.activeRequest())
    }

    @Test
    fun `complete result atomically clears ownership and makes timeout cancellation a no-op`() {
        val state = StmCoreExperimentClientProtocolState()
        val request = request("complete-request")
        state.begin(request)

        val transition = requireNotNull(
            state.acceptResult(
                request.requestId,
                request.experiment,
                teardownComplete = true,
            ),
        )

        assertTrue(transition.deliverResult)
        assertTrue(transition.teardownComplete)
        assertNull(state.activeRequest())
        assertFalse(state.hasActiveRequest())
        assertFalse(state.acceptTeardown(request.requestId))
    }

    @Test
    fun `remote disconnect clears ownership without accepting teardown`() {
        val state = StmCoreExperimentClientProtocolState()
        val request = request("disconnect-request")
        state.begin(request)
        requireNotNull(
            state.acceptResult(
                request.requestId,
                request.experiment,
                teardownComplete = false,
            ),
        )

        state.clearForRemoteDisconnect()

        assertNull(state.activeRequest())
        assertFalse(state.hasActiveRequest())
        assertFalse(state.acceptTeardown(request.requestId))
        state.clearForRemoteDisconnect()
        assertFalse(state.hasActiveRequest())
    }

    @Test
    fun `duplicate incomplete result is delivered once and later completion still tears down`() {
        val state = StmCoreExperimentClientProtocolState()
        val request = request("duplicate-request")
        state.begin(request)

        val first = requireNotNull(
            state.acceptResult(
                request.requestId,
                request.experiment,
                teardownComplete = false,
            ),
        )
        val duplicate = requireNotNull(
            state.acceptResult(
                request.requestId,
                request.experiment,
                teardownComplete = false,
            ),
        )
        val completed = requireNotNull(
            state.acceptResult(
                request.requestId,
                request.experiment,
                teardownComplete = true,
            ),
        )

        assertTrue(first.deliverResult)
        assertFalse(duplicate.deliverResult)
        assertFalse(completed.deliverResult)
        assertTrue(completed.teardownComplete)
        assertFalse(state.hasActiveRequest())
    }

    private fun request(requestId: String) = StmCoreExperimentRequest(
        requestId = requestId,
        experiment = StmCoreExperiment.GATE3B_ARBORIST_RUNNABLE,
    )
}
