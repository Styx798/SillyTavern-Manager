package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StmBundledNpmToolchainTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `prepares once and fully verifies an immutable target before reuse`() {
        val fixture = fixture("happy")

        val first = fixture.toolchain().prepare()
        val second = fixture.toolchain().prepare()

        assertFalse(first.reused)
        assertTrue(second.reused)
        assertEquals(first.copy(reused = true), second)
        assertEquals(fixture.expectedTarget(), first.toolchainDirectory)
        assertEquals(fixture.expectedTarget().resolve("npm"), first.npmDirectory)
        assertEquals(
            fixture.files.getValue("npm/package.json").decodeToString(),
            first.npmDirectory.resolve("package.json").readText(),
        )
        assertEquals(2, fixture.source.openCount(ARCHIVE_ASSET))
        assertEquals(2, fixture.source.openCount(INVENTORY_ASSET))
        assertStagingEmpty(fixture)
    }

    @Test
    fun `strict manifest identity and line grammar reject tampering`() {
        val identityFixture = fixture("manifest-identity")
        val tampered = identityFixture.manifestBytes.copyOf().also { bytes ->
            bytes[bytes.indexOf('n'.code.toByte())] = 'x'.code.toByte()
        }
        identityFixture.source.assets[MANIFEST_ASSET] = tampered

        assertFailure(StmBundledNpmToolchainErrorCode.ASSET_SHA256_MISMATCH) {
            identityFixture.toolchain().prepare()
        }
        assertStagingEmpty(identityFixture)

        val base = fixture("manifest-lines")
        val variants = listOf(
            base.manifestBytes.decodeToString().replace("\n", "\r\n").encodeToByteArray(),
            base.manifestBytes.dropLast(1).toByteArray(),
            base.manifestBytes.decodeToString().lineSequence().toMutableList().let { lines ->
                val first = lines[0]
                lines[0] = lines[1]
                lines[1] = first
                lines.joinToString("\n").encodeToByteArray()
            },
            base.manifestBytes + "unexpected=value\n".encodeToByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
        )
        variants.forEachIndexed { index, bytes ->
            val candidate = fixture("manifest-lines-$index")
            candidate.replaceManifest(bytes)
            assertFailure(StmBundledNpmToolchainErrorCode.INVALID_MANIFEST) {
                candidate.toolchain().prepare()
            }
            assertStagingEmpty(candidate)
        }
    }

    @Test
    fun `archive and license inventory identities are independently enforced`() {
        val archiveFixture = fixture("archive-mismatch")
        archiveFixture.source.assets[ARCHIVE_ASSET] =
            archiveFixture.archiveBytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertFailure(StmBundledNpmToolchainErrorCode.ASSET_SHA256_MISMATCH) {
            archiveFixture.toolchain().prepare()
        }
        assertFalse(archiveFixture.expectedTarget().exists())
        assertStagingEmpty(archiveFixture)

        val inventoryFixture = fixture("inventory-mismatch")
        inventoryFixture.source.assets[INVENTORY_ASSET] =
            inventoryFixture.inventoryBytes.copyOf().also { it[1] = (it[1] + 1).toByte() }

        assertFailure(StmBundledNpmToolchainErrorCode.ASSET_SHA256_MISMATCH) {
            inventoryFixture.toolchain().prepare()
        }
        assertFalse(inventoryFixture.expectedTarget().exists())
        assertStagingEmpty(inventoryFixture)
    }

    @Test
    fun `aggregate tree mismatch never commits extracted content`() {
        val fixture = fixture("tree-mismatch")
        fixture.replaceManifestField("tree_sha256", "f".repeat(64))

        assertFailure(StmBundledNpmToolchainErrorCode.TREE_MISMATCH) {
            fixture.toolchain().prepare()
        }

        assertFalse(fixture.expectedTarget().exists())
        assertStagingEmpty(fixture)
    }

    @Test
    fun `required entry binding mismatch is rejected after whole tree verification`() {
        val fixture = fixture("required-mismatch")
        fixture.replaceManifestField("npm_lib_cli_entry_sha256", "e".repeat(64))

        assertFailure(StmBundledNpmToolchainErrorCode.REQUIRED_ENTRY_MISMATCH) {
            fixture.toolchain().prepare()
        }

        assertFalse(fixture.expectedTarget().exists())
        assertStagingEmpty(fixture)
    }

    @Test
    fun `cancellation during archive copy removes incoming and extraction staging`() {
        val fixture = fixture("cancel")
        var cancelled = false
        fixture.source.streamWrapper = { asset, input ->
            if (asset != ARCHIVE_ASSET) {
                input
            } else {
                object : FilterInputStream(input) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        super.read(buffer, offset, length).also { count ->
                            if (count > 0) cancelled = true
                        }
                }
            }
        }

        assertFailure(StmBundledNpmToolchainErrorCode.OPERATION_CANCELLED) {
            fixture.toolchain().prepare(StmExtractionCancellation { cancelled })
        }

        assertFalse(fixture.expectedTarget().exists())
        assertStagingEmpty(fixture)
    }

    @Test
    fun `invalid existing target is preserved and is never replaced`() {
        val fixture = fixture("existing-invalid")
        val target = fixture.expectedTarget()
        val sentinel = target.resolve("npm/sentinel.txt")
        requireNotNull(sentinel.parentFile).mkdirs()
        sentinel.writeText("preserve-invalid-evidence")

        assertFailure(StmBundledNpmToolchainErrorCode.EXISTING_TARGET_INVALID) {
            fixture.toolchain().prepare()
        }

        assertEquals("preserve-invalid-evidence", sentinel.readText())
        assertEquals(setOf("npm"), target.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(0, fixture.source.openCount(ARCHIVE_ASSET))
        assertStagingEmpty(fixture)
    }

    @Test
    fun `failed directory rename has no copy fallback and cleans staging`() {
        val fixture = fixture("rename-failure")
        var calls = 0

        assertFailure(StmBundledNpmToolchainErrorCode.ATOMIC_RENAME_FAILED) {
            fixture.toolchain { _, _ ->
                calls += 1
                false
            }.prepare()
        }

        assertEquals(1, calls)
        assertFalse(fixture.expectedTarget().exists())
        assertStagingEmpty(fixture)
    }

    @Test
    fun `target appearing during rename is preserved and not cleaned as staging`() {
        val fixture = fixture("target-appeared")
        val target = fixture.expectedTarget().toPath()

        assertFailure(StmBundledNpmToolchainErrorCode.TARGET_APPEARED) {
            fixture.toolchain { _, destination ->
                assertEquals(target, destination)
                Files.createDirectories(destination)
                Files.write(
                    destination.resolve("sentinel.txt"),
                    "preserve-race-winner".encodeToByteArray(),
                )
                false
            }.prepare()
        }

        assertEquals(
            "preserve-race-winner",
            Files.readAllBytes(target.resolve("sentinel.txt")).decodeToString(),
        )
        assertStagingEmpty(fixture)
    }

    @Test
    fun `production assets pass the runtime extractor and immutable reuse scan`() {
        val assetRoot = productionAssetRoot()
        val manifestPath = assetRoot.resolve(StmBundledNpmToolchainFactory.MANIFEST_ASSET)
        val manifestBytes = Files.readAllBytes(manifestPath)
        assertEquals(StmBundledNpmToolchainFactory.MANIFEST_BYTES, manifestBytes.size.toLong())
        assertEquals(StmBundledNpmToolchainFactory.MANIFEST_SHA256, sha256(manifestBytes))

        val root = temporaryFolder.newFolder("production-assets")
        val store = root.resolve("store").apply { mkdirs() }
        val staging = root.resolve("staging").apply { mkdirs() }
        val toolchain = StmBundledNpmToolchain(
            storeRoot = store,
            stagingRoot = staging,
            manifestAsset = StmBundledNpmAssetBinding(
                assetName = StmBundledNpmToolchainFactory.MANIFEST_ASSET,
                bytes = StmBundledNpmToolchainFactory.MANIFEST_BYTES,
                sha256 = StmBundledNpmToolchainFactory.MANIFEST_SHA256,
            ),
            assetSource = StmBundledNpmAssetSource { assetName ->
                val asset = assetRoot.resolve(assetName).normalize()
                require(asset.startsWith(assetRoot) && asset != assetRoot) {
                    "Production asset escaped the module asset root"
                }
                Files.newInputStream(
                    asset,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS,
                )
            },
        )

        val first = toolchain.prepare()
        val second = toolchain.prepare()

        assertFalse(first.reused)
        assertEquals(first.copy(reused = true), second)
        assertEquals(NPM_VERSION, first.npmVersion)
        assertEquals(2_133, first.fileCount)
        assertEquals(549, first.directoryCount)
        assertEquals(11_785_613L, first.totalFileBytes)
        assertEquals(
            "86fe906883080018691b6d7ff9648394171d92c66a1261611430393a15810e03",
            first.treeSha256,
        )
        assertEquals(
            "third_party/npm-11.6.2/PACKAGE-LICENSES.json",
            first.licenseInventoryAsset,
        )
        assertEquals(0, first.licenseGapCount)
        assertTrue(first.npmDirectory.resolve("bin/npm-cli.js").isFile)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    private fun fixture(name: String): Fixture {
        val root = temporaryFolder.newFolder(name)
        val store = root.resolve("store").apply { mkdirs() }
        val staging = root.resolve("staging").apply { mkdirs() }
        val files = linkedMapOf(
            "npm/package.json" to
                "{\"name\":\"npm\",\"version\":\"$NPM_VERSION\"}\n".encodeToByteArray(),
            "npm/bin/npm-cli.js" to "require('../lib/cli.js')(process)\n".encodeToByteArray(),
            "npm/lib/cli.js" to "module.exports = require('./cli/entry.js')\n".encodeToByteArray(),
            "npm/lib/cli/entry.js" to "module.exports = process => process\n".encodeToByteArray(),
            "npm/lib/npm.js" to "module.exports = class Npm {}\n".encodeToByteArray(),
            "npm/node_modules/@npmcli/arborist/package.json" to
                "{\"name\":\"@npmcli/arborist\",\"version\":\"9.1.6\"}\n".encodeToByteArray(),
            "npm/LICENSE" to "Synthetic Artistic-2.0 fixture only\n".encodeToByteArray(),
            "npm/README.md" to "fixture\n".encodeToByteArray(),
            "npm/extra.txt" to "ninth fixture file\n".encodeToByteArray(),
        )
        val archive = zip(files)
        val inventory = "{\"format\":\"stm-npm-license-inventory-v1\",\"gaps\":9}\n"
            .encodeToByteArray()
        val manifest = manifest(files, archive, inventory)
        val source = MapAssetSource(
            mutableMapOf(
                MANIFEST_ASSET to manifest,
                ARCHIVE_ASSET to archive,
                INVENTORY_ASSET to inventory,
            ),
        )
        return Fixture(root, store, staging, files, archive, inventory, manifest, source)
    }

    private fun manifest(
        files: Map<String, ByteArray>,
        archive: ByteArray,
        inventory: ByteArray,
    ): ByteArray {
        val entries = fixtureTreeEntries(files)
        val fields = linkedMapOf(
            "format" to "STM-NPM-TOOL-ASSET-V1",
            "tool" to "npm",
            "npm_version" to NPM_VERSION,
            "node_requirement" to "^20.17.0 || >=22.9.0",
            "tested_node_version" to "24.17.0",
            "javet_version" to "5.0.9",
            "abi" to "arm64-v8a",
            "source_tarball_url" to "https://registry.npmjs.org/npm/-/npm-$NPM_VERSION.tgz",
            "source_tarball_bytes" to "2663834",
            "source_tarball_sha256" to
                "585f95094ee5cb2788ee11d90f2a518a7c9ef6e083fa141d0b63ca3383675a20",
            "source_tarball_sha512" to SOURCE_TARBALL_SHA512,
            "source_tarball_integrity" to
                "sha512-${Base64.getEncoder().encodeToString(SOURCE_TARBALL_SHA512.hexToBytes())}",
            "registry_git_head" to "5d41fb3a08249c7b40994c9f187fd25c241817ad",
            "registry_signature_status" to "metadata-present-unverified",
            "archive_asset" to ARCHIVE_ASSET,
            "archive_bytes" to archive.size.toString(),
            "archive_sha256" to sha256(archive),
            "tree_algorithm" to "stm-tree-identity-v1",
            "tree_sha256" to stmTreeIdentitySha256(entries),
            "file_count" to entries.count { it.type == StmZipManifestEntryType.FILE }.toString(),
            "directory_count" to
                entries.count { it.type == StmZipManifestEntryType.DIRECTORY }.toString(),
            "total_file_bytes" to files.values.sumOf { it.size.toLong() }.toString(),
            "root" to "npm",
        )
        listOf(
            "npm_package_json" to "npm/package.json",
            "npm_bin_cli" to "npm/bin/npm-cli.js",
            "npm_lib_cli" to "npm/lib/cli.js",
            "npm_lib_cli_entry" to "npm/lib/cli/entry.js",
            "npm_lib_npm" to "npm/lib/npm.js",
            "arborist_package_json" to "npm/node_modules/@npmcli/arborist/package.json",
            "npm_license" to "npm/LICENSE",
        ).forEach { (prefix, path) ->
            val content = files.getValue(path)
            fields["${prefix}_path"] = path
            fields["${prefix}_bytes"] = content.size.toString()
            fields["${prefix}_sha256"] = sha256(content)
        }
        fields["license_inventory_asset"] = INVENTORY_ASSET
        fields["license_inventory_bytes"] = inventory.size.toString()
        fields["license_inventory_sha256"] = sha256(inventory)
        fields["license_gap_count"] = "9"
        return fields.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) ->
            "$key=$value"
        }.encodeToByteArray()
    }

    private fun fixtureTreeEntries(files: Map<String, ByteArray>): List<StmZipManifestEntry> {
        val directories = sortedSetOf<String>()
        files.keys.forEach { path ->
            var parent = path.substringBeforeLast('/')
            while (parent.isNotEmpty()) {
                directories += parent
                parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
            }
        }
        return buildList {
            directories.forEach { path ->
                add(
                    StmZipManifestEntry(
                        path,
                        StmZipManifestEntryType.DIRECTORY,
                        0,
                        null,
                    ),
                )
            }
            files.forEach { (path, content) ->
                add(
                    StmZipManifestEntry(
                        path,
                        StmZipManifestEntryType.FILE,
                        content.size.toLong(),
                        sha256(content),
                    ),
                )
            }
        }.sortedBy(StmZipManifestEntry::relativePath)
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val directories = fixtureTreeEntries(files)
                .filter { it.type == StmZipManifestEntryType.DIRECTORY }
                .map { it.relativePath }
            directories.forEach { directory ->
                zip.putNextEntry(
                    ZipEntry("$directory/").apply {
                        method = ZipEntry.STORED
                        size = 0
                        compressedSize = 0
                        crc = 0
                    },
                )
                zip.closeEntry()
            }
            files.toSortedMap().forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun assertStagingEmpty(fixture: Fixture) {
        assertTrue(
            "Only this toolchain's incoming and extraction paths may be cleaned",
            fixture.staging.listFiles().orEmpty().isEmpty(),
        )
    }

    private fun productionAssetRoot(): Path {
        val protectionDomain = requireNotNull(javaClass.protectionDomain)
        val testClasses = Path.of(
            requireNotNull(protectionDomain.codeSource).location.toURI(),
        ).toAbsolutePath().normalize()
        return generateSequence(testClasses) { current -> current.parent }
            .map { ancestor -> ancestor.resolve("src/main/assets") }
            .firstOrNull { candidate ->
                Files.isRegularFile(
                    candidate.resolve(StmBundledNpmToolchainFactory.MANIFEST_ASSET),
                    LinkOption.NOFOLLOW_LINKS,
                )
            }?.toRealPath() ?: error("Could not locate stm_core production assets from test classes")
    }

    private inline fun assertFailure(
        code: StmBundledNpmToolchainErrorCode,
        block: () -> Unit,
    ): StmBundledNpmToolchainException {
        try {
            block()
            fail("Expected $code")
        } catch (error: StmBundledNpmToolchainException) {
            assertEquals(code, error.code)
            return error
        }
        throw AssertionError("unreachable")
    }

    private inner class Fixture(
        val root: java.io.File,
        val store: java.io.File,
        val staging: java.io.File,
        val files: Map<String, ByteArray>,
        val archiveBytes: ByteArray,
        val inventoryBytes: ByteArray,
        var manifestBytes: ByteArray,
        val source: MapAssetSource,
    ) {
        fun toolchain(): StmBundledNpmToolchain = newToolchain()

        fun toolchain(renamer: (Path, Path) -> Boolean): StmBundledNpmToolchain =
            newToolchain(renamer)

        private fun newToolchain(
            renamer: ((Path, Path) -> Boolean)? = null,
        ): StmBundledNpmToolchain {
            val binding = StmBundledNpmAssetBinding(
                assetName = MANIFEST_ASSET,
                bytes = manifestBytes.size.toLong(),
                sha256 = sha256(manifestBytes),
            )
            return if (renamer == null) {
                StmBundledNpmToolchain(
                    storeRoot = store,
                    stagingRoot = staging,
                    manifestAsset = binding,
                    assetSource = source,
                )
            } else {
                StmBundledNpmToolchain(
                    storeRoot = store,
                    stagingRoot = staging,
                    manifestAsset = binding,
                    assetSource = source,
                    directoryRenamer = renamer,
                )
            }
        }

        fun expectedTarget(): java.io.File {
            val treeSha256 = manifestBytes.decodeToString()
                .lineSequence()
                .single { it.startsWith("tree_sha256=") }
                .substringAfter('=')
            return store.canonicalFile.resolve("$NPM_VERSION-$treeSha256")
        }

        fun replaceManifest(bytes: ByteArray) {
            manifestBytes = bytes
            source.assets[MANIFEST_ASSET] = bytes
        }

        fun replaceManifestField(key: String, value: String) {
            val lines = manifestBytes.decodeToString().dropLast(1).split('\n').toMutableList()
            val index = lines.indexOfFirst { it.startsWith("$key=") }
            check(index >= 0)
            lines[index] = "$key=$value"
            replaceManifest(lines.joinToString("\n", postfix = "\n").encodeToByteArray())
        }
    }

    private class MapAssetSource(
        val assets: MutableMap<String, ByteArray>,
    ) : StmBundledNpmAssetSource {
        private val openCounts = mutableMapOf<String, Int>()
        var streamWrapper: (String, InputStream) -> InputStream = { _, input -> input }

        override fun open(assetName: String): InputStream {
            openCounts[assetName] = openCounts.getOrDefault(assetName, 0) + 1
            val input = ByteArrayInputStream(assets[assetName]?.copyOf() ?: error("Missing $assetName"))
            return streamWrapper(assetName, input)
        }

        fun openCount(assetName: String): Int = openCounts.getOrDefault(assetName, 0)
    }

    private companion object {
        const val NPM_VERSION = "11.6.2"
        const val MANIFEST_ASSET = "stm_core/tools/npm/11.6.2/npm-tool-manifest.stm"
        const val ARCHIVE_ASSET = "stm_core/tools/npm/11.6.2/npm-11.6.2.stmzip"
        const val INVENTORY_ASSET =
            "third_party/npm-11.6.2/npm-11.6.2-license-inventory.json"
        const val SOURCE_TARBALL_SHA512 =
            "ee22b335fcbc95662cdf3ab8a053daf045d9cf9c6df6040d28965abb707512b2" +
                "c16fa6c5eec049d34c74f78f390cebd14f697919eadb97756564d4f9eccc4954"
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}
