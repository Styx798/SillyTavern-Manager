package io.github.styx798.sillytavernmanager.core.logging

import android.net.Uri
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState

sealed interface DiagnosticLogExportResult {
    data object Success : DiagnosticLogExportResult

    data class Failure(
        val diagnosticDetail: String,
    ) : DiagnosticLogExportResult
}

interface DiagnosticLogExporter {
    suspend fun export(
        destination: Uri,
        coreState: StmCoreState,
        coreConnectionState: StmCoreConnectionState,
        entries: List<LogEntry>,
    ): DiagnosticLogExportResult
}

data class DiagnosticLogExportState(
    val exporting: Boolean = false,
    val completed: Boolean = false,
    val failed: Boolean = false,
)
