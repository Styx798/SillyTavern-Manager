package io.github.styx798.sillytavernmanager.stmcore.installer

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StmDependencySupplyManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val verifier = StmDependencySupplyManifestVerifier { keyId ->
        keyPair.public.takeIf { keyId == SIGNING_KEY_ID }
    }

    @Test
    fun `canonical manifest round trips and verifies its source runtime and signature`() {
        val manifest = manifest()
        val bytes = verifier.encode(manifest)
        val signature = sign(bytes, keyPair)

        assertEquals(manifest, verifier.decode(bytes))
        assertArrayEquals(bytes, verifier.encode(verifier.decode(bytes)))
        val result = verifier.verify(bytes, signature, source(), runtime())
        assertTrue(result is StmDependencyManifestVerification.Verified)
        result as StmDependencyManifestVerification.Verified
        assertEquals(manifest, result.manifest)
        assertEquals(bytes.sha256(), result.canonicalSha256)
    }

    @Test
    fun `rejects reordered duplicated noncanonical and invalid utf8 manifests`() {
        val canonical = verifier.encode(manifest())
        val text = canonical.toString(Charsets.UTF_8)
        val reordered = text.replace(
            "schema_version=1\nsupply_id=st-1.18.0-arm64\n",
            "supply_id=st-1.18.0-arm64\nschema_version=1\n",
        ).toByteArray()
        val duplicated = text.replace(
            "supply_id=st-1.18.0-arm64\n",
            "supply_id=st-1.18.0-arm64\nsupply_id=st-1.18.0-arm64\n",
        ).toByteArray()
        val noncanonicalNumber = text.replace(
            "dependencies_archive_bytes=1024\n",
            "dependencies_archive_bytes=01024\n",
        ).toByteArray()
        val crlf = text.replace("\n", "\r\n").toByteArray()
        val noFinalLf = canonical.copyOf(canonical.size - 1)
        val invalidUtf8 = canonical.copyOf().also { it[0] = 0xc3.toByte() }

        listOf(reordered, duplicated, noncanonicalNumber, crlf, noFinalLf, invalidUtf8)
            .forEach { bytes ->
                val result = verifier.verify(bytes, ByteArray(64), source(), runtime())
                assertRejected(result, StmDependencyManifestErrorCode.MANIFEST_FORMAT_INVALID)
            }
    }

    @Test
    fun `rejects source and runtime binding mismatches before accepting trust`() {
        val bytes = verifier.encode(manifest())
        val signature = sign(bytes, keyPair)

        assertRejected(
            verifier.verify(
                bytes,
                signature,
                source().copy(commitSha = "b".repeat(40)),
                runtime(),
            ),
            StmDependencyManifestErrorCode.SOURCE_BINDING_MISMATCH,
        )
        assertRejected(
            verifier.verify(
                bytes,
                signature,
                source(),
                runtime().copy(abi = "x86_64"),
            ),
            StmDependencyManifestErrorCode.RUNTIME_BINDING_MISMATCH,
        )
    }

    @Test
    fun `rejects unknown key malformed signature mismatch and tampered manifest`() {
        val bytes = verifier.encode(manifest())
        val signature = sign(bytes, keyPair)
        val unknownKeyBytes = verifier.encode(manifest().copy(signingKeyId = "unknown-key"))
        val tampered = bytes.toString(Charsets.UTF_8)
            .replace("bundle_bytes=64\n", "bundle_bytes=65\n")
            .toByteArray()

        assertRejected(
            verifier.verify(unknownKeyBytes, sign(unknownKeyBytes, keyPair), source(), runtime()),
            StmDependencyManifestErrorCode.TRUSTED_KEY_NOT_FOUND,
        )
        assertRejected(
            verifier.verify(bytes, ByteArray(63), source(), runtime()),
            StmDependencyManifestErrorCode.SIGNATURE_FORMAT_INVALID,
        )
        assertRejected(
            verifier.verify(bytes, ByteArray(64), source(), runtime()),
            StmDependencyManifestErrorCode.SIGNATURE_MISMATCH,
        )
        assertRejected(
            verifier.verify(tampered, signature, source(), runtime()),
            StmDependencyManifestErrorCode.SIGNATURE_MISMATCH,
        )
    }

    @Test
    fun `rejects invalid fields at canonical encoding boundary`() {
        listOf(
            manifest().copy(repository = "https://example.com/SillyTavern"),
            manifest().copy(buildImageDigest = "latest"),
            manifest().copy(dependencyTreeSymlinkCount = -1),
            manifest().copy(signingKeyId = "../key"),
            manifest().copy(deviceAbi = "x86_64"),
            manifest().copy(bundleSha256 = "not-a-hash"),
        ).forEach { invalid ->
            val error = runCatching { verifier.encode(invalid) }.exceptionOrNull()
            assertTrue("Expected invalid manifest rejection for $invalid", error != null)
        }
    }

    @Test
    fun `verifies regular payload length and hash and rejects tampering and links`() {
        val payload = temporaryFolder.newFile("dependencies.zip")
        val bytes = "signed dependency bytes".toByteArray()
        payload.writeBytes(bytes)

        val valid = verifier.verifyPayload(
            payload,
            expectedBytes = bytes.size.toLong(),
            expectedSha256 = bytes.sha256(),
            maximumBytes = 1024,
        )
        assertTrue(valid is StmDependencyPayloadVerification.Verified)

        val wrongLength = verifier.verifyPayload(
            payload,
            expectedBytes = bytes.size + 1L,
            expectedSha256 = bytes.sha256(),
            maximumBytes = 1024,
        )
        assertPayloadRejected(
            wrongLength,
            StmDependencyManifestErrorCode.PAYLOAD_LENGTH_MISMATCH,
        )
        val wrongHash = verifier.verifyPayload(
            payload,
            expectedBytes = bytes.size.toLong(),
            expectedSha256 = "0".repeat(64),
            maximumBytes = 1024,
        )
        assertPayloadRejected(
            wrongHash,
            StmDependencyManifestErrorCode.PAYLOAD_SHA256_MISMATCH,
        )

        val link = temporaryFolder.root.toPath().resolve("payload-link")
        java.nio.file.Files.createSymbolicLink(link, payload.toPath())
        val linked = verifier.verifyPayload(
            link.toFile(),
            expectedBytes = bytes.size.toLong(),
            expectedSha256 = bytes.sha256(),
            maximumBytes = 1024,
        )
        assertPayloadRejected(linked, StmDependencyManifestErrorCode.PAYLOAD_UNSAFE)
    }

    private fun manifest(): StmDependencySupplyManifest = StmDependencySupplyManifest(
        schemaVersion = 1,
        supplyId = "st-1.18.0-arm64",
        repository = REPOSITORY,
        stCommitSha = COMMIT,
        packageLockSha256 = LOCK_SHA,
        buildNodeVersion = "v24.17.0",
        buildNpmVersion = "11.6.2",
        buildImageDigest = "sha256:${"1".repeat(64)}",
        webpackConfigSha256 = "2".repeat(64),
        buildLibSha256 = "3".repeat(64),
        adapterSha256 = "4".repeat(64),
        prunePolicy = StmDependencyPrunePolicy.LOCKFILE_COMPLETE,
        prunePolicySha256 = "5".repeat(64),
        dependenciesArchiveSha256 = "6".repeat(64),
        dependenciesArchiveBytes = 1024,
        dependencyTreeSha256 = "7".repeat(64),
        dependencyTreeFileCount = 20_000,
        dependencyTreeDirectoryCount = 2_500,
        dependencyTreeSymlinkCount = 0,
        dependencyTreeBytes = 300_000_000,
        treeManifestSha256 = "8".repeat(64),
        treeManifestBytes = 2_000_000,
        sbomSha256 = "9".repeat(64),
        sbomBytes = 100_000,
        licenseManifestSha256 = "a".repeat(64),
        licenseManifestBytes = 50_000,
        bundleSha256 = "b".repeat(64),
        bundleBytes = 64,
        bundleLicenseSha256 = "c".repeat(64),
        bundleLicenseBytes = 32,
        postAdapterProgramTreeSha256 = "d".repeat(64),
        signingKeyId = SIGNING_KEY_ID,
        deviceNodeVersion = "v24.17.0",
        deviceJavetCoordinate = "com.caoccao.javet:javet-node-android-i18n:5.0.9",
        deviceAbi = "arm64-v8a",
    )

    private fun source() = StmDependencySourceBinding(REPOSITORY, COMMIT, LOCK_SHA)

    private fun runtime() = StmDependencyRuntimeBinding(
        nodeVersion = "v24.17.0",
        javetCoordinate = "com.caoccao.javet:javet-node-android-i18n:5.0.9",
        abi = "arm64-v8a",
    )

    private fun sign(bytes: ByteArray, pair: KeyPair): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(bytes)
            sign()
        }

    private fun assertRejected(
        result: StmDependencyManifestVerification,
        code: StmDependencyManifestErrorCode,
    ) {
        assertTrue(result is StmDependencyManifestVerification.Rejected)
        assertEquals(code, (result as StmDependencyManifestVerification.Rejected).code)
    }

    private fun assertPayloadRejected(
        result: StmDependencyPayloadVerification,
        code: StmDependencyManifestErrorCode,
    ) {
        assertTrue(result is StmDependencyPayloadVerification.Rejected)
        assertEquals(code, (result as StmDependencyPayloadVerification.Rejected).code)
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            "%02x".format(byte)
        }

    private companion object {
        const val REPOSITORY = "https://github.com/SillyTavern/SillyTavern"
        const val COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val LOCK_SHA = "7484f87e7dc6e99044ad532b80111c3e93463aaf1d5dbe377b3a4486bfe65f6f"
        const val SIGNING_KEY_ID = "stm-test-key-1"
    }
}
