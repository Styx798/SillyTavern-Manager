package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StmSillyTavernSourceInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts one GitHub commit root and records bounded source evidence`() {
        val payload = newPayload("accepted")
        val lockBytes = "{\"lockfileVersion\":3,\"packages\":{}}".toByteArray()
        val root = writeValidSource(
            payload = payload,
            packageJson =
                """
                {
                  "name": "sillytavern",
                  "version": "1.13.4",
                  "engines": {"npm": ">=9", "node": ">=18"},
                  "nested": {"version": "must-not-win", "engines": {"node": "bad"}}
                }
                """.trimIndent(),
            lockBytes = lockBytes,
            licenseName = "LICENSE.md",
            noticeName = "NOTICE.txt",
        )

        val result = StmSillyTavernSourceInspector().inspect(payload, COMMIT)
        val accepted = result as StmSillyTavernSourceInspectionResult.Accepted

        assertEquals(root.name, accepted.evidence.archiveRoot)
        assertEquals("1.13.4", accepted.evidence.stVersion)
        assertEquals(">=18", accepted.evidence.nodeRequirement)
        assertEquals(lockBytes.sha256(), accepted.evidence.packageLockSha256)
        assertEquals("LICENSE_AND_NOTICE_PRESENT", accepted.evidence.licenseStatus)
        assertEquals(listOf("LICENSE.md"), accepted.evidence.licenseFiles)
        assertEquals(listOf("NOTICE.txt"), accepted.evidence.noticeFiles)
        assertEquals(
            listOf("package-lock.json", "package.json", "server.js"),
            accepted.evidence.requiredFiles.map(
                StmSillyTavernRequiredFileEvidence::relativePath,
            ),
        )
        assertEquals(
            root.resolve("package-lock.json").length(),
            accepted.evidence.requiredFiles.single {
                it.relativePath == "package-lock.json"
            }.sizeBytes,
        )
    }

    @Test
    fun `accepts uppercase expected commit but emits archive root without identity upgrade`() {
        val payload = newPayload("uppercase-commit")
        val root = writeValidSource(payload)

        val result = StmSillyTavernSourceInspector().inspect(payload, COMMIT.uppercase())
            as StmSillyTavernSourceInspectionResult.Accepted

        assertEquals(root.name, result.evidence.archiveRoot)
    }

    @Test
    fun `rejects invalid exact commit before inspecting files`() {
        val payload = newPayload("invalid-commit")
        writeValidSource(payload)

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, "release"))

        assertEquals(StmSillyTavernSourceErrorCode.INVALID_EXACT_COMMIT, rejection.code)
    }

    @Test
    fun `rejects archive root that is not bound to full exact commit`() {
        val payload = newPayload("root-mismatch")
        writeValidSource(
            payload,
            rootName = "SillyTavern-${"b".repeat(40)}",
        )

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_COMMIT_MISMATCH, rejection.code)
    }

    @Test
    fun `rejects multiple archive roots`() {
        val payload = newPayload("multiple-roots")
        writeValidSource(payload)
        payload.resolve("another-root").mkdir()

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_COUNT_INVALID, rejection.code)
    }

    @Test
    fun `rejects a loose file at payload root even when it is the only entry`() {
        val payload = newPayload("root-file")
        payload.resolve("SillyTavern-$COMMIT").writeText("not a directory")

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_NOT_DIRECTORY, rejection.code)
    }

    @Test
    fun `rejects an extra loose file beside an otherwise valid root`() {
        val payload = newPayload("root-and-file")
        writeValidSource(payload)
        payload.resolve("unexpected.txt").writeText("not part of a GitHub archive root")

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_COUNT_INVALID, rejection.code)
    }

    @Test
    fun `rejects unsafe archive root names before using the name as evidence`() {
        val payload = newPayload("unsafe-root")
        writeValidSource(payload, rootName = "Silly Tavern-$COMMIT")

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_NAME_INVALID, rejection.code)
    }

    @Test
    fun `rejects each missing required regular file with path evidence`() {
        listOf("server.js", "package.json", "package-lock.json").forEachIndexed { index, name ->
            val payload = newPayload("missing-$index")
            val root = writeValidSource(payload)
            assertTrue(root.resolve(name).delete())

            val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

            assertEquals(StmSillyTavernSourceErrorCode.REQUIRED_FILE_MISSING, rejection.code)
            assertEquals(name, rejection.relativePath)
        }
    }

    @Test
    fun `rejects required path that is a directory`() {
        val payload = newPayload("required-directory")
        val root = writeValidSource(payload)
        assertTrue(root.resolve("package-lock.json").delete())
        assertTrue(root.resolve("package-lock.json").mkdir())

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.REQUIRED_FILE_NOT_REGULAR, rejection.code)
        assertEquals("package-lock.json", rejection.relativePath)
    }

    @Test
    fun `rejects symbolic link without following or modifying its target`() {
        val payload = newPayload("required-symlink")
        val root = writeValidSource(payload)
        val outside = temporaryFolder.newFile("outside-server.js").apply {
            writeText("sentinel")
        }
        assertTrue(root.resolve("server.js").delete())
        try {
            Files.createSymbolicLink(root.resolve("server.js").toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.UNSAFE_TREE_ENTRY, rejection.code)
        assertEquals("server.js", rejection.relativePath)
        assertEquals("sentinel", outside.readText())
    }

    @Test
    fun `rejects archive root symbolic link without following it`() {
        val payload = newPayload("root-symlink")
        val outside = temporaryFolder.newFolder("outside-root")
        try {
            Files.createSymbolicLink(
                payload.resolve("SillyTavern-$COMMIT").toPath(),
                outside.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_NOT_DIRECTORY, rejection.code)
    }

    @Test
    fun `nested fake version and engines fields never satisfy top-level metadata`() {
        val payload = newPayload("nested-fake")
        writeValidSource(
            payload,
            packageJson =
                """
                {
                  "name": "sillytavern",
                  "metadata": {
                    "version": "9.9.9",
                    "engines": {"node": ">=999"}
                  }
                }
                """.trimIndent(),
        )

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.PACKAGE_VERSION_MISSING, rejection.code)
    }

    @Test
    fun `top-level version and engines node must be strings`() {
        val versionPayload = newPayload("version-type")
        writeValidSource(
            versionPayload,
            packageJson = "{\"version\":1,\"engines\":{\"node\":\">=18\"}}",
        )
        val nodePayload = newPayload("node-type")
        writeValidSource(
            nodePayload,
            packageJson = "{\"version\":\"1.0.0\",\"engines\":{\"node\":18}}",
        )

        assertEquals(
            StmSillyTavernSourceErrorCode.PACKAGE_VERSION_INVALID,
            rejected(StmSillyTavernSourceInspector().inspect(versionPayload, COMMIT)).code,
        )
        assertEquals(
            StmSillyTavernSourceErrorCode.NODE_REQUIREMENT_INVALID,
            rejected(StmSillyTavernSourceInspector().inspect(nodePayload, COMMIT)).code,
        )
    }

    @Test
    fun `rejects invalid and truncated package JSON`() {
        listOf(
            "{\"version\":\"1.0.0\",\"engines\":{\"node\":\">=18\",}}",
            "{\"version\":\"1.0.0\",\"engines\":{\"node\":\">=18\"}",
            "{\"version\":\"1\",\"version\":\"2\",\"engines\":{\"node\":\">=18\"}}",
        ).forEachIndexed { index, json ->
            val payload = newPayload("invalid-json-$index")
            writeValidSource(payload, packageJson = json)

            val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

            assertEquals(StmSillyTavernSourceErrorCode.PACKAGE_JSON_INVALID, rejection.code)
        }
    }

    @Test
    fun `rejects package JSON that is not strict UTF-8`() {
        val payload = newPayload("invalid-utf8")
        val root = writeValidSource(payload)
        root.resolve("package.json").writeBytes(byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0x28))

        val rejection = rejected(StmSillyTavernSourceInspector().inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.PACKAGE_JSON_INVALID_UTF8, rejection.code)
    }

    @Test
    fun `rejects oversized package JSON before parsing`() {
        val payload = newPayload("oversized-json")
        writeValidSource(payload)
        val packageJson = payload.resolve("SillyTavern-$COMMIT/package.json")
        packageJson.writeText(" ".repeat(33))
        val inspector = StmSillyTavernSourceInspector(
            StmSillyTavernSourceInspectionPolicy(maxPackageJsonBytes = 32),
        )

        val rejection = rejected(inspector.inspect(payload, COMMIT))

        assertEquals(StmSillyTavernSourceErrorCode.PACKAGE_JSON_TOO_LARGE, rejection.code)
    }

    @Test
    fun `rejects bounded metadata strings containing padding or controls`() {
        val paddedPayload = newPayload("padded-version")
        writeValidSource(
            paddedPayload,
            packageJson = "{\"version\":\" 1.0.0\",\"engines\":{\"node\":\">=18\"}}",
        )
        val controlPayload = newPayload("control-node")
        writeValidSource(
            controlPayload,
            packageJson = "{\"version\":\"1.0.0\",\"engines\":{\"node\":\">=18\\n\"}}",
        )

        assertEquals(
            StmSillyTavernSourceErrorCode.PACKAGE_VERSION_INVALID,
            rejected(StmSillyTavernSourceInspector().inspect(paddedPayload, COMMIT)).code,
        )
        assertEquals(
            StmSillyTavernSourceErrorCode.NODE_REQUIREMENT_INVALID,
            rejected(StmSillyTavernSourceInspector().inspect(controlPayload, COMMIT)).code,
        )
    }

    @Test
    fun `rejects missing license but records notice as optional`() {
        val missingPayload = newPayload("missing-license")
        val missingRoot = writeValidSource(missingPayload, noticeName = "NOTICE")
        assertTrue(missingRoot.resolve("LICENSE").delete())
        val noNoticePayload = newPayload("no-notice")
        writeValidSource(noNoticePayload)

        assertEquals(
            StmSillyTavernSourceErrorCode.LICENSE_MISSING,
            rejected(StmSillyTavernSourceInspector().inspect(missingPayload, COMMIT)).code,
        )
        val accepted = StmSillyTavernSourceInspector().inspect(noNoticePayload, COMMIT)
            as StmSillyTavernSourceInspectionResult.Accepted
        assertEquals("LICENSE_PRESENT", accepted.evidence.licenseStatus)
        assertTrue(accepted.evidence.noticeFiles.isEmpty())
    }

    @Test
    fun `streams package lock hash and enforces its byte limit`() {
        val hashPayload = newPayload("lock-hash")
        val lockBytes = ByteArray(96 * 1024) { index -> (index % 251).toByte() }
        writeValidSource(hashPayload, lockBytes = lockBytes)
        val limitedPayload = newPayload("lock-limit")
        writeValidSource(limitedPayload, lockBytes = ByteArray(65) { 7 })

        val accepted = StmSillyTavernSourceInspector().inspect(hashPayload, COMMIT)
            as StmSillyTavernSourceInspectionResult.Accepted
        assertEquals(lockBytes.sha256(), accepted.evidence.packageLockSha256)

        val limited = StmSillyTavernSourceInspector(
            StmSillyTavernSourceInspectionPolicy(maxPackageLockBytes = 64),
        )
        assertEquals(
            StmSillyTavernSourceErrorCode.PACKAGE_LOCK_TOO_LARGE,
            rejected(limited.inspect(limitedPayload, COMMIT)).code,
        )
    }

    private fun newPayload(name: String): File = temporaryFolder.newFolder("payload-$name")

    private fun writeValidSource(
        payload: File,
        rootName: String = "SillyTavern-$COMMIT",
        packageJson: String =
            "{\"name\":\"sillytavern\",\"version\":\"1.0.0\"," +
                "\"engines\":{\"node\":\">=18\"}}",
        lockBytes: ByteArray = "{\"lockfileVersion\":3}".toByteArray(),
        licenseName: String = "LICENSE",
        noticeName: String? = null,
    ): File {
        val root = payload.resolve(rootName)
        assertTrue(root.mkdir())
        root.resolve("server.js").writeText("export const marker = true;\n")
        root.resolve("package.json").writeText(packageJson)
        root.resolve("package-lock.json").writeBytes(lockBytes)
        root.resolve(licenseName).writeText("Synthetic license fixture\n")
        noticeName?.let { root.resolve(it).writeText("Synthetic notice fixture\n") }
        root.resolve("public").mkdir()
        root.resolve("public/index.html").writeText("<!doctype html>\n")
        return root
    }

    private fun rejected(
        result: StmSillyTavernSourceInspectionResult,
    ): StmSillyTavernSourceInspectionResult.Rejected {
        assertTrue("Expected rejection but received $result", result is StmSillyTavernSourceInspectionResult.Rejected)
        return result as StmSillyTavernSourceInspectionResult.Rejected
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
