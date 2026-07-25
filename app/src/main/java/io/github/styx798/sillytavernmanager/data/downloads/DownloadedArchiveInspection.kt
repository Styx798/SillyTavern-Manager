package io.github.styx798.sillytavernmanager.data.downloads

import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentity
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrity
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveTrust
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.downloads.requireExactCommitSha
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

internal data class ArchiveContentRecord(
    val byteLength: Long,
    val sha256: String,
)

internal fun recordArchiveContent(file: File): ArchiveContentRecord {
    require(file.isFile) { "Archive content can only be recorded for a regular file." }
    val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
    var byteLength = 0L
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    FileInputStream(file).use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            if (Long.MAX_VALUE - byteLength < read) {
                throw IOException("Archive length overflow while recording SHA-256.")
            }
            digest.update(buffer, 0, read)
            byteLength += read
        }
    }
    return ArchiveContentRecord(
        byteLength = byteLength,
        sha256 = digest.digest().toLowerHex(),
    )
}

internal fun hasZipFormatHint(file: File): Boolean {
    if (!file.isFile || file.length() < ZIP_SIGNATURE_SIZE) return false
    return try {
        val header = ByteArray(ZIP_SIGNATURE_SIZE)
        FileInputStream(file).use { input ->
            if (input.read(header) != ZIP_SIGNATURE_SIZE) return false
        }
        header.contentEquals(ZIP_LOCAL_FILE_HEADER) ||
            header.contentEquals(ZIP_EMPTY_ARCHIVE_HEADER) ||
            header.contentEquals(ZIP_SPANNED_ARCHIVE_HEADER)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun classifyOwnedArchiveFileName(fileName: String): StDownloadChannel? =
    StDownloadChannel.entries.firstOrNull { channel ->
        fileName == channel.legacyFileName ||
            (
                fileName.startsWith("sillytavern-${channel.branch}-") &&
                    fileName.endsWith(ZIP_FILE_EXTENSION)
                )
    }

internal fun recoverResolvedDownloadFromOwnedRow(
    remoteUri: String?,
    localUri: String?,
    downloadDirectory: File?,
    resolvedAtEpochMillis: Long,
): ResolvedStDownload? {
    if (
        remoteUri == null ||
        localUri == null ||
        downloadDirectory == null ||
        resolvedAtEpochMillis <= 0
    ) {
        return null
    }

    val archivePrefix = "${StDownloadChannel.GITHUB_REPOSITORY_URL}/archive/"
    if (!remoteUri.startsWith(archivePrefix) || !remoteUri.endsWith(ZIP_FILE_EXTENSION)) {
        return null
    }
    val commit = remoteUri
        .removePrefix(archivePrefix)
        .removeSuffix(ZIP_FILE_EXTENSION)
        .let { candidate -> runCatching { requireExactCommitSha(candidate) }.getOrNull() }
        ?: return null
    if (remoteUri != StDownloadChannel.STABLE.exactArchiveUrl(commit)) return null

    val localFile = runCatching {
        val uri = URI(localUri)
        if (uri.scheme != FILE_URI_SCHEME || uri.host != null) return null
        File(uri).canonicalFile
    }.getOrNull() ?: return null
    val canonicalDirectory = runCatching { downloadDirectory.canonicalFile }.getOrNull() ?: return null
    val channel = StDownloadChannel.entries.firstOrNull { candidate ->
        val expected = File(canonicalDirectory, candidate.exactArchiveFileName(commit))
        runCatching { expected.canonicalFile == localFile }.getOrDefault(false)
    } ?: return null

    return ResolvedStDownload(
        channel = channel,
        exactCommit = commit,
        archiveUrl = remoteUri,
        fileName = channel.exactArchiveFileName(commit),
        resolvedAtEpochMillis = resolvedAtEpochMillis,
    )
}

internal fun describeLegacyUnidentifiedArchive(file: File): DownloadedStArchive? {
    if (!file.isFile) return null
    val channel = classifyOwnedArchiveFileName(file.name) ?: return null
    val byteLength = file.length()
    return DownloadedStArchive(
        channel = channel,
        fileName = file.name,
        sizeBytes = byteLength,
        identity = StArchiveIdentity(
            classification = StArchiveIdentityClassification.LEGACY_UNIDENTIFIED,
            channelRef = channel.branch,
        ),
        integrity = StArchiveIntegrity(
            classification = StArchiveIntegrityClassification.LEGACY_UNVERIFIED,
            byteLength = byteLength,
            sha256 = null,
            hasZipFormatHint = hasZipFormatHint(file),
        ),
        trust = StArchiveTrust.UNTRUSTED_LEGACY,
    )
}

internal fun describeRecordedExactArchive(
    file: File,
    resolved: ResolvedStDownload,
    content: ArchiveContentRecord,
    downloadedAtEpochMillis: Long,
): DownloadedStArchive? {
    if (
        !file.isFile ||
        file.name != resolved.fileName ||
        file.length() != content.byteLength ||
        !hasZipFormatHint(file)
    ) {
        return null
    }
    require(isLowerSha256(content.sha256)) { "Recorded archive SHA-256 must be canonical." }
    require(downloadedAtEpochMillis > 0) { "Downloaded-at time must be positive." }
    return DownloadedStArchive(
        channel = resolved.channel,
        fileName = file.name,
        sizeBytes = content.byteLength,
        downloadedAtEpochMillis = downloadedAtEpochMillis,
        identity = StArchiveIdentity(
            classification = StArchiveIdentityClassification.EXACT_COMMIT,
            channelRef = resolved.channel.branch,
            exactCommit = resolved.exactCommit,
            archiveUrl = resolved.archiveUrl,
        ),
        integrity = StArchiveIntegrity(
            classification = StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED,
            byteLength = content.byteLength,
            sha256 = content.sha256,
            hasZipFormatHint = true,
        ),
        trust = StArchiveTrust.DEGRADED_UNSIGNED_CATALOG,
    )
}

internal fun isLowerSha256(value: String): Boolean =
    value.length == SHA256_HEX_LENGTH && value.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }

private fun ByteArray.toLowerHex(): String {
    val output = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        output[index * 2] = LOWER_HEX_DIGITS[value ushr 4]
        output[index * 2 + 1] = LOWER_HEX_DIGITS[value and 0x0f]
    }
    return output.concatToString()
}

private const val SHA256_ALGORITHM = "SHA-256"
private const val SHA256_HEX_LENGTH = 64
private const val HASH_BUFFER_BYTES = 32 * 1024
private const val ZIP_SIGNATURE_SIZE = 4
private const val ZIP_FILE_EXTENSION = ".zip"
private const val FILE_URI_SCHEME = "file"
private const val LOWER_HEX_DIGITS = "0123456789abcdef"
private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
private val ZIP_EMPTY_ARCHIVE_HEADER = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
private val ZIP_SPANNED_ARCHIVE_HEADER = byteArrayOf(0x50, 0x4b, 0x07, 0x08)
