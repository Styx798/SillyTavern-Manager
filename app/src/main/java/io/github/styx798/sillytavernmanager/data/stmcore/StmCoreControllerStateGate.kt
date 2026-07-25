package io.github.styx798.sillytavernmanager.data.stmcore

import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState

/** Accepts revisions only from the current, explicitly connected Core process epoch. */
internal class StmCoreSnapshotEpoch {
    private var lastAcceptedRevision = 0L
    private var lastProcessIdentity: String? = null
    private var awaitingNewProcessIdentity = true

    fun accept(state: StmCoreState): Boolean {
        val processIdentity = state.processIdentity ?: return false
        if (awaitingNewProcessIdentity) {
            if (lastProcessIdentity != null && processIdentity == lastProcessIdentity) return false
            lastProcessIdentity = processIdentity
            lastAcceptedRevision = 0L
            awaitingNewProcessIdentity = false
        } else if (processIdentity != lastProcessIdentity) {
            return false
        }
        if (state.revision <= lastAcceptedRevision) return false
        lastAcceptedRevision = state.revision
        return true
    }

    fun disconnect() {
        awaitingNewProcessIdentity = true
    }
}

/** A positive result means the delivery function ran successfully while control was connected. */
internal fun deliverConnectedCoreCommand(
    connectionState: StmCoreConnectionState,
    unavailableReason: String,
    delivery: () -> Boolean,
    onDeliveryFailure: () -> Unit,
): StmCoreCommandResult {
    if (connectionState != StmCoreConnectionState.CONNECTED) {
        return StmCoreCommandResult.Rejected(unavailableReason)
    }
    return if (delivery()) {
        StmCoreCommandResult.Accepted
    } else {
        onDeliveryFailure()
        StmCoreCommandResult.Rejected(unavailableReason)
    }
}
