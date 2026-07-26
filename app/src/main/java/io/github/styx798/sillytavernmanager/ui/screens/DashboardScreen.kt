package io.github.styx798.sillytavernmanager.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.core.instances.StInstance
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreError
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreWorkload

@Composable
fun DashboardScreen(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    activeInstance: StInstance?,
    onStartSillyTavern: () -> Unit,
    onStopSillyTavern: () -> Unit,
    onOpenTavern: () -> Unit,
    onOpenCore: () -> Unit,
    onRestartCore: () -> Unit,
    onCloseCore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dashboard_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            CoreStatusCard(
                coreState = coreState,
                connectionState = connectionState,
                onOpenCore = onOpenCore,
                onRestartCore = onRestartCore,
                onCloseCore = onCloseCore,
            )
        }

        item {
            SillyTavernControlCard(
                coreState = coreState,
                connectionState = connectionState,
                activeInstance = activeInstance,
                onStart = onStartSillyTavern,
                onStop = onStopSillyTavern,
                onOpen = onOpenTavern,
            )
        }

    }
}

@Composable
private fun CoreStatusCard(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    onOpenCore: () -> Unit,
    onRestartCore: () -> Unit,
    onCloseCore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.runtime_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StateTextBadge(
                    text = stringResource(
                        when (connectionState) {
                            StmCoreConnectionState.CONNECTED -> R.string.core_health_healthy
                            StmCoreConnectionState.CONNECTING -> R.string.core_health_connecting
                            StmCoreConnectionState.DISCONNECTED -> R.string.core_health_disconnected
                            StmCoreConnectionState.CLOSED -> R.string.core_health_closed
                        },
                    ),
                )
            }
            if (connectionState != StmCoreConnectionState.CONNECTED) {
                Text(
                    text = stringResource(
                        when (connectionState) {
                            StmCoreConnectionState.CONNECTING ->
                                R.string.core_connection_connecting
                            StmCoreConnectionState.DISCONNECTED ->
                                R.string.core_connection_disconnected
                            StmCoreConnectionState.CLOSED ->
                                R.string.core_connection_closed
                            StmCoreConnectionState.CONNECTED ->
                                error("Connected Core has no disconnected message")
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            coreState.error?.localizedSummary()?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (connectionState == StmCoreConnectionState.CLOSED) {
                    Button(
                        onClick = onOpenCore,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_open_core))
                    }
                } else {
                    OutlinedButton(
                        onClick = onRestartCore,
                        enabled = connectionState == StmCoreConnectionState.CONNECTED,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_restart_core))
                    }
                    OutlinedButton(
                        onClick = onCloseCore,
                        enabled = connectionState == StmCoreConnectionState.CONNECTED,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_close_core))
                    }
                }
            }
        }
    }
}

@Composable
private fun SillyTavernControlCard(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    activeInstance: StInstance?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val version = coreState.activeSillyTavernVersion()
    val running = coreState.workload == StmCoreWorkload.SILLY_TAVERN &&
        coreState.runState in setOf(
            StmCoreRunState.STARTING,
            StmCoreRunState.RUNNING,
            StmCoreRunState.DRAINING,
        )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.st_runtime_controls_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    version == null -> stringResource(R.string.st_runtime_no_active)
                    activeInstance != null && running -> stringResource(
                        R.string.st_runtime_running_instance,
                        activeInstance.displayName,
                        version,
                    )
                    activeInstance != null -> stringResource(
                        R.string.st_runtime_stopped_instance,
                        activeInstance.displayName,
                        version,
                    )
                    running -> stringResource(R.string.st_runtime_running_version, version)
                    else -> stringResource(R.string.st_runtime_stopped_version, version)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    when {
                        coreState.runState == StmCoreRunState.STARTING && running ->
                            R.string.st_runtime_status_starting
                        coreState.runState == StmCoreRunState.RUNNING && running ->
                            R.string.st_runtime_status_running
                        coreState.runState == StmCoreRunState.DRAINING && running ->
                            R.string.st_runtime_status_stopping
                        else -> R.string.st_runtime_status_stopped
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (coreState.canOpenTavern) {
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_open_st))
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = coreState.canStop,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_stop_st))
                    }
                } else {
                    Button(
                        onClick = if (coreState.canStop && running) onStop else onStart,
                        enabled = connectionState == StmCoreConnectionState.CONNECTED &&
                            version != null &&
                            ((coreState.canStop && running) || coreState.canStart),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (coreState.canStop && running) {
                                    R.string.action_stop_st
                                } else {
                                    R.string.action_start_st
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun StmCoreState.activeSillyTavernVersion(): String? {
    val active = activeSlot ?: return null
    return slots.singleOrNull {
        it.id == active.slotId &&
            it.revision == active.slotRevision &&
            it.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
    }?.artifact?.stVersion
}

@Composable
private fun StmCoreState.localizedStatusDetail(): String? = error?.localizedSummary() ?: when (runState) {
    StmCoreRunState.STOPPED -> if (installerRecoveryComplete) {
        if (hasActiveSillyTavern()) {
            stringResource(R.string.core_summary_ready_st)
        } else {
            stringResource(R.string.core_summary_ready)
        }
    } else {
        stringResource(R.string.core_summary_recovering)
    }
    StmCoreRunState.STARTING -> nodeVersion?.let {
        stringResource(R.string.core_summary_waiting_health, it)
    } ?: stringResource(R.string.core_summary_starting)
    StmCoreRunState.RUNNING -> stringResource(
        if (workload == StmCoreWorkload.SILLY_TAVERN) {
            R.string.core_summary_running_st
        } else {
            R.string.core_summary_running
        },
    )
    StmCoreRunState.DRAINING -> stringResource(
        if (workload == StmCoreWorkload.SILLY_TAVERN) {
            R.string.core_summary_draining_st
        } else {
            R.string.core_summary_draining
        },
    )
    StmCoreRunState.CRASHED -> summary?.takeIf(String::isNotBlank)
}

private fun StmCoreState.hasActiveSillyTavern(): Boolean {
    val active = activeSlot ?: return false
    return slots.firstOrNull {
        it.id == active.slotId && it.revision == active.slotRevision
    }?.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
}

@Composable
private fun StmCoreError.localizedSummary(): String = when (code) {
    "CORE_PROCESS_DISCONNECTED" -> stringResource(R.string.core_error_process_disconnected)
    "CORE_PROCESS_RESTARTED" -> stringResource(R.string.core_error_process_restarted)
    "CHECKPOINT_CORRUPT" -> stringResource(R.string.core_error_checkpoint_corrupt)
    "FEATHER_ENGINE_FAILURE" -> stringResource(R.string.core_error_engine_failure)
    "START_TIMEOUT" -> stringResource(R.string.core_error_start_timeout)
    "SESSION_CREATE_FAILED" -> stringResource(R.string.core_error_session_create_failed)
    "SESSION_MISSING" -> stringResource(R.string.core_error_session_missing)
    "TERMINATE_EXECUTION_FAILED" -> stringResource(R.string.core_error_terminate_failed)
    "TERMINATE_TIMEOUT" -> stringResource(R.string.core_error_terminate_timeout)
    else -> stringResource(R.string.core_error_unknown, code)
}

@Composable
private fun BuildStatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.build_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.build_status_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BuildStatusRow(
                label = stringResource(R.string.build_shell_label),
                status = stringResource(R.string.build_ready),
                color = MaterialTheme.colorScheme.secondary,
            )
            BuildStatusRow(
                label = stringResource(R.string.build_runtime_label),
                status = stringResource(R.string.build_ready),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun BuildStatusRow(
    label: String,
    status: String,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.width(4.dp).height(28.dp),
            color = color,
            shape = MaterialTheme.shapes.small,
            content = {},
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun StatusBadge(runState: StmCoreRunState) {
    val background: Color = when (runState) {
        StmCoreRunState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        StmCoreRunState.CRASHED -> MaterialTheme.colorScheme.errorContainer
        StmCoreRunState.STARTING,
        StmCoreRunState.DRAINING,
        -> MaterialTheme.colorScheme.tertiaryContainer
        StmCoreRunState.STOPPED,
        -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground: Color = when (runState) {
        StmCoreRunState.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        StmCoreRunState.CRASHED -> MaterialTheme.colorScheme.onErrorContainer
        StmCoreRunState.STARTING,
        StmCoreRunState.DRAINING,
        -> MaterialTheme.colorScheme.onTertiaryContainer
        StmCoreRunState.STOPPED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = stringResource(runState.shortLabelRes()),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun StateTextBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
        )
    }
}

@StringRes
private fun StmCoreRunState.titleRes(): Int = when (this) {
    StmCoreRunState.STOPPED -> R.string.runtime_stopped
    StmCoreRunState.STARTING -> R.string.runtime_starting
    StmCoreRunState.RUNNING -> R.string.runtime_running
    StmCoreRunState.DRAINING -> R.string.runtime_stopping
    StmCoreRunState.CRASHED -> R.string.runtime_error
}

@StringRes
private fun StmCoreRunState.shortLabelRes(): Int = when (this) {
    StmCoreRunState.STOPPED -> R.string.runtime_badge_stopped
    StmCoreRunState.STARTING -> R.string.runtime_badge_starting
    StmCoreRunState.RUNNING -> R.string.runtime_badge_running
    StmCoreRunState.DRAINING -> R.string.runtime_badge_stopping
    StmCoreRunState.CRASHED -> R.string.runtime_badge_error
}
