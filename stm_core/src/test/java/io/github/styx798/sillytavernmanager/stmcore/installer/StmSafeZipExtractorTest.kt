package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.zip.UnrecognizedExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipShort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StmSafeZipExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `normal archive extracts through scratch and returns a stable verified manifest`() {
        val environment = environment("normal")
        val hello = "hello STM".toByteArray()
        val binary = ByteArray(1_024).also { Random(7).nextBytes(it) }
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec("root/", ByteArray(0), method = ZipEntry.STORED),
                ZipSpec("root/hello.txt", hello),
                ZipSpec("root/nested/data.bin", binary, method = ZipEntry.STORED),
            ),
        )

        val result = StmSafeZipExtractor().extract(
            artifact = environment.artifact.toFile(),
            operationStagingRoot = environment.operationRoot.toFile(),
        )

        assertEquals(environment.operationRoot.resolve("payload").toFile(), result.payloadDirectory)
        assertEquals(2, result.fileCount)
        assertEquals(2, result.directoryCount)
        assertEquals((hello.size + binary.size).toLong(), result.totalFileBytes)
        assertEquals(
            listOf("root", "root/hello.txt", "root/nested", "root/nested/data.bin"),
            result.entries.map(StmZipManifestEntry::relativePath),
        )
        assertEquals(
            sha256(hello),
            result.entries.single { it.relativePath == "root/hello.txt" }.sha256,
        )
        assertTrue(result.manifestSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            "hello STM",
            result.payloadDirectory.toPath().resolve("root/hello.txt").toFile().readText(),
        )
        assertFalse(Files.exists(environment.operationRoot.resolve("scratch"), LinkOption.NOFOLLOW_LINKS))
        assertSentinelUnchanged(environment)
    }

    @Test
    fun `signed archive fast mode keeps CRC safety without per-file sync or SHA`() {
        val environment = environment("signed-fast")
        val content = ByteArray(128 * 1024).also { Random(17).nextBytes(it) }
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec("node_modules/", ByteArray(0), method = ZipEntry.STORED),
                ZipSpec("node_modules/package.bin", content),
            ),
        )
        var syncCalls = 0
        val countingFactory = StmZipSinkFactory { path ->
            val delegate = DefaultStmZipSinkFactory.open(path)
            object : StmZipSink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    delegate.write(buffer, offset, length)
                }

                override fun sync() {
                    syncCalls += 1
                    delegate.sync()
                }

                override fun close() = delegate.close()
            }
        }

        val result = StmSafeZipExtractor(countingFactory).extract(
            artifact = environment.artifact.toFile(),
            operationStagingRoot = environment.operationRoot.toFile(),
            mode = StmZipExtractionMode.SIGNED_ARCHIVE_FAST,
        )

        assertEquals(0, syncCalls)
        assertEquals(content.size.toLong(), result.totalFileBytes)
        assertEquals(
            null,
            result.entries.single { it.relativePath == "node_modules/package.bin" }.sha256,
        )
        assertTrue(
            Files.readAllBytes(
                result.payloadDirectory.toPath().resolve("node_modules/package.bin"),
            ).contentEquals(content),
        )
    }

    @Test
    fun `path traversal is rejected without writing outside staging`() {
        val environment = environment("traversal")
        val escaped = environment.stagingParent.resolve("escaped.txt")
        writeZip(environment.artifact, listOf(ZipSpec("../escaped.txt", "bad".toByteArray())))

        assertFailure(StmZipErrorCode.PATH_REJECTED) {
            StmSafeZipExtractor().extract(
                environment.artifact.toFile(),
                environment.operationRoot.toFile(),
            )
        }

        assertFalse(Files.exists(escaped, LinkOption.NOFOLLOW_LINKS))
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `duplicate paths are rejected during preflight`() {
        val environment = environment("duplicate")
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec("same.txt", "one".toByteArray()),
                ZipSpec("same.txt", "two".toByteArray()),
            ),
        )

        assertFailure(StmZipErrorCode.DUPLICATE_PATH) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `case folded paths are rejected as portable collisions`() {
        val environment = environment("case-collision")
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec("Folder/A.txt", "one".toByteArray()),
                ZipSpec("folder/a.TXT", "two".toByteArray()),
            ),
        )

        assertFailure(StmZipErrorCode.NAME_COLLISION) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `NFC equivalent paths are rejected as normalization collisions`() {
        val environment = environment("nfc-collision")
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec("caf\u00e9/file.txt", "one".toByteArray()),
                ZipSpec("cafe\u0301/other.txt", "two".toByteArray()),
            ),
        )

        assertFailure(StmZipErrorCode.NAME_COLLISION) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `file and child directory paths cannot conflict`() {
        val environment = environment("file-directory-conflict")
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec("node", "file".toByteArray()),
                ZipSpec("node/child.txt", "child".toByteArray()),
            ),
        )

        assertFailure(StmZipErrorCode.FILE_DIRECTORY_CONFLICT) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `Unix symlink entries are rejected`() {
        val environment = environment("symlink")
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec(
                    name = "link",
                    content = "../../outside".toByteArray(),
                    unixMode = UNIX_SYMLINK or UNIX_PERMISSIONS_0777,
                ),
            ),
        )

        assertFailure(StmZipErrorCode.ENTRY_TYPE_REJECTED) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `Unix setuid entries are rejected`() {
        val environment = environment("setuid")
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec(
                    name = "executable",
                    content = "content".toByteArray(),
                    unixMode = UNIX_REGULAR_FILE or UNIX_SET_UID or UNIX_PERMISSIONS_0755,
                ),
            ),
        )

        assertFailure(StmZipErrorCode.ENTRY_TYPE_REJECTED) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `PKWARE Unix link metadata is rejected even without a symlink mode`() {
        val environment = environment("pkware-unix")
        writeZip(
            environment.artifact,
            listOf(
                ZipSpec(
                    name = "link-metadata",
                    content = ByteArray(0),
                    extraFieldHeaderId = PKWARE_UNIX_EXTRA_FIELD,
                ),
            ),
        )

        assertFailure(StmZipErrorCode.ENTRY_TYPE_REJECTED) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `entry count single file total size and compression ratio quotas are enforced`() {
        val cases = listOf(
            QuotaCase(
                name = "count",
                specs = listOf(
                    ZipSpec("one", byteArrayOf(1)),
                    ZipSpec("two", byteArrayOf(2)),
                ),
                policy = StmZipExtractionPolicy(maxEntries = 1),
            ),
            QuotaCase(
                name = "single",
                specs = listOf(ZipSpec("large", ByteArray(32))),
                policy = StmZipExtractionPolicy(maxSingleFileBytes = 31),
            ),
            QuotaCase(
                name = "total",
                specs = listOf(
                    ZipSpec("one", ByteArray(10)),
                    ZipSpec("two", ByteArray(10)),
                ),
                policy = StmZipExtractionPolicy(maxTotalUncompressedBytes = 19),
            ),
            QuotaCase(
                name = "path-nodes",
                specs = listOf(ZipSpec("parent/child", byteArrayOf(1))),
                policy = StmZipExtractionPolicy(maxPathNodes = 1),
            ),
            QuotaCase(
                name = "ratio",
                specs = listOf(ZipSpec("compressed", ByteArray(8_192))),
                policy = StmZipExtractionPolicy(maxCompressionRatio = 2),
            ),
        )

        cases.forEach { case ->
            val environment = environment("quota-${case.name}")
            writeZip(environment.artifact, case.specs)
            assertFailure(StmZipErrorCode.LIMIT_EXCEEDED) {
                StmSafeZipExtractor().extract(
                    environment.artifact.toFile(),
                    environment.operationRoot.toFile(),
                    case.policy,
                )
            }
            assertFailedOperationWasContained(environment)
        }
    }

    @Test
    fun `stored entry corruption is detected by explicit CRC verification`() {
        val environment = environment("crc")
        val content = "unique CRC payload".toByteArray()
        writeZip(
            environment.artifact,
            listOf(ZipSpec("crc.txt", content, method = ZipEntry.STORED)),
        )
        corruptFirstEntryData(environment.artifact)

        assertFailure(StmZipErrorCode.CRC_MISMATCH) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `truncated central directory is rejected before staging is created`() {
        val environment = environment("truncated")
        writeZip(environment.artifact, listOf(ZipSpec("file.txt", "payload".toByteArray())))
        val bytes = Files.readAllBytes(environment.artifact)
        Files.write(
            environment.artifact,
            bytes.copyOf(bytes.size - 12),
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        assertFailure(StmZipErrorCode.INVALID_ARCHIVE) {
            extract(environment)
        }
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `mid-stream cancellation removes only the owned operation root`() {
        val environment = environment("cancel")
        val content = ByteArray(256 * 1024).also { Random(11).nextBytes(it) }
        writeZip(environment.artifact, listOf(ZipSpec("large.bin", content)))
        var bytesWritten = 0L
        val countingFactory = StmZipSinkFactory { path ->
            val delegate = DefaultStmZipSinkFactory.open(path)
            object : StmZipSink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    delegate.write(buffer, offset, length)
                    bytesWritten += length
                }

                override fun sync() = delegate.sync()

                override fun close() = delegate.close()
            }
        }

        assertFailure(StmZipErrorCode.OPERATION_CANCELLED) {
            StmSafeZipExtractor(countingFactory).extract(
                artifact = environment.artifact.toFile(),
                operationStagingRoot = environment.operationRoot.toFile(),
                cancellation = StmExtractionCancellation { bytesWritten > 0 },
            )
        }

        assertTrue(bytesWritten > 0)
        assertFailedOperationWasContained(environment)
    }

    @Test
    fun `injected ENOSPC removes only the owned operation root`() {
        val environment = environment("enospc")
        writeZip(environment.artifact, listOf(ZipSpec("file.txt", "payload".toByteArray())))
        val noSpaceFactory = StmZipSinkFactory { path ->
            val delegate = DefaultStmZipSinkFactory.open(path)
            object : StmZipSink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    throw IOException("ENOSPC")
                }

                override fun sync() = delegate.sync()

                override fun close() = delegate.close()
            }
        }

        assertFailure(StmZipErrorCode.STORAGE_NO_SPACE) {
            StmSafeZipExtractor(noSpaceFactory).extract(
                environment.artifact.toFile(),
                environment.operationRoot.toFile(),
            )
        }

        assertFailedOperationWasContained(environment)
    }

    private fun extract(environment: TestEnvironment): StmZipExtractionResult =
        StmSafeZipExtractor().extract(
            environment.artifact.toFile(),
            environment.operationRoot.toFile(),
        )

    private fun environment(name: String): TestEnvironment {
        val root = temporaryFolder.newFolder(name).toPath()
        val stagingParent = Files.createDirectory(root.resolve("staging"))
        val sentinel = root.resolve("outside-sentinel.txt")
        sentinel.toFile().writeText(SENTINEL_CONTENT)
        return TestEnvironment(
            root = root,
            artifact = root.resolve("artifact.zip"),
            stagingParent = stagingParent,
            operationRoot = stagingParent.resolve("operation"),
            sentinel = sentinel,
        )
    }

    private fun writeZip(archive: Path, specs: List<ZipSpec>) {
        ZipArchiveOutputStream(archive.toFile()).use { output ->
            output.setEncoding(StandardCharsets.UTF_8.name())
            output.setUseLanguageEncodingFlag(true)
            specs.forEach { spec ->
                val entry = ZipArchiveEntry(spec.name)
                entry.method = spec.method
                entry.size = spec.content.size.toLong()
                val crc = CRC32().apply { update(spec.content) }.value
                entry.crc = crc
                if (spec.method == ZipEntry.STORED) {
                    entry.compressedSize = spec.content.size.toLong()
                }
                spec.unixMode?.let(entry::setUnixMode)
                spec.extraFieldHeaderId?.let { headerId ->
                    val field = UnrecognizedExtraField().apply {
                        setHeaderId(ZipShort(headerId))
                        setLocalFileDataData(ByteArray(PKWARE_UNIX_PAYLOAD_SIZE))
                        setCentralDirectoryData(ByteArray(PKWARE_UNIX_PAYLOAD_SIZE))
                    }
                    entry.addExtraField(field)
                }
                output.putArchiveEntry(entry)
                output.write(spec.content)
                output.closeArchiveEntry()
            }
        }
    }

    private fun corruptFirstEntryData(archive: Path) {
        val bytes = Files.readAllBytes(archive)
        assertEquals(LOCAL_FILE_HEADER_SIGNATURE, littleEndianInt(bytes, 0))
        val nameLength = littleEndianUnsignedShort(bytes, LOCAL_NAME_LENGTH_OFFSET)
        val extraLength = littleEndianUnsignedShort(bytes, LOCAL_EXTRA_LENGTH_OFFSET)
        val dataOffset = LOCAL_HEADER_SIZE + nameLength + extraLength
        bytes[dataOffset] = (bytes[dataOffset].toInt() xor 0x01).toByte()
        Files.write(archive, bytes, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun assertFailure(
        expectedCode: StmZipErrorCode,
        block: () -> Unit,
    ): StmZipExtractionException {
        try {
            block()
            fail("Expected $expectedCode")
        } catch (error: StmZipExtractionException) {
            assertEquals(expectedCode, error.code)
            return error
        }
        throw AssertionError("Unreachable")
    }

    private fun assertFailedOperationWasContained(environment: TestEnvironment) {
        assertFalse(Files.exists(environment.operationRoot, LinkOption.NOFOLLOW_LINKS))
        assertSentinelUnchanged(environment)
    }

    private fun assertSentinelUnchanged(environment: TestEnvironment) {
        assertEquals(SENTINEL_CONTENT, environment.sentinel.toFile().readText())
        assertTrue(Files.isDirectory(environment.stagingParent, LinkOption.NOFOLLOW_LINKS))
    }
}

private data class TestEnvironment(
    val root: Path,
    val artifact: Path,
    val stagingParent: Path,
    val operationRoot: Path,
    val sentinel: Path,
)

private data class ZipSpec(
    val name: String,
    val content: ByteArray,
    val unixMode: Int? = null,
    val method: Int = ZipEntry.DEFLATED,
    val extraFieldHeaderId: Int? = null,
)

private data class QuotaCase(
    val name: String,
    val specs: List<ZipSpec>,
    val policy: StmZipExtractionPolicy,
)

private fun sha256(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)

private const val SENTINEL_CONTENT = "outside must remain unchanged"
private const val UNIX_REGULAR_FILE = 0x8000
private const val UNIX_SYMLINK = 0xa000
private const val UNIX_SET_UID = 0x0800
private const val UNIX_PERMISSIONS_0755 = 0x01ed
private const val UNIX_PERMISSIONS_0777 = 0x01ff
private const val PKWARE_UNIX_EXTRA_FIELD = 0x000d
private const val PKWARE_UNIX_PAYLOAD_SIZE = 12
private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
private const val LOCAL_HEADER_SIZE = 30
private const val LOCAL_NAME_LENGTH_OFFSET = 26
private const val LOCAL_EXTRA_LENGTH_OFFSET = 28
