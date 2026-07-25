package io.github.styx798.sillytavernmanager.core.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StDownloadChannelTest {
    @Test
    fun `stable and preview use official branch names`() {
        assertEquals("release", StDownloadChannel.STABLE.branch)
        assertEquals("staging", StDownloadChannel.PREVIEW.branch)
    }

    @Test
    fun `legacy channels retain separate historical file names`() {
        assertNotEquals(
            StDownloadChannel.STABLE.legacyFileName,
            StDownloadChannel.PREVIEW.legacyFileName,
        )
    }

    @Test
    fun `archive URL and file name are pinned to an exact commit`() {
        val commit = "0123456789abcdef0123456789abcdef01234567"
        StDownloadChannel.entries.forEach { channel ->
            val url = channel.exactArchiveUrl(commit)
            val fileName = channel.exactArchiveFileName(commit)
            assertEquals(
                "https://github.com/SillyTavern/SillyTavern/archive/$commit.zip",
                url,
            )
            assertEquals("sillytavern-${channel.branch}-$commit.zip", fileName)
            assertFalse(url.contains("refs/heads"))
            assertFalse(url.endsWith("/${channel.branch}.zip"))
        }
    }

    @Test
    fun `accepts exact SHA-1 and SHA-256 object identifiers`() {
        assertEquals("a".repeat(40), requireExactCommitSha("A".repeat(40)))
        assertEquals("b".repeat(64), requireExactCommitSha("b".repeat(64)))
    }

    @Test
    fun `rejects abbreviated and non hexadecimal commit identifiers`() {
        listOf(
            "a".repeat(39),
            "a".repeat(41),
            "g".repeat(40),
            "../" + "a".repeat(40),
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                requireExactCommitSha(value)
            }
        }
    }

    @Test
    fun `commit API endpoint uses HTTPS and the named channel ref`() {
        StDownloadChannel.entries.forEach { channel ->
            assertTrue(channel.commitApiUrl.startsWith("https://api.github.com/"))
            assertTrue(channel.commitApiUrl.endsWith("/commits/${channel.branch}"))
        }
    }
}
