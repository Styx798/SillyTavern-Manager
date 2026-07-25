import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class ExtractThirdPartyLicenses @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputAar: RegularFileProperty

    @get:Input
    abstract val artifactDirectoryName: org.gradle.api.provider.Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val targetDirectory = artifactDirectoryName.get()
        files.sync {
            from(archives.zipTree(inputAar)) {
                include(
                    "META-INF/LICENSE",
                    "META-INF/LICENSE.txt",
                    "META-INF/NOTICE.txt",
                    "META-INF/LICENSE.node",
                    "META-INF/LICENSE.v8",
                )
                eachFile {
                    path = "third_party/$targetDirectory/$name"
                }
                includeEmptyDirs = false
            }
            into(outputDirectory)
        }
    }
}

@DisableCachingByDefault(because = "This task verifies immutable checked-in npm tool assets")
abstract class VerifyBundledNpmToolAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseInventoryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val supplementalLicenseInventoryFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val supplementalLicenseDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val archive = requireRegularNoFollow(archiveFile.get().asFile, "npm tool archive")
        val manifest = requireRegularNoFollow(manifestFile.get().asFile, "npm tool manifest")
        val inventory = requireRegularNoFollow(
            licenseInventoryFile.get().asFile,
            "npm package license inventory",
        )
        val supplementalInventory = requireRegularNoFollow(
            supplementalLicenseInventoryFile.get().asFile,
            "npm supplemental license inventory",
        )
        val supplementalDirectory = supplementalLicenseDirectory.get().asFile
        verifySupplementalLicenses(supplementalInventory, supplementalDirectory)

        val fields = parseStrictManifest(Files.readAllBytes(manifest.toPath()))
        verifyFixedManifestFields(fields)

        val archiveIdentity = hashFile(archive)
        requireManifestLong(fields, "archive_bytes", archiveIdentity.bytes)
        requireManifestValue(fields, "archive_sha256", archiveIdentity.sha256)

        val inventoryIdentity = hashFile(inventory)
        requireManifestLong(fields, "license_inventory_bytes", inventoryIdentity.bytes)
        requireManifestValue(fields, "license_inventory_sha256", inventoryIdentity.sha256)

        val criticalEntries = REQUIRED_ARCHIVE_ENTRIES.mapValues { (prefix, expected) ->
            requireManifestValue(fields, "${prefix}_path", expected.path)
            val manifestBytes = requireManifestLong(fields, "${prefix}_bytes")
            val manifestSha256 = requireManifestSha256(fields, "${prefix}_sha256")
            if (manifestBytes != expected.bytes || manifestSha256 != expected.sha256) {
                throw GradleException(
                    "Bundled npm identity changed for ${expected.path}: " +
                        "expected ${expected.bytes}/${expected.sha256}, " +
                        "manifest declared $manifestBytes/$manifestSha256",
                )
            }
            expected
        }

        val tree = verifyArchive(archive, criticalEntries)
        requireManifestLong(fields, "file_count", tree.fileCount.toLong())
        requireManifestLong(fields, "directory_count", tree.directoryCount.toLong())
        requireManifestLong(fields, "total_file_bytes", tree.totalFileBytes)
        requireManifestValue(fields, "tree_sha256", tree.treeSha256)

        if (tree.fileCount != EXPECTED_FILE_COUNT ||
            tree.directoryCount != EXPECTED_DIRECTORY_COUNT ||
            tree.totalFileBytes != EXPECTED_TOTAL_FILE_BYTES
        ) {
            throw GradleException(
                "Bundled npm tree shape changed: files=${tree.fileCount}, " +
                    "directories=${tree.directoryCount}, bytes=${tree.totalFileBytes}",
            )
        }

        logger.lifecycle(
            "Verified bundled npm $NPM_VERSION asset: " +
                "${archiveIdentity.bytes} archive bytes, ${tree.fileCount} files, " +
                "${tree.directoryCount} directories, tree ${tree.treeSha256}",
        )
    }

    private fun verifyFixedManifestFields(fields: Map<String, String>) {
        FIXED_MANIFEST_VALUES.forEach { (key, expected) ->
            requireManifestValue(fields, key, expected)
        }
        requireManifestLong(fields, "source_tarball_bytes", EXPECTED_SOURCE_TARBALL_BYTES)
        requireManifestLong(fields, "license_gap_count", EXPECTED_LICENSE_GAP_COUNT)
        requireManifestSha256(fields, "archive_sha256")
        requireManifestSha256(fields, "tree_sha256")
        requireManifestSha256(fields, "license_inventory_sha256")
    }

    private fun verifyArchive(
        archive: File,
        requiredEntries: Map<String, ExpectedArchiveEntry>,
    ): TreeVerification {
        ZipFile(archive).use { zip ->
            val entries = Collections.list(zip.entries())
            if (entries.isEmpty()) throw GradleException("Bundled npm archive is empty")

            val seenArchiveNames = linkedSetOf<String>()
            val seenTreePaths = linkedSetOf<String>()
            val seenDirectories = linkedSetOf<String>()
            val observedCritical = linkedMapOf<String, ByteIdentity>()
            val treeDigest = MessageDigest.getInstance(SHA_256)
            var previousTreePath: String? = null
            var fileCount = 0
            var directoryCount = 0
            var totalFileBytes = 0L

            entries.forEach { entry ->
                val archiveName = entry.name
                validateArchiveName(archiveName, entry.isDirectory)
                val treePath = if (entry.isDirectory) archiveName.dropLast(1) else archiveName
                previousTreePath?.let { previous ->
                    if (previous >= treePath) {
                        throw GradleException(
                            "Bundled npm ZIP tree paths are not in strict byte order: " +
                                "$previous then $treePath",
                        )
                    }
                }
                previousTreePath = treePath
                if (!seenArchiveNames.add(archiveName)) {
                    throw GradleException("Bundled npm ZIP contains duplicate entry $archiveName")
                }
                if (entry.extra?.isNotEmpty() == true || !entry.comment.isNullOrEmpty()) {
                    throw GradleException("Bundled npm ZIP entry contains extra metadata: $archiveName")
                }

                if (!seenTreePaths.add(treePath)) {
                    throw GradleException("Bundled npm tree contains conflicting path $treePath")
                }

                if (entry.isDirectory) {
                    if (entry.method != ZipEntry.STORED ||
                        entry.size != 0L ||
                        entry.compressedSize != 0L ||
                        entry.crc != 0L
                    ) {
                        throw GradleException(
                            "Bundled npm ZIP directory must be empty and STORED: $archiveName",
                        )
                    }
                    seenDirectories += treePath
                    directoryCount += 1
                    updateTreeDigest(treeDigest, "D\u0000$treePath\u0000")
                } else {
                    if (entry.method != ZipEntry.DEFLATED) {
                        throw GradleException(
                            "Bundled npm ZIP file entry is not DEFLATED: $archiveName",
                        )
                    }
                    val parent = treePath.substringBeforeLast('/', missingDelimiterValue = "")
                    if (parent !in seenDirectories) {
                        throw GradleException(
                            "Bundled npm file appears before or without its directory: $treePath",
                        )
                    }
                    val identity = hashZipEntry(zip, entry)
                    fileCount += 1
                    totalFileBytes = try {
                        Math.addExact(totalFileBytes, identity.bytes)
                    } catch (error: ArithmeticException) {
                        throw GradleException("Bundled npm byte accounting overflowed", error)
                    }
                    updateTreeDigest(
                        treeDigest,
                        "F\u0000$treePath\u0000${identity.bytes}\u0000${identity.sha256}\u0000",
                    )
                    if (requiredEntries.values.any { it.path == treePath }) {
                        observedCritical[treePath] = identity
                    }
                }
            }

            if (seenDirectories.firstOrNull() != ARCHIVE_ROOT ||
                seenDirectories.count { !it.startsWith("$ARCHIVE_ROOT/") && it != ARCHIVE_ROOT } != 0
            ) {
                throw GradleException("Bundled npm ZIP must contain the single root $ARCHIVE_ROOT/")
            }
            if (entries.size != fileCount + directoryCount) {
                throw GradleException("Bundled npm ZIP entry accounting did not close")
            }

            requiredEntries.values.forEach { expected ->
                val observed = observedCritical[expected.path]
                    ?: throw GradleException("Bundled npm ZIP is missing ${expected.path}")
                if (observed.bytes != expected.bytes || observed.sha256 != expected.sha256) {
                    throw GradleException(
                        "Bundled npm ZIP entry changed for ${expected.path}: " +
                            "observed ${observed.bytes}/${observed.sha256}",
                    )
                }
            }

            return TreeVerification(
                fileCount = fileCount,
                directoryCount = directoryCount,
                totalFileBytes = totalFileBytes,
                treeSha256 = treeDigest.digest().toHex(),
            )
        }
    }

    private fun hashZipEntry(zip: ZipFile, entry: ZipEntry): ByteIdentity {
        if (entry.size < 0L || entry.crc < 0L) {
            throw GradleException("Bundled npm ZIP entry lacks bounded size or CRC: ${entry.name}")
        }
        val digest = MessageDigest.getInstance(SHA_256)
        val crc = CRC32()
        var observed = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                observed = Math.addExact(observed, count.toLong())
                if (observed > entry.size) {
                    throw GradleException("Bundled npm ZIP entry exceeded its declared size: ${entry.name}")
                }
                digest.update(buffer, 0, count)
                crc.update(buffer, 0, count)
            }
        }
        if (observed != entry.size || crc.value != entry.crc) {
            throw GradleException("Bundled npm ZIP size or CRC mismatch: ${entry.name}")
        }
        return ByteIdentity(observed, digest.digest().toHex())
    }

    private fun parseStrictManifest(bytes: ByteArray): Map<String, String> {
        if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) {
            throw GradleException("npm tool manifest size is outside policy")
        }
        if (bytes.last() != '\n'.code.toByte() || bytes.any { byte ->
                val value = byte.toInt() and 0xff
                value != 0x0a && value !in 0x20..0x7e
            }
        ) {
            throw GradleException("npm tool manifest must be newline-terminated printable ASCII")
        }
        val decoder = StandardCharsets.US_ASCII.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw GradleException("npm tool manifest is not strict ASCII", error)
        }
        val fields = linkedMapOf<String, String>()
        text.dropLast(1).split('\n').forEachIndexed { index, line ->
            val separator = line.indexOf('=')
            if (separator <= 0 || separator == line.lastIndex) {
                throw GradleException("Invalid npm tool manifest line ${index + 1}")
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (!MANIFEST_KEY_PATTERN.matches(key) || value != value.trim()) {
                throw GradleException("Invalid npm tool manifest field $key")
            }
            if (fields.putIfAbsent(key, value) != null) {
                throw GradleException("Duplicate npm tool manifest field $key")
            }
        }
        if (fields.keys.toList() != REQUIRED_MANIFEST_KEYS) {
            throw GradleException(
                "npm tool manifest keys or ordering changed: " +
                    "expected $REQUIRED_MANIFEST_KEYS, observed ${fields.keys}",
            )
        }
        return fields
    }

    private fun requireManifestValue(fields: Map<String, String>, key: String, expected: String) {
        val observed = fields[key]
            ?: throw GradleException("npm tool manifest is missing $key")
        if (observed != expected) {
            throw GradleException("npm tool manifest $key changed: expected $expected, observed $observed")
        }
    }

    private fun requireManifestLong(
        fields: Map<String, String>,
        key: String,
        expected: Long? = null,
    ): Long {
        val value = fields[key]
            ?: throw GradleException("npm tool manifest is missing $key")
        if (!DECIMAL_PATTERN.matches(value)) {
            throw GradleException("npm tool manifest $key is not canonical decimal")
        }
        val number = value.toLongOrNull()
            ?: throw GradleException("npm tool manifest $key exceeds Long range")
        if (expected != null && number != expected) {
            throw GradleException("npm tool manifest $key changed: expected $expected, observed $number")
        }
        return number
    }

    private fun requireManifestSha256(fields: Map<String, String>, key: String): String {
        val value = fields[key]
            ?: throw GradleException("npm tool manifest is missing $key")
        if (!SHA256_PATTERN.matches(value)) {
            throw GradleException("npm tool manifest $key is not lowercase SHA-256")
        }
        return value
    }

    private fun requireRegularNoFollow(file: File, label: String): File {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw GradleException("$label is missing, linked, or not a regular file: $file")
        }
        return file
    }

    private fun hashFile(file: File): ByteIdentity = file.inputStream().use(::hashStream)

    private fun verifySupplementalLicenses(inventory: File, directory: File) {
        val inventoryIdentity = hashFile(inventory)
        if (inventoryIdentity.bytes != EXPECTED_SUPPLEMENTAL_INVENTORY_BYTES ||
            inventoryIdentity.sha256 != EXPECTED_SUPPLEMENTAL_INVENTORY_SHA256
        ) {
            throw GradleException(
                "npm supplemental license inventory changed: " +
                    "${inventoryIdentity.bytes}/${inventoryIdentity.sha256}",
            )
        }

        val directoryPath = directory.toPath()
        if (Files.isSymbolicLink(directoryPath) ||
            !Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw GradleException("npm supplemental license directory is missing or linked")
        }
        val children = Files.list(directoryPath).use { stream ->
            stream.sorted().toList()
        }
        val observedNames = children.map { it.fileName.toString() }
        if (observedNames != EXPECTED_SUPPLEMENTAL_LICENSES.keys.sorted()) {
            throw GradleException(
                "npm supplemental license file set changed: $observedNames",
            )
        }
        children.forEach { path ->
            val name = path.fileName.toString()
            val expected = EXPECTED_SUPPLEMENTAL_LICENSES.getValue(name)
            val file = requireRegularNoFollow(path.toFile(), "npm supplemental license $name")
            val identity = hashFile(file)
            if (identity.bytes != expected.bytes || identity.sha256 != expected.sha256) {
                throw GradleException(
                    "npm supplemental license changed for $name: " +
                        "${identity.bytes}/${identity.sha256}",
                )
            }
        }
    }

    private fun hashStream(input: InputStream): ByteIdentity {
        val digest = MessageDigest.getInstance(SHA_256)
        var observed = 0L
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            observed = Math.addExact(observed, count.toLong())
            digest.update(buffer, 0, count)
        }
        return ByteIdentity(observed, digest.digest().toHex())
    }

    private fun validateArchiveName(name: String, directory: Boolean) {
        if (name.isEmpty() || name.any { it.code !in 0x20..0x7e } || '\\' in name) {
            throw GradleException("Bundled npm ZIP has a non-portable entry name: $name")
        }
        if (directory != name.endsWith('/')) {
            throw GradleException("Bundled npm ZIP directory marker is inconsistent: $name")
        }
        val normalized = if (directory) name.dropLast(1) else name
        val segments = normalized.split('/')
        if (normalized.startsWith('/') ||
            segments.any { it.isEmpty() || it == "." || it == ".." } ||
            (normalized != ARCHIVE_ROOT && !normalized.startsWith("$ARCHIVE_ROOT/"))
        ) {
            throw GradleException("Bundled npm ZIP entry escaped its single root: $name")
        }
    }

    private fun updateTreeDigest(digest: MessageDigest, record: String) {
        digest.update(record.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private data class ByteIdentity(
        val bytes: Long,
        val sha256: String,
    )

    private data class ExpectedArchiveEntry(
        val path: String,
        val bytes: Long,
        val sha256: String,
    )

    private data class TreeVerification(
        val fileCount: Int,
        val directoryCount: Int,
        val totalFileBytes: Long,
        val treeSha256: String,
    )

    companion object {
        private const val NPM_VERSION = "11.6.2"
        private const val ARCHIVE_ROOT = "npm"
        private const val SHA_256 = "SHA-256"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val EXPECTED_SOURCE_TARBALL_BYTES = 2_663_834L
        private const val EXPECTED_FILE_COUNT = 2_133
        private const val EXPECTED_DIRECTORY_COUNT = 549
        private const val EXPECTED_TOTAL_FILE_BYTES = 11_785_613L
        private const val EXPECTED_LICENSE_GAP_COUNT = 0L
        private const val EXPECTED_SUPPLEMENTAL_INVENTORY_BYTES = 9_307L
        private const val EXPECTED_SUPPLEMENTAL_INVENTORY_SHA256 =
            "6081c68a5fad9f801dafb1412cc42663150d83115997eeda3e2cdf1a29db59ab"

        private val MANIFEST_KEY_PATTERN = Regex("^[a-z][a-z0-9_]*$")
        private val DECIMAL_PATTERN = Regex("^(?:0|[1-9][0-9]*)$")
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        private val REQUIRED_ARCHIVE_ENTRIES = linkedMapOf(
            "npm_package_json" to ExpectedArchiveEntry(
                "npm/package.json",
                6_535,
                "7cbb6a7c030b398a7591992750cfb3e2479ef4f8aaa40316e3deb98af3b8184c",
            ),
            "npm_bin_cli" to ExpectedArchiveEntry(
                "npm/bin/npm-cli.js",
                54,
                "8e5f6f3429f8cdbe693cdc29904e9d5a7b127a494bd15c804bd54c7403bfcbe7",
            ),
            "npm_lib_cli" to ExpectedArchiveEntry(
                "npm/lib/cli.js",
                407,
                "67666f06479f9b0bbc01412c198caadd4287d34d5ed74a02871ea78f186451e7",
            ),
            "npm_lib_cli_entry" to ExpectedArchiveEntry(
                "npm/lib/cli/entry.js",
                2_993,
                "f34418faf1d1211173b730f68957bca2dcc5afe3bc31ac9c6769e6e6c266ad67",
            ),
            "npm_lib_npm" to ExpectedArchiveEntry(
                "npm/lib/npm.js",
                14_213,
                "d1bbdffe7f379040e7b10d20563f331b5c620f90d8eaf8353380e885bce844eb",
            ),
            "arborist_package_json" to ExpectedArchiveEntry(
                "npm/node_modules/@npmcli/arborist/package.json",
                2_754,
                "c4c6c72fce623a67a3d92c6940a14143b1f576ae78736c7117a4a722eae94c4f",
            ),
            "npm_license" to ExpectedArchiveEntry(
                "npm/LICENSE",
                9_742,
                "7610d223851f421d315df5e77974f1c68a04b97e02060e5bbbcf13d95e3ca257",
            ),
        )

        private val EXPECTED_SUPPLEMENTAL_LICENSES = linkedMapOf(
            "Apache-2.0-sigstore-verify-3.0.0.txt" to ExpectedArchiveEntry(
                "Apache-2.0-sigstore-verify-3.0.0.txt",
                11_351,
                "364a130d2ca340bd56eb1e6d045fc6929bb0f9d0aa018f2c1949b29517e1cdd0",
            ),
            "CC-BY-3.0.txt" to ExpectedArchiveEntry(
                "CC-BY-3.0.txt",
                19_467,
                "e6bc9e9c474700b708f568bac9e5a8a9bcb2b1dad53442f5ba449fcb848b8e76",
            ),
            "CC0-1.0.txt" to ExpectedArchiveEntry(
                "CC0-1.0.txt",
                7_048,
                "a2010f343487d3f7618affe54f789f5487602331c0a8d03f49e9a7c547cf0499",
            ),
            "ISC-isexe-3.1.1.txt" to ExpectedArchiveEntry(
                "ISC-isexe-3.1.1.txt",
                775,
                "6dab8081cbcd304cfe3958576d6680cb33f49d39a5f43c53a1d0cf3666d29bd3",
            ),
            "ISC-npmcli-agent.txt" to ExpectedArchiveEntry(
                "ISC-npmcli-agent.txt",
                737,
                "b89e3e25040333b6a432c5de8e40800225ae65cbda24bb7e9f423d49f2b8e958",
            ),
            "MIT-eastasianwidth-0.2.0.txt" to ExpectedArchiveEntry(
                "MIT-eastasianwidth-0.2.0.txt",
                1_067,
                "ebd470d05030aee19ce6ccef1d70dce6f00f182e268a026294388e7e6b4b8bc0",
            ),
            "MIT-err-code-2.0.3.txt" to ExpectedArchiveEntry(
                "MIT-err-code-2.0.3.txt",
                1_064,
                "5cfd203775f0b7ba0bd84e059d37b224c161497bf2a2d649a13fabce46c1c452",
            ),
            "MIT-imurmurhash-0.1.4.txt" to ExpectedArchiveEntry(
                "MIT-imurmurhash-0.1.4.txt",
                1_090,
                "fa0943ddcaa857d901a9eb92254d89876297ecdfbc884d294a927df08ebcbbe8",
            ),
            "NOTICE-spdx-exceptions-2.5.0.txt" to ExpectedArchiveEntry(
                "NOTICE-spdx-exceptions-2.5.0.txt",
                788,
                "a1ea6977d5204668dc1876c54c9397f38354ae1ddf69c33249dbfc131b3f3e45",
            ),
        )

        private val REQUIRED_MANIFEST_KEYS = buildList {
            addAll(
                listOf(
                    "format",
                    "tool",
                    "npm_version",
                    "node_requirement",
                    "tested_node_version",
                    "javet_version",
                    "abi",
                    "source_tarball_url",
                    "source_tarball_bytes",
                    "source_tarball_sha256",
                    "source_tarball_sha512",
                    "source_tarball_integrity",
                    "registry_git_head",
                    "registry_signature_status",
                    "archive_asset",
                    "archive_bytes",
                    "archive_sha256",
                    "tree_algorithm",
                    "tree_sha256",
                    "file_count",
                    "directory_count",
                    "total_file_bytes",
                    "root",
                ),
            )
            REQUIRED_ARCHIVE_ENTRIES.keys.forEach { prefix ->
                add("${prefix}_path")
                add("${prefix}_bytes")
                add("${prefix}_sha256")
            }
            addAll(
                listOf(
                    "license_inventory_asset",
                    "license_inventory_bytes",
                    "license_inventory_sha256",
                    "license_gap_count",
                ),
            )
        }

        private val FIXED_MANIFEST_VALUES = linkedMapOf(
            "format" to "STM-NPM-TOOL-ASSET-V1",
            "tool" to "npm",
            "npm_version" to NPM_VERSION,
            "node_requirement" to "^20.17.0 || >=22.9.0",
            "tested_node_version" to "24.17.0",
            "javet_version" to "5.0.9",
            "abi" to "arm64-v8a",
            "source_tarball_url" to "https://registry.npmjs.org/npm/-/npm-11.6.2.tgz",
            "source_tarball_sha256" to
                "585f95094ee5cb2788ee11d90f2a518a7c9ef6e083fa141d0b63ca3383675a20",
            "source_tarball_sha512" to
                "ee22b335fcbc95662cdf3ab8a053daf045d9cf9c6df6040d28965abb707512b2" +
                "c16fa6c5eec049d34c74f78f390cebd14f697919eadb97756564d4f9eccc4954",
            "source_tarball_integrity" to
                "sha512-7iKzNfy8lWYs3zq4oFPa8EXZz5xt9gQNKJZau3B1ErLBb6bF7sBJ00x09485" +
                "DOvRT2l5Gerbl3VlZNT57MxJVA==",
            "registry_git_head" to "5d41fb3a08249c7b40994c9f187fd25c241817ad",
            "registry_signature_status" to "metadata-present-unverified",
            "archive_asset" to "stm_core/tools/npm/11.6.2/npm-11.6.2.stmzip",
            "tree_algorithm" to "stm-tree-identity-v1",
            "root" to ARCHIVE_ROOT,
            "license_inventory_asset" to "third_party/npm-11.6.2/PACKAGE-LICENSES.json",
        )
    }
}

plugins {
    alias(libs.plugins.android.library)
}

val useJavetI18n = providers.gradleProperty("stmJavetI18n")
    .map(String::toBoolean)
    .orElse(true)
val javetArtifactName = useJavetI18n.map { enabled ->
    if (enabled) "javet-node-android-i18n" else "javet-node-android"
}
val javetArtifactDirectory = javetArtifactName.map { "$it-5.0.9" }

android {
    namespace = "io.github.styx798.sillytavernmanager.stmcore"
    compileSdk = 36

    defaultConfig {
        minSdk = 31

        ndk {
            abiFilters += "arm64-v8a"
        }

        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "STM_CORE_VERSION", "\"0.1.0\"")
        buildConfigField(
            "String",
            "JAVET_ARTIFACT",
            javetArtifactName.map { "\"$it\"" }.get(),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        // STM Core deliberately supports Android arm64-v8a only.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val javetLicenseArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val commonsCompressLicenseArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val commonsCodecLicenseArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val commonsIoLicenseArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val commonsLang3LicenseArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val verifyBundledNpmToolAsset by tasks.registering(VerifyBundledNpmToolAsset::class) {
    group = "verification"
    description = "Verifies the fixed offline npm tool archive, manifest, and license inventory"
    archiveFile.set(
        layout.projectDirectory.file(
            "src/main/assets/stm_core/tools/npm/11.6.2/npm-11.6.2.stmzip",
        ),
    )
    manifestFile.set(
        layout.projectDirectory.file(
            "src/main/assets/stm_core/tools/npm/11.6.2/npm-tool-manifest.stm",
        ),
    )
    licenseInventoryFile.set(
        layout.projectDirectory.file(
            "src/main/assets/third_party/npm-11.6.2/PACKAGE-LICENSES.json",
        ),
    )
    supplementalLicenseInventoryFile.set(
        layout.projectDirectory.file(
            "src/main/assets/third_party/npm-11.6.2/SUPPLEMENTAL-LICENSES.json",
        ),
    )
    supplementalLicenseDirectory.set(
        layout.projectDirectory.dir(
            "src/main/assets/third_party/npm-11.6.2/supplemental",
        ),
    )
}

tasks.named("preBuild").configure {
    dependsOn(verifyBundledNpmToolAsset)
}

val prepareThirdPartyLicenseAssets by tasks.registering(ExtractThirdPartyLicenses::class) {
    outputDirectory.set(layout.buildDirectory.dir("generated/third-party-license-assets"))
    inputAar.set(layout.file(javetLicenseArtifact.elements.map { it.single().asFile }))
    artifactDirectoryName.set(javetArtifactDirectory)
}

fun registerArchiveLicenseTask(
    taskName: String,
    configuration: Configuration,
    artifactDirectory: String,
) = tasks.register<ExtractThirdPartyLicenses>(taskName) {
    outputDirectory.set(layout.buildDirectory.dir("generated/$taskName"))
    inputAar.set(layout.file(configuration.elements.map { it.single().asFile }))
    artifactDirectoryName.set(artifactDirectory)
}

val prepareCommonsCompressLicenseAssets = registerArchiveLicenseTask(
    "prepareCommonsCompressLicenseAssets",
    commonsCompressLicenseArtifact,
    "commons-compress-1.28.0",
)
val prepareCommonsCodecLicenseAssets = registerArchiveLicenseTask(
    "prepareCommonsCodecLicenseAssets",
    commonsCodecLicenseArtifact,
    "commons-codec-1.19.0",
)
val prepareCommonsIoLicenseAssets = registerArchiveLicenseTask(
    "prepareCommonsIoLicenseAssets",
    commonsIoLicenseArtifact,
    "commons-io-2.20.0",
)
val prepareCommonsLang3LicenseAssets = registerArchiveLicenseTask(
    "prepareCommonsLang3LicenseAssets",
    commonsLang3LicenseArtifact,
    "commons-lang3-3.18.0",
)

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareThirdPartyLicenseAssets,
            ExtractThirdPartyLicenses::outputDirectory,
        )
        listOf(
            prepareCommonsCompressLicenseAssets,
            prepareCommonsCodecLicenseAssets,
            prepareCommonsIoLicenseAssets,
            prepareCommonsLang3LicenseAssets,
        ).forEach { task ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                task,
                ExtractThirdPartyLicenses::outputDirectory,
            )
        }
    }
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.bouncy.castle)
    commonsCompressLicenseArtifact(libs.commons.compress) { isTransitive = false }
    commonsCodecLicenseArtifact(libs.commons.codec) { isTransitive = false }
    commonsIoLicenseArtifact(libs.commons.io) { isTransitive = false }
    commonsLang3LicenseArtifact(libs.commons.lang3) { isTransitive = false }

    if (useJavetI18n.get()) {
        implementation(libs.javet.node.android.i18n)
        javetLicenseArtifact(libs.javet.node.android.i18n) {
            isTransitive = false
        }
    } else {
        implementation(libs.javet.node.android)
        javetLicenseArtifact(libs.javet.node.android) {
            isTransitive = false
        }
    }

    testImplementation(libs.junit)
}
