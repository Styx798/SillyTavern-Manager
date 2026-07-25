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
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreError
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreWorkload

@Composable
fun DashboardScreen(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTavern: () -> Unit,
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
            CoreStatusCard(coreState = coreState, connectionState = connectionState)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.actions_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = if (coreState.canStop) onStop else onStart,
                            enabled = connectionState == StmCoreConnectionState.CONNECTED &&
                                (coreState.canStart || coreState.canStop),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (coreState.canStop) {
                                        R.string.action_stop
                                    } else {
                                        R.string.action_start
                                    },
                                ),
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenTavern,
                            enabled = connectionState == StmCoreConnectionState.CONNECTED &&
                                coreState.canOpenTavern,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_open_st))
                        }
                    }
                    Text(
                        text = stringResource(R.string.action_unavailable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            BuildStatusCard()
        }
    }
}

@Composable
private fun CoreStatusCard(
    coreState: StmCoreState,
    connectionState: StmCoreConnectionState,
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
                StatusBadge(runState = coreState.runState)
            }
            Text(
                text = stringResource(coreState.runState.titleRes()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (connectionState != StmCoreConnectionState.CONNECTED) {
                Text(
                    text = stringResource(
                        if (connectionState == StmCoreConnectionState.CONNECTING) {
                            R.string.core_connection_connecting
                        } else {
                            R.string.core_connection_disconnected
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            coreState.localizedStatusDetail()?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (coreState.runState == StmCoreRunState.CRASHED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
            InfoRow(
                label = stringResource(R.string.installed_version),
                value = coreState.componentIdentity,
            )
            InfoRow(
                label = stringResource(R.string.core_revision),
                value = coreState.revision.takeIf { it > 0 }?.toString()
                    ?: stringResource(R.string.not_available),
            )
        }
    }
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
