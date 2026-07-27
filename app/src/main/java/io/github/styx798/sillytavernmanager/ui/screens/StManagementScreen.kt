package io.github.styx798.sillytavernmanager.ui.screens

import androidx.annotation.StringRes
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.instances.StInstance
import io.github.styx798.sillytavernmanager.core.instances.StInstanceInstallFailure
import io.github.styx798.sillytavernmanager.core.instances.StInstanceInstallPhase
import io.github.styx798.sillytavernmanager.core.instances.StInstanceInstallState
import io.github.styx798.sillytavernmanager.core.instances.StInstanceState
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSupportedVersions

@Composable
fun StManagementScreen(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    instanceState: StInstanceState,
    installState: StInstanceInstallState,
    onInstallStable: (String) -> Unit,
    onCancelInstall: () -> Unit,
    onDismissInstall: () -> Unit,
    onRenameInstance: (String, String) -> Unit,
    onSelectInstance: (String) -> Unit,
    onClearInstanceError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showInstallDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    if (showInstallDialog) {
        NameInstanceDialog(
            titleRes = R.string.st_simple_install_dialog_title,
            initialName = "",
            confirmRes = R.string.st_simple_install_confirm,
            onDismiss = { showInstallDialog = false },
            onConfirm = { name ->
                showInstallDialog = false
                onInstallStable(name)
            },
        )
    }
    pendingRenameId?.let { instanceId ->
        instanceState.instances.singleOrNull { it.id == instanceId }?.let { instance ->
            NameInstanceDialog(
                titleRes = R.string.st_instance_rename_title,
                initialName = instance.displayName,
                confirmRes = R.string.st_instance_rename_confirm,
                onDismiss = { pendingRenameId = null },
                onConfirm = { name ->
                    pendingRenameId = null
                    onRenameInstance(instance.id, name)
                },
            )
        }
    }
    instanceState.error?.let {
        AlertDialog(
            onDismissRequest = onClearInstanceError,
            title = { Text(stringResource(R.string.st_instance_error_title)) },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = onClearInstanceError) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
    val canManage = connectionState == StmCoreConnectionState.CONNECTED &&
        coreState.installerRecoveryComplete &&
        coreState.runState == StmCoreRunState.STOPPED &&
        coreState.jobs.none { it.state.name in setOf("QUEUED", "RUNNING", "CANCELLING") } &&
        !installState.active
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.st_simple_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.st_simple_intro),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.st_simple_available_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            R.string.st_simple_official_version,
                            StmCoreSupportedVersions.SIGNED_STABLE,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.st_simple_signed_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showInstallDialog = true },
                        enabled = canManage,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.st_simple_install_action))
                    }
                }
            }
        }
        if (installState.phase != StInstanceInstallPhase.IDLE) {
            item {
                InstallProgressCard(
                    state = installState,
                    onCancel = onCancelInstall,
                    onDismiss = onDismissInstall,
                )
            }
        }
        item {
            InstalledVersionsCard(coreState)
        }
        item {
            InstancesCard(
                instances = instanceState.instances,
                activeInstanceId = instanceState.activeInstanceId,
                canSelect = canManage,
                onRename = { pendingRenameId = it },
                onSelect = onSelectInstance,
            )
        }
    }
}

@Composable
private fun InstalledVersionsCard(coreState: StmCoreState) {
    val versions = coreState.slots
        .filter {
            it.state == StmCoreSlotState.READY &&
                it.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
        }
        .mapNotNull { it.artifact?.stVersion }
        .distinct()
        .sortedDescending()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.st_simple_built_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (versions.isEmpty()) {
                Text(
                    text = stringResource(R.string.st_simple_built_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                versions.forEachIndexed { index, version ->
                    if (index > 0) HorizontalDivider()
                    Text(
                        text = version,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstancesCard(
    instances: List<StInstance>,
    activeInstanceId: String?,
    canSelect: Boolean,
    onRename: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.st_instances_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (instances.isEmpty()) {
                Text(
                    text = stringResource(R.string.st_instances_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                instances.forEachIndexed { index, instance ->
                    if (index > 0) HorizontalDivider()
                    InstanceRow(
                        instance = instance,
                        active = instance.id == activeInstanceId,
                        canSelect = canSelect,
                        onRename = { onRename(instance.id) },
                        onSelect = { onSelect(instance.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceRow(
    instance: StInstance,
    active: Boolean,
    canSelect: Boolean,
    onRename: () -> Unit,
    onSelect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = instance.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.st_instance_version, instance.stVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                Text(
                    text = stringResource(R.string.st_instance_current),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onRename) {
                Text(stringResource(R.string.st_instance_rename))
            }
            if (!active) {
                OutlinedButton(onClick = onSelect, enabled = canSelect) {
                    Text(stringResource(R.string.st_instance_select))
                }
            }
        }
    }
}

@Composable
private fun InstallProgressCard(
    state: StInstanceInstallState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentStep = state.currentChecklistStep()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    when (state.phase) {
                        StInstanceInstallPhase.COMPLETE -> R.string.st_install_complete_title
                        StInstanceInstallPhase.FAILED -> R.string.st_install_failed_title
                        StInstanceInstallPhase.CANCELLED -> R.string.st_install_cancelled_title
                        else -> R.string.st_install_progress_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            state.displayName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            LinearProgressIndicator(
                progress = { state.overallProgress() },
                modifier = Modifier.fillMaxWidth(),
            )
            INSTALL_CHECKLIST.forEachIndexed { index, label ->
                val marker = when {
                    state.phase == StInstanceInstallPhase.COMPLETE || index < currentStep -> "✓"
                    index == currentStep && state.active -> "●"
                    state.phase == StInstanceInstallPhase.FAILED && index == currentStep -> "!"
                    else -> "○"
                }
                Text(
                    text = "$marker  ${stringResource(label)}",
                    color = when (marker) {
                        "✓" -> MaterialTheme.colorScheme.primary
                        "!" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (state.phase == StInstanceInstallPhase.FAILED) {
                Text(
                    text = stringResource(state.failure.messageRes(), state.failureCode.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.active) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state.phase != StInstanceInstallPhase.CANCELLING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else if (state.terminal) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_dismiss))
                }
            }
        }
    }
}

@Composable
private fun NameInstanceDialog(
    @StringRes titleRes: Int,
    initialName: String,
    @StringRes confirmRes: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.st_instance_name_explanation))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.st_instance_name_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun StInstanceInstallState.currentChecklistStep(): Int = when (phase) {
    StInstanceInstallPhase.IDLE,
    StInstanceInstallPhase.DOWNLOADING_SOURCE,
    -> 0
    StInstanceInstallPhase.ACTIVATING,
    StInstanceInstallPhase.COMPLETE,
    -> 4
    StInstanceInstallPhase.FAILED,
    StInstanceInstallPhase.CANCELLING,
    StInstanceInstallPhase.CANCELLED,
    StInstanceInstallPhase.INSTALLING,
    -> when (corePhase) {
        null,
        StmCoreJobPhase.QUEUED,
        StmCoreJobPhase.COPYING_ARTIFACT,
        StmCoreJobPhase.PREFLIGHT,
        StmCoreJobPhase.EXTRACTING,
        StmCoreJobPhase.VALIDATING,
        -> 1
        StmCoreJobPhase.DOWNLOADING_RUNTIME_LAYER,
        StmCoreJobPhase.VERIFYING_RUNTIME_LAYER,
        StmCoreJobPhase.PREPARING_TOOLCHAIN,
        StmCoreJobPhase.INSTALLING_DEPENDENCIES,
        StmCoreJobPhase.BUILDING_BUNDLE,
        StmCoreJobPhase.ASSEMBLING_RUNTIME,
        -> 2
        StmCoreJobPhase.RUNNABLE_ACCEPTANCE -> 3
        StmCoreJobPhase.WRITING_MANIFEST,
        StmCoreJobPhase.COMMITTING_SLOT,
        StmCoreJobPhase.SWITCHING_ACTIVE,
        StmCoreJobPhase.REMOVING_SLOT,
        StmCoreJobPhase.CLEANING_UP,
        StmCoreJobPhase.COMPLETE,
        -> 4
    }
}

private fun StInstanceInstallState.overallProgress(): Float {
    val step = currentChecklistStep()
    if (phase == StInstanceInstallPhase.COMPLETE) return 1f
    val within = when {
        phase == StInstanceInstallPhase.DOWNLOADING_SOURCE -> downloadProgress ?: 0f
        phase == StInstanceInstallPhase.INSTALLING -> coreProgress?.toFloat() ?: 0.35f
        phase == StInstanceInstallPhase.ACTIVATING -> 0.75f
        else -> 0f
    }
    return ((step + within.coerceIn(0f, 1f)) / INSTALL_CHECKLIST.size)
        .coerceIn(0f, 1f)
}

@StringRes
private fun StInstanceInstallFailure?.messageRes(): Int = when (this) {
    StInstanceInstallFailure.INVALID_NAME -> R.string.st_install_error_invalid_name
    StInstanceInstallFailure.DUPLICATE_NAME -> R.string.st_install_error_duplicate_name
    StInstanceInstallFailure.DOWNLOAD_FAILED -> R.string.st_install_error_download
    StInstanceInstallFailure.CORE_REJECTED -> R.string.st_install_error_core
    StInstanceInstallFailure.INSTALL_FAILED -> R.string.st_install_error_install
    StInstanceInstallFailure.INSTANCE_REGISTRY_FAILED -> R.string.st_install_error_registry
    StInstanceInstallFailure.ACTIVATION_FAILED -> R.string.st_install_error_activation
    null -> R.string.st_install_error_unknown
}

private val INSTALL_CHECKLIST = listOf(
    R.string.st_install_step_source,
    R.string.st_install_step_verify,
    R.string.st_install_step_runtime,
    R.string.st_install_step_acceptance,
    R.string.st_install_step_instance,
)
