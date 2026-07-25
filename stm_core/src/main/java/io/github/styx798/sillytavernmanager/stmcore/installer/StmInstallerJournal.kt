package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreError
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.requireValidArtifact
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
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

internal enum class StmInstallerOperationType {
    DOWNLOAD,
    VERIFY,
    INSTALL,
    ACTIVATE,
    ROLLBACK,
    REMOVE,
    MIGRATE,
}

internal enum class StmInstallerJournalPhase {
    QUEUED,
    RUNNING,
    COPYING_ARTIFACT,
    PREFLIGHT,
    EXTRACTING,
    VERIFYING,
    DOWNLOADING_RUNTIME_LAYER,
    VERIFYING_RUNTIME_LAYER,
    PREPARING_TOOLCHAIN,
    INSTALLING_DEPENDENCIES,
    BUILDING_BUNDLE,
    ASSEMBLING_RUNTIME,
    RUNNABLE_ACCEPTANCE,
    WRITING_MANIFEST,
    COMMITTING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

internal data class StmInstallerJournalRecord(
    val operationId: String,
    val type: StmInstallerOperationType,
    val targetSlotId: String,
    val artifactSha256: String,
    val phase: StmInstallerJournalPhase,
    val stagingRelativeId: String,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val cancelRequested: Boolean,
    /** Complete terminal job semantics, atomically committed with the terminal journal phase. */
    val terminalReceipt: StmInstallerTerminalReceipt? = null,
)

internal data class StmInstallerTerminalReceipt(
    val jobPhase: StmCoreJobPhase,
    val jobState: StmCoreJobState,
    val error: StmCoreError? = null,
    val artifact: StmCoreArtifact? = null,
    /** Monotonic active-pointer revision for ACTIVATE/ROLLBACK success. */
    val activeRevision: Long? = null,
)

internal data class StmStoredInstallerJournal(
    val record: StmInstallerJournalRecord,
    val checksumSha256: String,
    val formatVersion: Int,
)

internal enum class StmInstallerEvidenceCode {
    ROOT_UNAVAILABLE,
    INVALID_FILE_NAME,
    TEMPORARY_RECORD,
    SYMBOLIC_LINK,
    NOT_REGULAR_FILE,
    OVERSIZED_RECORD,
    TRUNCATED_OR_TRAILING_RECORD,
    CHECKSUM_MISMATCH,
    UNSUPPORTED_FORMAT,
    INVALID_CONTENT,
    IO_FAILURE,
    STAGING_ENTRY_REJECTED,
    DUPLICATE_STAGING_CLAIM,
}

internal data class StmInstallerRecoveryEvidence(
    val relativeName: String,
    val code: StmInstallerEvidenceCode,
    val detail: String,
)

internal sealed interface StmInstallerJournalReadResult {
    data object Missing : StmInstallerJournalReadResult

    data class Loaded(val stored: StmStoredInstallerJournal) : StmInstallerJournalReadResult

    data class Corrupt(val evidence: StmInstallerRecoveryEvidence) :
        StmInstallerJournalReadResult
}

internal data class StmInstallerJournalScanResult(
    val journals: List<StmStoredInstallerJournal>,
    val corruptEvidence: List<StmInstallerRecoveryEvidence>,
)

internal enum class StmInstallerJournalFailpoint {
    BEFORE_WRITE,
    AFTER_TEMP_SYNC,
    BEFORE_ATOMIC_MOVE,
    AFTER_ATOMIC_MOVE,
}

internal fun interface StmInstallerJournalFaultInjector {
    fun hit(failpoint: StmInstallerJournalFailpoint)
}

/**
 * Bounded, checksummed operation journal storage. Callers must serialize writes at the STM Core
 * coordinator. All temporary records are created beside their target, synced, and atomically moved.
 */
internal class StmInstallerJournalStore(
    journalDirectory: File,
    private val faultInjector: StmInstallerJournalFaultInjector =
        StmInstallerJournalFaultInjector { },
) {
    internal val root: Path = journalDirectory.toPath().toAbsolutePath().normalize()

    @Synchronized
    fun write(record: StmInstallerJournalRecord): StmStoredInstallerJournal {
        record.requireValidJournalRecord(requireVerifyReceipt = true)
        ensureWritableRoot()
        val current = when (val existing = read(record.operationId)) {
            StmInstallerJournalReadResult.Missing -> null
            is StmInstallerJournalReadResult.Loaded -> existing.stored
            is StmInstallerJournalReadResult.Corrupt -> throw IOException(
                "Refusing to replace corrupt journal evidence: ${existing.evidence.detail}",
            )
        }
        current?.record?.validateJournalTransition(record)
        if (current?.record == record) return current

        val encoded = encodeJournal(record)
        val stored = decodeJournal(encoded)
        faultInjector.hit(StmInstallerJournalFailpoint.BEFORE_WRITE)

        val target = journalPath(record.operationId)
        val temporary = root.resolve(".${target.fileName}.tmp-${UUID.randomUUID()}")
        writeAndSyncJournal(temporary, encoded)
        faultInjector.hit(StmInstallerJournalFailpoint.AFTER_TEMP_SYNC)
        faultInjector.hit(StmInstallerJournalFailpoint.BEFORE_ATOMIC_MOVE)
        atomicReplaceJournal(temporary, target)
        faultInjector.hit(StmInstallerJournalFailpoint.AFTER_ATOMIC_MOVE)
        bestEffortSyncJournalDirectory(root)
        return stored
    }

    @Synchronized
    fun read(operationId: String): StmInstallerJournalReadResult {
        val canonicalId = operationId.canonicalUuidOrNull()
            ?: return StmInstallerJournalReadResult.Corrupt(
                evidence(
                    relativeName = operationId.take(MAX_EVIDENCE_NAME_CHARS),
                    code = StmInstallerEvidenceCode.INVALID_FILE_NAME,
                    detail = "Journal operation ID is not a canonical UUID",
                ),
            )
        inspectRootForRead()?.let { return StmInstallerJournalReadResult.Corrupt(it) }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return StmInstallerJournalReadResult.Missing
        }
        return readJournalPath(journalPath(canonicalId))
    }

    @Synchronized
    fun scan(): StmInstallerJournalScanResult {
        inspectRootForRead()?.let { rootEvidence ->
            return StmInstallerJournalScanResult(emptyList(), listOf(rootEvidence))
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return StmInstallerJournalScanResult(emptyList(), emptyList())
        }

        val journals = mutableListOf<StmStoredInstallerJournal>()
        val corrupt = mutableListOf<StmInstallerRecoveryEvidence>()
        try {
            Files.newDirectoryStream(root).use { entries ->
                entries.toList().sortedBy { it.fileName.toString() }.forEach { path ->
                    val name = path.fileName.toString()
                    if (Files.isSymbolicLink(path)) {
                        corrupt += evidence(
                            name,
                            StmInstallerEvidenceCode.SYMBOLIC_LINK,
                            "Journal directory entries cannot be symbolic links",
                        )
                        return@forEach
                    }
                    if (TEMPORARY_JOURNAL_PATTERN.matches(name)) {
                        corrupt += evidence(
                            name,
                            StmInstallerEvidenceCode.TEMPORARY_RECORD,
                            "Interrupted temporary journal requires explicit recovery",
                        )
                        return@forEach
                    }
                    val operationId = JOURNAL_FILE_PATTERN.matchEntire(name)
                        ?.groupValues
                        ?.get(1)
                        ?.canonicalUuidOrNull()
                    if (operationId == null) {
                        corrupt += evidence(
                            name,
                            StmInstallerEvidenceCode.INVALID_FILE_NAME,
                            "Unexpected journal directory entry",
                        )
                        return@forEach
                    }
                    when (val result = readJournalPath(path)) {
                        StmInstallerJournalReadResult.Missing -> corrupt += evidence(
                            name,
                            StmInstallerEvidenceCode.IO_FAILURE,
                            "Journal disappeared during scan",
                        )

                        is StmInstallerJournalReadResult.Loaded -> {
                            if (result.stored.record.operationId != operationId) {
                                corrupt += evidence(
                                    name,
                                    StmInstallerEvidenceCode.INVALID_CONTENT,
                                    "Journal file name and payload operation ID differ",
                                )
                            } else {
                                journals += result.stored
                            }
                        }

                        is StmInstallerJournalReadResult.Corrupt -> corrupt += result.evidence
                    }
                }
            }
        } catch (error: Exception) {
            corrupt += evidence(
                JOURNAL_ROOT_EVIDENCE_NAME,
                StmInstallerEvidenceCode.IO_FAILURE,
                error.safeJournalDetail(),
            )
        }
        return StmInstallerJournalScanResult(
            journals = journals.sortedBy { it.record.operationId },
            corruptEvidence = corrupt.sortedBy { it.relativeName },
        )
    }

    internal fun journalFile(operationId: String): File =
        journalPath(requireNotNull(operationId.canonicalUuidOrNull())).toFile()

    private fun readJournalPath(path: Path): StmInstallerJournalReadResult {
        val name = path.fileName.toString().take(MAX_EVIDENCE_NAME_CHARS)
        if (!path.normalize().startsWith(root) || path.normalize().parent != root) {
            return StmInstallerJournalReadResult.Corrupt(
                evidence(
                    name,
                    StmInstallerEvidenceCode.INVALID_FILE_NAME,
                    "Journal path escaped its configured directory",
                ),
            )
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return StmInstallerJournalReadResult.Missing
        }
        if (Files.isSymbolicLink(path)) {
            return StmInstallerJournalReadResult.Corrupt(
                evidence(name, StmInstallerEvidenceCode.SYMBOLIC_LINK, "Journal is a symbolic link"),
            )
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return StmInstallerJournalReadResult.Corrupt(
                evidence(
                    name,
                    StmInstallerEvidenceCode.NOT_REGULAR_FILE,
                    "Journal is not a regular file",
                ),
            )
        }

        return try {
            val bytes = readBoundedJournal(path)
            StmInstallerJournalReadResult.Loaded(decodeJournal(bytes))
        } catch (error: StmInstallerJournalFormatException) {
            StmInstallerJournalReadResult.Corrupt(evidence(name, error.code, error.message.orEmpty()))
        } catch (error: Exception) {
            StmInstallerJournalReadResult.Corrupt(
                evidence(name, StmInstallerEvidenceCode.IO_FAILURE, error.safeJournalDetail()),
            )
        }
    }

    private fun inspectRootForRead(): StmInstallerRecoveryEvidence? {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(root)) {
            return evidence(
                JOURNAL_ROOT_EVIDENCE_NAME,
                StmInstallerEvidenceCode.SYMBOLIC_LINK,
                "Journal root cannot be a symbolic link",
            )
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return evidence(
                JOURNAL_ROOT_EVIDENCE_NAME,
                StmInstallerEvidenceCode.ROOT_UNAVAILABLE,
                "Journal root is not a directory",
            )
        }
        return null
    }

    private fun ensureWritableRoot() {
        Files.createDirectories(root)
        inspectRootForRead()?.let { problem -> throw IOException(problem.detail) }
    }

    private fun journalPath(operationId: String): Path {
        val path = root.resolve("$operationId$JOURNAL_SUFFIX").normalize()
        require(path.parent == root && path.startsWith(root)) { "Journal path escaped its root" }
        return path
    }
}

internal enum class StmInstallerCompleteDisposition {
    RETAIN,
    CLEANUP_JOURNAL,
}

internal enum class StmInstallerRecoveryActionKind {
    FAIL_INTERRUPTED,
    CLEANUP_STAGING,
    RETAIN_COMPLETE_JOURNAL,
    CLEANUP_COMPLETE_JOURNAL,
    QUARANTINE_ORPHAN_STAGING,
}

internal data class StmInstallerRecoveryAction(
    val kind: StmInstallerRecoveryActionKind,
    val operationId: String?,
    val stagingRelativeId: String? = null,
    val stagingPath: File? = null,
)

internal data class StmInstallerRecoveryPlan(
    val stagingRoot: File,
    val actions: List<StmInstallerRecoveryAction>,
    val corruptEvidence: List<StmInstallerRecoveryEvidence>,
) {
    init {
        val normalizedRoot = stagingRoot.toPath().toAbsolutePath().normalize()
        actions.mapNotNull(StmInstallerRecoveryAction::stagingPath).forEach { file ->
            val path = file.toPath().toAbsolutePath().normalize()
            require(path.startsWith(normalizedRoot) && path.parent == normalizedRoot) {
                "Recovery action escaped the staging root"
            }
        }
    }
}

/**
 * Produces evidence and controlled actions only. It never deletes staging, READY slots, or data.
 */
internal class StmInstallerRecoveryPlanner(
    stagingRoot: File,
    private val completeDisposition: StmInstallerCompleteDisposition =
        StmInstallerCompleteDisposition.RETAIN,
) {
    private val root: Path = stagingRoot.toPath().toAbsolutePath().normalize()

    fun plan(scan: StmInstallerJournalScanResult): StmInstallerRecoveryPlan {
        val actions = mutableListOf<StmInstallerRecoveryAction>()
        val evidence = scan.corruptEvidence.toMutableList()
        val claimedStaging = linkedMapOf<String, String>()
        val stagingRootSafe = inspectStagingRoot(evidence)

        scan.journals.sortedBy { it.record.operationId }.forEach { stored ->
            val record = stored.record
            val formerClaim = claimedStaging.putIfAbsent(
                record.stagingRelativeId,
                record.operationId,
            )
            if (formerClaim != null && formerClaim != record.operationId) {
                evidence += recoveryEvidence(
                    record.stagingRelativeId,
                    StmInstallerEvidenceCode.DUPLICATE_STAGING_CLAIM,
                    "Multiple journals claim the same staging directory",
                )
                actions += StmInstallerRecoveryAction(
                    kind = StmInstallerRecoveryActionKind.FAIL_INTERRUPTED,
                    operationId = record.operationId,
                )
                return@forEach
            }

            when (record.phase) {
                StmInstallerJournalPhase.COMPLETE -> actions += StmInstallerRecoveryAction(
                    kind = if (completeDisposition == StmInstallerCompleteDisposition.RETAIN) {
                        StmInstallerRecoveryActionKind.RETAIN_COMPLETE_JOURNAL
                    } else {
                        StmInstallerRecoveryActionKind.CLEANUP_COMPLETE_JOURNAL
                    },
                    operationId = record.operationId,
                )

                StmInstallerJournalPhase.FAILED,
                StmInstallerJournalPhase.CANCELLED,
                -> addStagingActionIfSafe(
                    actions = actions,
                    evidence = evidence,
                    record = record,
                    kind = StmInstallerRecoveryActionKind.CLEANUP_STAGING,
                    stagingRootSafe = stagingRootSafe,
                )

                else -> {
                    actions += StmInstallerRecoveryAction(
                        kind = StmInstallerRecoveryActionKind.FAIL_INTERRUPTED,
                        operationId = record.operationId,
                    )
                    addStagingActionIfSafe(
                        actions = actions,
                        evidence = evidence,
                        record = record,
                        kind = StmInstallerRecoveryActionKind.CLEANUP_STAGING,
                        stagingRootSafe = stagingRootSafe,
                    )
                }
            }
        }

        if (stagingRootSafe && Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            scanOrphanStaging(claimedStaging.keys, actions, evidence)
        }

        return StmInstallerRecoveryPlan(
            stagingRoot = root.toFile(),
            actions = actions.distinct().sortedWith(
                compareBy<StmInstallerRecoveryAction> { it.operationId.orEmpty() }
                    .thenBy { it.kind.name },
            ),
            corruptEvidence = evidence.distinct().sortedWith(
                compareBy<StmInstallerRecoveryEvidence> { it.relativeName }
                    .thenBy { it.code.name },
            ),
        )
    }

    private fun addStagingActionIfSafe(
        actions: MutableList<StmInstallerRecoveryAction>,
        evidence: MutableList<StmInstallerRecoveryEvidence>,
        record: StmInstallerJournalRecord,
        kind: StmInstallerRecoveryActionKind,
        stagingRootSafe: Boolean,
    ) {
        if (!stagingRootSafe) return
        val path = safeStagingChild(record.stagingRelativeId)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            evidence += recoveryEvidence(
                record.stagingRelativeId,
                StmInstallerEvidenceCode.STAGING_ENTRY_REJECTED,
                "Claimed staging entry is not a real directory",
            )
            return
        }
        actions += StmInstallerRecoveryAction(
            kind = kind,
            operationId = record.operationId,
            stagingRelativeId = record.stagingRelativeId,
            stagingPath = path.toFile(),
        )
    }

    private fun scanOrphanStaging(
        claimedStaging: Set<String>,
        actions: MutableList<StmInstallerRecoveryAction>,
        evidence: MutableList<StmInstallerRecoveryEvidence>,
    ) {
        try {
            Files.newDirectoryStream(root).use { entries ->
                entries.toList().sortedBy { it.fileName.toString() }.forEach { path ->
                    val name = path.fileName.toString()
                    if (Files.isSymbolicLink(path) ||
                        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        evidence += recoveryEvidence(
                            name,
                            StmInstallerEvidenceCode.STAGING_ENTRY_REJECTED,
                            "Staging entries must be real directories",
                        )
                        return@forEach
                    }
                    val canonicalId = name.canonicalUuidOrNull()
                    if (canonicalId == null) {
                        evidence += recoveryEvidence(
                            name,
                            StmInstallerEvidenceCode.STAGING_ENTRY_REJECTED,
                            "Unrecognized staging directory is not a canonical UUID",
                        )
                        return@forEach
                    }
                    if (canonicalId in claimedStaging) return@forEach
                    val safePath = safeStagingChild(canonicalId)
                    actions += StmInstallerRecoveryAction(
                        kind = StmInstallerRecoveryActionKind.QUARANTINE_ORPHAN_STAGING,
                        operationId = null,
                        stagingRelativeId = canonicalId,
                        stagingPath = safePath.toFile(),
                    )
                }
            }
        } catch (error: Exception) {
            evidence += recoveryEvidence(
                STAGING_ROOT_EVIDENCE_NAME,
                StmInstallerEvidenceCode.IO_FAILURE,
                error.safeJournalDetail(),
            )
        }
    }

    private fun inspectStagingRoot(
        evidence: MutableList<StmInstallerRecoveryEvidence>,
    ): Boolean {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            evidence += recoveryEvidence(
                STAGING_ROOT_EVIDENCE_NAME,
                StmInstallerEvidenceCode.STAGING_ENTRY_REJECTED,
                "Staging root must be a real directory",
            )
            return false
        }
        return true
    }

    private fun safeStagingChild(relativeId: String): Path {
        require(relativeId.canonicalUuidOrNull() == relativeId) {
            "Staging relative ID must be a canonical UUID"
        }
        val child = root.resolve(relativeId).normalize()
        require(child.startsWith(root) && child.parent == root) {
            "Staging action escaped its configured root"
        }
        return child
    }
}

private fun StmInstallerJournalRecord.requireValidJournalRecord(
    requireVerifyReceipt: Boolean,
) {
    require(operationId.canonicalUuidOrNull() == operationId) {
        "Operation ID must be a canonical UUID"
    }
    require(stagingRelativeId.canonicalUuidOrNull() == stagingRelativeId) {
        "Staging relative ID must be a canonical UUID"
    }
    require(TARGET_SLOT_PATTERN.matches(targetSlotId) && targetSlotId != "." && targetSlotId != "..") {
        "Target slot ID is invalid"
    }
    require(targetSlotId.toByteArray(StandardCharsets.UTF_8).size <= MAX_TARGET_SLOT_BYTES) {
        "Target slot ID is too long"
    }
    require(ARTIFACT_SHA256_PATTERN.matches(artifactSha256)) {
        "Artifact SHA-256 must be 64 lowercase hexadecimal characters"
    }
    require(startedAtEpochMs > 0) { "Journal start time must be positive" }
    require(updatedAtEpochMs >= startedAtEpochMs) {
        "Journal update time cannot precede its start time"
    }
    when {
        phase in TERMINAL_JOURNAL_PHASES -> {
            if (requireVerifyReceipt) {
                requireNotNull(terminalReceipt) {
                    "A terminal journal requires complete terminal job semantics"
                }
            }
            terminalReceipt?.requireValidTerminalReceipt(this)
        }

        else -> require(terminalReceipt == null) { "A nonterminal journal cannot have a receipt" }
    }
}

private fun StmInstallerJournalRecord.validateJournalTransition(
    next: StmInstallerJournalRecord,
) {
    require(operationId == next.operationId)
    require(type == next.type) { "Journal operation type cannot change" }
    require(targetSlotId == next.targetSlotId) { "Journal target slot cannot change" }
    require(artifactSha256 == next.artifactSha256) { "Journal artifact identity cannot change" }
    require(stagingRelativeId == next.stagingRelativeId) { "Journal staging identity cannot change" }
    require(startedAtEpochMs == next.startedAtEpochMs) { "Journal start time cannot change" }
    require(next.updatedAtEpochMs >= updatedAtEpochMs) { "Journal update time cannot move backwards" }
    require(!cancelRequested || next.cancelRequested) { "A cancellation request cannot be cleared" }
    require(terminalReceipt == null || next.terminalReceipt == terminalReceipt) {
        "A terminal receipt cannot change"
    }
    if (phase in TERMINAL_JOURNAL_PHASES) {
        require(next == this) { "A terminal journal cannot transition" }
    }
}

private fun encodeJournal(record: StmInstallerJournalRecord): ByteArray {
    record.requireValidJournalRecord(requireVerifyReceipt = true)
    val payload = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeUuid(record.operationId)
            output.writeBoundedJournalString(record.type.name, MAX_ENUM_NAME_BYTES)
            output.writeBoundedJournalString(record.targetSlotId, MAX_TARGET_SLOT_BYTES)
            output.write(record.artifactSha256.hexToJournalBytes())
            output.writeBoundedJournalString(record.phase.name, MAX_ENUM_NAME_BYTES)
            output.writeUuid(record.stagingRelativeId)
            output.writeLong(record.startedAtEpochMs)
            output.writeLong(record.updatedAtEpochMs)
            output.writeByte(if (record.cancelRequested) 1 else 0)
            output.writeTerminalReceipt(record.terminalReceipt)
            output.flush()
        }
        buffer.toByteArray()
    }
    require(payload.size in 1..MAX_JOURNAL_PAYLOAD_BYTES) { "Journal payload is too large" }
    val checksum = sha256Journal(payload)
    return ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(JOURNAL_MAGIC)
            output.writeInt(JOURNAL_FORMAT_VERSION)
            output.writeInt(payload.size)
            output.write(payload)
            output.write(checksum)
            output.flush()
        }
        buffer.toByteArray()
    }
}

private fun decodeJournal(bytes: ByteArray): StmStoredInstallerJournal {
    if (bytes.size < MIN_JOURNAL_FILE_BYTES) {
        throw journalFormat(
            StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD,
            "Journal file is truncated",
        )
    }
    if (bytes.size > MAX_JOURNAL_FILE_BYTES) {
        throw journalFormat(
            StmInstallerEvidenceCode.OVERSIZED_RECORD,
            "Journal file exceeds the allowed range",
        )
    }
    val input = DataInputStream(ByteArrayInputStream(bytes))
    if (input.readInt() != JOURNAL_MAGIC) {
        throw journalFormat(StmInstallerEvidenceCode.UNSUPPORTED_FORMAT, "Journal magic did not match")
    }
    val formatVersion = input.readInt()
    if (formatVersion !in SUPPORTED_JOURNAL_FORMAT_VERSIONS) {
        throw journalFormat(StmInstallerEvidenceCode.UNSUPPORTED_FORMAT, "Journal version is unsupported")
    }
    val payloadLength = input.readInt()
    if (payloadLength !in 1..MAX_JOURNAL_PAYLOAD_BYTES ||
        bytes.size != JOURNAL_HEADER_BYTES + payloadLength + JOURNAL_CHECKSUM_BYTES
    ) {
        throw journalFormat(
            StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD,
            "Journal payload is truncated, trailing, or unbounded",
        )
    }
    val payload = ByteArray(payloadLength)
    input.readFully(payload)
    val expectedChecksum = ByteArray(JOURNAL_CHECKSUM_BYTES)
    input.readFully(expectedChecksum)
    if (input.read() != -1) {
        throw journalFormat(
            StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD,
            "Journal contains trailing bytes",
        )
    }
    val actualChecksum = sha256Journal(payload)
    if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
        throw journalFormat(StmInstallerEvidenceCode.CHECKSUM_MISMATCH, "Journal checksum did not match")
    }

    return try {
        val payloadInput = DataInputStream(ByteArrayInputStream(payload))
        val record = StmInstallerJournalRecord(
            operationId = payloadInput.readUuid(),
            type = StmInstallerOperationType.valueOf(
                payloadInput.readBoundedJournalString(MAX_ENUM_NAME_BYTES),
            ),
            targetSlotId = payloadInput.readBoundedJournalString(MAX_TARGET_SLOT_BYTES),
            artifactSha256 = ByteArray(JOURNAL_CHECKSUM_BYTES).also(payloadInput::readFully)
                .toJournalHex(),
            phase = StmInstallerJournalPhase.valueOf(
                payloadInput.readBoundedJournalString(MAX_ENUM_NAME_BYTES),
            ),
            stagingRelativeId = payloadInput.readUuid(),
            startedAtEpochMs = payloadInput.readLong(),
            updatedAtEpochMs = payloadInput.readLong(),
            cancelRequested = payloadInput.readStrictJournalBoolean(),
            terminalReceipt = if (formatVersion >= 2) {
                payloadInput.readTerminalReceipt()
            } else {
                null
            },
        ).also {
            it.requireValidJournalRecord(requireVerifyReceipt = formatVersion >= 2)
        }
        if (payloadInput.read() != -1) {
            throw journalFormat(
                StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD,
                "Journal payload contains trailing bytes",
            )
        }
        StmStoredInstallerJournal(record, actualChecksum.toJournalHex(), formatVersion)
    } catch (error: StmInstallerJournalFormatException) {
        throw error
    } catch (error: Exception) {
        throw journalFormat(StmInstallerEvidenceCode.INVALID_CONTENT, error.safeJournalDetail())
    }
}

private fun readBoundedJournal(path: Path): ByteArray {
    val declaredSize = Files.size(path)
    if (declaredSize < MIN_JOURNAL_FILE_BYTES) {
        throw journalFormat(
            StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD,
            "Journal length $declaredSize is truncated",
        )
    }
    if (declaredSize > MAX_JOURNAL_FILE_BYTES) {
        throw journalFormat(
            StmInstallerEvidenceCode.OVERSIZED_RECORD,
            "Journal length $declaredSize exceeds the allowed range",
        )
    }
    val output = ByteArrayOutputStream(declaredSize.toInt())
    Files.newByteChannel(
        path,
        setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ).use { channel ->
        val buffer = ByteBuffer.allocate(1024)
        while (true) {
            val count = channel.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            if (output.size() + count > MAX_JOURNAL_FILE_BYTES) {
                throw journalFormat(
                    StmInstallerEvidenceCode.OVERSIZED_RECORD,
                    "Journal grew beyond the allowed range while reading",
                )
            }
            output.write(buffer.array(), 0, count)
            buffer.clear()
        }
    }
    val bytes = output.toByteArray()
    if (bytes.size.toLong() != declaredSize) {
        throw journalFormat(
            StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD,
            "Journal length changed while reading",
        )
    }
    return bytes
}

private fun writeAndSyncJournal(path: Path, bytes: ByteArray) {
    FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    ).use { output ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (output.write(buffer) == 0) Thread.yield()
        }
        output.force(true)
    }
}

private fun atomicReplaceJournal(temporary: Path, target: Path) {
    try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (error: AtomicMoveNotSupportedException) {
        throw IOException("Atomic journal replacement is unavailable", error)
    }
}

private fun bestEffortSyncJournalDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // The journal file remains synced and atomically replaced; directory fsync is best effort.
    } catch (_: UnsupportedOperationException) {
        // Some file systems do not expose directories through FileChannel.
    } catch (_: SecurityException) {
        // The caller still receives a synced file with an atomic move.
    }
}

private fun DataOutputStream.writeUuid(value: String) {
    val uuid = UUID.fromString(value)
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

private fun DataInputStream.readUuid(): String = UUID(readLong(), readLong()).toString()

private fun DataOutputStream.writeBoundedJournalString(value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { "String length is outside bounds" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBoundedJournalString(maximumBytes: Int): String {
    val length = readInt()
    require(length in 1..maximumBytes && available() >= length) { "String is truncated or unbounded" }
    val bytes = ByteArray(length)
    readFully(bytes)
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

private fun DataInputStream.readStrictJournalBoolean(): Boolean = when (val value = readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException("Boolean value $value is invalid")
}

private fun DataOutputStream.writeTerminalReceipt(receipt: StmInstallerTerminalReceipt?) {
    writeByte(if (receipt == null) 0 else 1)
    if (receipt == null) return
    writeBoundedJournalString(receipt.jobPhase.name, MAX_ENUM_NAME_BYTES)
    writeBoundedJournalString(receipt.jobState.name, MAX_ENUM_NAME_BYTES)
    writeTerminalError(receipt.error)
    writeTerminalArtifact(receipt.artifact)
    writeByte(if (receipt.activeRevision == null) 0 else 1)
    receipt.activeRevision?.let { writeLong(it) }
}

private fun DataInputStream.readTerminalReceipt(): StmInstallerTerminalReceipt? {
    if (!readStrictJournalBoolean()) return null
    return StmInstallerTerminalReceipt(
        jobPhase = StmCoreJobPhase.valueOf(readBoundedJournalString(MAX_ENUM_NAME_BYTES)),
        jobState = StmCoreJobState.valueOf(readBoundedJournalString(MAX_ENUM_NAME_BYTES)),
        error = readTerminalError(),
        artifact = readTerminalArtifact(),
        activeRevision = if (readStrictJournalBoolean()) readLong() else null,
    )
}

private fun DataOutputStream.writeTerminalError(error: StmCoreError?) {
    writeByte(if (error == null) 0 else 1)
    if (error == null) return
    writeBoundedJournalString(error.domain, MAX_ERROR_DOMAIN_BYTES)
    writeBoundedJournalString(error.code, MAX_ERROR_CODE_BYTES)
    writeBoundedJournalString(error.summary, MAX_ERROR_SUMMARY_BYTES)
    writeOptionalJournalString(error.diagnosticDetail, MAX_ERROR_DETAIL_BYTES)
}

private fun DataInputStream.readTerminalError(): StmCoreError? {
    if (!readStrictJournalBoolean()) return null
    return StmCoreError(
        domain = readBoundedJournalString(MAX_ERROR_DOMAIN_BYTES),
        code = readBoundedJournalString(MAX_ERROR_CODE_BYTES),
        summary = readBoundedJournalString(MAX_ERROR_SUMMARY_BYTES),
        diagnosticDetail = readOptionalJournalString(MAX_ERROR_DETAIL_BYTES),
    )
}

private fun DataOutputStream.writeTerminalArtifact(artifact: StmCoreArtifact?) {
    writeByte(if (artifact == null) 0 else 1)
    if (artifact == null) return
    writeBoundedJournalString(artifact.kind.name, MAX_ENUM_NAME_BYTES)
    writeBoundedJournalString(artifact.repository, MAX_ARTIFACT_REPOSITORY_BYTES)
    writeBoundedJournalString(artifact.channel, MAX_ARTIFACT_CHANNEL_BYTES)
    writeBoundedJournalString(artifact.commitSha, MAX_ARTIFACT_COMMIT_BYTES)
    writeBoundedJournalString(artifact.downloadUrl, MAX_ARTIFACT_URL_BYTES)
    writeLong(artifact.downloadedAtEpochMs)
    writeLong(artifact.archiveLength)
    write(artifact.archiveSha256.hexToJournalBytes())
    writeBoundedJournalString(artifact.integrity.name, MAX_ENUM_NAME_BYTES)
    writeBoundedJournalString(artifact.trust.name, MAX_ENUM_NAME_BYTES)
    writeOptionalJournalString(artifact.catalogVersion, MAX_ARTIFACT_CATALOG_BYTES)
    writeOptionalJournalString(artifact.archiveRoot, MAX_ARTIFACT_ARCHIVE_ROOT_BYTES)
    writeOptionalJournalString(artifact.stVersion, MAX_ARTIFACT_ST_VERSION_BYTES)
    writeOptionalJournalString(artifact.nodeRequirement, MAX_ARTIFACT_NODE_REQUIREMENT_BYTES)
    writeOptionalJournalString(artifact.packageLockSha256, MAX_ARTIFACT_SHA_BYTES)
    writeOptionalJournalString(artifact.licenseStatus, MAX_ARTIFACT_LICENSE_STATUS_BYTES)
}

private fun DataInputStream.readTerminalArtifact(): StmCoreArtifact? {
    if (!readStrictJournalBoolean()) return null
    return StmCoreArtifact(
        kind = StmCoreArtifactKind.valueOf(readBoundedJournalString(MAX_ENUM_NAME_BYTES)),
        repository = readBoundedJournalString(MAX_ARTIFACT_REPOSITORY_BYTES),
        channel = readBoundedJournalString(MAX_ARTIFACT_CHANNEL_BYTES),
        commitSha = readBoundedJournalString(MAX_ARTIFACT_COMMIT_BYTES),
        downloadUrl = readBoundedJournalString(MAX_ARTIFACT_URL_BYTES),
        downloadedAtEpochMs = readLong(),
        archiveLength = readLong(),
        archiveSha256 = ByteArray(JOURNAL_CHECKSUM_BYTES).also { readFully(it) }.toJournalHex(),
        integrity = StmCoreArtifactIntegrity.valueOf(
            readBoundedJournalString(MAX_ENUM_NAME_BYTES),
        ),
        trust = StmCoreArtifactTrust.valueOf(readBoundedJournalString(MAX_ENUM_NAME_BYTES)),
        catalogVersion = readOptionalJournalString(MAX_ARTIFACT_CATALOG_BYTES),
        archiveRoot = readOptionalJournalString(MAX_ARTIFACT_ARCHIVE_ROOT_BYTES),
        stVersion = readOptionalJournalString(MAX_ARTIFACT_ST_VERSION_BYTES),
        nodeRequirement = readOptionalJournalString(MAX_ARTIFACT_NODE_REQUIREMENT_BYTES),
        packageLockSha256 = readOptionalJournalString(MAX_ARTIFACT_SHA_BYTES),
        licenseStatus = readOptionalJournalString(MAX_ARTIFACT_LICENSE_STATUS_BYTES),
    )
}

private fun DataOutputStream.writeOptionalJournalString(value: String?, maximumBytes: Int) {
    writeByte(if (value == null) 0 else 1)
    if (value != null) writeBoundedJournalString(value, maximumBytes)
}

private fun DataInputStream.readOptionalJournalString(maximumBytes: Int): String? =
    if (readStrictJournalBoolean()) readBoundedJournalString(maximumBytes) else null

private fun StmInstallerTerminalReceipt.requireValidTerminalReceipt(
    record: StmInstallerJournalRecord,
) {
    when (record.phase) {
        StmInstallerJournalPhase.COMPLETE -> {
            require(jobPhase == StmCoreJobPhase.COMPLETE) {
                "A COMPLETE journal receipt must have the COMPLETE job phase"
            }
            require(jobState == StmCoreJobState.SUCCEEDED && error == null) {
                "A COMPLETE journal receipt must represent success without an error"
            }
            if (record.type == StmInstallerOperationType.VERIFY) {
                requireNotNull(artifact) {
                    "A completed VERIFY journal requires its Core-derived artifact receipt"
                }.requireValidTerminalArtifact(record.artifactSha256)
            } else {
                require(artifact == null) {
                    "Only a successful VERIFY receipt may retain artifact evidence"
                }
            }
            if (record.type in ACTIVE_POINTER_OPERATION_TYPES) {
                require(activeRevision != null && activeRevision > 0) {
                    "An active-pointer terminal receipt requires its committed revision"
                }
            } else {
                require(activeRevision == null) {
                    "Only active-pointer operations may retain an active revision"
                }
            }
        }

        StmInstallerJournalPhase.FAILED -> {
            require(jobPhase in setOf(StmCoreJobPhase.COMPLETE, StmCoreJobPhase.CLEANING_UP)) {
                "A FAILED journal receipt has an invalid terminal job phase"
            }
            require(jobState == StmCoreJobState.FAILED && error != null && artifact == null) {
                "A FAILED journal receipt requires an error and cannot retain an artifact"
            }
            require(activeRevision == null) { "A failed receipt cannot retain an active revision" }
            error.requireValidTerminalError()
        }

        StmInstallerJournalPhase.CANCELLED -> {
            require(jobPhase == StmCoreJobPhase.CLEANING_UP) {
                "A CANCELLED journal receipt must use the CLEANING_UP job phase"
            }
            require(jobState == StmCoreJobState.CANCELLED && error == null && artifact == null) {
                "A CANCELLED journal receipt cannot contain error or artifact evidence"
            }
            require(activeRevision == null) { "A cancelled receipt cannot retain an active revision" }
        }

        else -> error("A nonterminal journal cannot validate a terminal receipt")
    }
}

private fun StmCoreArtifact.requireValidTerminalArtifact(expectedSha256: String) {
    requireValidArtifact()
    require(integrity == StmCoreArtifactIntegrity.VERIFIED) {
        "A VERIFY terminal receipt requires verified integrity"
    }
    require(trust != StmCoreArtifactTrust.REJECTED) {
        "A VERIFY terminal receipt cannot contain rejected trust"
    }
    require(archiveSha256 == expectedSha256) {
        "The VERIFY receipt hash must match the immutable journal artifact identity"
    }
    val identity = ArtifactIdentity(
        repository = repository,
        commitSha = commitSha,
        archiveSha256 = archiveSha256,
        archiveLength = archiveLength,
        downloadUrl = downloadUrl,
        catalogVersion = catalogVersion,
        kind = when (kind) {
            StmCoreArtifactKind.GATE2_SYNTHETIC -> ArtifactKind.SYNTHETIC_TEST_ARCHIVE
            StmCoreArtifactKind.SILLY_TAVERN_SOURCE -> ArtifactKind.UPSTREAM_SOURCE_ARCHIVE
        },
    )
    when (val validation = StmArtifactVerifier().validateIdentity(identity)) {
        ArtifactIdentityValidation.Valid -> Unit
        is ArtifactIdentityValidation.Invalid -> throw IllegalArgumentException(validation.detail)
    }
    if (kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE) {
        require(
            archiveRoot != null && stVersion != null && nodeRequirement != null &&
                packageLockSha256 != null && licenseStatus != null
        ) { "A SillyTavern VERIFY receipt requires complete Core-derived source evidence" }
    }
    requireBoundedJournalArtifactText(repository, MAX_ARTIFACT_REPOSITORY_BYTES, "repository")
    requireBoundedJournalArtifactText(channel, MAX_ARTIFACT_CHANNEL_BYTES, "channel")
    requireBoundedJournalArtifactText(commitSha, MAX_ARTIFACT_COMMIT_BYTES, "commit SHA")
    requireBoundedJournalArtifactText(downloadUrl, MAX_ARTIFACT_URL_BYTES, "download URL")
    requireOptionalBoundedJournalArtifactText(
        catalogVersion,
        MAX_ARTIFACT_CATALOG_BYTES,
        "catalog version",
    )
    requireOptionalBoundedJournalArtifactText(
        archiveRoot,
        MAX_ARTIFACT_ARCHIVE_ROOT_BYTES,
        "archive root",
    )
    requireOptionalBoundedJournalArtifactText(
        stVersion,
        MAX_ARTIFACT_ST_VERSION_BYTES,
        "SillyTavern version",
    )
    requireOptionalBoundedJournalArtifactText(
        nodeRequirement,
        MAX_ARTIFACT_NODE_REQUIREMENT_BYTES,
        "Node requirement",
    )
    requireOptionalBoundedJournalArtifactText(
        packageLockSha256,
        MAX_ARTIFACT_SHA_BYTES,
        "package-lock SHA-256",
    )
    requireOptionalBoundedJournalArtifactText(
        licenseStatus,
        MAX_ARTIFACT_LICENSE_STATUS_BYTES,
        "license status",
    )
}

private fun StmCoreError.requireValidTerminalError() {
    requireBoundedJournalArtifactText(domain, MAX_ERROR_DOMAIN_BYTES, "error domain")
    requireBoundedJournalArtifactText(code, MAX_ERROR_CODE_BYTES, "error code")
    requireBoundedJournalArtifactText(summary, MAX_ERROR_SUMMARY_BYTES, "error summary")
    requireOptionalBoundedJournalArtifactText(
        diagnosticDetail,
        MAX_ERROR_DETAIL_BYTES,
        "error detail",
    )
}

private fun requireBoundedJournalArtifactText(value: String, maximumBytes: Int, label: String) {
    require(value.toByteArray(StandardCharsets.UTF_8).size in 1..maximumBytes) {
        "Artifact $label is outside its journal bound"
    }
}

private fun requireOptionalBoundedJournalArtifactText(
    value: String?,
    maximumBytes: Int,
    label: String,
) {
    if (value != null) requireBoundedJournalArtifactText(value, maximumBytes, label)
}

private fun String.canonicalUuidOrNull(): String? = runCatching { UUID.fromString(this).toString() }
    .getOrNull()
    ?.takeIf { it == this && it != NIL_UUID }

private fun String.hexToJournalBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toJournalHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun sha256Journal(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun evidence(
    relativeName: String,
    code: StmInstallerEvidenceCode,
    detail: String,
): StmInstallerRecoveryEvidence = StmInstallerRecoveryEvidence(
    relativeName = relativeName.take(MAX_EVIDENCE_NAME_CHARS),
    code = code,
    detail = detail.take(MAX_EVIDENCE_DETAIL_CHARS),
)

private fun recoveryEvidence(
    relativeName: String,
    code: StmInstallerEvidenceCode,
    detail: String,
): StmInstallerRecoveryEvidence = evidence(relativeName, code, detail)

private fun Throwable.safeJournalDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(MAX_EVIDENCE_DETAIL_CHARS)

private class StmInstallerJournalFormatException(
    val code: StmInstallerEvidenceCode,
    message: String,
) : IOException(message)

private fun journalFormat(
    code: StmInstallerEvidenceCode,
    detail: String,
): StmInstallerJournalFormatException = StmInstallerJournalFormatException(code, detail)

private const val JOURNAL_MAGIC = 0x53544D4A // STMJ
private const val JOURNAL_FORMAT_VERSION = 2
private val SUPPORTED_JOURNAL_FORMAT_VERSIONS = 1..JOURNAL_FORMAT_VERSION
private const val JOURNAL_HEADER_BYTES = Int.SIZE_BYTES * 3
private const val JOURNAL_CHECKSUM_BYTES = 32
private const val MIN_JOURNAL_FILE_BYTES = JOURNAL_HEADER_BYTES + 1 + JOURNAL_CHECKSUM_BYTES
private const val MAX_JOURNAL_FILE_BYTES = 16 * 1024
private const val MAX_JOURNAL_PAYLOAD_BYTES = 12 * 1024
private const val MAX_TARGET_SLOT_BYTES = 128
private const val MAX_ENUM_NAME_BYTES = 64
private const val MAX_ARTIFACT_REPOSITORY_BYTES = 200
private const val MAX_ARTIFACT_CHANNEL_BYTES = 80
private const val MAX_ARTIFACT_COMMIT_BYTES = 64
private const val MAX_ARTIFACT_URL_BYTES = 2 * 1024
private const val MAX_ARTIFACT_CATALOG_BYTES = 128
private const val MAX_ARTIFACT_ARCHIVE_ROOT_BYTES = 1024
private const val MAX_ARTIFACT_ST_VERSION_BYTES = 128
private const val MAX_ARTIFACT_NODE_REQUIREMENT_BYTES = 256
private const val MAX_ARTIFACT_SHA_BYTES = 64
private const val MAX_ARTIFACT_LICENSE_STATUS_BYTES = 256
private const val MAX_ERROR_DOMAIN_BYTES = 80
private const val MAX_ERROR_CODE_BYTES = 120
private const val MAX_ERROR_SUMMARY_BYTES = 500
private const val MAX_ERROR_DETAIL_BYTES = 1000
private const val MAX_EVIDENCE_NAME_CHARS = 256
private const val MAX_EVIDENCE_DETAIL_CHARS = 500
private const val JOURNAL_SUFFIX = ".journal"
private const val JOURNAL_ROOT_EVIDENCE_NAME = "<journal-root>"
private const val STAGING_ROOT_EVIDENCE_NAME = "<staging-root>"
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private val JOURNAL_FILE_PATTERN = Regex(
    "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.journal$",
)
private val TEMPORARY_JOURNAL_PATTERN = Regex(
    "^\\.[0-9a-f-]{36}\\.journal\\.tmp-[0-9a-f-]{36}$",
)
private val TARGET_SLOT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val ARTIFACT_SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val TERMINAL_JOURNAL_PHASES = setOf(
    StmInstallerJournalPhase.COMPLETE,
    StmInstallerJournalPhase.FAILED,
    StmInstallerJournalPhase.CANCELLED,
)
private val ACTIVE_POINTER_OPERATION_TYPES = setOf(
    StmInstallerOperationType.ACTIVATE,
    StmInstallerOperationType.ROLLBACK,
)
