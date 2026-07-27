package io.github.styx798.sillytavernmanager.core.userdata

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

data class UserDataBackup(
    val instanceId: String,
    val fileName: String,
    val createdAtEpochMs: Long,
    val sizeBytes: Long,
)

data class UserDataBackupState(
    val backups: List<UserDataBackup> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val exportComplete: Boolean = false,
)

interface UserDataBackupRepository {
    val state: StateFlow<UserDataBackupState>

    fun refresh()

    suspend fun export(
        instanceId: String,
        fileName: String,
        destination: Uri,
    ): Result<Unit>

    fun clearResult()
}
