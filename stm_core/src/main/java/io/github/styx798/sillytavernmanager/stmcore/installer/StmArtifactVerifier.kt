package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Locale

/**
 * The kind is part of the signed artifact identity. A legacy archive deliberately cannot become a
 * reproducible identity by attaching the repository's current branch head after it was downloaded.
 */
enum class ArtifactKind {
    /** Reserved for the official SillyTavern GitHub source archive. */
    UPSTREAM_SOURCE_ARCHIVE,
    SYNTHETIC_TEST_ARCHIVE,
    LEGACY_UNIDENTIFIED_ARCHIVE,
}

data class ArtifactIdentity(
    val repository: String,
    val commitSha: String,
    val archiveSha256: String,
    val archiveLength: Long,
    val downloadUrl: String,
    val catalogVersion: String? = null,
    val kind: ArtifactKind,
)

enum class ArtifactIdentityErrorCode {
    INVALID_REPOSITORY,
    INVALID_COMMIT_SHA,
    INVALID_ARCHIVE_SHA256,
    INVALID_ARCHIVE_LENGTH,
    INVALID_DOWNLOAD_URL,
    URL_NOT_BOUND_TO_EXACT_COMMIT,
    INVALID_CATALOG_VERSION,
    LEGACY_IDENTITY_FORBIDDEN,
}

sealed interface ArtifactIdentityValidation {
    data object Valid : ArtifactIdentityValidation

    data class Invalid(
        val code: ArtifactIdentityErrorCode,
        val detail: String,
    ) : ArtifactIdentityValidation
}

enum class ArtifactIntegrityErrorCode {
    INVALID_IDENTITY,
    TARGET_PARENT_UNAVAILABLE,
    TARGET_ALREADY_EXISTS,
    ARCHIVE_EXCEEDS_POLICY,
    LENGTH_EXCEEDED,
    LENGTH_MISMATCH,
    SHA256_MISMATCH,
    IO_ERROR,
}

sealed interface ArtifactIntegrityResult {
    data class Verified(
        val protectedTemporaryFile: File,
        val archiveLength: Long,
        val archiveSha256: String,
    ) : ArtifactIntegrityResult

    data class Rejected(
        val code: ArtifactIntegrityErrorCode,
        val detail: String,
        val identityError: ArtifactIdentityErrorCode? = null,
        val observedLength: Long? = null,
        val observedSha256: String? = null,
        val partialFileRemoved: Boolean = true,
    ) : ArtifactIntegrityResult
}

enum class ArtifactTrustDecision {
    VERIFIED,
    DEGRADED_UNSIGNED_CATALOG,
    REJECTED,
}

enum class ArtifactTrustErrorCode {
    INVALID_IDENTITY,
    INVALID_SIGNATURE_FORMAT,
    SIGNATURE_MISMATCH,
    CRYPTOGRAPHIC_FAILURE,
}

sealed interface ArtifactTrustResult {
    val decision: ArtifactTrustDecision

    data class Verified(
        val catalogVersion: String,
        val canonicalSha256: String,
    ) : ArtifactTrustResult {
        override val decision: ArtifactTrustDecision = ArtifactTrustDecision.VERIFIED
    }

    data class DegradedUnsignedCatalog(
        val detail: String,
    ) : ArtifactTrustResult {
        override val decision: ArtifactTrustDecision =
            ArtifactTrustDecision.DEGRADED_UNSIGNED_CATALOG
    }

    data class Rejected(
        val code: ArtifactTrustErrorCode,
        val detail: String,
        val identityError: ArtifactIdentityErrorCode? = null,
    ) : ArtifactTrustResult {
        override val decision: ArtifactTrustDecision = ArtifactTrustDecision.REJECTED
    }
}

/**
 * Validates a reproducible artifact identity, copies bytes into a new Core-owned temporary file,
 * and verifies detached Ed25519 catalog signatures. Identity, integrity, and trust remain separate
 * results: a matching SHA-256 never upgrades an unsigned catalog to trusted.
 *
 * [verifyAndCopy] requires a target that does not exist inside a caller-controlled protected
 * directory. It atomically creates that target, hashes the same bytes it writes, forces them to
 * storage, and removes the partial file on rejection. The caller may atomically rename a successful
 * temporary file only after receiving [ArtifactIntegrityResult.Verified].
 */
class StmArtifactVerifier(
    private val maximumArchiveLength: Long = DEFAULT_MAXIMUM_ARCHIVE_LENGTH,
) {
    init {
        require(maximumArchiveLength > 0) { "Maximum archive length must be positive" }
    }

    fun validateIdentity(identity: ArtifactIdentity): ArtifactIdentityValidation {
        if (identity.kind == ArtifactKind.LEGACY_UNIDENTIFIED_ARCHIVE) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.LEGACY_IDENTITY_FORBIDDEN,
                "A legacy unidentified archive cannot be assigned a current commit SHA",
            )
        }
        val repositoryUri = parseHttpsUri(identity.repository, MAX_REPOSITORY_LENGTH)
        if (repositoryUri == null) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.INVALID_REPOSITORY,
                "The repository must be a bounded HTTPS URI without credentials, query, or fragment",
            )
        }
        if (identity.kind == ArtifactKind.UPSTREAM_SOURCE_ARCHIVE &&
            !repositoryUri.isOfficialSillyTavernRepository()
        ) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.INVALID_REPOSITORY,
                "A SillyTavern source artifact must identify the official GitHub owner and repository",
            )
        }
        if (!GIT_COMMIT_PATTERN.matches(identity.commitSha)) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.INVALID_COMMIT_SHA,
                "The commit identity must be exactly 40 or 64 hexadecimal characters",
            )
        }
        if (!SHA256_PATTERN.matches(identity.archiveSha256)) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.INVALID_ARCHIVE_SHA256,
                "The archive SHA-256 must be exactly 64 hexadecimal characters",
            )
        }
        if (identity.archiveLength <= 0) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.INVALID_ARCHIVE_LENGTH,
                "The expected archive length must be positive",
            )
        }
        val downloadUri = parseHttpsUri(identity.downloadUrl, MAX_DOWNLOAD_URL_LENGTH)
            ?: return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.INVALID_DOWNLOAD_URL,
                "The download URL must be a bounded HTTPS URI without credentials, query, or fragment",
            )
        val downloadUrlIsBound = when (identity.kind) {
            ArtifactKind.UPSTREAM_SOURCE_ARCHIVE ->
                downloadUri.isOfficialSillyTavernCommitArchive(identity.commitSha)

            ArtifactKind.SYNTHETIC_TEST_ARCHIVE ->
                downloadUri.pathBindsExactCommit(identity.commitSha)

            ArtifactKind.LEGACY_UNIDENTIFIED_ARCHIVE -> false
        }
        if (!downloadUrlIsBound) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.URL_NOT_BOUND_TO_EXACT_COMMIT,
                if (identity.kind == ArtifactKind.UPSTREAM_SOURCE_ARCHIVE) {
                    "A SillyTavern source download must use the official GitHub exact-commit ZIP template"
                } else {
                    "The download URL path must contain the exact commit, not a branch, tag, or latest ref"
                },
            )
        }
        if (identity.catalogVersion != null &&
            !CATALOG_VERSION_PATTERN.matches(identity.catalogVersion)
        ) {
            return ArtifactIdentityValidation.Invalid(
                ArtifactIdentityErrorCode.INVALID_CATALOG_VERSION,
                "The catalog version contains unsupported characters or is too long",
            )
        }
        return ArtifactIdentityValidation.Valid
    }

    fun verifyAndCopy(
        identity: ArtifactIdentity,
        source: InputStream,
        protectedTemporaryFile: File,
    ): ArtifactIntegrityResult {
        when (val validation = validateIdentity(identity)) {
            ArtifactIdentityValidation.Valid -> Unit
            is ArtifactIdentityValidation.Invalid -> return ArtifactIntegrityResult.Rejected(
                code = ArtifactIntegrityErrorCode.INVALID_IDENTITY,
                detail = validation.detail,
                identityError = validation.code,
            )
        }
        if (identity.archiveLength > maximumArchiveLength) {
            return ArtifactIntegrityResult.Rejected(
                code = ArtifactIntegrityErrorCode.ARCHIVE_EXCEEDS_POLICY,
                detail = "Expected archive length exceeds the configured policy limit",
            )
        }

        val target = protectedTemporaryFile.toPath()
        val parent = target.parent
        if (parent == null ||
            !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(parent)
        ) {
            return ArtifactIntegrityResult.Rejected(
                code = ArtifactIntegrityErrorCode.TARGET_PARENT_UNAVAILABLE,
                detail = "The protected temporary file parent must be an existing real directory",
            )
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return ArtifactIntegrityResult.Rejected(
                code = ArtifactIntegrityErrorCode.TARGET_ALREADY_EXISTS,
                detail = "The protected temporary file must not already exist",
            )
        }

        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var observedLength = 0L
        var excessLength: Long? = null
        var targetCreated = false

        try {
            FileChannel.open(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                targetCreated = true
                while (true) {
                    val count = source.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    if (count.toLong() > identity.archiveLength - observedLength) {
                        excessLength = observedLength + count
                        break
                    }
                    digest.update(buffer, 0, count)
                    writeFully(output, buffer, count)
                    observedLength += count
                }
                if (excessLength == null) output.force(true)
            }
        } catch (error: Exception) {
            val removed = !targetCreated || runCatching { Files.deleteIfExists(target) }.isSuccess
            return ArtifactIntegrityResult.Rejected(
                code = ArtifactIntegrityErrorCode.IO_ERROR,
                detail = error.safeDetail(),
                observedLength = observedLength,
                partialFileRemoved = removed,
            )
        }

        excessLength?.let { length ->
            return rejectAndRemove(
                target = target,
                code = ArtifactIntegrityErrorCode.LENGTH_EXCEEDED,
                detail = "The archive contains more bytes than its declared identity",
                observedLength = length,
            )
        }

        val observedHashBytes = digest.digest()
        val observedHash = observedHashBytes.toHex()
        if (observedLength != identity.archiveLength) {
            return rejectAndRemove(
                target = target,
                code = ArtifactIntegrityErrorCode.LENGTH_MISMATCH,
                detail = "The archive ended before its declared length",
                observedLength = observedLength,
                observedSha256 = observedHash,
            )
        }
        val expectedHashBytes = identity.archiveSha256.hexToBytes()
        if (!MessageDigest.isEqual(expectedHashBytes, observedHashBytes)) {
            return rejectAndRemove(
                target = target,
                code = ArtifactIntegrityErrorCode.SHA256_MISMATCH,
                detail = "The archive SHA-256 does not match its declared identity",
                observedLength = observedLength,
                observedSha256 = observedHash,
            )
        }
        return ArtifactIntegrityResult.Verified(
            protectedTemporaryFile = protectedTemporaryFile,
            archiveLength = observedLength,
            archiveSha256 = observedHash,
        )
    }

    fun verifyTrust(
        identity: ArtifactIdentity,
        detachedSignature: ByteArray?,
        trustedCatalogPublicKey: PublicKey?,
    ): ArtifactTrustResult {
        when (val validation = validateIdentity(identity)) {
            ArtifactIdentityValidation.Valid -> Unit
            is ArtifactIdentityValidation.Invalid -> return ArtifactTrustResult.Rejected(
                code = ArtifactTrustErrorCode.INVALID_IDENTITY,
                detail = validation.detail,
                identityError = validation.code,
            )
        }
        if (identity.catalogVersion == null ||
            detachedSignature == null ||
            trustedCatalogPublicKey == null
        ) {
            return ArtifactTrustResult.DegradedUnsignedCatalog(
                "A catalog version, detached signature, and trusted public key are all required",
            )
        }
        if (detachedSignature.size != ED25519_SIGNATURE_LENGTH) {
            return ArtifactTrustResult.Rejected(
                code = ArtifactTrustErrorCode.INVALID_SIGNATURE_FORMAT,
                detail = "An Ed25519 detached signature must be exactly 64 bytes",
            )
        }

        val canonical = canonicalBytes(identity)
        return try {
            if (
                StmEd25519Verifier.verify(
                    trustedCatalogPublicKey,
                    canonical,
                    detachedSignature,
                )
            ) {
                ArtifactTrustResult.Verified(
                    catalogVersion = identity.catalogVersion,
                    canonicalSha256 = MessageDigest.getInstance(SHA256_ALGORITHM)
                        .digest(canonical)
                        .toHex(),
                )
            } else {
                ArtifactTrustResult.Rejected(
                    code = ArtifactTrustErrorCode.SIGNATURE_MISMATCH,
                    detail = "The detached catalog signature did not match the canonical identity",
                )
            }
        } catch (error: Exception) {
            ArtifactTrustResult.Rejected(
                code = ArtifactTrustErrorCode.CRYPTOGRAPHIC_FAILURE,
                detail = error.safeDetail(),
            )
        }
    }

    /** Fixed-order, length-prefixed bytes avoid JSON ordering and separator ambiguity. */
    fun canonicalBytes(identity: ArtifactIdentity): ByteArray {
        val validation = validateIdentity(identity)
        require(validation == ArtifactIdentityValidation.Valid) {
            (validation as ArtifactIdentityValidation.Invalid).detail
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeCanonicalString(CANONICAL_MAGIC)
                output.writeInt(CANONICAL_FORMAT_VERSION)
                output.writeCanonicalString(identity.kind.name)
                output.writeCanonicalString(identity.repository)
                output.writeCanonicalString(identity.commitSha.lowercase(Locale.ROOT))
                output.writeCanonicalString(identity.archiveSha256.lowercase(Locale.ROOT))
                output.writeLong(identity.archiveLength)
                output.writeCanonicalString(identity.downloadUrl)
                output.writeCanonicalString(identity.catalogVersion.orEmpty())
                output.flush()
            }
            bytes.toByteArray()
        }
    }

    private fun rejectAndRemove(
        target: java.nio.file.Path,
        code: ArtifactIntegrityErrorCode,
        detail: String,
        observedLength: Long,
        observedSha256: String? = null,
    ): ArtifactIntegrityResult.Rejected {
        val removed = runCatching { Files.deleteIfExists(target) }.isSuccess
        return ArtifactIntegrityResult.Rejected(
            code = code,
            detail = detail,
            observedLength = observedLength,
            observedSha256 = observedSha256,
            partialFileRemoved = removed,
        )
    }

    private fun writeFully(channel: FileChannel, source: ByteArray, length: Int) {
        val buffer = ByteBuffer.wrap(source, 0, length)
        while (buffer.hasRemaining()) {
            check(channel.write(buffer) > 0) { "The protected archive target stopped accepting bytes" }
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_ARCHIVE_LENGTH: Long = 512L * 1024L * 1024L

        private const val MAX_REPOSITORY_LENGTH = 2_048
        private const val MAX_DOWNLOAD_URL_LENGTH = 4_096
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val ED25519_SIGNATURE_LENGTH = 64
        private const val SHA256_ALGORITHM = "SHA-256"
        private const val CANONICAL_MAGIC = "STM_ARTIFACT_IDENTITY"
        private const val CANONICAL_FORMAT_VERSION = 1
        private val GIT_COMMIT_PATTERN = Regex("^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        private val CATALOG_VERSION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

private const val OFFICIAL_GITHUB_HOST = "github.com"
private const val OFFICIAL_SILLY_TAVERN_REPOSITORY_PATH = "/SillyTavern/SillyTavern"

private fun parseHttpsUri(value: String, maximumLength: Int): URI? {
    if (value.isBlank() || value.length > maximumLength || value != value.trim()) return null
    return runCatching { URI(value) }.getOrNull()?.takeIf { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            !uri.rawPath.isNullOrBlank()
    }
}

private fun URI.isOfficialSillyTavernRepository(): Boolean {
    if (port != -1 || !host.equals(OFFICIAL_GITHUB_HOST, ignoreCase = true)) return false
    return rawPath.equals(OFFICIAL_SILLY_TAVERN_REPOSITORY_PATH, ignoreCase = true) ||
        rawPath.equals("$OFFICIAL_SILLY_TAVERN_REPOSITORY_PATH.git", ignoreCase = true)
}

private fun URI.isOfficialSillyTavernCommitArchive(commitSha: String): Boolean {
    if (port != -1 || !host.equals(OFFICIAL_GITHUB_HOST, ignoreCase = true)) return false
    val expectedPath =
        "$OFFICIAL_SILLY_TAVERN_REPOSITORY_PATH/archive/${commitSha.lowercase(Locale.ROOT)}.zip"
    return rawPath.equals(expectedPath, ignoreCase = true)
}

private fun URI.pathBindsExactCommit(commitSha: String): Boolean {
    val raw = rawPath ?: return false
    val loweredPath = raw.lowercase(Locale.ROOT)
    if ("/refs/heads/" in loweredPath || "/refs/tags/" in loweredPath) return false
    val expected = commitSha.lowercase(Locale.ROOT)
    return raw.split('/')
        .filter(String::isNotBlank)
        .any { segment ->
            val lowered = segment.lowercase(Locale.ROOT)
            lowered == expected ||
                lowered.removeSuffix(".zip") == expected ||
                lowered.removeSuffix(".tar.gz") == expected ||
                lowered.removeSuffix(".tgz") == expected
        }
}

private fun DataOutputStream.writeCanonicalString(value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}

private fun Throwable.safeDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)
