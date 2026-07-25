package io.github.styx798.sillytavernmanager.data.downloads

import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveTrust
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedArchiveInspectionTest {
    @Test
    fun `records actual streamed length and SHA-256`() {
        withTemporaryFile("abc".encodeToByteArray()) { file ->
            val content = recordArchiveContent(file)
            assertEquals(3L, content.byteLength)
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                content.sha256,
            )
        }
    }

    @Test
    fun `recorded exact archive remains degraded without a signed catalog`() {
        withTemporaryDirectory { directory ->
            val commit = "a".repeat(40)
            val resolved = ResolvedStDownload(
                channel = StDownloadChannel.STABLE,
                exactCommit = commit,
                archiveUrl = StDownloadChannel.STABLE.exactArchiveUrl(commit),
                fileName = StDownloadChannel.STABLE.exactArchiveFileName(commit),
                resolvedAtEpochMillis = 100L,
            )
            val file = File(directory, resolved.fileName)
            file.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00))
            val content = recordArchiveContent(file)

            val archive = requireNotNull(
                describeRecordedExactArchive(
                    file = file,
                    resolved = resolved,
                    content = content,
                    downloadedAtEpochMillis = 200L,
                ),
            )
            assertEquals(
                StArchiveIdentityClassification.EXACT_COMMIT,
                archive.identity.classification,
            )
            assertEquals(commit, archive.identity.exactCommit)
            assertEquals(
                StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED,
                archive.integrity.classification,
            )
            assertEquals(content.sha256, archive.integrity.sha256)
            assertEquals(StArchiveTrust.DEGRADED_UNSIGNED_CATALOG, archive.trust)
        }
    }

    @Test
    fun `historical fixed archive remains legacy unidentified`() {
        withTemporaryDirectory { directory ->
            val file = File(directory, StDownloadChannel.STABLE.legacyFileName)
            file.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00))

            val archive = requireNotNull(describeLegacyUnidentifiedArchive(file))
            assertEquals(
                StArchiveIdentityClassification.LEGACY_UNIDENTIFIED,
                archive.identity.classification,
            )
            assertNull(archive.identity.exactCommit)
            assertNull(archive.identity.archiveUrl)
            assertEquals(
                StArchiveIntegrityClassification.LEGACY_UNVERIFIED,
                archive.integrity.classification,
            )
            assertNull(archive.integrity.sha256)
            assertTrue(archive.integrity.hasZipFormatHint)
            assertEquals(StArchiveTrust.UNTRUSTED_LEGACY, archive.trust)
        }
    }

    @Test
    fun `commit-looking orphan is not granted exact identity from its file name`() {
        withTemporaryDirectory { directory ->
            val commit = "a".repeat(40)
            val file = File(directory, "sillytavern-staging-$commit.zip")
            file.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))

            val archive = requireNotNull(describeLegacyUnidentifiedArchive(file))
            assertEquals(StDownloadChannel.PREVIEW, archive.channel)
            assertEquals(
                StArchiveIdentityClassification.LEGACY_UNIDENTIFIED,
                archive.identity.classification,
            )
            assertNull(archive.identity.exactCommit)
        }
    }

    @Test
    fun `owned DownloadManager row restores exact identity for its canonical destination`() {
        withTemporaryDirectory { directory ->
            val commit = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
            StDownloadChannel.entries.forEach { channel ->
                val target = File(directory, channel.exactArchiveFileName(commit))
                val resolved = requireNotNull(
                    recoverResolvedDownloadFromOwnedRow(
                        remoteUri = channel.exactArchiveUrl(commit),
                        localUri = target.toURI().toString(),
                        downloadDirectory = directory,
                        resolvedAtEpochMillis = 123L,
                    ),
                )

                assertEquals(channel, resolved.channel)
                assertEquals(commit, resolved.exactCommit)
                assertEquals(channel.exactArchiveUrl(commit), resolved.archiveUrl)
                assertEquals(channel.exactArchiveFileName(commit), resolved.fileName)
                assertEquals(123L, resolved.resolvedAtEpochMillis)
            }
        }
    }

    @Test
    fun `file name alone cannot restore exact identity without the matching owned row`() {
        withTemporaryDirectory { directory ->
            val commit = "a".repeat(40)
            val target = File(directory, StDownloadChannel.STABLE.exactArchiveFileName(commit))

            assertNull(
                recoverResolvedDownloadFromOwnedRow(
                    remoteUri = "https://example.com/archive/$commit.zip",
                    localUri = target.toURI().toString(),
                    downloadDirectory = directory,
                    resolvedAtEpochMillis = 123L,
                ),
            )
            assertNull(
                recoverResolvedDownloadFromOwnedRow(
                    remoteUri = StDownloadChannel.STABLE.exactArchiveUrl(commit),
                    localUri = File(
                        directory.parentFile,
                        StDownloadChannel.STABLE.exactArchiveFileName(commit),
                    ).toURI().toString(),
                    downloadDirectory = directory,
                    resolvedAtEpochMillis = 123L,
                ),
            )
            assertNull(
                recoverResolvedDownloadFromOwnedRow(
                    remoteUri = StDownloadChannel.STABLE.exactArchiveUrl(commit),
                    localUri = target.toURI().toString(),
                    downloadDirectory = directory,
                    resolvedAtEpochMillis = 0L,
                ),
            )
        }
    }

    @Test
    fun `unowned file names are ignored`() {
        withTemporaryFile(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) { file ->
            assertNull(describeLegacyUnidentifiedArchive(file))
        }
    }

    private fun withTemporaryFile(bytes: ByteArray, assertion: (File) -> Unit) {
        val file = File.createTempFile("stm-content-record-", ".bin")
        try {
            file.writeBytes(bytes)
            assertion(file)
        } finally {
            file.delete()
        }
    }

    private fun withTemporaryDirectory(assertion: (File) -> Unit) {
        val directory = createTempDirectory("stm-legacy-archive-").toFile()
        try {
            assertion(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
