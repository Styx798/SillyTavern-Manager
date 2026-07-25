package io.github.styx798.sillytavernmanager.core.downloads

enum class StDownloadChannel(
    val branch: String,
    val legacyFileName: String,
) {
    STABLE(
        branch = "release",
        legacyFileName = "sillytavern-release.zip",
    ),
    PREVIEW(
        branch = "staging",
        legacyFileName = "sillytavern-staging.zip",
    ),
    ;

    val commitApiUrl: String
        get() = "$GITHUB_API_REPOSITORY_URL/commits/$branch"

    fun exactArchiveUrl(commitSha: String): String {
        val exactCommit = requireExactCommitSha(commitSha)
        return "$GITHUB_REPOSITORY_URL/archive/$exactCommit.zip"
    }

    fun exactArchiveFileName(commitSha: String): String {
        val exactCommit = requireExactCommitSha(commitSha)
        return "sillytavern-$branch-$exactCommit.zip"
    }

    companion object {
        const val REPOSITORY = "SillyTavern/SillyTavern"
        const val GITHUB_REPOSITORY_URL = "https://github.com/$REPOSITORY"
        const val GITHUB_API_REPOSITORY_URL = "https://api.github.com/repos/$REPOSITORY"
    }
}

enum class StDownloadPhase {
    RESOLVING,
    DOWNLOADING,
    RECORDING_CONTENT_HASH,
}

data class ActiveStDownload(
    val channel: StDownloadChannel,
    val phase: StDownloadPhase = StDownloadPhase.DOWNLOADING,
    val exactCommit: String? = null,
    val archiveUrl: String? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
) {
    val progress: Float?
        get() = totalBytes
            ?.takeIf { it > 0 }
            ?.let { total -> (bytesDownloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) }
}

enum class StArchiveIdentityClassification {
    EXACT_COMMIT,
    LEGACY_UNIDENTIFIED,
}

data class StArchiveIdentity(
    val classification: StArchiveIdentityClassification,
    val repository: String = StDownloadChannel.REPOSITORY,
    val channelRef: String,
    val exactCommit: String? = null,
    val archiveUrl: String? = null,
)

enum class StArchiveIntegrityClassification {
    CONTENT_SHA256_RECORDED,
    LEGACY_UNVERIFIED,
}

data class StArchiveIntegrity(
    val classification: StArchiveIntegrityClassification,
    val byteLength: Long,
    val sha256: String? = null,
    val hasZipFormatHint: Boolean,
)

enum class StArchiveTrust {
    DEGRADED_UNSIGNED_CATALOG,
    UNTRUSTED_LEGACY,
}

data class DownloadedStArchive(
    val channel: StDownloadChannel,
    val fileName: String,
    val sizeBytes: Long,
    val downloadedAtEpochMillis: Long? = null,
    val identity: StArchiveIdentity = StArchiveIdentity(
        classification = StArchiveIdentityClassification.LEGACY_UNIDENTIFIED,
        channelRef = channel.branch,
    ),
    val integrity: StArchiveIntegrity = StArchiveIntegrity(
        classification = StArchiveIntegrityClassification.LEGACY_UNVERIFIED,
        byteLength = sizeBytes,
        hasZipFormatHint = false,
    ),
    val trust: StArchiveTrust = StArchiveTrust.UNTRUSTED_LEGACY,
)

enum class StDownloadFailureReason {
    ALREADY_DOWNLOADED,
    DOWNLOAD_MANAGER_UNAVAILABLE,
    VERSION_RESOLUTION_FAILED,
    DOWNLOAD_FAILED,
    INVALID_ARCHIVE,
    STORAGE_UNAVAILABLE,
}

data class StDownloadFailure(
    val channel: StDownloadChannel?,
    val reason: StDownloadFailureReason,
    val detailCode: Int? = null,
)

data class StDownloadState(
    val active: ActiveStDownload? = null,
    val archives: List<DownloadedStArchive> = emptyList(),
    val failure: StDownloadFailure? = null,
)

fun requireExactCommitSha(value: String): String {
    require(value.length == SHA1_HEX_LENGTH || value.length == SHA256_HEX_LENGTH) {
        "An exact Git commit must contain 40 or 64 hexadecimal characters."
    }
    require(value.all { character -> character.isAsciiHexDigit() }) {
        "An exact Git commit must contain only hexadecimal characters."
    }
    return value.lowercase()
}

private fun Char.isAsciiHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val SHA1_HEX_LENGTH = 40
private const val SHA256_HEX_LENGTH = 64
