package io.github.styx798.sillytavernmanager.ui.screens

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.core.downloads.ActiveStDownload
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveTrust
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadFailure
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadFailureReason
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadPhase
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreError
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJob
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreTransferProgress
import java.util.Date

@Composable
fun VersionsScreen(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    downloadState: StDownloadState,
    onStartDownload: (StDownloadChannel) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: (StDownloadChannel) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    onClearDownloadFailure: () -> Unit,
    onImportDownloadedArchive: (DownloadedStArchive) -> Unit,
    onInstallDownloadedArchive: (DownloadedStArchive, StmCoreInstallMode) -> Unit,
    onActivateSlot: (String) -> Unit,
    onRollback: () -> Unit,
    onRemoveSlot: (String) -> Unit,
    onVerifySlot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDownloadOptions by rememberSaveable { mutableStateOf(false) }
    var pendingRemoveSlotId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRollback by rememberSaveable { mutableStateOf(false) }
    var pendingLocalBuildSlotId by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedInstallRecoveryOperationId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    if (showDownloadOptions) {
        DownloadOptionsDialog(
            archives = downloadState.archives,
            onDismiss = { showDownloadOptions = false },
            onDownload = { channel ->
                showDownloadOptions = false
                onStartDownload(channel)
            },
        )
    }
    pendingRemoveSlotId?.let { slotId ->
        AlertDialog(
            onDismissRequest = { pendingRemoveSlotId = null },
            title = { Text(text = stringResource(R.string.st_slot_remove_confirm_title)) },
            text = {
                Text(text = stringResource(R.string.st_slot_remove_confirm_body, slotId))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveSlotId = null
                        onRemoveSlot(slotId)
                    },
                ) {
                    Text(text = stringResource(R.string.st_slot_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveSlotId = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (confirmRollback) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text(text = stringResource(R.string.st_slot_rollback_confirm_title)) },
            text = { Text(text = stringResource(R.string.st_slot_rollback_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRollback = false
                        onRollback()
                    },
                ) {
                    Text(text = stringResource(R.string.st_slot_rollback))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRollback = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
    pendingLocalBuildSlotId?.let { slotId ->
        val archive = downloadState.archives.singleOrNull {
            it.coreSlotIdOrNull() == slotId
        }
        if (archive != null) {
            AlertDialog(
                onDismissRequest = { pendingLocalBuildSlotId = null },
                title = {
                    Text(text = stringResource(R.string.st_install_local_confirm_title))
                },
                text = {
                    Text(
                        text = stringResource(
                            if (archive.channel == StDownloadChannel.PREVIEW) {
                                R.string.st_install_preview_local_confirm_body
                            } else {
                                R.string.st_install_local_confirm_body
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingLocalBuildSlotId = null
                            onInstallDownloadedArchive(
                                archive,
                                StmCoreInstallMode.LOCAL_NPM_BUILD,
                            )
                        },
                    ) {
                        Text(text = stringResource(R.string.st_install_local_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingLocalBuildSlotId = null }) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }

    val hasActiveJob = coreState.jobs.any { job -> job.state in ACTIVE_JOB_STATES }
    val isConnected = connectionState == StmCoreConnectionState.CONNECTED
    val canMaintain = isConnected &&
        coreState.installerRecoveryComplete &&
        coreState.runState == StmCoreRunState.STOPPED &&
        !hasActiveJob
    val canPrepare = isConnected &&
        coreState.installerRecoveryComplete &&
        coreState.runState in setOf(
            StmCoreRunState.STOPPED,
            StmCoreRunState.RUNNING,
        ) && !hasActiveJob
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.st_manager_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.st_manager_intro),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            ManagerSection(title = stringResource(R.string.st_source_title)) {
                Text(
                    text = stringResource(R.string.st_source_official),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                DetailValue(
                    label = stringResource(R.string.st_source_repository),
                    value = OFFICIAL_ST_REPOSITORY,
                    modifier = Modifier.padding(top = 14.dp),
                    monospace = true,
                )
                BranchRow(
                    labelRes = R.string.st_channel_stable,
                    branch = StDownloadChannel.STABLE.branch,
                    modifier = Modifier.padding(top = 16.dp),
                )
                BranchRow(
                    labelRes = R.string.st_channel_preview,
                    branch = StDownloadChannel.PREVIEW.branch,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = { showDownloadOptions = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    enabled = downloadState.active == null,
                ) {
                    Text(text = stringResource(R.string.st_action_download))
                }
                Text(
                    text = stringResource(R.string.st_download_source_note),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        downloadState.failure?.let { failure ->
            item {
                DownloadFailureCard(
                    failure = failure,
                    onDismiss = onClearDownloadFailure,
                )
            }
        }

        downloadState.active?.let { active ->
            item {
                DownloadProgressCard(active = active, onCancel = onCancelDownload)
            }
        }

        coreState.runtimeTransfer?.let { progress ->
            item {
                RuntimeLayerTransferCard(progress)
            }
        }

        item {
            DownloadedArchivesCard(
                archives = downloadState.archives,
                canPrepare = canPrepare,
                onImport = onImportDownloadedArchive,
                onInstall = { archive ->
                    val policy = archive.channel.installPolicy()
                    if (policy.requiresUserConfirmation) {
                        pendingLocalBuildSlotId = archive.coreSlotIdOrNull()
                    } else {
                        onInstallDownloadedArchive(archive, policy.mode)
                    }
                },
                onDelete = onDeleteDownload,
                onDeleteAll = onDeleteAllDownloads,
            )
        }

        val latestInstallJob = coreState.jobs
            .filter { it.type == StmCoreJobType.INSTALL }
            .maxByOrNull(StmCoreJob::updatedAtEpochMs)
        val recoverableInstallJob = latestInstallJob?.takeIf { job ->
            job.state == StmCoreJobState.FAILED &&
                job.error?.code in RECOVERABLE_PREBUILT_ERROR_CODES &&
                coreState.slots.none {
                    it.id == job.targetId && it.state == StmCoreSlotState.READY
                }
        }
        val recoveryArchive = recoverableInstallJob?.let { job ->
            downloadState.archives.singleOrNull { it.coreSlotIdOrNull() == job.targetId }
        }
        if (
            recoverableInstallJob != null &&
            recoveryArchive != null &&
            recoverableInstallJob.operationId != dismissedInstallRecoveryOperationId &&
            !hasActiveJob
        ) {
            item {
                InstallRecoveryCard(
                    job = recoverableInstallJob,
                    canPrepare = canPrepare,
                    onRetry = {
                        onInstallDownloadedArchive(
                            recoveryArchive,
                            StmCoreInstallMode.FAST_SIGNED_RUNTIME,
                        )
                    },
                    onLocalBuild = {
                        pendingLocalBuildSlotId = recoverableInstallJob.targetId
                    },
                    onDismiss = {
                        dismissedInstallRecoveryOperationId =
                            recoverableInstallJob.operationId
                    },
                )
            }
        }

        item {
            CoreSlotsSection(
                coreState = coreState,
                canMaintain = canMaintain,
                onActivateSlot = onActivateSlot,
                onRollback = { confirmRollback = true },
                onRemoveSlot = { slotId -> pendingRemoveSlotId = slotId },
                onVerifySlot = onVerifySlot,
            )
        }
    }
}

@Composable
private fun RuntimeLayerTransferCard(progress: StmCoreTransferProgress) {
    val context = LocalContext.current
    val percent = (progress.fraction * 100.0).toInt().coerceIn(0, 100)
    ManagerSection(title = stringResource(R.string.st_runtime_download_title)) {
        LinearProgressIndicator(
            progress = { progress.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                R.string.st_runtime_download_progress,
                percent,
                Formatter.formatShortFileSize(context, progress.transferredBytes),
                Formatter.formatShortFileSize(context, progress.totalBytes),
            ),
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.st_runtime_download_speed,
                Formatter.formatShortFileSize(context, progress.bytesPerSecond),
            ),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstallRecoveryCard(
    job: StmCoreJob,
    canPrepare: Boolean,
    onRetry: () -> Unit,
    onLocalBuild: () -> Unit,
    onDismiss: () -> Unit,
) {
    val transportUnavailable =
        job.error?.code == PREBUILT_RUNTIME_TRANSPORT_UNAVAILABLE
    ManagerSection(title = stringResource(R.string.st_install_fast_unavailable_title)) {
        Text(
            text = stringResource(
                if (transportUnavailable) {
                    R.string.st_install_transport_unavailable_body
                } else {
                    R.string.st_install_not_available_body
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (transportUnavailable) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                enabled = canPrepare,
            ) {
                Text(text = stringResource(R.string.st_install_retry_fast))
            }
        }
        OutlinedButton(
            onClick = onLocalBuild,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (transportUnavailable) 10.dp else 16.dp),
            enabled = canPrepare,
        ) {
            Text(text = stringResource(R.string.st_install_use_local))
        }
        Text(
            text = stringResource(R.string.st_install_local_warning),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(text = stringResource(R.string.st_install_not_now))
        }
    }
}

@Composable
private fun CoreSlotsSection(
    coreState: StmCoreState,
    canMaintain: Boolean,
    onActivateSlot: (String) -> Unit,
    onRollback: () -> Unit,
    onRemoveSlot: (String) -> Unit,
    onVerifySlot: (String) -> Unit,
) {
    val managedSlots = coreState.slots.userVisibleStSlots()
    val activeSlot = coreState.activeSlot?.takeIf { active ->
        managedSlots.any { slot -> slot.id == active.slotId }
    }
    val runningSlot = coreState.runningSlot?.takeIf { running ->
        managedSlots.any { slot -> slot.id == running.slotId }
    }
    ManagerSection(title = stringResource(R.string.st_core_slots_title)) {
        Text(
            text = stringResource(R.string.st_core_slots_authority),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (managedSlots.isEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                text = stringResource(R.string.st_core_slots_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            managedSlots.sortedBy(StmCoreSlot::id).forEach { slot ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                SlotCard(
                    slot = slot,
                    isActive = activeSlot?.slotId == slot.id,
                    isRunning = runningSlot?.slotId == slot.id,
                    canMaintain = canMaintain,
                    onActivate = { onActivateSlot(slot.id) },
                    onRemove = { onRemoveSlot(slot.id) },
                    onVerify = { onVerifySlot(slot.id) },
                )
            }
            OutlinedButton(
                onClick = onRollback,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                enabled = activeSlot != null && canMaintain,
            ) {
                Text(text = stringResource(R.string.st_slot_rollback))
            }
            Text(
                text = when {
                    coreState.runState != StmCoreRunState.STOPPED -> {
                        stringResource(R.string.st_core_maintenance_requires_stopped)
                    }

                    canMaintain -> stringResource(R.string.st_core_maintenance_idle_note)
                    else -> stringResource(R.string.st_core_maintenance_busy)
                },
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SlotCard(
    slot: StmCoreSlot,
    isActive: Boolean,
    isRunning: Boolean,
    canMaintain: Boolean,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
    onVerify: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = slot.artifact?.stVersion?.takeIf(String::isNotBlank)?.let { version ->
                    stringResource(R.string.st_installed_version_name, version)
                } ?: stringResource(R.string.st_installed_version_unknown),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = slot.artifact?.channel?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.st_installed_channel_unknown),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StateBadge(text = slot.state.displayName())
    }
    if (isActive || isRunning) {
        Text(
            text = when {
                isActive && isRunning -> stringResource(R.string.st_slot_active_and_running)
                isActive -> stringResource(R.string.st_slot_active)
                else -> stringResource(R.string.st_slot_running)
            },
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }

    if (slot.state == StmCoreSlotState.READY) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onVerify,
                modifier = Modifier.weight(1f),
                enabled = canMaintain,
            ) {
                Text(text = stringResource(R.string.st_slot_verify_full))
            }
            OutlinedButton(
                onClick = onActivate,
                modifier = Modifier.weight(1f),
                enabled = canMaintain && !isActive,
            ) {
                Text(text = stringResource(R.string.st_slot_activate))
            }
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.weight(1f),
                enabled = canMaintain && !isActive && !isRunning,
            ) {
                Text(text = stringResource(R.string.st_slot_remove))
            }
        }
    } else if (slot.state == StmCoreSlotState.BROKEN) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onVerify,
                modifier = Modifier.weight(1f),
                enabled = canMaintain,
            ) {
                Text(text = stringResource(R.string.st_slot_verify_full))
            }
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.weight(1f),
                enabled = canMaintain && !isActive && !isRunning,
            ) {
                Text(text = stringResource(R.string.st_slot_remove))
            }
        }
    }
}

/**
 * Keep synthetic acceptance fixtures and their raw Core identifiers out of the user-facing
 * version manager. They remain visible in the Logs screen as diagnostic records.
 */
internal fun List<StmCoreSlot>.userVisibleStSlots(): List<StmCoreSlot> =
    filter { slot -> slot.artifact?.kind != StmCoreArtifactKind.GATE2_SYNTHETIC }

@Composable
internal fun CoreStateRecordsSection(coreState: StmCoreState) {
    val activeSlot = coreState.activeSlot
    val runningSlot = coreState.runningSlot
    ManagerSection(title = stringResource(R.string.logs_core_state_title)) {
        Text(
            text = stringResource(R.string.logs_core_state_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusLine(
            label = stringResource(R.string.st_core_snapshot_revision),
            value = coreState.revision.toString(),
            modifier = Modifier.padding(top = 14.dp),
        )
        StatusLine(
            label = stringResource(R.string.st_core_active_slot),
            value = activeSlot?.slotId ?: stringResource(R.string.st_core_no_active_slot),
            modifier = Modifier.padding(top = 8.dp),
        )
        activeSlot?.let { active ->
            DetailValue(
                label = stringResource(R.string.st_core_active_revision),
                value = stringResource(
                    R.string.st_core_active_revision_value,
                    active.slotRevision,
                    active.activeRevision,
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        runningSlot?.let { running ->
            StatusLine(
                label = stringResource(R.string.st_core_running_slot),
                value = running.slotId,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        coreState.error?.let { error ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            ErrorEvidence(error = error)
        }
        if (coreState.slots.isEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                text = stringResource(R.string.logs_core_slots_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            coreState.slots.sortedBy(StmCoreSlot::id).forEach { slot ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                CoreSlotRecord(
                    slot = slot,
                    isActive = activeSlot?.slotId == slot.id,
                    isRunning = runningSlot?.slotId == slot.id,
                )
            }
        }
    }
}

@Composable
private fun CoreSlotRecord(
    slot: StmCoreSlot,
    isActive: Boolean,
    isRunning: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.st_slot_name, slot.id),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.st_slot_revision, slot.revision),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StateBadge(text = slot.state.displayName())
    }
    if (isActive || isRunning) {
        Text(
            text = when {
                isActive && isRunning -> stringResource(R.string.st_slot_active_and_running)
                isActive -> stringResource(R.string.st_slot_active)
                else -> stringResource(R.string.st_slot_running)
            },
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
    ArtifactEvidence(artifact = slot.artifact, slot = slot)
    ManifestEvidence(slot = slot)
}

@Composable
private fun ArtifactEvidence(artifact: StmCoreArtifact?, slot: StmCoreSlot? = null) {
    val context = LocalContext.current
    EvidenceGroup(title = stringResource(R.string.st_artifact_identity_title)) {
        DetailValue(
            label = stringResource(R.string.st_artifact_kind),
            value = artifact?.kind?.displayName() ?: stringResource(R.string.not_available),
        )
        DetailValue(
            label = stringResource(R.string.st_artifact_repository),
            value = artifact?.repository ?: slot?.repository ?: stringResource(R.string.not_available),
            monospace = true,
        )
        DetailValue(
            label = stringResource(R.string.st_artifact_channel),
            value = artifact?.channel ?: stringResource(R.string.not_available),
        )
        DetailValue(
            label = stringResource(R.string.st_artifact_commit),
            value = artifact?.commitSha ?: slot?.commitSha ?: stringResource(R.string.not_available),
            monospace = true,
        )
        artifact?.downloadUrl?.let { url ->
            DetailValue(
                label = stringResource(R.string.st_artifact_download_url),
                value = url,
                monospace = true,
            )
        }
        artifact?.stVersion?.let { version ->
            DetailValue(label = stringResource(R.string.st_artifact_st_version), value = version)
        }
        Text(
            text = when (artifact?.kind) {
                StmCoreArtifactKind.GATE2_SYNTHETIC -> {
                    stringResource(R.string.st_artifact_gate2_synthetic_note)
                }

                StmCoreArtifactKind.SILLY_TAVERN_SOURCE -> {
                    stringResource(R.string.st_artifact_source_stage3_note)
                }

                null -> stringResource(R.string.st_artifact_identity_missing)
            },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    EvidenceGroup(title = stringResource(R.string.st_artifact_integrity_title)) {
        DetailValue(
            label = stringResource(R.string.st_artifact_integrity_status),
            value = artifact?.integrity?.displayName() ?: stringResource(R.string.not_available),
        )
        DetailValue(
            label = stringResource(R.string.st_artifact_length),
            value = artifact?.archiveLength?.let { length ->
                Formatter.formatShortFileSize(context, length)
            } ?: stringResource(R.string.not_available),
        )
        DetailValue(
            label = stringResource(R.string.st_artifact_sha256),
            value = artifact?.archiveSha256 ?: stringResource(R.string.not_available),
            monospace = true,
        )
        artifact?.packageLockSha256?.let { sha256 ->
            DetailValue(
                label = stringResource(R.string.st_artifact_package_lock_sha256),
                value = sha256,
                monospace = true,
            )
        }
    }

    EvidenceGroup(title = stringResource(R.string.st_artifact_trust_title)) {
        DetailValue(
            label = stringResource(R.string.st_artifact_trust_status),
            value = artifact?.trust?.displayName() ?: stringResource(R.string.not_available),
        )
        DetailValue(
            label = stringResource(R.string.st_artifact_catalog),
            value = artifact?.catalogVersion ?: stringResource(R.string.st_artifact_catalog_absent),
        )
        artifact?.licenseStatus?.let { status ->
            DetailValue(label = stringResource(R.string.st_artifact_license), value = status)
        }
    }
}

@Composable
private fun ManifestEvidence(slot: StmCoreSlot) {
    val context = LocalContext.current
    EvidenceGroup(title = stringResource(R.string.st_manifest_title)) {
        DetailValue(
            label = stringResource(R.string.st_manifest_sha256),
            value = slot.manifestSha256 ?: stringResource(R.string.not_available),
            monospace = true,
        )
        DetailValue(
            label = stringResource(R.string.st_manifest_files),
            value = slot.manifestFileCount?.toString() ?: stringResource(R.string.not_available),
        )
        DetailValue(
            label = stringResource(R.string.st_manifest_bytes),
            value = slot.manifestTotalBytes?.let { bytes ->
                Formatter.formatShortFileSize(context, bytes)
            } ?: stringResource(R.string.not_available),
        )
    }
}

@Composable
internal fun CoreJobsSection(jobs: List<StmCoreJob>, onCancel: (String) -> Unit) {
    ManagerSection(title = stringResource(R.string.st_core_jobs_title)) {
        if (jobs.isEmpty()) {
            Text(
                text = stringResource(R.string.st_core_jobs_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            jobs.sortedByDescending(StmCoreJob::updatedAtEpochMs).forEachIndexed { index, job ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                CoreJobCard(job = job, onCancel = { onCancel(job.operationId) })
            }
        }
    }
}

@Composable
private fun CoreJobCard(job: StmCoreJob, onCancel: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = job.type.displayName(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.st_job_target, job.targetId),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StateBadge(text = job.state.displayName())
    }
    DetailValue(
        label = stringResource(R.string.st_job_operation),
        value = job.operationId,
        modifier = Modifier.padding(top = 10.dp),
        monospace = true,
    )
    DetailValue(
        label = stringResource(R.string.st_job_phase),
        value = job.phase.displayName(),
    )
    job.progress?.let { progress ->
        LinearProgressIndicator(
            progress = { progress.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
    DetailValue(
        label = stringResource(R.string.st_job_updated),
        value = buildString {
            append(DateFormat.getMediumDateFormat(context).format(Date(job.updatedAtEpochMs)))
            append(' ')
            append(DateFormat.getTimeFormat(context).format(Date(job.updatedAtEpochMs)))
        },
        modifier = Modifier.padding(top = 8.dp),
    )
    job.error?.let { error ->
        ErrorEvidence(error = error, modifier = Modifier.padding(top = 10.dp))
    }
    job.artifact?.let { artifact ->
        ArtifactEvidence(artifact = artifact)
    }
    if (job.state in CANCELLABLE_JOB_STATES) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Text(text = stringResource(R.string.st_job_cancel))
        }
    }
}

@Composable
private fun ErrorEvidence(error: StmCoreError, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.st_core_error_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.st_core_error_code, error.domain, error.code),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = error.summary,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            error.diagnosticDetail?.let { detail ->
                SelectionContainer {
                    Text(
                        text = detail,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadOptionsDialog(
    archives: List<DownloadedStArchive>,
    onDismiss: () -> Unit,
    onDownload: (StDownloadChannel) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.st_download_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StDownloadChannel.entries.forEach { channel ->
                    DownloadChannelButton(
                        channel = channel,
                        alreadyDownloaded = archives.any { archive -> archive.channel == channel },
                        onDownload = onDownload,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DownloadChannelButton(
    channel: StDownloadChannel,
    alreadyDownloaded: Boolean,
    onDownload: (StDownloadChannel) -> Unit,
) {
    OutlinedButton(
        onClick = { onDownload(channel) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !alreadyDownloaded,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = channel.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (alreadyDownloaded) {
                    stringResource(R.string.st_download_already_downloaded)
                } else {
                    stringResource(R.string.st_download_branch, channel.branch)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (channel == StDownloadChannel.PREVIEW) {
                Text(
                    text = stringResource(R.string.st_download_preview_warning),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(active: ActiveStDownload, onCancel: () -> Unit) {
    val context = LocalContext.current
    ManagerSection(title = stringResource(R.string.st_download_running, active.channel.displayName())) {
        Text(
            text = active.phase.displayName(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val progress = active.progress
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }
        if (active.phase != StDownloadPhase.RESOLVING) {
            Text(
                text = active.totalBytes?.let { total ->
                    stringResource(
                        R.string.st_download_progress_known,
                        Formatter.formatShortFileSize(context, active.bytesDownloaded),
                        Formatter.formatShortFileSize(context, total),
                    )
                } ?: stringResource(
                    R.string.st_download_progress_unknown,
                    Formatter.formatShortFileSize(context, active.bytesDownloaded),
                ),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        active.exactCommit?.let { commit ->
            DetailValue(
                label = stringResource(R.string.st_download_exact_commit),
                value = commit,
                modifier = Modifier.padding(top = 8.dp),
                monospace = true,
            )
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text(text = stringResource(R.string.st_download_cancel))
        }
    }
}

@Composable
private fun DownloadedArchivesCard(
    archives: List<DownloadedStArchive>,
    canPrepare: Boolean,
    onImport: (DownloadedStArchive) -> Unit,
    onInstall: (DownloadedStArchive) -> Unit,
    onDelete: (StDownloadChannel) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val context = LocalContext.current
    ManagerSection(title = stringResource(R.string.st_downloaded_title)) {
        if (archives.isEmpty()) {
            Text(
                text = stringResource(R.string.st_downloaded_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            archives.forEachIndexed { index, archive ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = archive.channel.displayName(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.st_downloaded_file_detail,
                                archive.fileName,
                                Formatter.formatShortFileSize(context, archive.sizeBytes),
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onDelete(archive.channel) }) {
                        Text(text = stringResource(R.string.action_delete))
                    }
                }

                EvidenceGroup(title = stringResource(R.string.st_artifact_identity_title)) {
                    DetailValue(
                        label = stringResource(R.string.st_download_identity_status),
                        value = archive.identity.classification.displayName(),
                    )
                    DetailValue(
                        label = stringResource(R.string.st_artifact_repository),
                        value = if (
                            archive.identity.classification ==
                            StArchiveIdentityClassification.EXACT_COMMIT
                        ) {
                            archive.identity.repository
                        } else {
                            stringResource(R.string.st_download_legacy_repository_unknown)
                        },
                        monospace = archive.identity.classification ==
                            StArchiveIdentityClassification.EXACT_COMMIT,
                    )
                    DetailValue(
                        label = stringResource(R.string.st_artifact_commit),
                        value = archive.identity.exactCommit
                            ?: stringResource(R.string.st_download_legacy_no_commit),
                        monospace = archive.identity.exactCommit != null,
                    )
                }
                EvidenceGroup(title = stringResource(R.string.st_artifact_integrity_title)) {
                    DetailValue(
                        label = stringResource(R.string.st_download_integrity_status),
                        value = archive.integrity.classification.displayName(),
                    )
                    DetailValue(
                        label = stringResource(R.string.st_artifact_length),
                        value = Formatter.formatShortFileSize(context, archive.integrity.byteLength),
                    )
                    DetailValue(
                        label = stringResource(R.string.st_artifact_sha256),
                        value = archive.integrity.sha256
                            ?: stringResource(R.string.st_download_legacy_no_hash),
                        monospace = archive.integrity.sha256 != null,
                    )
                    DetailValue(
                        label = stringResource(R.string.st_download_zip_hint),
                        value = if (archive.integrity.hasZipFormatHint) {
                            stringResource(R.string.st_download_zip_hint_present)
                        } else {
                            stringResource(R.string.st_download_zip_hint_missing)
                        },
                    )
                }
                EvidenceGroup(title = stringResource(R.string.st_artifact_trust_title)) {
                    DetailValue(
                        label = stringResource(R.string.st_artifact_trust_status),
                        value = archive.trust.displayName(),
                    )
                }
                if (archive.identity.classification == StArchiveIdentityClassification.EXACT_COMMIT &&
                    archive.integrity.classification ==
                    StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED &&
                    archive.trust == StArchiveTrust.DEGRADED_UNSIGNED_CATALOG
                ) {
                    val installPolicy = archive.channel.installPolicy()
                    Button(
                        onClick = { onInstall(archive) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        enabled = canPrepare,
                    ) {
                        Text(
                            text = stringResource(
                                if (installPolicy.requiresUserConfirmation) {
                                    R.string.st_download_preview_local_install
                                } else {
                                    R.string.st_download_core_install
                                },
                            ),
                        )
                    }
                    Text(
                        text = stringResource(
                            if (installPolicy.requiresUserConfirmation) {
                                R.string.st_download_preview_install_note
                            } else {
                                R.string.st_download_core_install_note
                            },
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onImport(archive) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        enabled = canPrepare,
                    ) {
                        Text(text = stringResource(R.string.st_download_core_preflight))
                    }
                    Text(
                        text = stringResource(R.string.st_download_core_preflight_note),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.st_downloaded_not_installed_note),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onDeleteAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(text = stringResource(R.string.st_download_delete_all))
            }
        }
    }
}

private fun DownloadedStArchive.coreSlotIdOrNull(): String? =
    identity.exactCommit?.let { commit -> "st-${channel.branch}-${commit.lowercase()}" }

internal data class StDownloadInstallPolicy(
    val mode: StmCoreInstallMode,
    val requiresUserConfirmation: Boolean,
)

internal fun StDownloadChannel.installPolicy(): StDownloadInstallPolicy = when (this) {
    StDownloadChannel.STABLE -> StDownloadInstallPolicy(
        mode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
        requiresUserConfirmation = false,
    )

    StDownloadChannel.PREVIEW -> StDownloadInstallPolicy(
        mode = StmCoreInstallMode.LOCAL_NPM_BUILD,
        requiresUserConfirmation = true,
    )
}

private const val PREBUILT_RUNTIME_TRANSPORT_UNAVAILABLE =
    "PREBUILT_RUNTIME_TRANSPORT_UNAVAILABLE"
private const val PREBUILT_RUNTIME_NOT_AVAILABLE =
    "PREBUILT_RUNTIME_NOT_AVAILABLE"
private val RECOVERABLE_PREBUILT_ERROR_CODES = setOf(
    PREBUILT_RUNTIME_TRANSPORT_UNAVAILABLE,
    PREBUILT_RUNTIME_NOT_AVAILABLE,
)

@Composable
private fun DownloadFailureCard(failure: StDownloadFailure, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.st_download_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(failure.messageRes()),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            failure.detailCode?.let { detailCode ->
                Text(
                    text = stringResource(R.string.st_download_error_code, detailCode),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(text = stringResource(R.string.action_dismiss))
            }
        }
    }
}

@Composable
private fun EvidenceGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun DetailValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StateBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BranchRow(
    @StringRes labelRes: Int,
    branch: String,
    modifier: Modifier = Modifier,
) {
    StatusLine(label = stringResource(labelRes), value = branch, modifier = modifier)
}

@Composable
private fun ManagerSection(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun StDownloadChannel.displayName(): String = when (this) {
    StDownloadChannel.STABLE -> stringResource(R.string.st_channel_stable)
    StDownloadChannel.PREVIEW -> stringResource(R.string.st_channel_preview)
}

@Composable
private fun StDownloadPhase.displayName(): String = when (this) {
    StDownloadPhase.RESOLVING -> stringResource(R.string.st_download_phase_resolving)
    StDownloadPhase.DOWNLOADING -> stringResource(R.string.st_download_phase_downloading)
    StDownloadPhase.RECORDING_CONTENT_HASH -> stringResource(R.string.st_download_phase_hashing)
}

@Composable
private fun StArchiveIdentityClassification.displayName(): String = when (this) {
    StArchiveIdentityClassification.EXACT_COMMIT -> stringResource(R.string.st_identity_exact)
    StArchiveIdentityClassification.LEGACY_UNIDENTIFIED -> stringResource(R.string.st_identity_legacy)
}

@Composable
private fun StArchiveIntegrityClassification.displayName(): String = when (this) {
    StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED -> {
        stringResource(R.string.st_integrity_hash_recorded)
    }

    StArchiveIntegrityClassification.LEGACY_UNVERIFIED -> {
        stringResource(R.string.st_integrity_legacy_unverified)
    }
}

@Composable
private fun StArchiveTrust.displayName(): String = when (this) {
    StArchiveTrust.DEGRADED_UNSIGNED_CATALOG -> stringResource(R.string.st_trust_degraded_unsigned)
    StArchiveTrust.UNTRUSTED_LEGACY -> stringResource(R.string.st_trust_legacy_untrusted)
}

@Composable
private fun StmCoreSlotState.displayName(): String = when (this) {
    StmCoreSlotState.ABSENT -> stringResource(R.string.st_slot_state_absent)
    StmCoreSlotState.STAGING -> stringResource(R.string.st_slot_state_staging)
    StmCoreSlotState.VERIFYING -> stringResource(R.string.st_slot_state_verifying)
    StmCoreSlotState.READY -> stringResource(R.string.st_slot_state_ready)
    StmCoreSlotState.BROKEN -> stringResource(R.string.st_slot_state_broken)
    StmCoreSlotState.RETIRED -> stringResource(R.string.st_slot_state_retired)
}

@Composable
private fun StmCoreArtifactKind.displayName(): String = when (this) {
    StmCoreArtifactKind.GATE2_SYNTHETIC -> stringResource(R.string.st_artifact_kind_gate2)
    StmCoreArtifactKind.SILLY_TAVERN_SOURCE -> stringResource(R.string.st_artifact_kind_source)
}

@Composable
private fun StmCoreArtifactIntegrity.displayName(): String = when (this) {
    StmCoreArtifactIntegrity.PENDING -> stringResource(R.string.st_integrity_pending)
    StmCoreArtifactIntegrity.VERIFIED -> stringResource(R.string.st_integrity_verified)
    StmCoreArtifactIntegrity.FAILED -> stringResource(R.string.st_integrity_failed)
}

@Composable
private fun StmCoreArtifactTrust.displayName(): String = when (this) {
    StmCoreArtifactTrust.TRUSTED_CATALOG -> stringResource(R.string.st_trust_catalog)
    StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG -> {
        stringResource(R.string.st_trust_degraded_unsigned)
    }

    StmCoreArtifactTrust.REJECTED -> stringResource(R.string.st_trust_rejected)
}

@Composable
private fun StmCoreJobType.displayName(): String = when (this) {
    StmCoreJobType.DOWNLOAD -> stringResource(R.string.st_job_type_download)
    StmCoreJobType.VERIFY -> stringResource(R.string.st_job_type_verify)
    StmCoreJobType.INSTALL -> stringResource(R.string.st_job_type_install)
    StmCoreJobType.ACTIVATE -> stringResource(R.string.st_job_type_activate)
    StmCoreJobType.ROLLBACK -> stringResource(R.string.st_job_type_rollback)
    StmCoreJobType.REMOVE -> stringResource(R.string.st_job_type_remove)
    StmCoreJobType.MIGRATE -> stringResource(R.string.st_job_type_migrate)
    StmCoreJobType.USER_DATA_BACKUP -> stringResource(R.string.user_data_creating_backup)
    StmCoreJobType.USER_DATA_IMPORT -> stringResource(R.string.user_data_importing)
    StmCoreJobType.USER_DATA_RESTORE -> stringResource(R.string.user_data_restoring)
    StmCoreJobType.USER_DATA_DELETE_BACKUP ->
        stringResource(R.string.user_data_deleting_backup)
    StmCoreJobType.USER_DATA_MIGRATE,
    StmCoreJobType.USER_DATA_FINALIZE_MIGRATION,
    -> stringResource(R.string.user_data_migrating)
}

@Composable
private fun StmCoreJobState.displayName(): String = when (this) {
    StmCoreJobState.QUEUED -> stringResource(R.string.st_job_state_queued)
    StmCoreJobState.RUNNING -> stringResource(R.string.st_job_state_running)
    StmCoreJobState.CANCELLING -> stringResource(R.string.st_job_state_cancelling)
    StmCoreJobState.SUCCEEDED -> stringResource(R.string.st_job_state_succeeded)
    StmCoreJobState.FAILED -> stringResource(R.string.st_job_state_failed)
    StmCoreJobState.CANCELLED -> stringResource(R.string.st_job_state_cancelled)
}

@Composable
private fun StmCoreJobPhase.displayName(): String = when (this) {
    StmCoreJobPhase.QUEUED -> stringResource(R.string.st_job_phase_queued)
    StmCoreJobPhase.COPYING_ARTIFACT -> stringResource(R.string.st_job_phase_copying)
    StmCoreJobPhase.PREFLIGHT -> stringResource(R.string.st_job_phase_preflight)
    StmCoreJobPhase.EXTRACTING -> stringResource(R.string.st_job_phase_extracting)
    StmCoreJobPhase.DOWNLOADING_RUNTIME_LAYER ->
        stringResource(R.string.st_job_phase_runtime_download)
    StmCoreJobPhase.VERIFYING_RUNTIME_LAYER ->
        stringResource(R.string.st_job_phase_runtime_verify)
    StmCoreJobPhase.PREPARING_TOOLCHAIN -> stringResource(R.string.st_job_phase_toolchain)
    StmCoreJobPhase.INSTALLING_DEPENDENCIES ->
        stringResource(R.string.st_job_phase_dependencies)
    StmCoreJobPhase.BUILDING_BUNDLE -> stringResource(R.string.st_job_phase_bundle)
    StmCoreJobPhase.ASSEMBLING_RUNTIME -> stringResource(R.string.st_job_phase_runtime)
    StmCoreJobPhase.RUNNABLE_ACCEPTANCE -> stringResource(R.string.st_job_phase_runnable)
    StmCoreJobPhase.VALIDATING -> stringResource(R.string.st_job_phase_validating)
    StmCoreJobPhase.WRITING_MANIFEST -> stringResource(R.string.st_job_phase_manifest)
    StmCoreJobPhase.COMMITTING_SLOT -> stringResource(R.string.st_job_phase_committing)
    StmCoreJobPhase.SWITCHING_ACTIVE -> stringResource(R.string.st_job_phase_switching)
    StmCoreJobPhase.REMOVING_SLOT -> stringResource(R.string.st_job_phase_removing)
    StmCoreJobPhase.CLEANING_UP -> stringResource(R.string.st_job_phase_cleaning)
    StmCoreJobPhase.COMPLETE -> stringResource(R.string.st_job_phase_complete)
}

@StringRes
private fun StDownloadFailure.messageRes(): Int = when (reason) {
    StDownloadFailureReason.ALREADY_DOWNLOADED -> R.string.st_download_error_already_downloaded
    StDownloadFailureReason.DOWNLOAD_MANAGER_UNAVAILABLE -> R.string.st_download_error_manager_unavailable
    StDownloadFailureReason.VERSION_RESOLUTION_FAILED -> R.string.st_download_error_resolution
    StDownloadFailureReason.DOWNLOAD_FAILED -> R.string.st_download_error_failed
    StDownloadFailureReason.INVALID_ARCHIVE -> R.string.st_download_error_invalid_archive
    StDownloadFailureReason.STORAGE_UNAVAILABLE -> R.string.st_download_error_storage
}

private val ACTIVE_JOB_STATES = setOf(
    StmCoreJobState.QUEUED,
    StmCoreJobState.RUNNING,
    StmCoreJobState.CANCELLING,
)
private val CANCELLABLE_JOB_STATES = setOf(
    StmCoreJobState.QUEUED,
    StmCoreJobState.RUNNING,
)
private const val OFFICIAL_ST_REPOSITORY = "https://github.com/SillyTavern/SillyTavern.git"
