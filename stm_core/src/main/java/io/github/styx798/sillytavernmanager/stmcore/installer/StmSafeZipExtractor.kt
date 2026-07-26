package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
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
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

internal data class StmZipExtractionPolicy(
    val maxArchiveBytes: Long = 512L * MEBIBYTE,
    val maxEntries: Int = 50_000,
    val maxPathBytes: Int = 4_096,
    val maxSegmentBytes: Int = 255,
    val maxDepth: Int = 64,
    val maxPathNodes: Int = 100_000,
    val maxSingleFileBytes: Long = 256L * MEBIBYTE,
    val maxTotalUncompressedBytes: Long = 2L * GIBIBYTE,
    val maxCompressionRatio: Long = 200,
) {
    init {
        require(maxArchiveBytes > 0)
        require(maxEntries > 0)
        require(maxPathBytes > 0)
        require(maxSegmentBytes > 0)
        require(maxDepth > 0)
        require(maxPathNodes > 0)
        require(maxSingleFileBytes >= 0)
        require(maxTotalUncompressedBytes >= 0)
        require(maxCompressionRatio > 0)
    }
}

/**
 * STRICT is reserved for unsigned/local inputs that need a durable per-file content manifest.
 * SIGNED_ARCHIVE_FAST trusts a previously verified whole-archive identity, while retaining ZIP
 * structure, path, type, size, limit and CRC enforcement during extraction.
 */
internal enum class StmZipExtractionMode {
    STRICT,
    SIGNED_ARCHIVE_FAST,
}

internal fun interface StmExtractionCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = StmExtractionCancellation { false }
    }
}

internal enum class StmZipErrorCode {
    INVALID_ARGUMENT,
    INVALID_ARCHIVE,
    STAGING_ALREADY_EXISTS,
    PATH_REJECTED,
    DUPLICATE_PATH,
    NAME_COLLISION,
    FILE_DIRECTORY_CONFLICT,
    ENTRY_TYPE_REJECTED,
    UNSUPPORTED_FEATURE,
    LIMIT_EXCEEDED,
    SIZE_MISMATCH,
    CRC_MISMATCH,
    OPERATION_CANCELLED,
    STORAGE_NO_SPACE,
    IO_FAILURE,
}

internal class StmZipExtractionException(
    val code: StmZipErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal enum class StmZipManifestEntryType {
    FILE,
    DIRECTORY,
}

internal data class StmZipManifestEntry(
    val relativePath: String,
    val type: StmZipManifestEntryType,
    val sizeBytes: Long,
    val sha256: String?,
)

internal data class StmZipExtractionResult(
    val payloadDirectory: File,
    val entries: List<StmZipManifestEntry>,
    val fileCount: Int,
    val directoryCount: Int,
    val totalFileBytes: Long,
    val manifestSha256: String,
)

internal interface StmZipSink : Closeable {
    @Throws(IOException::class)
    fun write(buffer: ByteArray, offset: Int, length: Int)

    @Throws(IOException::class)
    fun sync()
}

internal fun interface StmZipSinkFactory {
    @Throws(IOException::class)
    fun open(path: Path): StmZipSink
}

internal object DefaultStmZipSinkFactory : StmZipSinkFactory {
    override fun open(path: Path): StmZipSink = FileChannelStmZipSink(
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ),
    )
}

private class FileChannelStmZipSink(
    private val channel: FileChannel,
) : StmZipSink {
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val bytes = ByteBuffer.wrap(buffer, offset, length)
        while (bytes.hasRemaining()) {
            if (channel.write(bytes) == 0) Thread.yield()
        }
    }

    override fun sync() {
        channel.force(true)
    }

    override fun close() {
        channel.close()
    }
}

internal class StmSafeZipExtractor(
    private val sinkFactory: StmZipSinkFactory = DefaultStmZipSinkFactory,
) {
    fun extract(
        artifact: File,
        operationStagingRoot: File,
        policy: StmZipExtractionPolicy = StmZipExtractionPolicy(),
        cancellation: StmExtractionCancellation = StmExtractionCancellation.NONE,
        mode: StmZipExtractionMode = StmZipExtractionMode.STRICT,
    ): StmZipExtractionResult {
        val artifactPath = artifact.toPath().toAbsolutePath().normalize()
        val operationRoot = operationStagingRoot.toPath().toAbsolutePath().normalize()
        var ownsOperationRoot = false

        try {
            validateArtifact(artifactPath, policy)
            validateOperationRoot(operationRoot, artifactPath)
            cancellation.throwIfCancelled()

            return openZip(artifactPath).use { zip ->
                val plan = preflight(zip, policy, cancellation)
                cancellation.throwIfCancelled()

                try {
                    Files.createDirectory(operationRoot)
                    ownsOperationRoot = true
                } catch (error: FileAlreadyExistsException) {
                    throw StmZipExtractionException(
                        StmZipErrorCode.STAGING_ALREADY_EXISTS,
                        "Operation staging root already exists",
                        error,
                    )
                }

                val payloadRoot = Files.createDirectory(operationRoot.resolve(PAYLOAD_DIRECTORY))
                val scratchRoot = Files.createDirectory(operationRoot.resolve(SCRATCH_DIRECTORY))
                createDirectories(payloadRoot, plan.directories)

                val extractedFiles = extractFiles(
                    zip = zip,
                    plan = plan,
                    payloadRoot = payloadRoot,
                    scratchRoot = scratchRoot,
                    policy = policy,
                    cancellation = cancellation,
                    mode = mode,
                )
                Files.delete(scratchRoot)

                when (mode) {
                    StmZipExtractionMode.STRICT -> verifyAndBuildManifest(
                        payloadRoot = payloadRoot,
                        plan = plan,
                        extractedFiles = extractedFiles,
                        cancellation = cancellation,
                    )

                    StmZipExtractionMode.SIGNED_ARCHIVE_FAST -> buildExtractionResult(
                        payloadRoot = payloadRoot,
                        plan = plan,
                        extractedFiles = extractedFiles,
                    )
                }
            }
        } catch (error: Exception) {
            val primary = error.toExtractionException()
            if (ownsOperationRoot) {
                try {
                    deleteTreeNoFollow(operationRoot)
                } catch (cleanupError: Exception) {
                    primary.addSuppressed(cleanupError)
                }
            }
            throw primary
        }
    }

    private fun validateArtifact(artifact: Path, policy: StmZipExtractionPolicy) {
        val attributes = try {
            Files.readAttributes(
                artifact,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: IOException) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARGUMENT,
                "Artifact is not readable",
                error,
            )
        }
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARGUMENT,
                "Artifact must be a regular file",
            )
        }
        if (attributes.size() !in 1..policy.maxArchiveBytes) {
            throw StmZipExtractionException(
                StmZipErrorCode.LIMIT_EXCEEDED,
                "Artifact size exceeds policy",
            )
        }
    }

    private fun validateOperationRoot(operationRoot: Path, artifact: Path) {
        if (operationRoot.parent == null || operationRoot == operationRoot.root) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARGUMENT,
                "Operation staging root is unsafe",
            )
        }
        if (operationRoot == artifact || artifact.startsWith(operationRoot)) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARGUMENT,
                "Artifact cannot be inside operation staging",
            )
        }
        if (Files.exists(operationRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw StmZipExtractionException(
                StmZipErrorCode.STAGING_ALREADY_EXISTS,
                "Operation staging root already exists",
            )
        }
        val parentAttributes = try {
            Files.readAttributes(
                operationRoot.parent,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: IOException) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARGUMENT,
                "Operation staging parent is unavailable",
                error,
            )
        }
        if (!parentAttributes.isDirectory || parentAttributes.isSymbolicLink) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARGUMENT,
                "Operation staging parent must be a real directory",
            )
        }
    }

    private fun openZip(artifact: Path): CommonsZipFile = try {
        val builder = CommonsZipFile.builder()
        builder.setPath(artifact)
        builder.setCharset(StandardCharsets.UTF_8)
        builder.setUseUnicodeExtraFields(false)
        builder.setIgnoreLocalFileHeader(false)
        builder.setMaxNumberOfDisks(1)
        builder.get()
    } catch (error: IOException) {
        throw StmZipExtractionException(
            StmZipErrorCode.INVALID_ARCHIVE,
            "ZIP structure is invalid",
            error,
        )
    } catch (error: RuntimeException) {
        throw StmZipExtractionException(
            StmZipErrorCode.INVALID_ARCHIVE,
            "ZIP structure is invalid",
            error,
        )
    }

    private fun preflight(
        zip: CommonsZipFile,
        policy: StmZipExtractionPolicy,
        cancellation: StmExtractionCancellation,
    ): ExtractionPlan {
        if (zip.firstLocalFileHeaderOffset != 0L) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARCHIVE,
                "ZIP preambles are not accepted",
            )
        }

        val plannedFiles = mutableListOf<PlannedFile>()
        val explicitNames = mutableMapOf<String, PlannedNodeType>()
        val canonicalNodeTypes = linkedMapOf<String, PlannedNodeType>()
        val canonicalNodeOrigins = mutableMapOf<String, String>()
        val portableNodeOrigins = mutableMapOf<String, String>()
        var entryCount = 0
        var totalUncompressed = 0L

        val entries = zip.entries
        while (entries.hasMoreElements()) {
            cancellation.throwIfCancelled()
            val entry = entries.nextElement()
            entryCount += 1
            if (entryCount > policy.maxEntries) {
                throw StmZipExtractionException(
                    StmZipErrorCode.LIMIT_EXCEEDED,
                    "ZIP entry count exceeds policy",
                )
            }

            validateSupportedFeatures(zip, entry)
            val validatedPath = validatePath(entry, policy)
            val nodeType = validateEntryType(entry, validatedPath.isDirectory)
            validateExtraFields(entry)
            validateEntrySizes(entry, nodeType, policy)

            val priorExplicitType = explicitNames.putIfAbsent(
                validatedPath.rawIdentity,
                nodeType,
            )
            if (priorExplicitType != null) {
                val code = if (priorExplicitType == nodeType) {
                    StmZipErrorCode.DUPLICATE_PATH
                } else {
                    StmZipErrorCode.FILE_DIRECTORY_CONFLICT
                }
                throw StmZipExtractionException(code, "ZIP contains a duplicate path")
            }

            registerPathNodes(
                path = validatedPath,
                finalType = nodeType,
                canonicalNodeTypes = canonicalNodeTypes,
                canonicalNodeOrigins = canonicalNodeOrigins,
                portableNodeOrigins = portableNodeOrigins,
                maxPathNodes = policy.maxPathNodes,
            )

            if (nodeType == PlannedNodeType.FILE) {
                totalUncompressed = checkedAdd(totalUncompressed, entry.size)
                if (totalUncompressed > policy.maxTotalUncompressedBytes) {
                    throw StmZipExtractionException(
                        StmZipErrorCode.LIMIT_EXCEEDED,
                        "ZIP total size exceeds policy",
                    )
                }
                plannedFiles += PlannedFile(
                    archiveEntry = entry,
                    relativePath = validatedPath.canonicalRelativePath,
                    expectedSize = entry.size,
                    expectedCrc = entry.crc,
                )
            }
        }

        if (entryCount == 0) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARCHIVE,
                "ZIP contains no entries",
            )
        }

        val directories = canonicalNodeTypes
            .filterValues { it == PlannedNodeType.DIRECTORY }
            .keys
            .sortedWith(compareBy<String>({ it.count(PATH_SEPARATOR::equals) }, { it }))
        return ExtractionPlan(
            files = plannedFiles.sortedBy(PlannedFile::relativePath),
            directories = directories,
            nodeTypes = canonicalNodeTypes.toSortedMap(),
        )
    }

    private fun validateSupportedFeatures(zip: CommonsZipFile, entry: ZipArchiveEntry) {
        val flags = entry.generalPurposeBit
        if (flags.usesEncryption() || flags.usesStrongEncryption()) {
            throw StmZipExtractionException(
                StmZipErrorCode.UNSUPPORTED_FEATURE,
                "Encrypted ZIP entries are not accepted",
            )
        }
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            throw StmZipExtractionException(
                StmZipErrorCode.UNSUPPORTED_FEATURE,
                "ZIP compression method is not accepted",
            )
        }
        if (!zip.canReadEntryData(entry)) {
            throw StmZipExtractionException(
                StmZipErrorCode.UNSUPPORTED_FEATURE,
                "ZIP entry data cannot be read",
            )
        }
    }

    private fun validatePath(
        entry: ZipArchiveEntry,
        policy: StmZipExtractionPolicy,
    ): ValidatedPath {
        val rawName = entry.rawName ?: throw StmZipExtractionException(
            StmZipErrorCode.PATH_REJECTED,
            "ZIP entry has no raw name",
        )
        if (rawName.isEmpty() || rawName.size > policy.maxPathBytes) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP entry path length is invalid",
            )
        }
        if (rawName.any { byte ->
                val value = byte.toInt() and 0xff
                value == 0 || value == BACKSLASH_BYTE || value < SPACE_BYTE || value == DELETE_BYTE
            }
        ) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP entry path contains forbidden bytes",
            )
        }

        val decodedName = decodeRawName(rawName, entry.generalPurposeBit.usesUTF8ForNames())
        if (decodedName != entry.name || decodedName.isEmpty()) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP entry name is ambiguous",
            )
        }
        if (decodedName.any(Character::isISOControl)) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP entry name contains control characters",
            )
        }
        if (decodedName.startsWith(PATH_SEPARATOR) || decodedName.startsWith(BACKSLASH)) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "Absolute ZIP paths are not accepted",
            )
        }

        val isDirectory = decodedName.endsWith(PATH_SEPARATOR)
        val pathWithoutDirectoryMarker = if (isDirectory) decodedName.dropLast(1) else decodedName
        if (pathWithoutDirectoryMarker.isEmpty()) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP entry path is empty",
            )
        }
        val rawSegments = pathWithoutDirectoryMarker.split(PATH_SEPARATOR)
        if (rawSegments.size > policy.maxDepth || rawSegments.any(String::isEmpty)) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP entry path depth is invalid",
            )
        }
        if (rawSegments.first().isWindowsDrivePrefix()) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "Windows absolute ZIP paths are not accepted",
            )
        }

        val canonicalSegments = rawSegments.map { segment ->
            if (segment == CURRENT_DIRECTORY || segment == PARENT_DIRECTORY) {
                throw StmZipExtractionException(
                    StmZipErrorCode.PATH_REJECTED,
                    "ZIP entry path traversal is not accepted",
                )
            }
            if (segment.toByteArray(StandardCharsets.UTF_8).size > policy.maxSegmentBytes) {
                throw StmZipExtractionException(
                    StmZipErrorCode.PATH_REJECTED,
                    "ZIP entry path segment is too long",
                )
            }
            Normalizer.normalize(segment, Normalizer.Form.NFC).also { canonical ->
                if (
                    canonical.isEmpty() ||
                    canonical == CURRENT_DIRECTORY ||
                    canonical == PARENT_DIRECTORY ||
                    canonical.contains(PATH_SEPARATOR) ||
                    canonical.contains(BACKSLASH)
                ) {
                    throw StmZipExtractionException(
                        StmZipErrorCode.PATH_REJECTED,
                        "ZIP entry path normalization is unsafe",
                    )
                }
            }
        }
        val canonicalPath = canonicalSegments.joinToString(PATH_SEPARATOR)
        if (canonicalPath.toByteArray(StandardCharsets.UTF_8).size > policy.maxPathBytes) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "Normalized ZIP entry path is too long",
            )
        }

        val portableSegments = canonicalSegments.map(::portableSegment)
        return ValidatedPath(
            rawIdentity = decodedName,
            rawSegments = rawSegments,
            canonicalSegments = canonicalSegments,
            portableSegments = portableSegments,
            canonicalRelativePath = canonicalPath,
            isDirectory = isDirectory,
        )
    }

    private fun decodeRawName(rawName: ByteArray, usesUtf8: Boolean): String {
        if (!usesUtf8 && rawName.any { (it.toInt() and 0xff) > ASCII_MAX }) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "Non-ASCII ZIP names require the UTF-8 flag",
            )
        }
        return try {
            val charset = if (usesUtf8) StandardCharsets.UTF_8 else StandardCharsets.US_ASCII
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawName))
                .toString()
        } catch (error: CharacterCodingException) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP entry name encoding is invalid",
                error,
            )
        }
    }

    private fun registerPathNodes(
        path: ValidatedPath,
        finalType: PlannedNodeType,
        canonicalNodeTypes: MutableMap<String, PlannedNodeType>,
        canonicalNodeOrigins: MutableMap<String, String>,
        portableNodeOrigins: MutableMap<String, String>,
        maxPathNodes: Int,
    ) {
        for (index in path.canonicalSegments.indices) {
            val canonicalPrefix = path.canonicalSegments.take(index + 1).joinToString(PATH_SEPARATOR)
            val rawPrefix = path.rawSegments.take(index + 1).joinToString(PATH_SEPARATOR)
            val portablePrefix = path.portableSegments.take(index + 1).joinToString(PATH_SEPARATOR)
            val nodeType = if (index == path.canonicalSegments.lastIndex) {
                finalType
            } else {
                PlannedNodeType.DIRECTORY
            }

            canonicalNodeTypes[canonicalPrefix]?.let { existingType ->
                if (existingType != nodeType) {
                    throw StmZipExtractionException(
                        StmZipErrorCode.FILE_DIRECTORY_CONFLICT,
                        "ZIP file and directory paths conflict",
                    )
                }
            }
            canonicalNodeOrigins[canonicalPrefix]?.let { existingOrigin ->
                if (existingOrigin != rawPrefix) {
                    throw StmZipExtractionException(
                        StmZipErrorCode.NAME_COLLISION,
                        "ZIP paths collide after Unicode normalization",
                    )
                }
            }
            portableNodeOrigins[portablePrefix]?.let { existingCanonical ->
                if (existingCanonical != canonicalPrefix) {
                    throw StmZipExtractionException(
                        StmZipErrorCode.NAME_COLLISION,
                        "ZIP paths collide under portable name rules",
                    )
                }
            }

            if (
                canonicalPrefix !in canonicalNodeTypes &&
                canonicalNodeTypes.size >= maxPathNodes
            ) {
                throw StmZipExtractionException(
                    StmZipErrorCode.LIMIT_EXCEEDED,
                    "ZIP path node count exceeds policy",
                )
            }

            canonicalNodeTypes.putIfAbsent(canonicalPrefix, nodeType)
            canonicalNodeOrigins.putIfAbsent(canonicalPrefix, rawPrefix)
            portableNodeOrigins.putIfAbsent(portablePrefix, canonicalPrefix)
        }
    }

    private fun validateEntryType(entry: ZipArchiveEntry, directoryByName: Boolean): PlannedNodeType {
        if (entry.platform != ZipArchiveEntry.PLATFORM_UNIX) {
            return if (directoryByName) PlannedNodeType.DIRECTORY else PlannedNodeType.FILE
        }

        val mode = entry.unixMode
        if ((mode and UNIX_SPECIAL_PERMISSION_MASK) != 0) {
            throw StmZipExtractionException(
                StmZipErrorCode.ENTRY_TYPE_REJECTED,
                "Special Unix permission bits are not accepted",
            )
        }
        return when (mode and UNIX_FILE_TYPE_MASK) {
            UNIX_REGULAR_FILE -> {
                if (directoryByName) rejectTypeMismatch()
                PlannedNodeType.FILE
            }

            UNIX_DIRECTORY -> {
                if (!directoryByName) rejectTypeMismatch()
                PlannedNodeType.DIRECTORY
            }

            else -> throw StmZipExtractionException(
                StmZipErrorCode.ENTRY_TYPE_REJECTED,
                "Only regular files and directories are accepted",
            )
        }
    }

    private fun rejectTypeMismatch(): Nothing = throw StmZipExtractionException(
        StmZipErrorCode.ENTRY_TYPE_REJECTED,
        "ZIP name and Unix entry type disagree",
    )

    private fun validateExtraFields(entry: ZipArchiveEntry) {
        validateExtraFieldBytes(entry.localFileDataExtra)
        validateExtraFieldBytes(entry.centralDirectoryExtra)
    }

    private fun validateExtraFieldBytes(extra: ByteArray) {
        var offset = 0
        while (offset < extra.size) {
            if (extra.size - offset < EXTRA_FIELD_HEADER_SIZE) {
                throw StmZipExtractionException(
                    StmZipErrorCode.INVALID_ARCHIVE,
                    "ZIP extra field is truncated",
                )
            }
            val headerId = littleEndianUnsignedShort(extra, offset)
            val dataSize = littleEndianUnsignedShort(extra, offset + 2)
            val nextOffset = offset + EXTRA_FIELD_HEADER_SIZE + dataSize
            if (nextOffset > extra.size) {
                throw StmZipExtractionException(
                    StmZipErrorCode.INVALID_ARCHIVE,
                    "ZIP extra field size is invalid",
                )
            }
            if (headerId == PKWARE_UNIX_EXTRA_FIELD || headerId == ASI_UNIX_EXTRA_FIELD) {
                throw StmZipExtractionException(
                    StmZipErrorCode.ENTRY_TYPE_REJECTED,
                    "ZIP link or device metadata is not accepted",
                )
            }
            offset = nextOffset
        }
    }

    private fun validateEntrySizes(
        entry: ZipArchiveEntry,
        type: PlannedNodeType,
        policy: StmZipExtractionPolicy,
    ) {
        if (entry.size < 0 || entry.compressedSize < 0 || entry.crc < 0) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARCHIVE,
                "ZIP entry size or CRC is unknown",
            )
        }
        if (type == PlannedNodeType.DIRECTORY) {
            if (entry.size != 0L || entry.compressedSize != 0L || entry.crc != 0L) {
                throw StmZipExtractionException(
                    StmZipErrorCode.ENTRY_TYPE_REJECTED,
                    "ZIP directories cannot contain data",
                )
            }
            return
        }
        if (entry.size > policy.maxSingleFileBytes) {
            throw StmZipExtractionException(
                StmZipErrorCode.LIMIT_EXCEEDED,
                "ZIP entry size exceeds policy",
            )
        }
        if (entry.method == ZipEntry.STORED && entry.size != entry.compressedSize) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARCHIVE,
                "Stored ZIP entry size is inconsistent",
            )
        }
        if (entry.size > 0 && entry.compressedSize == 0L) {
            throw StmZipExtractionException(
                StmZipErrorCode.LIMIT_EXCEEDED,
                "ZIP entry compression ratio is invalid",
            )
        }
        if (exceedsRatio(entry.size, entry.compressedSize, policy.maxCompressionRatio)) {
            throw StmZipExtractionException(
                StmZipErrorCode.LIMIT_EXCEEDED,
                "ZIP entry compression ratio exceeds policy",
            )
        }
    }

    private fun createDirectories(payloadRoot: Path, directories: List<String>) {
        directories.forEach { relativePath ->
            val destination = resolveContained(payloadRoot, relativePath)
            Files.createDirectory(destination)
        }
    }

    private fun extractFiles(
        zip: CommonsZipFile,
        plan: ExtractionPlan,
        payloadRoot: Path,
        scratchRoot: Path,
        policy: StmZipExtractionPolicy,
        cancellation: StmExtractionCancellation,
        mode: StmZipExtractionMode,
    ): Map<String, ExtractedFile> {
        val extracted = linkedMapOf<String, ExtractedFile>()
        var totalWritten = 0L

        plan.files.forEachIndexed { index, planned ->
            cancellation.throwIfCancelled()
            val scratchPath = scratchRoot.resolve(index.toString().padStart(8, '0') + PART_SUFFIX)
            val destination = resolveContained(payloadRoot, planned.relativePath)
            val crc = CRC32()
            val sha256 = if (mode == StmZipExtractionMode.STRICT) {
                MessageDigest.getInstance(SHA_256)
            } else {
                null
            }
            var fileWritten = 0L

            val input = zip.getInputStream(planned.archiveEntry) ?: throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARCHIVE,
                "ZIP entry data is unavailable",
            )
            input.use { source ->
                sinkFactory.open(scratchPath).use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        cancellation.throwIfCancelled()
                        val count = try {
                            source.read(buffer)
                        } catch (error: IOException) {
                            if (cancellation.isCancelled()) cancellation.throwIfCancelled()
                            throw error
                        }
                        if (count < 0) break
                        if (count == 0) continue

                        val nextFileWritten = checkedAdd(fileWritten, count.toLong())
                        if (
                            nextFileWritten > planned.expectedSize ||
                            nextFileWritten > policy.maxSingleFileBytes
                        ) {
                            throw StmZipExtractionException(
                                StmZipErrorCode.SIZE_MISMATCH,
                                "ZIP entry produced more data than declared",
                            )
                        }
                        val nextTotalWritten = checkedAdd(totalWritten, count.toLong())
                        if (nextTotalWritten > policy.maxTotalUncompressedBytes) {
                            throw StmZipExtractionException(
                                StmZipErrorCode.LIMIT_EXCEEDED,
                                "ZIP output exceeds total size policy",
                            )
                        }

                        sink.write(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        sha256?.update(buffer, 0, count)
                        fileWritten = nextFileWritten
                        totalWritten = nextTotalWritten
                    }
                    if (fileWritten != planned.expectedSize) {
                        throw StmZipExtractionException(
                            StmZipErrorCode.SIZE_MISMATCH,
                            "ZIP entry produced fewer bytes than declared",
                        )
                    }
                    if (crc.value != planned.expectedCrc) {
                        throw StmZipExtractionException(
                            StmZipErrorCode.CRC_MISMATCH,
                            "ZIP entry CRC does not match",
                        )
                    }
                    cancellation.throwIfCancelled()
                    if (mode == StmZipExtractionMode.STRICT) sink.sync()
                }
            }

            try {
                Files.move(scratchPath, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (error: AtomicMoveNotSupportedException) {
                throw StmZipExtractionException(
                    StmZipErrorCode.IO_FAILURE,
                    "Atomic staging move is unavailable",
                    error,
                )
            }
            extracted[planned.relativePath] = ExtractedFile(
                size = fileWritten,
                sha256 = sha256?.digest()?.toHex(),
            )
        }
        return extracted
    }

    private fun buildExtractionResult(
        payloadRoot: Path,
        plan: ExtractionPlan,
        extractedFiles: Map<String, ExtractedFile>,
    ): StmZipExtractionResult {
        val entries = plan.nodeTypes.map { (relativePath, type) ->
            when (type) {
                PlannedNodeType.DIRECTORY -> StmZipManifestEntry(
                    relativePath = relativePath,
                    type = StmZipManifestEntryType.DIRECTORY,
                    sizeBytes = 0,
                    sha256 = null,
                )

                PlannedNodeType.FILE -> extractedFiles.getValue(relativePath).let { file ->
                    StmZipManifestEntry(
                        relativePath = relativePath,
                        type = StmZipManifestEntryType.FILE,
                        sizeBytes = file.size,
                        sha256 = file.sha256,
                    )
                }
            }
        }
        return StmZipExtractionResult(
            payloadDirectory = payloadRoot.toFile(),
            entries = entries,
            fileCount = entries.count { it.type == StmZipManifestEntryType.FILE },
            directoryCount = entries.count { it.type == StmZipManifestEntryType.DIRECTORY },
            totalFileBytes = entries
                .asSequence()
                .filter { it.type == StmZipManifestEntryType.FILE }
                .fold(0L) { total, entry -> checkedAdd(total, entry.sizeBytes) },
            manifestSha256 = stmTreeIdentitySha256(entries),
        )
    }

    private fun verifyAndBuildManifest(
        payloadRoot: Path,
        plan: ExtractionPlan,
        extractedFiles: Map<String, ExtractedFile>,
        cancellation: StmExtractionCancellation,
    ): StmZipExtractionResult {
        val actualNodes = linkedMapOf<String, PlannedNodeType>()
        val verifiedFiles = linkedMapOf<String, ExtractedFile>()

        Files.walkFileTree(payloadRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                cancellation.throwIfCancelled()
                if (directory != payloadRoot) {
                    if (!attributes.isDirectory || attributes.isSymbolicLink) {
                        throw StmZipExtractionException(
                            StmZipErrorCode.ENTRY_TYPE_REJECTED,
                            "Extracted tree contains a non-directory",
                        )
                    }
                    actualNodes[toManifestPath(payloadRoot.relativize(directory))] =
                        PlannedNodeType.DIRECTORY
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                cancellation.throwIfCancelled()
                if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                    throw StmZipExtractionException(
                        StmZipErrorCode.ENTRY_TYPE_REJECTED,
                        "Extracted tree contains a special file",
                    )
                }
                val relativePath = toManifestPath(payloadRoot.relativize(file))
                actualNodes[relativePath] = PlannedNodeType.FILE
                verifiedFiles[relativePath] = ExtractedFile(
                    size = attributes.size(),
                    sha256 = sha256(file, cancellation),
                )
                return FileVisitResult.CONTINUE
            }
        })

        if (actualNodes.toSortedMap() != plan.nodeTypes) {
            throw StmZipExtractionException(
                StmZipErrorCode.INVALID_ARCHIVE,
                "Extracted tree does not match its validated plan",
            )
        }
        if (verifiedFiles != extractedFiles) {
            throw StmZipExtractionException(
                StmZipErrorCode.SIZE_MISMATCH,
                "Extracted file verification failed",
            )
        }

        val entries = plan.nodeTypes.map { (relativePath, type) ->
            when (type) {
                PlannedNodeType.DIRECTORY -> StmZipManifestEntry(
                    relativePath = relativePath,
                    type = StmZipManifestEntryType.DIRECTORY,
                    sizeBytes = 0,
                    sha256 = null,
                )

                PlannedNodeType.FILE -> verifiedFiles.getValue(relativePath).let { file ->
                    StmZipManifestEntry(
                        relativePath = relativePath,
                        type = StmZipManifestEntryType.FILE,
                        sizeBytes = file.size,
                        sha256 = file.sha256,
                    )
                }
            }
        }
        return StmZipExtractionResult(
            payloadDirectory = payloadRoot.toFile(),
            entries = entries,
            fileCount = entries.count { it.type == StmZipManifestEntryType.FILE },
            directoryCount = entries.count { it.type == StmZipManifestEntryType.DIRECTORY },
            totalFileBytes = entries
                .asSequence()
                .filter { it.type == StmZipManifestEntryType.FILE }
                .fold(0L) { total, entry -> checkedAdd(total, entry.sizeBytes) },
            manifestSha256 = stmTreeIdentitySha256(entries),
        )
    }

    private fun resolveContained(root: Path, relativePath: String): Path {
        val resolved = relativePath
            .split(PATH_SEPARATOR)
            .fold(root) { current, segment -> current.resolve(segment) }
            .normalize()
        if (resolved == root || !resolved.startsWith(root)) {
            throw StmZipExtractionException(
                StmZipErrorCode.PATH_REJECTED,
                "ZIP destination escapes staging",
            )
        }
        return resolved
    }
}

private enum class PlannedNodeType {
    FILE,
    DIRECTORY,
}

private data class PlannedFile(
    val archiveEntry: ZipArchiveEntry,
    val relativePath: String,
    val expectedSize: Long,
    val expectedCrc: Long,
)

private data class ExtractionPlan(
    val files: List<PlannedFile>,
    val directories: List<String>,
    val nodeTypes: Map<String, PlannedNodeType>,
)

private data class ValidatedPath(
    val rawIdentity: String,
    val rawSegments: List<String>,
    val canonicalSegments: List<String>,
    val portableSegments: List<String>,
    val canonicalRelativePath: String,
    val isDirectory: Boolean,
)

private data class ExtractedFile(
    val size: Long,
    val sha256: String?,
)

private fun StmExtractionCancellation.throwIfCancelled() {
    if (isCancelled()) {
        throw StmZipExtractionException(
            StmZipErrorCode.OPERATION_CANCELLED,
            "ZIP extraction was cancelled",
        )
    }
}

private fun Exception.toExtractionException(): StmZipExtractionException = when (this) {
    is StmZipExtractionException -> this
    is ZipException, is EOFException -> StmZipExtractionException(
        StmZipErrorCode.INVALID_ARCHIVE,
        "ZIP entry data is malformed or truncated",
        this,
    )
    is IOException -> StmZipExtractionException(
        code = if (isNoSpaceError()) {
            StmZipErrorCode.STORAGE_NO_SPACE
        } else {
            StmZipErrorCode.IO_FAILURE
        },
        message = if (isNoSpaceError()) {
            "Storage has no space for ZIP extraction"
        } else {
            "ZIP extraction failed during I/O"
        },
        cause = this,
    )

    else -> StmZipExtractionException(
        StmZipErrorCode.INVALID_ARCHIVE,
        "ZIP extraction failed",
        this,
    )
}

private fun IOException.isNoSpaceError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message.orEmpty()
        if (
            message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left on device", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun String.isWindowsDrivePrefix(): Boolean =
    length >= 2 && this[0].isLetter() && this[1] == ':'

private fun portableSegment(segment: String): String {
    val compatibilityNormalized = Normalizer.normalize(segment, Normalizer.Form.NFKC)
    val folded = compatibilityNormalized
        .uppercase(Locale.ROOT)
        .lowercase(Locale.ROOT)
    val portable = Normalizer.normalize(folded, Normalizer.Form.NFKC)
    if (
        portable.isEmpty() ||
        portable == CURRENT_DIRECTORY ||
        portable == PARENT_DIRECTORY ||
        portable.contains(PATH_SEPARATOR) ||
        portable.contains(BACKSLASH)
    ) {
        throw StmZipExtractionException(
            StmZipErrorCode.PATH_REJECTED,
            "ZIP entry has an unsafe portable name",
        )
    }
    return portable
}

private fun exceedsRatio(size: Long, compressedSize: Long, ratio: Long): Boolean {
    if (size == 0L) return false
    if (compressedSize == 0L) return true
    if (compressedSize > Long.MAX_VALUE / ratio) return false
    return size > compressedSize * ratio
}

private fun checkedAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw StmZipExtractionException(
        StmZipErrorCode.LIMIT_EXCEEDED,
        "ZIP size accounting overflowed",
        error,
    )
}

private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun sha256(file: Path, cancellation: StmExtractionCancellation): String {
    val digest = MessageDigest.getInstance(SHA_256)
    Files.newInputStream(
        file,
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    ).use { input ->
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            cancellation.throwIfCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

internal fun stmTreeIdentitySha256(entries: List<StmZipManifestEntry>): String {
    val digest = MessageDigest.getInstance(SHA_256)
    entries.forEach { entry ->
        val record = when (entry.type) {
            StmZipManifestEntryType.DIRECTORY -> "D\u0000${entry.relativePath}\u0000"
            StmZipManifestEntryType.FILE ->
                "F\u0000${entry.relativePath}\u0000${entry.sizeBytes}\u0000${entry.sha256}\u0000"
        }
        digest.update(record.toByteArray(StandardCharsets.UTF_8))
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}

private fun toManifestPath(relativePath: Path): String =
    (0 until relativePath.nameCount).joinToString(PATH_SEPARATOR) { index ->
        relativePath.getName(index).toString()
    }

private fun deleteTreeNoFollow(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
            throw error
        }

        override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
            if (error != null) throw error
            Files.deleteIfExists(directory)
            return FileVisitResult.CONTINUE
        }
    })
}

private const val MEBIBYTE = 1024L * 1024L
private const val GIBIBYTE = 1024L * MEBIBYTE
private const val COPY_BUFFER_SIZE = 64 * 1024
private const val PAYLOAD_DIRECTORY = "payload"
private const val SCRATCH_DIRECTORY = "scratch"
private const val PART_SUFFIX = ".part"
private const val SHA_256 = "SHA-256"
private const val PATH_SEPARATOR = "/"
private const val BACKSLASH = "\\"
private const val CURRENT_DIRECTORY = "."
private const val PARENT_DIRECTORY = ".."
private const val BACKSLASH_BYTE = 0x5c
private const val SPACE_BYTE = 0x20
private const val DELETE_BYTE = 0x7f
private const val ASCII_MAX = 0x7f
private const val EXTRA_FIELD_HEADER_SIZE = 4
private const val PKWARE_UNIX_EXTRA_FIELD = 0x000d
private const val ASI_UNIX_EXTRA_FIELD = 0x756e
private const val UNIX_FILE_TYPE_MASK = 0xf000
private const val UNIX_REGULAR_FILE = 0x8000
private const val UNIX_DIRECTORY = 0x4000
private const val UNIX_SPECIAL_PERMISSION_MASK = 0x0e00
