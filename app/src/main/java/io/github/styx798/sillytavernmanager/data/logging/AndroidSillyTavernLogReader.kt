package io.github.styx798.sillytavernmanager.data.logging

import android.content.Context
import io.github.styx798.sillytavernmanager.core.logging.SillyTavernLogReader
import io.github.styx798.sillytavernmanager.core.logging.SillyTavernLogSnapshot
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSillyTavernLogReader(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : SillyTavernLogReader {
    private val logFile = StmCorePaths.logsRoot(context.applicationContext)
        .resolve(SILLY_TAVERN_NODE_LOG)
        .toPath()

    override suspend fun readTail(): SillyTavernLogSnapshot = withContext(Dispatchers.IO) {
        readSillyTavernLogTail(logFile, clock())
    }

    private companion object {
        const val SILLY_TAVERN_NODE_LOG = "sillytavern-node.log"
    }
}

internal fun readSillyTavernLogTail(
    logFile: Path,
    readAtEpochMs: Long,
    maxBytes: Long = MAX_ST_LOG_BYTES,
    maxLines: Int = MAX_ST_LOG_LINES,
): SillyTavernLogSnapshot {
    if (!Files.isRegularFile(logFile, LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(logFile)
    ) {
        return SillyTavernLogSnapshot(readAtEpochMs = readAtEpochMs)
    }
    val size = Files.size(logFile)
    val bytesToRead = minOf(size, maxBytes).toInt()
    if (bytesToRead == 0) {
        return SillyTavernLogSnapshot(
            available = true,
            readAtEpochMs = readAtEpochMs,
        )
    }
    val bytes = ByteArray(bytesToRead)
    RandomAccessFile(logFile.toFile(), "r").use { file ->
        file.seek(size - bytesToRead)
        file.readFully(bytes)
    }
    val truncatedByBytes = size > bytesToRead
    val decoded = String(bytes, StandardCharsets.UTF_8)
    val completeText = if (truncatedByBytes) {
        decoded.substringAfter('\n', missingDelimiterValue = "")
    } else {
        decoded
    }
    val allLines = completeText
        .lineSequence()
        .filter(String::isNotBlank)
        .toList()
    val truncatedByLines = allLines.size > maxLines
    return SillyTavernLogSnapshot(
        available = true,
        lines = allLines.takeLast(maxLines),
        truncated = truncatedByBytes || truncatedByLines,
        readAtEpochMs = readAtEpochMs,
    )
}

private const val MAX_ST_LOG_BYTES = 256_000L
private const val MAX_ST_LOG_LINES = 500
