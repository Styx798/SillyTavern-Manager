package io.github.styx798.sillytavernmanager.data.downloads

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFormatHintTest {
    @Test
    fun `recognizes standard and empty ZIP format hints`() {
        withTemporaryFile(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)) { file ->
            assertTrue(hasZipFormatHint(file))
        }
        withTemporaryFile(byteArrayOf(0x50, 0x4B, 0x05, 0x06)) { file ->
            assertTrue(hasZipFormatHint(file))
        }
    }

    @Test
    fun `does not mistake HTML or a truncated PK prefix for a ZIP hint`() {
        withTemporaryFile("<!doctype html>".encodeToByteArray()) { file ->
            assertFalse(hasZipFormatHint(file))
        }
        withTemporaryFile(byteArrayOf(0x50, 0x4B)) { file ->
            assertFalse(hasZipFormatHint(file))
        }
    }

    private fun withTemporaryFile(
        bytes: ByteArray,
        assertion: (File) -> Unit,
    ) {
        val file = File.createTempFile("stm-download-test-", ".bin")
        try {
            file.writeBytes(bytes)
            assertion(file)
        } finally {
            file.delete()
        }
    }
}
