package io.github.styx798.sillytavernmanager.data.logging

import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.core.content.pm.PackageInfoCompat
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExportResult
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExporter
import io.github.styx798.sillytavernmanager.core.logging.LogEntry
import io.github.styx798.sillytavernmanager.core.logging.StmDiagnosticEnvironment
import io.github.styx798.sillytavernmanager.core.logging.StmDiagnosticReportFormatter
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreConnectionState
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption

class AndroidDiagnosticLogExporter(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiagnosticLogExporter {
    private val appContext = context.applicationContext

    override suspend fun export(
        destination: android.net.Uri,
        coreState: StmCoreState,
        coreConnectionState: StmCoreConnectionState,
        entries: List<LogEntry>,
    ): DiagnosticLogExportResult = runCatching {
        val runtimeLog = readRuntimeLogTail()
        val report = StmDiagnosticReportFormatter.format(
            generatedAtEpochMs = clock(),
            environment = readEnvironment(),
            coreState = coreState,
            coreConnectionState = coreConnectionState,
            entries = entries,
            runtimeLogTail = runtimeLog?.text,
            runtimeLogTruncated = runtimeLog?.truncated == true,
        )
        val resolver = appContext.contentResolver
        resolver.openOutputStream(destination, "w")?.use { output ->
            output.write(report.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } ?: error("The selected document provider did not open an output stream")
    }.fold(
        onSuccess = { DiagnosticLogExportResult.Success },
        onFailure = { error ->
            DiagnosticLogExportResult.Failure(
                diagnosticDetail = error.stackTraceToString().take(MAX_FAILURE_DETAIL_CHARS),
            )
        },
    )

    private fun readEnvironment(): StmDiagnosticEnvironment {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val appVersion = buildString {
            append(packageInfo.versionName ?: "unknown")
            append(" (")
            append(PackageInfoCompat.getLongVersionCode(packageInfo))
            append(')')
        }
        val webView = WebView.getCurrentWebViewPackage()
        return StmDiagnosticEnvironment(
            appVersion = appVersion,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            webViewPackage = webView?.packageName,
            webViewVersion = webView?.versionName,
        )
    }

    private fun readRuntimeLogTail(): RuntimeLogTail? {
        val logFile = StmCorePaths.logsRoot(appContext)
            .resolve(SILLY_TAVERN_NODE_LOG)
            .toPath()
        if (!Files.isRegularFile(logFile, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(logFile)
        ) {
            return null
        }
        val size = Files.size(logFile)
        val bytesToRead = minOf(size, MAX_RUNTIME_LOG_EXPORT_BYTES).toInt()
        val bytes = ByteArray(bytesToRead)
        RandomAccessFile(logFile.toFile(), "r").use { file ->
            file.seek(size - bytesToRead)
            file.readFully(bytes)
        }
        return RuntimeLogTail(
            text = String(bytes, StandardCharsets.UTF_8),
            truncated = size > bytesToRead,
        )
    }

    private data class RuntimeLogTail(
        val text: String,
        val truncated: Boolean,
    )

    private companion object {
        const val SILLY_TAVERN_NODE_LOG = "sillytavern-node.log"
        const val MAX_RUNTIME_LOG_EXPORT_BYTES = 512_000L
        const val MAX_FAILURE_DETAIL_CHARS = 8_000
    }
}
