package io.github.styx798.sillytavernmanager.core.logging

import io.github.styx798.sillytavernmanager.stmcore.StmCoreActiveSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreError
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmDiagnosticReportFormatterTest {
    @Test
    fun `report includes bounded diagnostic context without secret placeholders`() {
        val report = StmDiagnosticReportFormatter.format(
            generatedAtEpochMs = 1_700_000_000_000,
            environment = StmDiagnosticEnvironment(
                appVersion = "0.1 (1)",
                androidVersion = "15",
                sdkInt = 35,
                manufacturer = "Samsung",
                model = "Test Device",
                supportedAbis = listOf("arm64-v8a"),
                webViewPackage = "com.google.android.webview",
                webViewVersion = "149.0",
            ),
            coreConnectionState = StmCoreConnectionState.CONNECTING,
            coreState = StmCoreState(
                revision = 12,
                updatedAtEpochMs = 1_700_000_000_000,
                processIdentity = "test",
                processId = 42,
                installerRecoveryComplete = true,
                runState = StmCoreRunState.CRASHED,
                activeSlot = StmCoreActiveSlot("slot-a", 2, 3),
                summary = "failed safely",
                error = StmCoreError(
                    domain = "runtime",
                    code = "TEST_FAILURE",
                    summary = "Synthetic failure",
                    diagnosticDetail = "stack line",
                ),
            ),
            entries = listOf(
                LogEntry(
                    sequence = 1,
                    timestampMillis = 1_700_000_000_000,
                    source = LogSource.APP,
                    level = LogLevel.ERROR,
                    message = "Export me Authorization: Bearer do-not-export",
                ),
            ),
            runtimeLogTail = "[error] Node failed api_key=do-not-export",
            runtimeLogTruncated = true,
        )

        assertTrue(report.contains("Android: 15 (API 35)"))
        assertTrue(report.contains("WebView: com.google.android.webview 149.0"))
        assertTrue(report.contains("Connection state: CONNECTING"))
        assertTrue(report.contains("Process identity: test"))
        assertTrue(report.contains("Snapshot updated UTC:"))
        assertTrue(report.contains("runtime/TEST_FAILURE: Synthetic failure"))
        assertTrue(report.contains("Active slot: slot-a"))
        assertTrue(report.contains("APP/ERROR: Export me Authorization: [REDACTED]"))
        assertTrue(report.contains("[older bytes omitted]"))
        assertTrue(report.contains("[error] Node failed api_key=[REDACTED]"))
        assertFalse(report.contains("do-not-export"))
        assertFalse(report.contains("API_KEY"))
        assertFalse(report.contains("Bearer do-not-export"))
    }
}
