package io.github.styx798.sillytavernmanager.core.stmcore

import android.net.Uri
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import kotlinx.coroutines.flow.StateFlow

enum class StmCoreConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    CLOSED,
}

interface StmCoreController {
    val state: StateFlow<StmCoreState>
    val connectionState: StateFlow<StmCoreConnectionState>

    /** Reconnects only after app-task removal; an explicit Core close remains closed. */
    fun resumeAppTask()

    /** Starts the selected SillyTavern slot. Core itself enters standby when the app binds. */
    suspend fun start(instanceId: String? = null): StmCoreCommandResult

    /** Completion or failure is represented by a higher-revision [StmCoreState]. */
    suspend fun stop(): StmCoreCommandResult

    /** Reconnects a Core that the user explicitly closed. */
    suspend fun openCore(): StmCoreCommandResult

    /** Stops ST and maintenance, restarts the private Core process, and leaves ST stopped. */
    suspend fun restartCore(): StmCoreCommandResult

    /** Stops ST and maintenance, then closes the private Core process. */
    suspend fun closeCore(): StmCoreCommandResult

    /** Re-arms the same soft wait interval; this is intentionally unlimited. */
    suspend fun continueWaiting(operationId: String): StmCoreCommandResult

    suspend fun installCachedArtifact(
        slotId: String,
        cacheFileName: String,
        artifact: StmCoreArtifact,
        installMode: StmCoreInstallMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
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
        installMode: StmCoreInstallMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
    ): StmCoreCommandResult

    suspend fun cancelJob(operationId: String): StmCoreCommandResult

    suspend fun activate(slotId: String): StmCoreCommandResult

    suspend fun rollback(): StmCoreCommandResult

    suspend fun remove(slotId: String): StmCoreCommandResult

    /** Explicit slow diagnostic: re-hashes every file in the selected immutable slot. */
    suspend fun verifySlot(slotId: String): StmCoreCommandResult

    suspend fun createUserDataBackup(
        instanceId: String,
        displayName: String,
    ): StmCoreCommandResult

    suspend fun replaceUserData(
        instanceId: String,
        displayName: String,
        source: Uri,
        backupFirst: Boolean,
    ): StmCoreCommandResult

    suspend fun restoreUserDataBackup(
        instanceId: String,
        backupFileName: String,
    ): StmCoreCommandResult

    suspend fun deleteUserDataBackup(
        instanceId: String,
        backupFileName: String,
    ): StmCoreCommandResult

    suspend fun migrateLegacyUserData(instanceId: String): StmCoreCommandResult

    suspend fun finalizeLegacyUserDataMigration(instanceId: String): StmCoreCommandResult
}

sealed interface StmCoreCommandResult {
    data object Accepted : StmCoreCommandResult

    data class Rejected(val reason: String) : StmCoreCommandResult
}
