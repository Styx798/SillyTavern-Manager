package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmArtifactVerifierTest {
    private val verifier = StmArtifactVerifier(maximumArchiveLength = 1024 * 1024)

    @Test
    fun `accepts exact 40 and 64 hexadecimal commit identities`() {
        listOf("a".repeat(40), "b".repeat(64)).forEach { commit ->
            val identity = identity(
                commitSha = commit,
                downloadUrl = "https://github.com/SillyTavern/SillyTavern/archive/$commit.zip",
            )

            assertEquals(ArtifactIdentityValidation.Valid, verifier.validateIdentity(identity))
        }
    }

    @Test
    fun `accepts official SillyTavern repository with or without git suffix`() {
        listOf(
            "https://github.com/SillyTavern/SillyTavern",
            "https://github.com/SillyTavern/SillyTavern.git",
        ).forEach { repository ->
            assertEquals(
                ArtifactIdentityValidation.Valid,
                verifier.validateIdentity(identity(repository = repository)),
            )
        }
    }

    @Test
    fun `rejects SillyTavern source identity from another host or repository`() {
        listOf(
            "https://example.com/SillyTavern/SillyTavern",
            "https://github.com:443/SillyTavern/SillyTavern",
            "https://github.com/attacker/SillyTavern",
            "https://github.com/SillyTavern/attacker",
        ).forEach { repository ->
            val result = verifier.validateIdentity(identity(repository = repository))

            assertTrue(result is ArtifactIdentityValidation.Invalid)
            result as ArtifactIdentityValidation.Invalid
            assertEquals(ArtifactIdentityErrorCode.INVALID_REPOSITORY, result.code)
        }
    }

    @Test
    fun `rejects exact commit SillyTavern source URLs from another host or repository`() {
        val commit = "1".repeat(40)
        listOf(
            "https://example.com/SillyTavern/SillyTavern/archive/$commit.zip",
            "https://codeload.github.com/SillyTavern/SillyTavern/zip/$commit",
            "https://github.com:443/SillyTavern/SillyTavern/archive/$commit.zip",
            "https://github.com/attacker/SillyTavern/archive/$commit.zip",
            "https://github.com/SillyTavern/attacker/archive/$commit.zip",
        ).forEach { downloadUrl ->
            val result = verifier.validateIdentity(identity(downloadUrl = downloadUrl))

            assertTrue(result is ArtifactIdentityValidation.Invalid)
            result as ArtifactIdentityValidation.Invalid
            assertEquals(
                ArtifactIdentityErrorCode.URL_NOT_BOUND_TO_EXACT_COMMIT,
                result.code,
            )
        }
    }

    @Test
    fun `rejects noncanonical SillyTavern exact commit archive paths`() {
        val commit = "1".repeat(40)
        listOf(
            "https://github.com/SillyTavern/SillyTavern/releases/archive/$commit.zip",
            "https://github.com/SillyTavern/SillyTavern/archive/$commit.tar.gz",
            "https://github.com/SillyTavern/SillyTavern/archive/$commit.zip/extra",
        ).forEach { downloadUrl ->
            val result = verifier.validateIdentity(identity(downloadUrl = downloadUrl))

            assertTrue(result is ArtifactIdentityValidation.Invalid)
            result as ArtifactIdentityValidation.Invalid
            assertEquals(
                ArtifactIdentityErrorCode.URL_NOT_BOUND_TO_EXACT_COMMIT,
                result.code,
            )
        }
    }

    @Test
    fun `preserves generic repository and exact commit URL semantics for synthetic artifacts`() {
        val commit = "c".repeat(40)
        val result = verifier.validateIdentity(
            identity(
                repository = "https://artifacts.example.test/stm-fixture",
                commitSha = commit,
                downloadUrl = "https://cdn.example.test/builds/$commit.tar.gz",
                kind = ArtifactKind.SYNTHETIC_TEST_ARCHIVE,
            ),
        )

        assertEquals(ArtifactIdentityValidation.Valid, result)
    }

    @Test
    fun `copies and verifies the same bytes into a new protected temporary file`() {
        val content = "deterministic SillyTavern artifact".encodeToByteArray()
        val directory = Files.createTempDirectory("stm-artifact-verified-").toFile()
        val target = directory.resolve("artifact.partial")
        try {
            val result = verifier.verifyAndCopy(
                identity = identity(content = content),
                source = ByteArrayInputStream(content),
                protectedTemporaryFile = target,
            )

            assertTrue(result is ArtifactIntegrityResult.Verified)
            result as ArtifactIntegrityResult.Verified
            assertEquals(content.size.toLong(), result.archiveLength)
            assertEquals(sha256(content), result.archiveSha256)
            assertArrayEquals(content, target.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects same-length tampering and removes the partial file`() {
        val expected = "expected artifact bytes".encodeToByteArray()
        val tampered = expected.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val directory = Files.createTempDirectory("stm-artifact-tampered-").toFile()
        val target = directory.resolve("artifact.partial")
        try {
            val result = verifier.verifyAndCopy(
                identity = identity(content = expected),
                source = ByteArrayInputStream(tampered),
                protectedTemporaryFile = target,
            )

            assertTrue(result is ArtifactIntegrityResult.Rejected)
            result as ArtifactIntegrityResult.Rejected
            assertEquals(ArtifactIntegrityErrorCode.SHA256_MISMATCH, result.code)
            assertTrue(result.partialFileRemoved)
            assertFalse(target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects a truncated archive and removes the partial file`() {
        val expected = "complete artifact bytes".encodeToByteArray()
        val truncated = expected.copyOf(expected.size - 3)
        val directory = Files.createTempDirectory("stm-artifact-truncated-").toFile()
        val target = directory.resolve("artifact.partial")
        try {
            val result = verifier.verifyAndCopy(
                identity = identity(content = expected),
                source = ByteArrayInputStream(truncated),
                protectedTemporaryFile = target,
            )

            assertTrue(result is ArtifactIntegrityResult.Rejected)
            result as ArtifactIntegrityResult.Rejected
            assertEquals(ArtifactIntegrityErrorCode.LENGTH_MISMATCH, result.code)
            assertEquals(truncated.size.toLong(), result.observedLength)
            assertTrue(result.partialFileRemoved)
            assertFalse(target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects floating branch archive URLs`() {
        val identity = identity(
            downloadUrl =
                "https://github.com/SillyTavern/SillyTavern/archive/refs/heads/release.zip",
        )

        val result = verifier.validateIdentity(identity)

        assertTrue(result is ArtifactIdentityValidation.Invalid)
        result as ArtifactIdentityValidation.Invalid
        assertEquals(ArtifactIdentityErrorCode.URL_NOT_BOUND_TO_EXACT_COMMIT, result.code)
    }

    @Test
    fun `rejects declared archives above the configured policy before creating output`() {
        val content = ByteArray(32)
        val directory = Files.createTempDirectory("stm-artifact-policy-").toFile()
        val target = directory.resolve("artifact.partial")
        try {
            val result = StmArtifactVerifier(maximumArchiveLength = 16).verifyAndCopy(
                identity = identity(content = content),
                source = ByteArrayInputStream(content),
                protectedTemporaryFile = target,
            )

            assertTrue(result is ArtifactIntegrityResult.Rejected)
            result as ArtifactIntegrityResult.Rejected
            assertEquals(ArtifactIntegrityErrorCode.ARCHIVE_EXCEEDS_POLICY, result.code)
            assertFalse(target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy unidentified archives cannot be assigned a current commit`() {
        val result = verifier.validateIdentity(
            identity(kind = ArtifactKind.LEGACY_UNIDENTIFIED_ARCHIVE),
        )

        assertTrue(result is ArtifactIdentityValidation.Invalid)
        result as ArtifactIdentityValidation.Invalid
        assertEquals(ArtifactIdentityErrorCode.LEGACY_IDENTITY_FORBIDDEN, result.code)
    }

    @Test
    fun `verifies a detached Ed25519 signature over canonical identity bytes`() {
        val identity = identity(catalogVersion = "catalog-2026.07.22")
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(verifier.canonicalBytes(identity))
            sign()
        }

        val result = verifier.verifyTrust(identity, signature, keyPair.public)

        assertTrue(result is ArtifactTrustResult.Verified)
        result as ArtifactTrustResult.Verified
        assertEquals(ArtifactTrustDecision.VERIFIED, result.decision)
        assertEquals(identity.catalogVersion, result.catalogVersion)
        assertTrue(result.canonicalSha256.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `rejects a mismatched detached Ed25519 signature`() {
        val identity = identity(catalogVersion = "catalog-1")
        val signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signingKey.private)
            update(verifier.canonicalBytes(identity))
            sign()
        }

        val result = verifier.verifyTrust(identity, signature, wrongKey.public)

        assertTrue(result is ArtifactTrustResult.Rejected)
        result as ArtifactTrustResult.Rejected
        assertEquals(ArtifactTrustDecision.REJECTED, result.decision)
        assertEquals(ArtifactTrustErrorCode.SIGNATURE_MISMATCH, result.code)
    }

    @Test
    fun `missing catalog signature remains explicitly degraded`() {
        val result = verifier.verifyTrust(
            identity = identity(catalogVersion = null),
            detachedSignature = null,
            trustedCatalogPublicKey = null,
        )

        assertTrue(result is ArtifactTrustResult.DegradedUnsignedCatalog)
        assertEquals(ArtifactTrustDecision.DEGRADED_UNSIGNED_CATALOG, result.decision)
    }

    private fun identity(
        content: ByteArray = "artifact".encodeToByteArray(),
        repository: String = "https://github.com/SillyTavern/SillyTavern.git",
        commitSha: String = "1".repeat(40),
        downloadUrl: String =
            "https://github.com/SillyTavern/SillyTavern/archive/${"1".repeat(40)}.zip",
        catalogVersion: String? = null,
        kind: ArtifactKind = ArtifactKind.UPSTREAM_SOURCE_ARCHIVE,
    ): ArtifactIdentity = ArtifactIdentity(
        repository = repository,
        commitSha = commitSha,
        archiveSha256 = sha256(content),
        archiveLength = content.size.toLong(),
        downloadUrl = downloadUrl,
        catalogVersion = catalogVersion,
        kind = kind,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
