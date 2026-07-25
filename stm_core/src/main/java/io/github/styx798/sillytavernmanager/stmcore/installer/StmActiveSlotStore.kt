package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal data class StmActiveSlotRef(
    val slotId: String,
    val slotRevision: Long,
)

internal data class StmActiveSlotPointer(
    val current: StmActiveSlotRef,
    val previous: StmActiveSlotRef?,
    val activeRevision: Long,
    val operationId: String,
)

internal data class StmStoredActiveSlotPointer(
    val pointer: StmActiveSlotPointer,
    val checksumSha256: String,
)

internal enum class StmActiveSlotRecordSource {
    CURRENT,
    PREVIOUS,
}

internal sealed interface StmActiveSlotReadResult {
    data object Missing : StmActiveSlotReadResult

    data class Loaded(
        val stored: StmStoredActiveSlotPointer,
        val source: StmActiveSlotRecordSource,
    ) : StmActiveSlotReadResult

    data class Corrupt(val detail: String) : StmActiveSlotReadResult
}

internal enum class StmActiveSlotFailpoint {
    BEFORE_WRITE,
    ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
    ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC,
}

internal fun interface StmActiveSlotFaultInjector {
    fun hit(failpoint: StmActiveSlotFailpoint)
}

/**
 * Durable, single-writer storage for the active-slot pointer.
 *
 * The current document and its previous complete document are separate atomic files. A caller
 * must still serialize access at the STM Core coordinator level; the synchronization here only
 * prevents concurrent writes through one store instance.
 */
internal class StmActiveSlotStore(
    activeFile: File,
    private val faultInjector: StmActiveSlotFaultInjector = StmActiveSlotFaultInjector { },
) {
    internal val currentFile: File = activeFile.absoluteFile
    internal val previousFile: File = File(
        requireNotNull(currentFile.parentFile) { "The active-slot file requires a parent directory" },
        currentFile.name + PREVIOUS_SUFFIX,
    )

    @Synchronized
    fun read(): StmActiveSlotReadResult {
        val current = readOne(currentFile.toPath())
        if (current is FileReadResult.Valid) {
            return StmActiveSlotReadResult.Loaded(
                stored = current.stored,
                source = StmActiveSlotRecordSource.CURRENT,
            )
        }

        val previous = readOne(previousFile.toPath())
        if (previous is FileReadResult.Valid) {
            return StmActiveSlotReadResult.Loaded(
                stored = previous.stored,
                source = StmActiveSlotRecordSource.PREVIOUS,
            )
        }

        if (current is FileReadResult.Missing && previous is FileReadResult.Missing) {
            return StmActiveSlotReadResult.Missing
        }

        return StmActiveSlotReadResult.Corrupt(
            detail = buildString {
                append("No valid active-slot record")
                (current as? FileReadResult.Invalid)?.let { append("; current: ${it.detail}") }
                (previous as? FileReadResult.Invalid)?.let { append("; previous: ${it.detail}") }
            }.take(MAX_ERROR_DETAIL_CHARS),
        )
    }

    @Synchronized
    fun write(pointer: StmActiveSlotPointer): StmStoredActiveSlotPointer {
        pointer.requireValid()
        val parent = requireNotNull(currentFile.parentFile)
        Files.createDirectories(parent.toPath())

        val existing = when (val result = read()) {
            StmActiveSlotReadResult.Missing -> null
            is StmActiveSlotReadResult.Loaded -> result
            is StmActiveSlotReadResult.Corrupt -> {
                throw IOException("Refusing to replace corrupt active-slot state: ${result.detail}")
            }
        }

        if (existing?.stored?.pointer == pointer &&
            existing.source == StmActiveSlotRecordSource.CURRENT
        ) {
            return existing.stored
        }

        validateTransition(existing?.stored?.pointer, pointer)
        val encoded = encode(pointer)
        val stored = decode(encoded)

        faultInjector.hit(StmActiveSlotFailpoint.BEFORE_WRITE)

        existing?.stored?.let { old ->
            writeAtomicWithoutFaults(previousFile.toPath(), encode(old.pointer))
        }

        val temporary = temporaryPathFor(currentFile.toPath())
        writeAndSync(temporary, encoded)
        faultInjector.hit(
            StmActiveSlotFailpoint.ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
        )
        atomicReplace(temporary, currentFile.toPath())
        faultInjector.hit(
            StmActiveSlotFailpoint.ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC,
        )
        bestEffortSyncDirectory(parent.toPath())
        return stored
    }

    /**
     * Moves unreadable or semantically unusable pointer records out of the authoritative names.
     * The previous record is moved first, so interruption can only leave the old current record
     * authoritative; the next recovery pass can safely retry.
     */
    @Synchronized
    fun quarantineForRecovery(recoveryId: String): List<File> {
        require(UUID.fromString(recoveryId).toString().equals(recoveryId, ignoreCase = true)) {
            "Recovery quarantine ID must be a UUID"
        }
        val parent = requireNotNull(currentFile.parentFile).toPath().toAbsolutePath().normalize()
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(parent)) { "Active-slot state directory cannot be a symlink" }
        val quarantine = parent.resolve(QUARANTINE_DIRECTORY)
        if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(quarantine) && Files.isDirectory(
                quarantine,
                LinkOption.NOFOLLOW_LINKS,
            )) { "Active-slot quarantine path is unsafe" }
        } else {
            Files.createDirectory(quarantine)
        }

        val moved = mutableListOf<File>()
        listOf(previousFile.toPath(), currentFile.toPath()).forEach { sourceInput ->
            val source = sourceInput.toAbsolutePath().normalize()
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return@forEach
            require(source.parent == parent) { "Active-slot record escaped its state directory" }
            val target = quarantine.resolve("${source.fileName}-$recoveryId")
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                "Active-slot recovery quarantine target already exists"
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target)
            }
            moved += target.toFile()
        }
        bestEffortSyncDirectory(quarantine)
        bestEffortSyncDirectory(parent)
        return moved
    }

    @Synchronized
    fun cleanupTemporaryFilesForRecovery(): Int {
        val parent = requireNotNull(currentFile.parentFile).toPath().toAbsolutePath().normalize()
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return 0
        require(!Files.isSymbolicLink(parent) && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            "Active-slot state directory is unsafe"
        }
        val prefixes = listOf(
            ".${currentFile.name}.tmp-",
            ".${previousFile.name}.tmp-",
        )
        var scanned = 0
        var removed = 0
        Files.newDirectoryStream(parent).use { entries ->
            for (entry in entries) {
                scanned += 1
                require(scanned <= MAX_STATE_DIRECTORY_ENTRIES) {
                    "Active-slot state directory contains too many entries"
                }
                val name = entry.fileName.toString()
                val prefix = prefixes.singleOrNull(name::startsWith) ?: continue
                val suffix = name.removePrefix(prefix)
                if (!UUID_TEXT_PATTERN.matches(suffix)) continue
                if (!Files.isSymbolicLink(entry) &&
                    !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw IOException("Active-slot temporary evidence is not a no-follow file")
                }
                Files.delete(entry)
                removed += 1
            }
        }
        if (removed > 0) bestEffortSyncDirectory(parent)
        return removed
    }

    private fun validateTransition(
        existing: StmActiveSlotPointer?,
        next: StmActiveSlotPointer,
    ) {
        if (existing == null) {
            require(next.activeRevision == INITIAL_ACTIVE_REVISION) {
                "The first active-slot revision must be $INITIAL_ACTIVE_REVISION"
            }
            require(next.previous == null) {
                "The first active-slot pointer cannot claim a previous slot"
            }
            return
        }

        if (next == existing) return
        require(existing.activeRevision < Long.MAX_VALUE) {
            "The active-slot revision cannot advance beyond Long.MAX_VALUE"
        }
        require(next.activeRevision == existing.activeRevision + 1) {
            "The active-slot revision must advance by exactly one"
        }
        require(next.previous == existing.current) {
            "A new active-slot pointer must preserve the old current slot as previous"
        }
    }

    private fun writeAtomicWithoutFaults(target: Path, bytes: ByteArray) {
        val temporary = temporaryPathFor(target)
        writeAndSync(temporary, bytes)
        atomicReplace(temporary, target)
        bestEffortSyncDirectory(requireNotNull(target.parent))
    }

    private fun readOne(path: Path): FileReadResult {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return FileReadResult.Missing
        if (Files.isSymbolicLink(path)) return FileReadResult.Invalid("symbolic links are forbidden")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return FileReadResult.Invalid("record is not a regular file")
        }

        return try {
            val declaredSize = Files.size(path)
            require(declaredSize in MIN_FILE_BYTES.toLong()..MAX_FILE_BYTES.toLong()) {
                "record length $declaredSize is outside the allowed range"
            }
            val bytes = Files.readAllBytes(path)
            require(bytes.size == declaredSize.toInt()) { "record length changed while reading" }
            FileReadResult.Valid(decode(bytes))
        } catch (error: Exception) {
            FileReadResult.Invalid(error.safeDetail())
        }
    }

    private fun encode(pointer: StmActiveSlotPointer): ByteArray {
        pointer.requireValid()
        val payload = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeLong(pointer.activeRevision)
                output.writeSlotRef(pointer.current)
                output.writeBoolean(pointer.previous != null)
                pointer.previous?.let(output::writeSlotRef)
                output.writeBoundedUtf8(pointer.operationId, ACTIVE_SLOT_MAX_OPERATION_ID_BYTES)
                output.flush()
            }
            buffer.toByteArray()
        }
        require(payload.size in 1..MAX_PAYLOAD_BYTES) { "Active-slot payload is too large" }
        val checksum = sha256(payload)

        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FILE_MAGIC)
                output.writeInt(FILE_FORMAT_VERSION)
                output.writeInt(payload.size)
                output.write(payload)
                output.write(checksum)
                output.flush()
            }
            buffer.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): StmStoredActiveSlotPointer {
        require(bytes.size in MIN_FILE_BYTES..MAX_FILE_BYTES) {
            "Active-slot record size is outside the allowed range"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == FILE_MAGIC) { "Active-slot magic did not match" }
        require(input.readInt() == FILE_FORMAT_VERSION) { "Active-slot format is unsupported" }
        val payloadLength = input.readInt()
        require(payloadLength in 1..MAX_PAYLOAD_BYTES) { "Active-slot payload length is invalid" }
        require(bytes.size == HEADER_BYTES + payloadLength + CHECKSUM_BYTES) {
            "Active-slot record has a truncated or trailing payload"
        }

        val payload = ByteArray(payloadLength)
        input.readFully(payload)
        val expectedChecksum = ByteArray(CHECKSUM_BYTES)
        input.readFully(expectedChecksum)
        require(input.read() == -1) { "Active-slot record contains trailing bytes" }
        val actualChecksum = sha256(payload)
        require(MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
            "Active-slot checksum did not match"
        }

        val payloadInput = DataInputStream(ByteArrayInputStream(payload))
        val pointer = StmActiveSlotPointer(
            activeRevision = payloadInput.readLong(),
            current = payloadInput.readSlotRef(),
            previous = when (payloadInput.readStrictBoolean()) {
                true -> payloadInput.readSlotRef()
                false -> null
            },
            operationId = payloadInput.readBoundedUtf8(ACTIVE_SLOT_MAX_OPERATION_ID_BYTES),
        ).also { it.requireValid() }
        require(payloadInput.read() == -1) { "Active-slot payload contains trailing bytes" }
        return StmStoredActiveSlotPointer(
            pointer = pointer,
            checksumSha256 = actualChecksum.toHex(),
        )
    }

    private sealed interface FileReadResult {
        data object Missing : FileReadResult

        data class Valid(val stored: StmStoredActiveSlotPointer) : FileReadResult

        data class Invalid(val detail: String) : FileReadResult
    }

    private companion object {
        const val FILE_MAGIC = 0x53544D41 // STMA
        const val FILE_FORMAT_VERSION = 1
        const val INITIAL_ACTIVE_REVISION = 1L
        const val HEADER_BYTES = Int.SIZE_BYTES * 3
        const val CHECKSUM_BYTES = 32
        const val MIN_FILE_BYTES = HEADER_BYTES + 1 + CHECKSUM_BYTES
        const val MAX_FILE_BYTES = 16 * 1024
        const val MAX_PAYLOAD_BYTES = 4 * 1024
        const val MAX_ERROR_DETAIL_CHARS = 500
        const val PREVIOUS_SUFFIX = ".previous"
        const val QUARANTINE_DIRECTORY = "active-slot-quarantine"
        const val MAX_STATE_DIRECTORY_ENTRIES = 1_024
        val UUID_TEXT_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
        )
    }
}

private fun StmActiveSlotPointer.requireValid() {
    current.requireValid()
    previous?.requireValid()
    require(activeRevision > 0) { "The active-slot revision must be positive" }
    require(operationId.toByteArray(StandardCharsets.UTF_8).size <= ACTIVE_SLOT_MAX_OPERATION_ID_BYTES) {
        "The active-slot operation ID is too long"
    }
    require(ACTIVE_SLOT_OPERATION_ID_PATTERN.matches(operationId)) {
        "The active-slot operation ID contains forbidden characters"
    }
    require(previous?.slotId != current.slotId) {
        "The current and previous active slots must be different"
    }
}

private fun StmActiveSlotRef.requireValid() {
    require(slotRevision > 0) { "The active slot revision must be positive" }
    require(slotId.toByteArray(StandardCharsets.UTF_8).size <= ACTIVE_SLOT_MAX_SLOT_ID_BYTES) {
        "The active slot ID is too long"
    }
    require(ACTIVE_SLOT_ID_PATTERN.matches(slotId)) {
        "The active slot ID contains forbidden characters"
    }
    require(slotId != "." && slotId != "..") { "The active slot ID is reserved" }
}

private fun DataOutputStream.writeSlotRef(slot: StmActiveSlotRef) {
    writeBoundedUtf8(slot.slotId, ACTIVE_SLOT_MAX_SLOT_ID_BYTES)
    writeLong(slot.slotRevision)
}

private fun DataInputStream.readSlotRef(): StmActiveSlotRef = StmActiveSlotRef(
    slotId = readBoundedUtf8(ACTIVE_SLOT_MAX_SLOT_ID_BYTES),
    slotRevision = readLong(),
).also { it.requireValid() }

private fun DataOutputStream.writeBoundedUtf8(value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { "String length is outside bounds" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBoundedUtf8(maximumBytes: Int): String {
    val length = readInt()
    require(length in 1..maximumBytes) { "String length is outside bounds" }
    require(available() >= length) { "String is truncated" }
    val bytes = ByteArray(length)
    readFully(bytes)
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

private fun DataInputStream.readStrictBoolean(): Boolean = when (val value = readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException("Boolean value $value is invalid")
}

private fun writeAndSync(path: Path, bytes: ByteArray) {
    FileOutputStream(path.toFile()).use { output ->
        output.write(bytes)
        output.flush()
        output.fd.sync()
    }
}

private fun atomicReplace(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (error: AtomicMoveNotSupportedException) {
        throw IOException("The active-slot filesystem does not support atomic replacement", error)
    }
}

private fun temporaryPathFor(target: Path): Path = requireNotNull(target.parent).resolve(
    ".${target.fileName}.tmp-${UUID.randomUUID()}",
)

private fun bestEffortSyncDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
    } catch (_: IOException) {
        // Some filesystems and JVM providers do not expose directory fsync through FileChannel.
    } catch (_: UnsupportedOperationException) {
        // Atomic file replacement remains mandatory; only directory fsync is best-effort here.
    } catch (_: SecurityException) {
        // The caller still gets a fully synced file and atomic replacement.
    }
}

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
}

private fun Throwable.safeDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)

private const val ACTIVE_SLOT_MAX_SLOT_ID_BYTES = 128
private const val ACTIVE_SLOT_MAX_OPERATION_ID_BYTES = 128
private val ACTIVE_SLOT_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val ACTIVE_SLOT_OPERATION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
