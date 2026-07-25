package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal data class StmBundledNpmAssetBinding(
    val assetName: String,
    val bytes: Long,
    val sha256: String,
) {
    init {
        requireSafeAssetName(assetName)
        require(bytes in 1..MAX_MANIFEST_BYTES) { "Manifest asset length is outside bounds" }
        require(SHA256_PATTERN.matches(sha256)) { "Manifest asset SHA-256 is invalid" }
    }
}

internal fun interface StmBundledNpmAssetSource {
    @Throws(IOException::class)
    fun open(assetName: String): InputStream
}

internal enum class StmBundledNpmToolchainErrorCode {
    INVALID_MANIFEST,
    ASSET_UNAVAILABLE,
    ASSET_LENGTH_MISMATCH,
    ASSET_SHA256_MISMATCH,
    ARCHIVE_REJECTED,
    TREE_MISMATCH,
    REQUIRED_ENTRY_MISMATCH,
    EXISTING_TARGET_INVALID,
    TARGET_APPEARED,
    ATOMIC_RENAME_FAILED,
    OPERATION_CANCELLED,
    IO_FAILURE,
}

internal class StmBundledNpmToolchainException(
    val code: StmBundledNpmToolchainErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class StmPreparedNpmToolchain(
    val npmVersion: String,
    val toolchainDirectory: File,
    val npmDirectory: File,
    val manifestSha256: String,
    val archiveSha256: String,
    val treeSha256: String,
    val fileCount: Int,
    val directoryCount: Int,
    val totalFileBytes: Long,
    val licenseInventoryAsset: String,
    val licenseInventorySha256: String,
    val licenseGapCount: Int,
    val reused: Boolean,
)

/**
 * Materializes one build-bound npm toolchain into a Core-private immutable version directory.
 *
 * The manifest identity is supplied by trusted application code. The manifest then binds both the
 * deterministic ZIP and its license inventory. A matching existing target is scanned and hashed in
 * full; an invalid target is evidence and is never replaced or deleted. New content is extracted
 * beneath [stagingRoot] and committed only by a requested atomic same-filesystem directory move.
 * There is no copy fallback and this publisher never requests target replacement.
 */
internal class StmBundledNpmToolchain(
    storeRoot: File,
    stagingRoot: File,
    private val manifestAsset: StmBundledNpmAssetBinding,
    private val assetSource: StmBundledNpmAssetSource,
    private val zipExtractor: StmSafeZipExtractor = StmSafeZipExtractor(),
    private val directoryRenamer: (Path, Path) -> Boolean = { source, target ->
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: IOException) {
            false
        }
    },
) {
    private val storeRoot: Path = initializeToolchainRoot(storeRoot.toPath())
    private val stagingRoot: Path = initializeToolchainRoot(stagingRoot.toPath())

    init {
        require(this.storeRoot != this.stagingRoot) {
            "Toolchain store and staging roots must be different"
        }
        require(!this.storeRoot.startsWith(this.stagingRoot)) {
            "Toolchain store cannot be nested inside staging"
        }
        require(!this.stagingRoot.startsWith(this.storeRoot)) {
            "Toolchain staging cannot be nested inside the store"
        }
    }

    @Synchronized
    fun prepare(
        cancellation: StmExtractionCancellation = StmExtractionCancellation.NONE,
    ): StmPreparedNpmToolchain {
        val manifestBytes = readVerifiedAsset(
            binding = manifestAsset,
            maximumBytes = MAX_MANIFEST_BYTES,
            cancellation = cancellation,
        )
        val manifest = StmBundledNpmManifest.parse(manifestBytes)
        if (
            manifest.archiveAsset == manifestAsset.assetName ||
            manifest.licenseInventoryAsset == manifestAsset.assetName ||
            manifest.archiveAsset == manifest.licenseInventoryAsset
        ) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.INVALID_MANIFEST,
                "Manifest, archive, and license inventory assets must be distinct",
            )
        }

        verifyAsset(
            name = manifest.licenseInventoryAsset,
            expectedBytes = manifest.licenseInventoryBytes,
            expectedSha256 = manifest.licenseInventorySha256,
            maximumBytes = MAX_LICENSE_INVENTORY_BYTES,
            cancellation = cancellation,
        )

        val target = directChild(
            storeRoot,
            "${manifest.npmVersion}-${manifest.treeSha256}",
        )
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                validateTree(target, manifest, cancellation)
            } catch (error: StmBundledNpmToolchainException) {
                if (error.code == StmBundledNpmToolchainErrorCode.OPERATION_CANCELLED) throw error
                throw toolchainFailure(
                    StmBundledNpmToolchainErrorCode.EXISTING_TARGET_INVALID,
                    "Existing npm toolchain target failed immutable verification",
                    error,
                )
            } catch (error: Exception) {
                throw toolchainFailure(
                    StmBundledNpmToolchainErrorCode.EXISTING_TARGET_INVALID,
                    "Existing npm toolchain target failed immutable verification",
                    error,
                )
            }
            verifyAsset(
                name = manifest.archiveAsset,
                expectedBytes = manifest.archiveBytes,
                expectedSha256 = manifest.archiveSha256,
                maximumBytes = MAX_ARCHIVE_BYTES,
                cancellation = cancellation,
            )
            return manifest.prepared(
                target = target,
                manifestSha256 = manifestAsset.sha256,
                reused = true,
            )
        }

        val nonce = UUID.randomUUID().toString()
        val incoming = directChild(stagingRoot, ".npm-incoming-$nonce.stmzip")
        val operationRoot = directChild(stagingRoot, ".npm-extract-$nonce")
        var primaryFailure: StmBundledNpmToolchainException? = null

        try {
            copyVerifiedAsset(
                name = manifest.archiveAsset,
                expectedBytes = manifest.archiveBytes,
                expectedSha256 = manifest.archiveSha256,
                target = incoming,
                cancellation = cancellation,
            )
            val extracted = try {
                zipExtractor.extract(
                    artifact = incoming.toFile(),
                    operationStagingRoot = operationRoot.toFile(),
                    policy = manifest.extractionPolicy(),
                    cancellation = cancellation,
                )
            } catch (error: StmZipExtractionException) {
                if (error.code == StmZipErrorCode.OPERATION_CANCELLED) {
                    throw toolchainFailure(
                        StmBundledNpmToolchainErrorCode.OPERATION_CANCELLED,
                        "npm toolchain preparation was cancelled",
                        error,
                    )
                }
                throw toolchainFailure(
                    StmBundledNpmToolchainErrorCode.ARCHIVE_REJECTED,
                    "Bundled npm archive failed safe extraction",
                    error,
                )
            }
            validateExtraction(extracted, manifest)
            validateRequiredEntries(extracted.entries, manifest)
            syncDirectoryTree(extracted.payloadDirectory.toPath())

            synchronized(NPM_TOOLCHAIN_PUBLISH_MONITOR) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw toolchainFailure(
                        StmBundledNpmToolchainErrorCode.TARGET_APPEARED,
                        "npm toolchain target appeared before commit and was not replaced",
                    )
                }
                if (!directoryRenamer(extracted.payloadDirectory.toPath(), target)) {
                    throw toolchainFailure(
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            StmBundledNpmToolchainErrorCode.TARGET_APPEARED
                        } else {
                            StmBundledNpmToolchainErrorCode.ATOMIC_RENAME_FAILED
                        },
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            "npm toolchain target appeared during commit and was not replaced"
                        } else {
                            "The filesystem did not complete the atomic npm toolchain move"
                        },
                    )
                }
            }
            syncDirectoryBestEffort(storeRoot)
            validateTree(target, manifest, cancellation)
            return manifest.prepared(
                target = target,
                manifestSha256 = manifestAsset.sha256,
                reused = false,
            )
        } catch (error: StmBundledNpmToolchainException) {
            primaryFailure = error
            throw error
        } catch (error: IOException) {
            val wrapped = toolchainFailure(
                StmBundledNpmToolchainErrorCode.IO_FAILURE,
                "npm toolchain preparation failed during I/O",
                error,
            )
            primaryFailure = wrapped
            throw wrapped
        } catch (error: RuntimeException) {
            val wrapped = toolchainFailure(
                StmBundledNpmToolchainErrorCode.IO_FAILURE,
                "npm toolchain preparation failed",
                error,
            )
            primaryFailure = wrapped
            throw wrapped
        } finally {
            val cleanupErrors = listOf(incoming, operationRoot).mapNotNull { path ->
                runCatching { deleteDirectStagingEntry(path) }.exceptionOrNull()
            }
            if (cleanupErrors.isNotEmpty()) {
                val cleanupFailure = toolchainFailure(
                    StmBundledNpmToolchainErrorCode.IO_FAILURE,
                    "npm toolchain staging cleanup failed",
                    cleanupErrors.first(),
                )
                cleanupErrors.drop(1).forEach(cleanupFailure::addSuppressed)
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun copyVerifiedAsset(
        name: String,
        expectedBytes: Long,
        expectedSha256: String,
        target: Path,
        cancellation: StmExtractionCancellation,
    ) {
        if (expectedBytes > MAX_ARCHIVE_BYTES) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.INVALID_MANIFEST,
                "Archive length exceeds the toolchain policy",
            )
        }
        val digest = MessageDigest.getInstance(SHA_256)
        var observed = 0L
        val source = openAsset(name)
        try {
            source.use { input ->
                FileChannel.open(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        cancellation.throwIfToolchainCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        observed = checkedAssetLength(observed, count, expectedBytes)
                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                        while (bytes.hasRemaining()) {
                            if (output.write(bytes) == 0) Thread.yield()
                        }
                        digest.update(buffer, 0, count)
                    }
                    cancellation.throwIfToolchainCancelled()
                    output.force(true)
                }
            }
        } catch (error: StmBundledNpmToolchainException) {
            throw error
        } catch (error: IOException) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.IO_FAILURE,
                "Bundled npm archive could not be copied",
                error,
            )
        }
        verifyObservedAsset(name, observed, digest.digest().toHex(), expectedBytes, expectedSha256)
        syncDirectoryBestEffort(stagingRoot)
    }

    private fun readVerifiedAsset(
        binding: StmBundledNpmAssetBinding,
        maximumBytes: Long,
        cancellation: StmExtractionCancellation,
    ): ByteArray {
        val output = ByteArrayOutputStream(binding.bytes.toInt())
        readAndVerifyAsset(
            name = binding.assetName,
            expectedBytes = binding.bytes,
            expectedSha256 = binding.sha256,
            maximumBytes = maximumBytes,
            cancellation = cancellation,
        ) { buffer, count -> output.write(buffer, 0, count) }
        return output.toByteArray()
    }

    private fun verifyAsset(
        name: String,
        expectedBytes: Long,
        expectedSha256: String,
        maximumBytes: Long,
        cancellation: StmExtractionCancellation,
    ) {
        readAndVerifyAsset(
            name = name,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
            maximumBytes = maximumBytes,
            cancellation = cancellation,
        ) { _, _ -> }
    }

    private inline fun readAndVerifyAsset(
        name: String,
        expectedBytes: Long,
        expectedSha256: String,
        maximumBytes: Long,
        cancellation: StmExtractionCancellation,
        consume: (ByteArray, Int) -> Unit,
    ) {
        if (expectedBytes !in 1..maximumBytes || !SHA256_PATTERN.matches(expectedSha256)) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.INVALID_MANIFEST,
                "Asset identity is outside toolchain policy",
            )
        }
        requireSafeAssetName(name)
        val digest = MessageDigest.getInstance(SHA_256)
        var observed = 0L
        val source = openAsset(name)
        try {
            source.use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    cancellation.throwIfToolchainCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    observed = checkedAssetLength(observed, count, expectedBytes)
                    digest.update(buffer, 0, count)
                    consume(buffer, count)
                }
            }
        } catch (error: StmBundledNpmToolchainException) {
            throw error
        } catch (error: IOException) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.IO_FAILURE,
                "Bundled npm asset could not be read",
                error,
            )
        }
        verifyObservedAsset(name, observed, digest.digest().toHex(), expectedBytes, expectedSha256)
    }

    private fun openAsset(name: String): InputStream = try {
        assetSource.open(name)
    } catch (error: Exception) {
        throw toolchainFailure(
            StmBundledNpmToolchainErrorCode.ASSET_UNAVAILABLE,
            "Bundled npm asset is unavailable",
            error,
        )
    }

    private fun validateExtraction(
        extraction: StmZipExtractionResult,
        manifest: StmBundledNpmManifest,
    ) {
        validateSingleRoot(extraction.entries, manifest.root)
        if (
            extraction.fileCount != manifest.fileCount ||
            extraction.directoryCount != manifest.directoryCount ||
            extraction.totalFileBytes != manifest.totalFileBytes ||
            extraction.manifestSha256 != manifest.treeSha256
        ) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
                "Extracted npm tree identity did not match the bundled manifest",
            )
        }
    }

    private fun validateTree(
        target: Path,
        manifest: StmBundledNpmManifest,
        cancellation: StmExtractionCancellation,
    ) {
        val attributes = Files.readAttributes(
            target,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
                "npm toolchain target must be a real directory",
            )
        }
        val entries = scanTree(target, cancellation)
        validateSingleRoot(entries, manifest.root)
        val files = entries.count { it.type == StmZipManifestEntryType.FILE }
        val directories = entries.count { it.type == StmZipManifestEntryType.DIRECTORY }
        val total = entries.asSequence()
            .filter { it.type == StmZipManifestEntryType.FILE }
            .fold(0L) { sum, entry -> Math.addExact(sum, entry.sizeBytes) }
        if (
            files != manifest.fileCount ||
            directories != manifest.directoryCount ||
            total != manifest.totalFileBytes ||
            stmTreeIdentitySha256(entries) != manifest.treeSha256
        ) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
                "npm toolchain target differs from its immutable tree identity",
            )
        }
        validateRequiredEntries(entries, manifest)
    }

    private fun scanTree(
        target: Path,
        cancellation: StmExtractionCancellation,
    ): List<StmZipManifestEntry> {
        val entries = mutableListOf<StmZipManifestEntry>()
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                cancellation.throwIfToolchainCancelled()
                if (!attributes.isDirectory || attributes.isSymbolicLink) {
                    throw toolchainFailure(
                        StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
                        "npm toolchain contains an unsafe directory",
                    )
                }
                if (directory != target) {
                    entries += StmZipManifestEntry(
                        relativePath = toolchainRelativePath(target, directory),
                        type = StmZipManifestEntryType.DIRECTORY,
                        sizeBytes = 0,
                        sha256 = null,
                    )
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                cancellation.throwIfToolchainCancelled()
                if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                    throw toolchainFailure(
                        StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
                        "npm toolchain contains a link or special file",
                    )
                }
                entries += StmZipManifestEntry(
                    relativePath = toolchainRelativePath(target, file),
                    type = StmZipManifestEntryType.FILE,
                    sizeBytes = attributes.size(),
                    sha256 = hashStableFile(file, attributes.size(), cancellation),
                )
                return FileVisitResult.CONTINUE
            }
        })
        return entries.sortedBy(StmZipManifestEntry::relativePath)
    }

    private fun hashStableFile(
        file: Path,
        expectedBytes: Long,
        cancellation: StmExtractionCancellation,
    ): String {
        val digest = MessageDigest.getInstance(SHA_256)
        Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                cancellation.throwIfToolchainCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val after = Files.readAttributes(
            file,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!after.isRegularFile || after.isSymbolicLink || after.size() != expectedBytes) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
                "npm toolchain file changed while it was verified",
            )
        }
        return digest.digest().toHex()
    }

    private fun validateSingleRoot(entries: List<StmZipManifestEntry>, root: String) {
        val rootEntry = entries.singleOrNull { it.relativePath == root }
        if (
            rootEntry?.type != StmZipManifestEntryType.DIRECTORY ||
            entries.any { it.relativePath != root && !it.relativePath.startsWith("$root/") }
        ) {
            throw toolchainFailure(
                StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
                "Bundled npm tree must contain exactly one npm root",
            )
        }
    }

    private fun validateRequiredEntries(
        entries: List<StmZipManifestEntry>,
        manifest: StmBundledNpmManifest,
    ) {
        val byPath = entries.associateBy(StmZipManifestEntry::relativePath)
        manifest.requiredEntries.forEach { expected ->
            val actual = byPath[expected.path]
            if (
                actual?.type != StmZipManifestEntryType.FILE ||
                actual.sizeBytes != expected.bytes ||
                actual.sha256 != expected.sha256
            ) {
                throw toolchainFailure(
                    StmBundledNpmToolchainErrorCode.REQUIRED_ENTRY_MISMATCH,
                    "A required npm entry did not match its manifest binding",
                )
            }
        }
    }

    private fun syncDirectoryTree(root: Path) {
        val directories = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                directories.add(directory)
                return FileVisitResult.CONTINUE
            }
        })
        directories.asReversed().forEach(::syncDirectoryBestEffort)
    }

    private fun deleteDirectStagingEntry(path: Path) {
        require(path.parent == stagingRoot) { "Only direct toolchain staging entries may be removed" }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                throw error
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                error?.let { throw it }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

private data class StmBundledNpmRequiredEntry(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

private data class StmBundledNpmManifest(
    val npmVersion: String,
    val archiveAsset: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val treeSha256: String,
    val fileCount: Int,
    val directoryCount: Int,
    val totalFileBytes: Long,
    val root: String,
    val requiredEntries: List<StmBundledNpmRequiredEntry>,
    val licenseInventoryAsset: String,
    val licenseInventoryBytes: Long,
    val licenseInventorySha256: String,
    val licenseGapCount: Int,
) {
    fun extractionPolicy(): StmZipExtractionPolicy = StmZipExtractionPolicy(
        maxArchiveBytes = archiveBytes,
        maxEntries = Math.addExact(fileCount, directoryCount),
        maxPathNodes = Math.addExact(fileCount, directoryCount),
        maxSingleFileBytes = totalFileBytes,
        maxTotalUncompressedBytes = totalFileBytes,
    )

    fun prepared(
        target: Path,
        manifestSha256: String,
        reused: Boolean,
    ): StmPreparedNpmToolchain = StmPreparedNpmToolchain(
        npmVersion = npmVersion,
        toolchainDirectory = target.toFile(),
        npmDirectory = target.resolve(root).toFile(),
        manifestSha256 = manifestSha256,
        archiveSha256 = archiveSha256,
        treeSha256 = treeSha256,
        fileCount = fileCount,
        directoryCount = directoryCount,
        totalFileBytes = totalFileBytes,
        licenseInventoryAsset = licenseInventoryAsset,
        licenseInventorySha256 = licenseInventorySha256,
        licenseGapCount = licenseGapCount,
        reused = reused,
    )

    companion object {
        fun parse(bytes: ByteArray): StmBundledNpmManifest {
            val text = decodeStrictUtf8(bytes)
            if (
                text.isEmpty() ||
                !text.endsWith('\n') ||
                '\r' in text ||
                text.dropLast(1).any { it == '\u0000' || it.isISOControl() && it != '\n' }
            ) {
                invalidManifest("Manifest must be strict UTF-8 text with LF line endings")
            }
            val lines = text.dropLast(1).split('\n')
            if (lines.size != MANIFEST_KEYS.size || lines.any(String::isEmpty)) {
                invalidManifest("Manifest line count did not match its fixed schema")
            }
            val values = linkedMapOf<String, String>()
            lines.forEachIndexed { index, line ->
                val separator = line.indexOf('=')
                if (separator <= 0) invalidManifest("Manifest line is not key=value")
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                if (key != MANIFEST_KEYS[index] || value.isEmpty() || value != value.trim()) {
                    invalidManifest("Manifest keys or values did not match the fixed schema")
                }
                values[key] = value
            }

            if (values.getValue("format") != MANIFEST_FORMAT) {
                invalidManifest("Manifest format is unsupported")
            }
            if (values.getValue("tool") != NPM_TOOL) invalidManifest("Manifest tool is not npm")
            val version = values.getValue("npm_version")
            if (!SEMVER_PATTERN.matches(version)) invalidManifest("npm version is invalid")
            requireBoundedText(values.getValue("node_requirement"), 128, "node requirement")
            if (!SEMVER_PATTERN.matches(values.getValue("tested_node_version"))) {
                invalidManifest("Tested Node version is invalid")
            }
            if (!SEMVER_PATTERN.matches(values.getValue("javet_version"))) {
                invalidManifest("Javet version is invalid")
            }
            if (values.getValue("abi") != REQUIRED_ABI) invalidManifest("ABI is unsupported")
            validateSourceUrl(values.getValue("source_tarball_url"), version)
            parsePositiveLong(values.getValue("source_tarball_bytes"), MAX_SOURCE_TARBALL_BYTES)
            requireSha256(values.getValue("source_tarball_sha256"))
            val sourceSha512 = requireSha512(values.getValue("source_tarball_sha512"))
            validateIntegrity(values.getValue("source_tarball_integrity"), sourceSha512)
            if (!GIT_HEAD_PATTERN.matches(values.getValue("registry_git_head"))) {
                invalidManifest("Registry git head is invalid")
            }
            if (values.getValue("registry_signature_status") !in REGISTRY_SIGNATURE_STATUSES) {
                invalidManifest("Registry signature status is invalid")
            }

            val archiveAsset = requireManifestAssetName(values.getValue("archive_asset"))
            val archiveBytes = parsePositiveLong(values.getValue("archive_bytes"), MAX_ARCHIVE_BYTES)
            val archiveSha256 = requireSha256(values.getValue("archive_sha256"))
            if (values.getValue("tree_algorithm") != TREE_ALGORITHM) {
                invalidManifest("Tree identity algorithm is unsupported")
            }
            val treeSha256 = requireSha256(values.getValue("tree_sha256"))
            val fileCount = parsePositiveInt(values.getValue("file_count"), MAX_TREE_NODES)
            val directoryCount = parsePositiveInt(
                values.getValue("directory_count"),
                MAX_TREE_NODES,
            )
            if (fileCount > MAX_TREE_NODES - directoryCount) {
                invalidManifest("Tree node count exceeds policy")
            }
            val totalFileBytes = parsePositiveLong(
                values.getValue("total_file_bytes"),
                MAX_TOTAL_FILE_BYTES,
            )
            val root = values.getValue("root")
            if (root != NPM_TOOL) invalidManifest("Manifest root must be npm")

            val required = REQUIRED_ENTRY_PREFIXES.map { (prefix, expectedPath) ->
                val path = values.getValue("${prefix}_path")
                if (path != expectedPath) invalidManifest("Required npm entry path is invalid")
                StmBundledNpmRequiredEntry(
                    path = path,
                    bytes = parsePositiveLong(
                        values.getValue("${prefix}_bytes"),
                        totalFileBytes,
                    ),
                    sha256 = requireSha256(values.getValue("${prefix}_sha256")),
                )
            }
            if (required.map(StmBundledNpmRequiredEntry::path).toSet().size != required.size) {
                invalidManifest("Required npm entry paths are not unique")
            }

            val inventoryAsset = requireManifestAssetName(
                values.getValue("license_inventory_asset"),
            )
            val inventoryBytes = parsePositiveLong(
                values.getValue("license_inventory_bytes"),
                MAX_LICENSE_INVENTORY_BYTES,
            )
            val inventorySha256 = requireSha256(values.getValue("license_inventory_sha256"))
            val gapCount = parseNonNegativeInt(values.getValue("license_gap_count"), fileCount)
            if (archiveAsset == inventoryAsset) {
                invalidManifest("Archive and license inventory assets must be distinct")
            }

            return StmBundledNpmManifest(
                npmVersion = version,
                archiveAsset = archiveAsset,
                archiveBytes = archiveBytes,
                archiveSha256 = archiveSha256,
                treeSha256 = treeSha256,
                fileCount = fileCount,
                directoryCount = directoryCount,
                totalFileBytes = totalFileBytes,
                root = root,
                requiredEntries = required,
                licenseInventoryAsset = inventoryAsset,
                licenseInventoryBytes = inventoryBytes,
                licenseInventorySha256 = inventorySha256,
                licenseGapCount = gapCount,
            )
        }
    }
}

private fun initializeToolchainRoot(input: Path): Path {
    val absolute = input.toAbsolutePath().normalize()
    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(absolute)) {
        throw IllegalArgumentException("npm toolchain roots cannot be symbolic links")
    }
    Files.createDirectories(absolute)
    require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
        "npm toolchain root is not a directory"
    }
    return absolute.toRealPath()
}

private fun directChild(root: Path, name: String): Path {
    require(name.matches(Regex("[A-Za-z0-9.][A-Za-z0-9._-]{0,127}"))) {
        "npm toolchain path identifier is invalid"
    }
    require(name != "." && name != ".." && !name.contains("..")) {
        "npm toolchain path identifier is reserved"
    }
    val child = root.resolve(name).normalize()
    require(child.parent == root && child.startsWith(root)) {
        "npm toolchain path escaped its root"
    }
    return child
}

private fun toolchainRelativePath(root: Path, path: Path): String {
    val relative = root.relativize(path.toAbsolutePath().normalize())
    val value = (0 until relative.nameCount).joinToString("/") { index ->
        Normalizer.normalize(relative.getName(index).toString(), Normalizer.Form.NFC)
    }
    if (
        value.isEmpty() ||
        value.toByteArray(StandardCharsets.UTF_8).size > MAX_RELATIVE_PATH_BYTES ||
        value.startsWith('/') ||
        '\\' in value ||
        value.split('/').any { it.isEmpty() || it == "." || it == ".." }
    ) {
        throw toolchainFailure(
            StmBundledNpmToolchainErrorCode.TREE_MISMATCH,
            "npm toolchain contains an unsafe path",
        )
    }
    return value
}

private fun checkedAssetLength(current: Long, count: Int, expected: Long): Long {
    val next = try {
        Math.addExact(current, count.toLong())
    } catch (error: ArithmeticException) {
        throw toolchainFailure(
            StmBundledNpmToolchainErrorCode.ASSET_LENGTH_MISMATCH,
            "Bundled npm asset length overflowed",
            error,
        )
    }
    if (next > expected) {
        throw toolchainFailure(
            StmBundledNpmToolchainErrorCode.ASSET_LENGTH_MISMATCH,
            "Bundled npm asset exceeded its declared length",
        )
    }
    return next
}

private fun verifyObservedAsset(
    name: String,
    observedBytes: Long,
    observedSha256: String,
    expectedBytes: Long,
    expectedSha256: String,
) {
    if (observedBytes != expectedBytes) {
        throw toolchainFailure(
            StmBundledNpmToolchainErrorCode.ASSET_LENGTH_MISMATCH,
            "Bundled npm asset length did not match: $name",
        )
    }
    if (!MessageDigest.isEqual(observedSha256.hexToBytes(), expectedSha256.hexToBytes())) {
        throw toolchainFailure(
            StmBundledNpmToolchainErrorCode.ASSET_SHA256_MISMATCH,
            "Bundled npm asset SHA-256 did not match: $name",
        )
    }
}

private fun validateSourceUrl(value: String, version: String) {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        invalidManifest("Source tarball URL is invalid")
    }
    if (
        uri.scheme != "https" ||
        uri.host != "registry.npmjs.org" ||
        uri.port != -1 ||
        uri.userInfo != null ||
        uri.query != null ||
        uri.fragment != null ||
        uri.path != "/npm/-/npm-$version.tgz"
    ) {
        invalidManifest("Source tarball URL is not the exact official npm artifact")
    }
}

private fun validateIntegrity(value: String, expectedSha512: String) {
    if (!value.startsWith("sha512-")) invalidManifest("Source integrity is not SHA-512")
    val decoded = try {
        Base64.getDecoder().decode(value.removePrefix("sha512-"))
    } catch (_: IllegalArgumentException) {
        invalidManifest("Source integrity base64 is invalid")
    }
    if (
        decoded.size != SHA512_BYTES ||
        !MessageDigest.isEqual(decoded, expectedSha512.hexToBytes())
    ) {
        invalidManifest("Source integrity does not match source SHA-512")
    }
}

private fun decodeStrictUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .also { text ->
            if (text.startsWith('\uFEFF')) invalidManifest("Manifest cannot contain a BOM")
        }
} catch (error: CharacterCodingException) {
    throw toolchainFailure(
        StmBundledNpmToolchainErrorCode.INVALID_MANIFEST,
        "Manifest is not strict UTF-8",
        error,
    )
}

private fun requireBoundedText(value: String, maximumBytes: Int, label: String) {
    if (
        value.isBlank() ||
        value.toByteArray(StandardCharsets.UTF_8).size > maximumBytes ||
        value.any(Character::isISOControl)
    ) {
        invalidManifest("Manifest $label is outside bounds")
    }
}

private fun requireSafeAssetName(value: String) {
    if (
        value.isEmpty() ||
        value.toByteArray(StandardCharsets.UTF_8).size > MAX_ASSET_NAME_BYTES ||
        value.startsWith('/') ||
        '\\' in value ||
        value.split('/').any { segment ->
            !ASSET_SEGMENT_PATTERN.matches(segment) || segment == "." || segment == ".."
        }
    ) {
        throw IllegalArgumentException("Bundled npm asset name is unsafe")
    }
}

private fun requireManifestAssetName(value: String): String = try {
    requireSafeAssetName(value)
    value
} catch (_: IllegalArgumentException) {
    invalidManifest("Bundled npm asset name is unsafe")
}

private fun parsePositiveLong(value: String, maximum: Long): Long {
    if (!POSITIVE_INTEGER_PATTERN.matches(value)) invalidManifest("Manifest number is invalid")
    val parsed = value.toLongOrNull() ?: invalidManifest("Manifest number overflowed")
    if (parsed !in 1..maximum) invalidManifest("Manifest number is outside bounds")
    return parsed
}

private fun parsePositiveInt(value: String, maximum: Int): Int {
    val parsed = parsePositiveLong(value, maximum.toLong())
    return parsed.toInt()
}

private fun parseNonNegativeInt(value: String, maximum: Int): Int {
    if (!NON_NEGATIVE_INTEGER_PATTERN.matches(value)) invalidManifest("Manifest number is invalid")
    val parsed = value.toIntOrNull() ?: invalidManifest("Manifest number overflowed")
    if (parsed !in 0..maximum) invalidManifest("Manifest number is outside bounds")
    return parsed
}

private fun requireSha256(value: String): String {
    if (!SHA256_PATTERN.matches(value)) invalidManifest("Manifest SHA-256 is invalid")
    return value
}

private fun requireSha512(value: String): String {
    if (!SHA512_PATTERN.matches(value)) invalidManifest("Manifest SHA-512 is invalid")
    return value
}

private fun syncDirectoryBestEffort(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // Directory fsync is unavailable on some Android/JVM filesystem providers.
    } catch (_: UnsupportedOperationException) {
        // File fsync and the directory rename remain mandatory.
    } catch (_: SecurityException) {
        // The Core-private roots still retain their file-level durability guarantees.
    }
}

private fun StmExtractionCancellation.throwIfToolchainCancelled() {
    if (isCancelled()) {
        throw toolchainFailure(
            StmBundledNpmToolchainErrorCode.OPERATION_CANCELLED,
            "npm toolchain preparation was cancelled",
        )
    }
}

private fun toolchainFailure(
    code: StmBundledNpmToolchainErrorCode,
    message: String,
    cause: Throwable? = null,
): StmBundledNpmToolchainException = StmBundledNpmToolchainException(code, message, cause)

private fun invalidManifest(message: String): Nothing = throw toolchainFailure(
    StmBundledNpmToolchainErrorCode.INVALID_MANIFEST,
    message,
)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private const val MANIFEST_FORMAT = "STM-NPM-TOOL-ASSET-V1"
private const val TREE_ALGORITHM = "stm-tree-identity-v1"
private const val NPM_TOOL = "npm"
private const val REQUIRED_ABI = "arm64-v8a"
private const val SHA_256 = "SHA-256"
private const val SHA512_BYTES = 64
private const val COPY_BUFFER_SIZE = 64 * 1024
private const val MAX_ASSET_NAME_BYTES = 512
private const val MAX_RELATIVE_PATH_BYTES = 4 * 1024
private const val MAX_MANIFEST_BYTES = 64L * 1024
private const val MAX_LICENSE_INVENTORY_BYTES = 16L * 1024 * 1024
private const val MAX_SOURCE_TARBALL_BYTES = 64L * 1024 * 1024
private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
private const val MAX_TOTAL_FILE_BYTES = 512L * 1024 * 1024
private const val MAX_TREE_NODES = 100_000

private val NPM_TOOLCHAIN_PUBLISH_MONITOR = Any()

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val SHA512_PATTERN = Regex("[0-9a-f]{128}")
private val GIT_HEAD_PATTERN = Regex("[0-9a-f]{40}")
private val SEMVER_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
private val POSITIVE_INTEGER_PATTERN = Regex("[1-9][0-9]*")
private val NON_NEGATIVE_INTEGER_PATTERN = Regex("0|[1-9][0-9]*")
private val ASSET_SEGMENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private val REQUIRED_ENTRY_PREFIXES = listOf(
    "npm_package_json" to "npm/package.json",
    "npm_bin_cli" to "npm/bin/npm-cli.js",
    "npm_lib_cli" to "npm/lib/cli.js",
    "npm_lib_cli_entry" to "npm/lib/cli/entry.js",
    "npm_lib_npm" to "npm/lib/npm.js",
    "arborist_package_json" to "npm/node_modules/@npmcli/arborist/package.json",
    "npm_license" to "npm/LICENSE",
)

private val REGISTRY_SIGNATURE_STATUSES = setOf(
    "verified",
    "metadata-present-unverified",
)

private val MANIFEST_KEYS = buildList {
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
    REQUIRED_ENTRY_PREFIXES.forEach { (prefix, _) ->
        add("${prefix}_path")
        add("${prefix}_bytes")
        add("${prefix}_sha256")
    }
    add("license_inventory_asset")
    add("license_inventory_bytes")
    add("license_inventory_sha256")
    add("license_gap_count")
}
