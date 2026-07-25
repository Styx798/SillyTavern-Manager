package io.github.styx798.sillytavernmanager.ui.screens

import android.net.Uri
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
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.logging.LogEntry
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExportState
import io.github.styx798.sillytavernmanager.core.logging.LogLevel
import io.github.styx798.sillytavernmanager.core.logging.LogSource
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogsScreen(
    coreState: StmCoreState,
    entries: List<LogEntry>,
    exportState: DiagnosticLogExportState,
    onExport: (Uri) -> Unit,
    onClearExportResult: () -> Unit,
    onCancelCoreJob: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        destination?.let(onExport)
    }
    val exportFileName = {
        "STM-diagnostics-" +
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now()) +
            ".txt"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.logs_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.logs_intro),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            DiagnosticExportSection(
                state = exportState,
                onExport = {
                    onClearExportResult()
                    createDocument.launch(exportFileName())
                },
                onDismissResult = onClearExportResult,
            )
        }
        item {
            CoreStateRecordsSection(coreState = coreState)
        }
        item {
            CoreJobsSection(
                jobs = coreState.jobs,
                onCancel = onCancelCoreJob,
            )
        }
        item {
            AppEventsSection(entries = entries)
        }
    }
}

@Composable
private fun DiagnosticExportSection(
    state: DiagnosticLogExportState,
    onExport: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.logs_export_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.logs_export_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onExport,
                enabled = !state.exporting,
            ) {
                Text(
                    stringResource(
                        if (state.exporting) {
                            R.string.logs_export_working
                        } else {
                            R.string.logs_export_action
                        },
                    ),
                )
            }
            when {
                state.completed -> ExportResultMessage(
                    text = stringResource(R.string.logs_export_success),
                    onDismiss = onDismissResult,
                )

                state.failed -> ExportResultMessage(
                    text = stringResource(R.string.logs_export_failure),
                    onDismiss = onDismissResult,
                )
            }
        }
    }
}

@Composable
private fun ExportResultMessage(
    text: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.material3.TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.action_dismiss))
        }
    }
}

@Composable
private fun AppEventsSection(entries: List<LogEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.logs_app_events_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (entries.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.logs_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            entries.sortedByDescending(LogEntry::sequence).forEach { entry ->
                LogEntryCard(entry = entry)
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: LogEntry) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    val timestamp = formatter.format(Instant.ofEpochMilli(entry.timestampMillis))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${stringResource(entry.source.labelRes())} · ${stringResource(entry.level.labelRes())}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun LogLevel.labelRes(): Int = when (this) {
    LogLevel.INFO -> R.string.log_level_info
    LogLevel.WARNING -> R.string.log_level_warning
    LogLevel.ERROR -> R.string.log_level_error
}

private fun LogSource.labelRes(): Int = when (this) {
    LogSource.APP -> R.string.log_source_app
    LogSource.RUNTIME -> R.string.log_source_runtime
}
