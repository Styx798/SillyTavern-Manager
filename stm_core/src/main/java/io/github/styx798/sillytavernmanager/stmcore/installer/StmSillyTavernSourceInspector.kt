package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

internal data class StmSillyTavernSourceInspectionPolicy(
    val maxPackageJsonBytes: Int = 1_048_576,
    val maxPackageLockBytes: Long = 64L * 1_048_576L,
    val maxTreeEntries: Int = 50_000,
    val maxTreeDepth: Int = 64,
    val maxPathBytes: Int = 4_096,
    val maxSegmentBytes: Int = 255,
    val maxJsonDepth: Int = 64,
    val maxJsonNodes: Int = 100_000,
    val maxJsonStringChars: Int = 65_536,
    val maxVersionBytes: Int = 128,
    val maxNodeRequirementBytes: Int = 256,
) {
    init {
        require(maxPackageJsonBytes > 0)
        require(maxPackageLockBytes > 0)
        require(maxTreeEntries > 0)
        require(maxTreeDepth > 0)
        require(maxPathBytes > 0)
        require(maxSegmentBytes > 0)
        require(maxJsonDepth > 0)
        require(maxJsonNodes > 0)
        require(maxJsonStringChars > 0)
        require(maxVersionBytes > 0)
        require(maxNodeRequirementBytes > 0)
    }
}

internal enum class StmSillyTavernSourceErrorCode {
    INVALID_EXACT_COMMIT,
    PAYLOAD_NOT_DIRECTORY,
    ARCHIVE_ROOT_COUNT_INVALID,
    ARCHIVE_ROOT_NOT_DIRECTORY,
    ARCHIVE_ROOT_NAME_INVALID,
    ARCHIVE_ROOT_COMMIT_MISMATCH,
    UNSAFE_TREE_ENTRY,
    TREE_LIMIT_EXCEEDED,
    REQUIRED_FILE_MISSING,
    REQUIRED_FILE_NOT_REGULAR,
    LICENSE_MISSING,
    PACKAGE_JSON_TOO_LARGE,
    PACKAGE_JSON_INVALID_UTF8,
    PACKAGE_JSON_INVALID,
    PACKAGE_VERSION_MISSING,
    PACKAGE_VERSION_INVALID,
    NODE_REQUIREMENT_MISSING,
    NODE_REQUIREMENT_INVALID,
    PACKAGE_LOCK_TOO_LARGE,
    IO_FAILURE,
}

internal data class StmSillyTavernRequiredFileEvidence(
    val relativePath: String,
    val sizeBytes: Long,
)

/**
 * Stage 2 source evidence only. It proves archive structure and records package metadata; it does
 * not install dependencies, execute scripts, or establish that the source is runnable.
 */
internal data class StmSillyTavernSourceEvidence(
    val archiveRoot: String,
    val stVersion: String,
    val nodeRequirement: String,
    val packageLockSha256: String,
    val licenseStatus: String,
    val requiredFiles: List<StmSillyTavernRequiredFileEvidence>,
    val licenseFiles: List<String>,
    val noticeFiles: List<String>,
)

internal sealed interface StmSillyTavernSourceInspectionResult {
    data class Accepted(
        val evidence: StmSillyTavernSourceEvidence,
    ) : StmSillyTavernSourceInspectionResult

    data class Rejected(
        val code: StmSillyTavernSourceErrorCode,
        val detail: String,
        val relativePath: String? = null,
    ) : StmSillyTavernSourceInspectionResult
}

/**
 * Inspects an already extracted GitHub source archive without trusting its directory name as an
 * identity claim. The caller must separately verify the archive identity and integrity.
 */
internal class StmSillyTavernSourceInspector(
    private val policy: StmSillyTavernSourceInspectionPolicy =
        StmSillyTavernSourceInspectionPolicy(),
) {
    fun inspect(
        payloadDirectory: File,
        expectedExactCommit: String,
    ): StmSillyTavernSourceInspectionResult = try {
        inspectOrThrow(payloadDirectory.toPath(), expectedExactCommit)
    } catch (failure: SourceInspectionFailure) {
        StmSillyTavernSourceInspectionResult.Rejected(
            code = failure.code,
            detail = failure.message ?: "Source inspection was rejected",
            relativePath = failure.relativePath,
        )
    } catch (_: IOException) {
        StmSillyTavernSourceInspectionResult.Rejected(
            code = StmSillyTavernSourceErrorCode.IO_FAILURE,
            detail = "Source inspection could not read the extracted payload",
        )
    } catch (_: SecurityException) {
        StmSillyTavernSourceInspectionResult.Rejected(
            code = StmSillyTavernSourceErrorCode.IO_FAILURE,
            detail = "Source inspection was denied access to the extracted payload",
        )
    }

    private fun inspectOrThrow(
        payloadDirectory: Path,
        expectedExactCommit: String,
    ): StmSillyTavernSourceInspectionResult.Accepted {
        val normalizedCommit = expectedExactCommit.lowercase(Locale.ROOT)
        if (!EXACT_COMMIT_PATTERN.matches(normalizedCommit)) {
            reject(
                StmSillyTavernSourceErrorCode.INVALID_EXACT_COMMIT,
                "The expected commit must be exactly 40 or 64 hexadecimal characters",
            )
        }

        val payload = payloadDirectory.toAbsolutePath().normalize()
        val payloadAttributes = readAttributesOrReject(
            payload,
            StmSillyTavernSourceErrorCode.PAYLOAD_NOT_DIRECTORY,
            "The extracted payload must be an existing real directory",
        )
        if (!payloadAttributes.isDirectory || payloadAttributes.isSymbolicLink) {
            reject(
                StmSillyTavernSourceErrorCode.PAYLOAD_NOT_DIRECTORY,
                "The extracted payload must be an existing real directory",
            )
        }

        val rootEntries = Files.newDirectoryStream(payload).use { stream ->
            val entries = ArrayList<Path>(2)
            for (entry in stream) {
                entries.add(entry)
                if (entries.size > 1) break
            }
            entries
        }
        if (rootEntries.size != 1) {
            reject(
                StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_COUNT_INVALID,
                "A GitHub source archive must contain exactly one top-level entry",
            )
        }

        val archiveRoot = rootEntries.single()
        val rootAttributes = readAttributesOrReject(
            archiveRoot,
            StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_NOT_DIRECTORY,
            "The archive top-level entry must be a real directory",
        )
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) {
            reject(
                StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_NOT_DIRECTORY,
                "The archive top-level entry must be a real directory",
            )
        }

        val archiveRootName = archiveRoot.fileName.toString()
        validateArchiveRootName(archiveRootName, normalizedCommit)
        validateTree(archiveRoot)

        val server = requireRegularFile(archiveRoot, SERVER_FILE)
        val packageJson = requireRegularFile(archiveRoot, PACKAGE_JSON_FILE)
        val packageLock = requireRegularFile(archiveRoot, PACKAGE_LOCK_FILE)
        val rootFiles = listRootRegularFiles(archiveRoot)
        val licenseFiles = rootFiles.filter(::isLicenseFile).sorted()
        if (licenseFiles.isEmpty()) {
            reject(
                StmSillyTavernSourceErrorCode.LICENSE_MISSING,
                "The archive root must contain LICENSE or a LICENSE extension file",
            )
        }
        val noticeFiles = rootFiles.filter(::isNoticeFile).sorted()

        val packageJsonBytes = readBoundedPackageJson(packageJson.path)
        val packageMetadata = parsePackageMetadata(packageJsonBytes)
        val lockDigest = sha256Bounded(packageLock.path, policy.maxPackageLockBytes)

        val requiredFiles = listOf(server, packageJson, packageLock)
            .map { file ->
                StmSillyTavernRequiredFileEvidence(
                    relativePath = file.relativePath,
                    sizeBytes = file.sizeBytes,
                )
            }
            .sortedBy(StmSillyTavernRequiredFileEvidence::relativePath)

        return StmSillyTavernSourceInspectionResult.Accepted(
            StmSillyTavernSourceEvidence(
                archiveRoot = archiveRootName,
                stVersion = packageMetadata.version,
                nodeRequirement = packageMetadata.nodeRequirement,
                packageLockSha256 = lockDigest.sha256,
                licenseStatus = if (noticeFiles.isEmpty()) {
                    LICENSE_PRESENT
                } else {
                    LICENSE_AND_NOTICE_PRESENT
                },
                requiredFiles = requiredFiles,
                licenseFiles = licenseFiles,
                noticeFiles = noticeFiles,
            ),
        )
    }

    private fun validateArchiveRootName(name: String, expectedCommit: String) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        if (nameBytes.size > policy.maxSegmentBytes || !SAFE_ARCHIVE_ROOT_PATTERN.matches(name)) {
            reject(
                StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_NAME_INVALID,
                "The archive root name is not a bounded repository-commit directory name",
            )
        }
        val commitSuffix = "-$expectedCommit"
        if (!name.lowercase(Locale.ROOT).endsWith(commitSuffix) ||
            name.length <= commitSuffix.length
        ) {
            reject(
                StmSillyTavernSourceErrorCode.ARCHIVE_ROOT_COMMIT_MISMATCH,
                "The archive root name is not bound to the expected exact commit",
            )
        }
    }

    private fun validateTree(root: Path) {
        var entryCount = 0
        Files.walkFileTree(
            root,
            emptySet(),
            policy.maxTreeDepth,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    inspectEntry(root, directory, attributes, isRoot = directory == root)
                    if (!attributes.isDirectory || attributes.isSymbolicLink) {
                        rejectUnsafe(root, directory)
                    }
                    entryCount += 1
                    checkTreeEntryCount(entryCount)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    inspectEntry(root, file, attributes, isRoot = false)
                    if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                        rejectUnsafe(root, file)
                    }
                    entryCount += 1
                    checkTreeEntryCount(entryCount)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    reject(
                        StmSillyTavernSourceErrorCode.IO_FAILURE,
                        "Source inspection could not read an archive entry",
                        safeRelativePath(root, file),
                    )
                }
            },
        )
    }

    private fun inspectEntry(
        root: Path,
        entry: Path,
        attributes: BasicFileAttributes,
        isRoot: Boolean,
    ) {
        if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
            rejectUnsafe(root, entry)
        }
        if (isRoot) return

        val relative = root.relativize(entry)
        if (relative.nameCount > policy.maxTreeDepth) {
            reject(
                StmSillyTavernSourceErrorCode.TREE_LIMIT_EXCEEDED,
                "An archive path exceeds the inspection depth limit",
                safeRelativePath(root, entry),
            )
        }
        val relativeText = relative.joinToString("/") { it.toString() }
        if (relativeText.toByteArray(StandardCharsets.UTF_8).size > policy.maxPathBytes ||
            relative.any { segment ->
                segment.toString().toByteArray(StandardCharsets.UTF_8).size > policy.maxSegmentBytes
            }
        ) {
            reject(
                StmSillyTavernSourceErrorCode.TREE_LIMIT_EXCEEDED,
                "An archive path exceeds an inspection length limit",
            )
        }
    }

    private fun checkTreeEntryCount(entryCount: Int) {
        if (entryCount > policy.maxTreeEntries) {
            reject(
                StmSillyTavernSourceErrorCode.TREE_LIMIT_EXCEEDED,
                "The archive contains too many filesystem entries",
            )
        }
    }

    private fun rejectUnsafe(root: Path, entry: Path): Nothing = reject(
        StmSillyTavernSourceErrorCode.UNSAFE_TREE_ENTRY,
        "The archive tree contains a symbolic link or non-regular entry",
        safeRelativePath(root, entry),
    )

    private fun requireRegularFile(root: Path, name: String): InspectedFile {
        val path = root.resolve(name)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            reject(
                StmSillyTavernSourceErrorCode.REQUIRED_FILE_MISSING,
                "A required SillyTavern source file is missing",
                name,
            )
        }
        val attributes = readAttributesOrReject(
            path,
            StmSillyTavernSourceErrorCode.REQUIRED_FILE_NOT_REGULAR,
            "A required SillyTavern source file is not a regular file",
            name,
        )
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            reject(
                StmSillyTavernSourceErrorCode.REQUIRED_FILE_NOT_REGULAR,
                "A required SillyTavern source file is not a regular file",
                name,
            )
        }
        return InspectedFile(path, name, attributes.size())
    }

    private fun listRootRegularFiles(root: Path): List<String> =
        Files.newDirectoryStream(root).use { stream ->
            stream.mapNotNull { entry ->
                val attributes = Files.readAttributes(
                    entry,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (attributes.isRegularFile && !attributes.isSymbolicLink) {
                    entry.fileName.toString()
                } else {
                    null
                }
            }
        }

    private fun readBoundedPackageJson(path: Path): ByteArray {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.size() > policy.maxPackageJsonBytes) {
            reject(
                StmSillyTavernSourceErrorCode.PACKAGE_JSON_TOO_LARGE,
                "package.json exceeds the inspection size limit",
                PACKAGE_JSON_FILE,
            )
        }

        val output = ByteArrayOutputStream(attributes.size().toInt().coerceAtLeast(32))
        val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
        openReadNoFollow(path).use { channel ->
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                if (output.size() > policy.maxPackageJsonBytes - count) {
                    reject(
                        StmSillyTavernSourceErrorCode.PACKAGE_JSON_TOO_LARGE,
                        "package.json exceeds the inspection size limit",
                        PACKAGE_JSON_FILE,
                    )
                }
                output.write(buffer.array(), 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun parsePackageMetadata(bytes: ByteArray): PackageMetadata {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            reject(
                StmSillyTavernSourceErrorCode.PACKAGE_JSON_INVALID_UTF8,
                "package.json is not strict UTF-8",
                PACKAGE_JSON_FILE,
            )
        }

        val root = try {
            StrictJsonParser(
                input = text,
                maxDepth = policy.maxJsonDepth,
                maxNodes = policy.maxJsonNodes,
                maxStringChars = policy.maxJsonStringChars,
            ).parse()
        } catch (_: StrictJsonException) {
            reject(
                StmSillyTavernSourceErrorCode.PACKAGE_JSON_INVALID,
                "package.json is not a complete strict JSON document",
                PACKAGE_JSON_FILE,
            )
        }

        val rootObject = root as? StrictJsonValue.ObjectValue ?: reject(
            StmSillyTavernSourceErrorCode.PACKAGE_JSON_INVALID,
            "package.json must contain a top-level JSON object",
            PACKAGE_JSON_FILE,
        )
        val versionValue = rootObject.members[VERSION_KEY] ?: reject(
            StmSillyTavernSourceErrorCode.PACKAGE_VERSION_MISSING,
            "package.json is missing the top-level version field",
            PACKAGE_JSON_FILE,
        )
        val version = (versionValue as? StrictJsonValue.StringValue)?.value ?: reject(
            StmSillyTavernSourceErrorCode.PACKAGE_VERSION_INVALID,
            "The top-level package version must be a bounded string",
            PACKAGE_JSON_FILE,
        )
        validateMetadataString(
            value = version,
            maxBytes = policy.maxVersionBytes,
            code = StmSillyTavernSourceErrorCode.PACKAGE_VERSION_INVALID,
            detail = "The top-level package version must be a bounded string",
        )

        val engines = rootObject.members[ENGINES_KEY] ?: reject(
            StmSillyTavernSourceErrorCode.NODE_REQUIREMENT_MISSING,
            "package.json is missing the top-level engines object",
            PACKAGE_JSON_FILE,
        )
        val enginesObject = engines as? StrictJsonValue.ObjectValue ?: reject(
            StmSillyTavernSourceErrorCode.NODE_REQUIREMENT_INVALID,
            "The top-level engines field must be an object containing node",
            PACKAGE_JSON_FILE,
        )
        val nodeValue = enginesObject.members[NODE_KEY] ?: reject(
            StmSillyTavernSourceErrorCode.NODE_REQUIREMENT_MISSING,
            "package.json is missing the top-level engines.node field",
            PACKAGE_JSON_FILE,
        )
        val nodeRequirement = (nodeValue as? StrictJsonValue.StringValue)?.value ?: reject(
            StmSillyTavernSourceErrorCode.NODE_REQUIREMENT_INVALID,
            "The top-level engines.node value must be a bounded string",
            PACKAGE_JSON_FILE,
        )
        validateMetadataString(
            value = nodeRequirement,
            maxBytes = policy.maxNodeRequirementBytes,
            code = StmSillyTavernSourceErrorCode.NODE_REQUIREMENT_INVALID,
            detail = "The top-level engines.node value must be a bounded string",
        )
        return PackageMetadata(version, nodeRequirement)
    }

    private fun validateMetadataString(
        value: String,
        maxBytes: Int,
        code: StmSillyTavernSourceErrorCode,
        detail: String,
    ) {
        if (value.isEmpty() ||
            value != value.trim() ||
            value.any(Char::isISOControl) ||
            value.toByteArray(StandardCharsets.UTF_8).size > maxBytes
        ) {
            reject(code, detail, PACKAGE_JSON_FILE)
        }
    }

    private fun sha256Bounded(path: Path, maximumBytes: Long): FileDigest {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.size() > maximumBytes) {
            reject(
                StmSillyTavernSourceErrorCode.PACKAGE_LOCK_TOO_LARGE,
                "package-lock.json exceeds the inspection size limit",
                PACKAGE_LOCK_FILE,
            )
        }

        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
        var observedBytes = 0L
        openReadNoFollow(path).use { channel ->
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                if (count.toLong() > maximumBytes - observedBytes) {
                    reject(
                        StmSillyTavernSourceErrorCode.PACKAGE_LOCK_TOO_LARGE,
                        "package-lock.json exceeds the inspection size limit",
                        PACKAGE_LOCK_FILE,
                    )
                }
                digest.update(buffer.array(), 0, count)
                observedBytes += count
            }
        }
        return FileDigest(observedBytes, digest.digest().toHex())
    }

    private fun openReadNoFollow(path: Path) = Files.newByteChannel(
        path,
        setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    )

    private fun readAttributesOrReject(
        path: Path,
        code: StmSillyTavernSourceErrorCode,
        detail: String,
        relativePath: String? = null,
    ): BasicFileAttributes = try {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    } catch (_: IOException) {
        reject(code, detail, relativePath)
    }

    private fun safeRelativePath(root: Path, entry: Path): String? = runCatching {
        val relative = root.relativize(entry).joinToString("/") { it.toString() }
        relative.takeIf {
            it.toByteArray(StandardCharsets.UTF_8).size <= policy.maxPathBytes
        }
    }.getOrNull()

    private fun reject(
        code: StmSillyTavernSourceErrorCode,
        detail: String,
        relativePath: String? = null,
    ): Nothing = throw SourceInspectionFailure(code, detail, relativePath)

    private data class InspectedFile(
        val path: Path,
        val relativePath: String,
        val sizeBytes: Long,
    )

    private data class FileDigest(
        val bytes: Long,
        val sha256: String,
    )

    private data class PackageMetadata(
        val version: String,
        val nodeRequirement: String,
    )

    private companion object {
        const val STREAM_BUFFER_SIZE = 32 * 1024
        const val SERVER_FILE = "server.js"
        const val PACKAGE_JSON_FILE = "package.json"
        const val PACKAGE_LOCK_FILE = "package-lock.json"
        const val VERSION_KEY = "version"
        const val ENGINES_KEY = "engines"
        const val NODE_KEY = "node"
        const val LICENSE_PRESENT = "LICENSE_PRESENT"
        const val LICENSE_AND_NOTICE_PRESENT = "LICENSE_AND_NOTICE_PRESENT"
        const val SHA256_ALGORITHM = "SHA-256"

        val EXACT_COMMIT_PATTERN = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
        val SAFE_ARCHIVE_ROOT_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        val LICENSE_FILE_PATTERN = Regex("^LICENSE(?:\\.[A-Za-z0-9][A-Za-z0-9._-]*)?$")
        val NOTICE_FILE_PATTERN = Regex("^NOTICE(?:\\.[A-Za-z0-9][A-Za-z0-9._-]*)?$")

        fun isLicenseFile(name: String): Boolean = LICENSE_FILE_PATTERN.matches(name)

        fun isNoticeFile(name: String): Boolean = NOTICE_FILE_PATTERN.matches(name)
    }
}

private class SourceInspectionFailure(
    val code: StmSillyTavernSourceErrorCode,
    message: String,
    val relativePath: String?,
) : IOException(message)

private sealed interface StrictJsonValue {
    data class ObjectValue(val members: Map<String, StrictJsonValue>) : StrictJsonValue

    data class ArrayValue(val elements: List<StrictJsonValue>) : StrictJsonValue

    data class StringValue(val value: String) : StrictJsonValue

    data class NumberValue(val source: String) : StrictJsonValue

    data class BooleanValue(val value: Boolean) : StrictJsonValue

    data object NullValue : StrictJsonValue
}

private class StrictJsonException : IllegalArgumentException()

private class StrictJsonParser(
    private val input: String,
    private val maxDepth: Int,
    private val maxNodes: Int,
    private val maxStringChars: Int,
) {
    private var index = 0
    private var nodeCount = 0

    fun parse(): StrictJsonValue {
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        if (index != input.length) fail()
        return value
    }

    private fun parseValue(depth: Int): StrictJsonValue {
        if (depth > maxDepth || ++nodeCount > maxNodes || index >= input.length) fail()
        return when (input[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> StrictJsonValue.StringValue(parseString())
            't' -> {
                consumeLiteral("true")
                StrictJsonValue.BooleanValue(true)
            }
            'f' -> {
                consumeLiteral("false")
                StrictJsonValue.BooleanValue(false)
            }
            'n' -> {
                consumeLiteral("null")
                StrictJsonValue.NullValue
            }
            '-', in '0'..'9' -> StrictJsonValue.NumberValue(parseNumber())
            else -> fail()
        }
    }

    private fun parseObject(depth: Int): StrictJsonValue.ObjectValue {
        expect('{')
        skipWhitespace()
        val members = LinkedHashMap<String, StrictJsonValue>()
        if (takeIfPresent('}')) return StrictJsonValue.ObjectValue(members)
        while (true) {
            if (index >= input.length || input[index] != '"') fail()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            val value = parseValue(depth + 1)
            if (members.put(key, value) != null) fail()
            skipWhitespace()
            if (takeIfPresent('}')) break
            expect(',')
            skipWhitespace()
        }
        return StrictJsonValue.ObjectValue(members)
    }

    private fun parseArray(depth: Int): StrictJsonValue.ArrayValue {
        expect('[')
        skipWhitespace()
        val elements = ArrayList<StrictJsonValue>()
        if (takeIfPresent(']')) return StrictJsonValue.ArrayValue(elements)
        while (true) {
            elements += parseValue(depth + 1)
            skipWhitespace()
            if (takeIfPresent(']')) break
            expect(',')
            skipWhitespace()
        }
        return StrictJsonValue.ArrayValue(elements)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < input.length) {
            val character = input[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> appendEscape(result)
                character.code < 0x20 -> fail()
                Character.isHighSurrogate(character) -> {
                    if (index >= input.length || !Character.isLowSurrogate(input[index])) fail()
                    appendBounded(result, character)
                    appendBounded(result, input[index++])
                }
                Character.isLowSurrogate(character) -> fail()
                else -> appendBounded(result, character)
            }
        }
        fail()
    }

    private fun appendEscape(result: StringBuilder) {
        if (index >= input.length) fail()
        when (val escape = input[index++]) {
            '"', '\\', '/' -> appendBounded(result, escape)
            'b' -> appendBounded(result, '\b')
            'f' -> appendBounded(result, '\u000C')
            'n' -> appendBounded(result, '\n')
            'r' -> appendBounded(result, '\r')
            't' -> appendBounded(result, '\t')
            'u' -> {
                val first = readUnicodeEscape()
                when {
                    Character.isHighSurrogate(first) -> {
                        if (index + 1 >= input.length ||
                            input[index] != '\\' ||
                            input[index + 1] != 'u'
                        ) {
                            fail()
                        }
                        index += 2
                        val second = readUnicodeEscape()
                        if (!Character.isLowSurrogate(second)) fail()
                        appendBounded(result, first)
                        appendBounded(result, second)
                    }
                    Character.isLowSurrogate(first) -> fail()
                    else -> appendBounded(result, first)
                }
            }
            else -> fail()
        }
    }

    private fun readUnicodeEscape(): Char {
        if (index > input.length - 4) fail()
        var value = 0
        repeat(4) {
            value = value * 16 + input[index++].digitToIntOrNull(16).orFail()
        }
        return value.toChar()
    }

    private fun appendBounded(result: StringBuilder, value: Char) {
        if (result.length >= maxStringChars) fail()
        result.append(value)
    }

    private fun parseNumber(): String {
        val start = index
        if (takeIfPresent('-') && index >= input.length) fail()
        when {
            takeIfPresent('0') -> {
                if (index < input.length && input[index] in '0'..'9') fail()
            }
            index < input.length && input[index] in '1'..'9' -> {
                index += 1
                while (index < input.length && input[index] in '0'..'9') index += 1
            }
            else -> fail()
        }
        if (takeIfPresent('.')) {
            if (index >= input.length || input[index] !in '0'..'9') fail()
            while (index < input.length && input[index] in '0'..'9') index += 1
        }
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index += 1
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index += 1
            if (index >= input.length || input[index] !in '0'..'9') fail()
            while (index < input.length && input[index] in '0'..'9') index += 1
        }
        return input.substring(start, index)
    }

    private fun consumeLiteral(literal: String) {
        if (!input.regionMatches(index, literal, 0, literal.length)) fail()
        index += literal.length
    }

    private fun skipWhitespace() {
        while (index < input.length &&
            (input[index] == ' ' || input[index] == '\t' ||
                input[index] == '\r' || input[index] == '\n')
        ) {
            index += 1
        }
    }

    private fun expect(character: Char) {
        if (index >= input.length || input[index] != character) fail()
        index += 1
    }

    private fun takeIfPresent(character: Char): Boolean {
        if (index >= input.length || input[index] != character) return false
        index += 1
        return true
    }

    private fun Int?.orFail(): Int = this ?: fail()

    private fun fail(): Nothing = throw StrictJsonException()
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}
