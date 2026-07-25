package io.github.styx798.sillytavernmanager.core.stmcore

import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import kotlinx.coroutines.flow.StateFlow

enum class StmCoreConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

interface StmCoreController {
    val state: StateFlow<StmCoreState>
    val connectionState: StateFlow<StmCoreConnectionState>

    /** Accepted means the Core transition request was delivered, not that it finished. */
    suspend fun start(): StmCoreCommandResult

    /** Completion or failure is represented by a higher-revision [StmCoreState]. */
    suspend fun stop(): StmCoreCommandResult

    suspend fun installCachedArtifact(
        slotId: String,
        cacheFileName: String,
        artifact: StmCoreArtifact,
    ): StmCoreCommandResult

    /** Transfers a read-only external download descriptor; Core re-hashes before any extraction. */
    suspend fun importDownloadedArchive(
        slotId: String,
        archive: DownloadedStArchive,
    ): StmCoreCommandResult

    /** Runs the full Core staging, local build, runnable acceptance, and READY slot commit. */
    suspend fun installDownloadedArchive(
        slotId: String,
        archive: DownloadedStArchive,
    ): StmCoreCommandResult

    suspend fun cancelJob(operationId: String): StmCoreCommandResult

    suspend fun activate(slotId: String): StmCoreCommandResult

    suspend fun rollback(): StmCoreCommandResult

    suspend fun remove(slotId: String): StmCoreCommandResult
}

sealed interface StmCoreCommandResult {
    data object Accepted : StmCoreCommandResult

    data class Rejected(val reason: String) : StmCoreCommandResult
}
