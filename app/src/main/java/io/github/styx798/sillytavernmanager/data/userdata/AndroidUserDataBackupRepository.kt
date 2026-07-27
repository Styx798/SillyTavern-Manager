package io.github.styx798.sillytavernmanager.data.userdata

import android.content.Context
import android.net.Uri
import io.github.styx798.sillytavernmanager.core.userdata.UserDataBackup
import io.github.styx798.sillytavernmanager.core.userdata.UserDataBackupRepository
import io.github.styx798.sillytavernmanager.core.userdata.UserDataBackupState
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AndroidUserDataBackupRepository(context: Context) : UserDataBackupRepository {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(UserDataBackupState())

    override val state: StateFlow<UserDataBackupState> = mutableState.asStateFlow()

    override fun refresh() {
        mutableState.update { it.copy(loading = true, error = null, exportComplete = false) }
        mutableState.value = runCatching {
            val root = StmCorePaths.backupsRoot(appContext)
            val backups = root.listFiles()
                .orEmpty()
                .filter(::isRealDirectory)
                .flatMap { instanceDirectory ->
                    instanceDirectory.listFiles()
                        .orEmpty()
                        .filter { it.name.matches(SAFE_BACKUP_NAME) && isRegularNoFollow(it) }
                        .map { file ->
                            UserDataBackup(
                                instanceId = instanceDirectory.name,
                                fileName = file.name,
                                createdAtEpochMs = file.lastModified(),
                                sizeBytes = file.length(),
                            )
                        }
                }
                .sortedByDescending(UserDataBackup::createdAtEpochMs)
            UserDataBackupState(backups = backups)
        }.getOrElse { error ->
            UserDataBackupState(error = error.message ?: "Backup list could not be read")
        }
    }

    override suspend fun export(
        instanceId: String,
        fileName: String,
        destination: Uri,
    ): Result<Unit> = runCatching {
        require(instanceId.matches(UUID_ID)) { "ST instance ID is invalid" }
        require(fileName.matches(SAFE_BACKUP_NAME)) { "Backup file name is invalid" }
        val source = StmCorePaths.instanceBackupsRoot(appContext, instanceId).resolve(fileName)
        require(isRegularNoFollow(source)) { "Backup file is unavailable" }
        val resolver = appContext.contentResolver
        requireNotNull(resolver.openOutputStream(destination, "wt")) {
            "Android did not provide the export destination"
        }.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        Unit
    }.onSuccess {
        mutableState.update { it.copy(exportComplete = true, error = null) }
    }.onFailure { error ->
        mutableState.update {
            it.copy(error = error.message ?: "Backup export failed", exportComplete = false)
        }
    }

    override fun clearResult() {
        mutableState.update { it.copy(error = null, exportComplete = false) }
    }

    private fun isRealDirectory(file: java.io.File): Boolean =
        Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.name.matches(UUID_ID)

    private fun isRegularNoFollow(file: java.io.File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())

    private companion object {
        val UUID_ID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            RegexOption.IGNORE_CASE,
        )
        val SAFE_BACKUP_NAME = Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]{0,119}\\.zip")
    }
}
