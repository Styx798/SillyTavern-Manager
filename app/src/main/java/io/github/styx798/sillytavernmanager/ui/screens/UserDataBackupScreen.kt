package io.github.styx798.sillytavernmanager.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.instances.StInstance
import io.github.styx798.sillytavernmanager.core.instances.StInstanceDataMode
import io.github.styx798.sillytavernmanager.core.instances.StInstanceState
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.core.userdata.UserDataBackup
import io.github.styx798.sillytavernmanager.core.userdata.UserDataBackupState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJob
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UserDataBackupScreen(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    instanceState: StInstanceState,
    backupState: UserDataBackupState,
    onCreateBackup: (String) -> Unit,
    onReplaceUserData: (String, android.net.Uri, Boolean) -> Unit,
    onRestoreBackup: (String, String) -> Unit,
    onDeleteBackup: (String, String) -> Unit,
    onExportBackup: (String, String, android.net.Uri) -> Unit,
    onRefresh: () -> Unit,
    onRetryMigration: (String) -> Unit,
    onClearResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingImportInstanceId by remember { mutableStateOf<String?>(null) }
    var selectedImportUri by remember { mutableStateOf<String?>(null) }
    var pendingRestore by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && pendingImportInstanceId != null) {
            selectedImportUri = uri.toString()
        } else {
            pendingImportInstanceId = null
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val key = pendingExport
        pendingExport = null
        if (uri != null && key != null) {
            backupState.backups.singleOrNull { it.key == key }?.let { backup ->
                onExportBackup(backup.instanceId, backup.fileName, uri)
            }
        }
    }
    val activeJob = coreState.jobs.lastOrNull {
        it.type in USER_DATA_JOB_TYPES && it.state in ACTIVE_JOB_STATES
    }
    val latestTerminalJob = coreState.jobs
        .filter { it.type in USER_DATA_JOB_TYPES && it.state !in ACTIVE_JOB_STATES }
        .maxByOrNull { it.updatedAtEpochMs }
    val latestFailedJob = latestTerminalJob?.takeIf { it.state == StmCoreJobState.FAILED }
    val canManage = connectionState == StmCoreConnectionState.CONNECTED &&
        coreState.installerRecoveryComplete &&
        coreState.runState == StmCoreRunState.STOPPED &&
        activeJob == null

    selectedImportUri?.let { encodedUri ->
        val instanceId = pendingImportInstanceId
        if (instanceId != null) {
            AlertDialog(
                onDismissRequest = {
                    selectedImportUri = null
                    pendingImportInstanceId = null
                },
                title = { Text(stringResource(R.string.user_data_import_warning_title)) },
                text = { Text(stringResource(R.string.user_data_import_warning_body)) },
                confirmButton = {
                    Column {
                        TextButton(
                            onClick = {
                                onReplaceUserData(
                                    instanceId,
                                    android.net.Uri.parse(encodedUri),
                                    true,
                                )
                                selectedImportUri = null
                                pendingImportInstanceId = null
                            },
                        ) {
                            Text(stringResource(R.string.user_data_import_backup_then_replace))
                        }
                        TextButton(
                            onClick = {
                                onReplaceUserData(
                                    instanceId,
                                    android.net.Uri.parse(encodedUri),
                                    false,
                                )
                                selectedImportUri = null
                                pendingImportInstanceId = null
                            },
                        ) {
                            Text(stringResource(R.string.user_data_import_replace_without_backup))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            selectedImportUri = null
                            pendingImportInstanceId = null
                        },
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
    pendingRestore?.let { key ->
        backupState.backups.singleOrNull { it.key == key }?.let { backup ->
            AlertDialog(
                onDismissRequest = { pendingRestore = null },
                title = { Text(stringResource(R.string.user_data_restore_title)) },
                text = { Text(stringResource(R.string.user_data_restore_warning)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRestore = null
                            onRestoreBackup(backup.instanceId, backup.fileName)
                        },
                    ) {
                        Text(stringResource(R.string.user_data_restore_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRestore = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
    pendingDelete?.let { key ->
        backupState.backups.singleOrNull { it.key == key }?.let { backup ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.user_data_delete_backup_title)) },
                text = { Text(stringResource(R.string.user_data_delete_backup_warning)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            onDeleteBackup(backup.instanceId, backup.fileName)
                        },
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
    if (backupState.error != null || backupState.exportComplete) {
        AlertDialog(
            onDismissRequest = onClearResult,
            title = {
                Text(
                    stringResource(
                        if (backupState.error == null) {
                            R.string.user_data_export_complete
                        } else {
                            R.string.user_data_operation_failed
                        },
                    ),
                )
            },
            text = backupState.error?.let { error -> { Text(error) } },
            confirmButton = {
                TextButton(onClick = onClearResult) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.user_data_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.user_data_intro),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        activeJob?.let { job ->
            item { UserDataProgressCard(job) }
        }
        if (activeJob == null && latestFailedJob != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.user_data_operation_failed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = latestFailedJob.error?.summary
                                ?: stringResource(R.string.user_data_operation_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        instanceState.instances.forEach { instance ->
            item {
                InstanceBackupCard(
                    instance = instance,
                    backups = backupState.backups.filter { it.instanceId == instance.id },
                    canManage = canManage &&
                        instance.dataMode == StInstanceDataMode.ISOLATED,
                    canRetryMigration = canManage,
                    onCreateBackup = { onCreateBackup(instance.id) },
                    onImport = {
                        pendingImportInstanceId = instance.id
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onRestore = { pendingRestore = it.key },
                    onExport = {
                        pendingExport = it.key
                        exportLauncher.launch(it.fileName)
                    },
                    onDelete = { pendingDelete = it.key },
                    onRetryMigration = { onRetryMigration(instance.id) },
                )
            }
        }
        val knownIds = instanceState.instances.map(StInstance::id).toSet()
        val orphaned = backupState.backups.filter { it.instanceId !in knownIds }
        if (orphaned.isNotEmpty()) {
            item {
                BackupListCard(
                    title = stringResource(R.string.user_data_orphaned_backups),
                    backups = orphaned,
                    canRestore = false,
                    canMutate = canManage,
                    onRestore = {},
                    onExport = {
                        pendingExport = it.key
                        exportLauncher.launch(it.fileName)
                    },
                    onDelete = { pendingDelete = it.key },
                )
            }
        }
        if (instanceState.instances.isEmpty() && orphaned.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.user_data_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedButton(
                onClick = onRefresh,
                enabled = !backupState.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun InstanceBackupCard(
    instance: StInstance,
    backups: List<UserDataBackup>,
    canManage: Boolean,
    canRetryMigration: Boolean,
    onCreateBackup: () -> Unit,
    onImport: () -> Unit,
    onRestore: (UserDataBackup) -> Unit,
    onExport: (UserDataBackup) -> Unit,
    onDelete: (UserDataBackup) -> Unit,
    onRetryMigration: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = instance.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (instance.dataMode == StInstanceDataMode.LEGACY_SHARED_ROOT) {
                Text(
                    text = stringResource(R.string.user_data_migration_required),
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = onRetryMigration,
                    enabled = canRetryMigration,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.user_data_retry_migration))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onCreateBackup, enabled = canManage) {
                        Text(stringResource(R.string.user_data_create_backup))
                    }
                    OutlinedButton(onClick = onImport, enabled = canManage) {
                        Text(stringResource(R.string.user_data_import))
                    }
                }
                if (!canManage) {
                    Text(
                        text = stringResource(R.string.user_data_stop_required),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            if (backups.isEmpty()) {
                Text(
                    text = stringResource(R.string.user_data_no_backups),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BackupRows(
                    backups = backups,
                    canRestore = true,
                    canMutate = canManage,
                    onRestore = onRestore,
                    onExport = onExport,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun BackupListCard(
    title: String,
    backups: List<UserDataBackup>,
    canRestore: Boolean,
    canMutate: Boolean,
    onRestore: (UserDataBackup) -> Unit,
    onExport: (UserDataBackup) -> Unit,
    onDelete: (UserDataBackup) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            BackupRows(backups, canRestore, canMutate, onRestore, onExport, onDelete)
        }
    }
}

@Composable
private fun BackupRows(
    backups: List<UserDataBackup>,
    canRestore: Boolean,
    canMutate: Boolean,
    onRestore: (UserDataBackup) -> Unit,
    onExport: (UserDataBackup) -> Unit,
    onDelete: (UserDataBackup) -> Unit,
) {
    backups.forEachIndexed { index, backup ->
        if (index > 0) HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(backup.fileName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${formatBackupTime(backup.createdAtEpochMs)} · " +
                    formatBackupSize(backup.sizeBytes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canRestore) {
                    TextButton(onClick = { onRestore(backup) }, enabled = canMutate) {
                        Text(stringResource(R.string.user_data_restore))
                    }
                }
                TextButton(onClick = { onExport(backup) }) {
                    Text(stringResource(R.string.user_data_export))
                }
                TextButton(onClick = { onDelete(backup) }, enabled = canMutate) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun UserDataProgressCard(job: StmCoreJob) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(job.type.userDataOperationLabel()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.user_data_operation_in_progress),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val UserDataBackup.key: String
    get() = "$instanceId/$fileName"

private fun formatBackupTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMillis))

private fun formatBackupSize(bytes: Long): String {
    val mib = bytes.toDouble() / (1_024.0 * 1_024.0)
    return if (mib >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MiB", mib)
    } else {
        String.format(Locale.getDefault(), "%.1f KiB", bytes / 1_024.0)
    }
}

private fun StmCoreJobType.userDataOperationLabel(): Int = when (this) {
    StmCoreJobType.USER_DATA_BACKUP -> R.string.user_data_creating_backup
    StmCoreJobType.USER_DATA_IMPORT -> R.string.user_data_importing
    StmCoreJobType.USER_DATA_RESTORE -> R.string.user_data_restoring
    StmCoreJobType.USER_DATA_DELETE_BACKUP -> R.string.user_data_deleting_backup
    StmCoreJobType.USER_DATA_MIGRATE -> R.string.user_data_migrating
    StmCoreJobType.USER_DATA_FINALIZE_MIGRATION -> R.string.user_data_migrating
    else -> R.string.user_data_operation_in_progress
}

private val USER_DATA_JOB_TYPES = setOf(
    StmCoreJobType.USER_DATA_BACKUP,
    StmCoreJobType.USER_DATA_IMPORT,
    StmCoreJobType.USER_DATA_RESTORE,
    StmCoreJobType.USER_DATA_DELETE_BACKUP,
    StmCoreJobType.USER_DATA_MIGRATE,
    StmCoreJobType.USER_DATA_FINALIZE_MIGRATION,
)

private val ACTIVE_JOB_STATES = setOf(
    StmCoreJobState.QUEUED,
    StmCoreJobState.RUNNING,
    StmCoreJobState.CANCELLING,
)
