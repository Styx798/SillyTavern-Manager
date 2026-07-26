package io.github.styx798.sillytavernmanager.data.downloads

import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSupportedVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactStDownloadTest {
    @Test
    fun `supported release download stays pinned to its signed catalog commit`() {
        val resolved = resolveExactStDownload(
            channel = StDownloadChannel.STABLE,
            exactCommit = StmCoreSupportedVersions.SIGNED_STABLE_COMMIT,
            resolvedAtEpochMillis = 7,
        )

        assertEquals(StmCoreSupportedVersions.SIGNED_STABLE_COMMIT, resolved.exactCommit)
        assertEquals(
            StDownloadChannel.STABLE.exactArchiveUrl(
                StmCoreSupportedVersions.SIGNED_STABLE_COMMIT,
            ),
            resolved.archiveUrl,
        )
        assertEquals(
            StDownloadChannel.STABLE.exactArchiveFileName(
                StmCoreSupportedVersions.SIGNED_STABLE_COMMIT,
            ),
            resolved.fileName,
        )
    }

    @Test
    fun `exact download rejects an invalid identity before network work`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveExactStDownload(
                channel = StDownloadChannel.STABLE,
                exactCommit = "release",
                resolvedAtEpochMillis = 7,
            )
        }
    }
}
