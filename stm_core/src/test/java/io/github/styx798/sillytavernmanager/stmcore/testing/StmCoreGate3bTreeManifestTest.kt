package io.github.styx798.sillytavernmanager.stmcore.testing

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StmCoreGate3bTreeManifestTest {
    @Test
    fun `scanner emits signed-prebuilt compatible canonical manifest`() {
        val parent = Files.createTempDirectory("stm-gate3b-tree")
        val root = Files.createDirectory(parent.resolve("node_modules"))
        val packageRoot = Files.createDirectory(root.resolve("package"))
        Files.write(packageRoot.resolve("index.js"), "module.exports = 1;\n".toByteArray())

        try {
            val scan = Gate3bTreeScanner.scan(root, includeManifest = true)
            val parsed = Gate3bTreeManifestCodec.parse(requireNotNull(scan.manifestBytes))

            assertEquals(1, scan.fingerprint.files)
            assertEquals(1, scan.fingerprint.directories)
            assertEquals(1, parsed.fileCount)
            assertEquals(2, parsed.directoryCount)
            assertEquals(
                Gate3bTreeEntryType.FILE,
                parsed.entries["node_modules/package/index.js"]?.type,
            )
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `comparison reports path type size and content differences separately`() {
        val left = manifest(
            "D\tnode_modules",
            "D\tnode_modules/changed-type",
            file("node_modules/content", 1, "a"),
            file("node_modules/left", 2, "b"),
            file("node_modules/size", 3, "c"),
        )
        val right = manifest(
            "D\tnode_modules",
            file("node_modules/changed-type", 0, "d"),
            file("node_modules/content", 1, "e"),
            file("node_modules/right", 4, "f"),
            file("node_modules/size", 5, "c"),
        )

        val diff = Gate3bTreeManifestCodec.compare(left, right)

        assertEquals(5, diff.differentPaths)
        assertEquals(1, diff.onlyLeft)
        assertEquals(1, diff.onlyRight)
        assertEquals(1, diff.typeMismatches)
        assertEquals(1, diff.sizeMismatches)
        assertEquals(1, diff.contentMismatches)
        assertEquals(4, diff.byteDeltaRightMinusLeft)
        assertTrue(diff.details.contains("node_modules/content|file|"))
    }

    @Test(expected = IllegalStateException::class)
    fun `parser rejects paths outside node_modules`() {
        manifest(
            "D\tnode_modules",
            file("outside/file", 1, "a"),
        )
    }

    @Test
    fun `json comparison reports nested additions removals and value changes`() {
        val left = mapOf(
            "packages" to mapOf(
                "a" to mapOf("dev" to true, "version" to "1"),
                "b" to emptyMap<String, Any>(),
            ),
            "same" to 1,
        )
        val right = mapOf(
            "packages" to mapOf(
                "a" to mapOf("version" to "2", "optional" to true),
                "c" to emptyMap<String, Any>(),
            ),
            "same" to 1,
        )

        val diff = Gate3bJsonDiff.compare(left, right)

        assertEquals(5, diff.count)
        assertTrue(diff.details.contains("/packages/a/dev|only_left|true"))
        assertTrue(diff.details.contains("/packages/a/optional|only_right|true"))
        assertTrue(diff.details.contains("/packages/a/version|value|1|2"))
    }

    @Test
    fun `file evidence must match the current tree manifest entry`() {
        val manifest = manifest(
            "D\tnode_modules",
            "F\tnode_modules/.package-lock.json\t3\t" +
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )

        requireGate3bFileEvidence(
            manifest = manifest,
            path = "node_modules/.package-lock.json",
            loaded = Gate3bLoadedBytes(
                bytes = "abc".toByteArray(),
                sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            label = "hidden lock",
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `file evidence rejects a stale sidecar`() {
        val manifest = manifest(
            "D\tnode_modules",
            file("node_modules/.package-lock.json", 3, "a"),
        )

        requireGate3bFileEvidence(
            manifest = manifest,
            path = "node_modules/.package-lock.json",
            loaded = Gate3bLoadedBytes("abc".toByteArray(), "b".repeat(64)),
            label = "hidden lock",
        )
    }

    private fun manifest(vararg lines: String): Gate3bTreeManifest {
        val bytes = buildString {
            append(Gate3bTreeManifestCodec.MAGIC).append('\n')
            lines.forEach { append(it).append('\n') }
        }.toByteArray()
        return Gate3bTreeManifestCodec.parse(bytes)
    }

    private fun file(path: String, size: Long, shaCharacter: String): String =
        "F\t$path\t$size\t${shaCharacter.repeat(64)}"
}
