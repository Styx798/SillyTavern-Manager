package io.github.styx798.sillytavernmanager.core.logging

import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import java.time.Instant

data class StmDiagnosticEnvironment(
    val appVersion: String,
    val androidVersion: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val supportedAbis: List<String>,
    val webViewPackage: String?,
    val webViewVersion: String?,
)

object StmDiagnosticReportFormatter {
    fun format(
        generatedAtEpochMs: Long,
        environment: StmDiagnosticEnvironment,
        coreState: StmCoreState,
        entries: List<LogEntry>,
        runtimeLogTail: String?,
        runtimeLogTruncated: Boolean,
    ): String = buildString {
        appendLine("STM DIAGNOSTIC REPORT")
        appendLine("Generated UTC: ${Instant.ofEpochMilli(generatedAtEpochMs)}")
        appendLine("Review before sharing: this report may contain local paths and technical details.")
        appendLine()

        appendLine("[Environment]")
        appendLine("App: SillyTavern Manager ${environment.appVersion}")
        appendLine("Android: ${environment.androidVersion} (API ${environment.sdkInt})")
        appendLine("Device: ${environment.manufacturer} ${environment.model}")
        appendLine("ABIs: ${environment.supportedAbis.joinToString().ifBlank { "unknown" }}")
        appendLine(
            "WebView: " +
                listOfNotNull(environment.webViewPackage, environment.webViewVersion)
                    .joinToString(" ")
                    .ifBlank { "unknown" },
        )
        appendLine()

        appendLine("[STM Core]")
        appendLine("Protocol: ${coreState.protocolVersion}")
        appendLine("Core version: ${coreState.coreVersion}")
        appendLine("Node: ${coreState.nodeVersion ?: "unknown"}")
        appendLine("Revision: ${coreState.revision}")
        appendLine("Run state: ${coreState.runState}")
        appendLine("Workload: ${coreState.workload}")
        appendLine("Installer recovery complete: ${coreState.installerRecoveryComplete}")
        appendLine("Process ID: ${coreState.processId ?: "none"}")
        appendLine("Session ID: ${coreState.sessionId ?: "none"}")
        appendLine("Loopback: ${coreState.localBaseUrl ?: "none"}")
        appendLine("Summary: ${sanitize(coreState.summary ?: "none")}")
        appendLine(
            "Active slot: " +
                (coreState.activeSlot?.let {
                    "${it.slotId} slotRevision=${it.slotRevision} activeRevision=${it.activeRevision}"
                } ?: "none"),
        )
        appendLine(
            "Running slot: " +
                (coreState.runningSlot?.let {
                    "${it.slotId} slotRevision=${it.slotRevision} activeRevision=${it.activeRevision}"
                } ?: "none"),
        )
        appendStructuredError("Core error", coreState.error)
        appendLine()

        appendLine("[Slots]")
        if (coreState.slots.isEmpty()) {
            appendLine("none")
        } else {
            coreState.slots.sortedBy { it.id }.forEach { slot ->
                appendLine(
                    "- ${slot.id}: state=${slot.state} revision=${slot.revision} " +
                        "kind=${slot.artifact?.kind ?: "none"} " +
                        "stVersion=${slot.artifact?.stVersion ?: "none"} " +
                        "commit=${slot.commitSha ?: "none"} " +
                        "manifest=${slot.manifestSha256 ?: "none"}",
                )
            }
        }
        appendLine()

        appendLine("[Core jobs]")
        if (coreState.jobs.isEmpty()) {
            appendLine("none")
        } else {
            coreState.jobs.sortedBy { it.startedAtEpochMs }.forEach { job ->
                appendLine(
                    "- ${job.operationId}: type=${job.type} target=${job.targetId} " +
                        "phase=${job.phase} state=${job.state} progress=${job.progress ?: "none"}",
                )
                appendStructuredError("  job error", job.error)
            }
        }
        appendLine()

        appendLine("[STM app events]")
        if (entries.isEmpty()) {
            appendLine("none")
        } else {
            entries.sortedBy(LogEntry::sequence).forEach { entry ->
                appendLine(
                    "${Instant.ofEpochMilli(entry.timestampMillis)} " +
                        "#${entry.sequence} ${entry.source}/${entry.level}: " +
                        sanitize(entry.message),
                )
            }
        }
        appendLine()

        appendLine("[SillyTavern Node log tail]")
        if (runtimeLogTail == null) {
            appendLine("not available")
        } else {
            if (runtimeLogTruncated) appendLine("[older bytes omitted]")
            append(sanitize(runtimeLogTail))
            if (!runtimeLogTail.endsWith('\n')) appendLine()
        }
    }

    private fun StringBuilder.appendStructuredError(
        label: String,
        error: io.github.styx798.sillytavernmanager.stmcore.StmCoreError?,
    ) {
        if (error == null) {
            appendLine("$label: none")
            return
        }
        appendLine("$label: ${error.domain}/${error.code}: ${sanitize(error.summary)}")
        error.diagnosticDetail?.takeIf(String::isNotBlank)?.let {
            appendLine("$label detail: ${sanitize(it)}")
        }
    }

    internal fun sanitize(value: String): String = SECRET_VALUE.replace(
        AUTHORIZATION_VALUE.replace(value) { match ->
            match.groupValues[1] + "[REDACTED]"
        },
    ) { match ->
        match.groupValues[1] + "[REDACTED]"
    }

    private val AUTHORIZATION_VALUE = Regex(
        """(?i)(authorization["']?\s*[:=]\s*["']?)(?:bearer\s+)?[^"'\s,;}]+""",
    )
    private val SECRET_VALUE = Regex(
        """(?i)((?:api[_-]?key|token|password|secret)["']?\s*[:=]\s*["']?)[^"'\s,;}]+""",
    )
}
