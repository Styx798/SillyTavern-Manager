package io.github.styx798.sillytavernmanager.data.logging

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSillyTavernLogReaderTest {
    @Test
    fun `missing runtime log is unavailable`() {
        val root = Files.createTempDirectory("stm-log-missing")

        val snapshot = readSillyTavernLogTail(root.resolve("missing.log"), 7)

        assertFalse(snapshot.available)
        assertEquals(7L, snapshot.readAtEpochMs)
    }

    @Test
    fun `tail keeps bounded complete lines`() {
        val root = Files.createTempDirectory("stm-log-tail")
        val log = root.resolve("sillytavern-node.log")
        Files.write(log, "first\nsecond\nthird\n".toByteArray())

        val snapshot = readSillyTavernLogTail(
            logFile = log,
            readAtEpochMs = 9,
            maxBytes = 1_000,
            maxLines = 2,
        )

        assertTrue(snapshot.available)
        assertTrue(snapshot.truncated)
        assertEquals(listOf("second", "third"), snapshot.lines)
    }

    @Test
    fun `byte truncation drops an incomplete first line`() {
        val root = Files.createTempDirectory("stm-log-byte-tail")
        val log = root.resolve("sillytavern-node.log")
        Files.write(log, "long-first-line\nsecond\nthird\n".toByteArray())

        val snapshot = readSillyTavernLogTail(
            logFile = log,
            readAtEpochMs = 11,
            maxBytes = 15,
            maxLines = 10,
        )

        assertTrue(snapshot.truncated)
        assertEquals(listOf("second", "third"), snapshot.lines)
    }
}
