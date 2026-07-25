package io.github.styx798.sillytavernmanager.stmcore

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

internal sealed interface CheckpointReadResult {
    data object Missing : CheckpointReadResult

    data class Loaded(val state: StmCoreState) : CheckpointReadResult

    data class Corrupt(
        val detail: String,
        val revisionHint: Long? = null,
    ) : CheckpointReadResult
}

internal class StmCoreCheckpointStore(context: Context) {
    private val checkpoint = AtomicFile(StmCorePaths.checkpointFile(context))

    fun read(): CheckpointReadResult {
        var bytes: ByteArray? = null
        return try {
            validateCheckpointPaths()
            bytes = checkpoint.openRead().use(::readBoundedCheckpoint)
            CheckpointReadResult.Loaded(StmCoreCheckpointCodec.decode(requireNotNull(bytes)))
        } catch (_: FileNotFoundException) {
            CheckpointReadResult.Missing
        } catch (error: Exception) {
            CheckpointReadResult.Corrupt(
                detail = error.safeMessage(),
                revisionHint = bytes?.let(StmCoreCheckpointCodec::revisionHint),
            )
        }
    }

    fun write(state: StmCoreState) {
        state.requireValidCoreSnapshot()
        val parent = checkpoint.baseFile.parentFile
        check(parent != null && (parent.isDirectory || parent.mkdirs())) {
            "STM Core state directory could not be created"
        }
        validateCheckpointPaths()
        val bytes = StmCoreCheckpointCodec.encode(state)
        val output = checkpoint.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            checkpoint.finishWrite(output)
        } catch (error: Exception) {
            checkpoint.failWrite(output)
            throw error
        }
    }

    private fun validateCheckpointPaths() {
        val base = checkpoint.baseFile.toPath().toAbsolutePath().normalize()
        val parent = requireNotNull(base.parent) { "STM Core checkpoint requires a parent" }
        require(!Files.isSymbolicLink(parent) && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            "STM Core checkpoint parent must be a real no-follow directory"
        }
        listOf(base, parent.resolve("${base.fileName}.bak"), parent.resolve("${base.fileName}.new"))
            .forEach { path ->
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    require(!Files.isSymbolicLink(path) &&
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) { "STM Core checkpoint control path is unsafe" }
                }
            }
    }

}

private fun readBoundedCheckpoint(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        if (read == 0) continue
        total += read
        require(total <= MAX_CHECKPOINT_BYTES) { "Checkpoint exceeds the size limit" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal object StmCoreCheckpointCodec {
    fun encode(state: StmCoreState): ByteArray {
        state.requireValidCoreSnapshot()
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(FILE_MAGIC)
                output.writeInt(FORMAT_VERSION_3)
                output.writeStateV3(state)
            }
            bytes.toByteArray().also { encoded ->
                require(encoded.size <= MAX_CHECKPOINT_BYTES) { "Checkpoint is too large" }
            }
        }
    }

    fun decode(bytes: ByteArray): StmCoreState {
        return try {
            require(bytes.size in 1..MAX_CHECKPOINT_BYTES) { "Checkpoint size is invalid" }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readUTF() == FILE_MAGIC) { "Checkpoint magic did not match" }
            val formatVersion = input.readInt()
            val loaded = when (formatVersion) {
                FORMAT_VERSION_1 -> input.readStateV1()
                FORMAT_VERSION_2 -> input.readStateV2()
                FORMAT_VERSION_3 -> input.readStateV3()
                else -> error("Checkpoint format $formatVersion is unsupported")
            }
            require(input.read() == -1) { "Checkpoint contains trailing bytes" }
            loaded.requireValidCoreSnapshot()
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Checkpoint is truncated or malformed", error)
        }
    }

    fun revisionHint(bytes: ByteArray): Long? = runCatching {
        require(bytes.size in 1..MAX_CHECKPOINT_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readUTF() == FILE_MAGIC)
        require(input.readInt() in FORMAT_VERSION_1..FORMAT_VERSION_3)
        input.readInt()
        input.readLong().takeIf { it > 0 }
    }.getOrNull()
}

private const val FILE_MAGIC = "STM_CORE_CHECKPOINT"
private const val FORMAT_VERSION_1 = 1
private const val FORMAT_VERSION_2 = 2
private const val FORMAT_VERSION_3 = 3
private const val MAX_COLLECTION_SIZE = 10_000
private const val MAX_CHECKPOINT_BYTES = 16 * 1024 * 1024

private fun DataOutputStream.writeStateV2(state: StmCoreState) {
    writeCommonState(state)
    writeInt(state.slots.size)
    state.slots.forEach(::writeSlotV2)
    writeActiveSlot(state.activeSlot)
    writeActiveSlot(state.runningSlot)
    writeInt(state.jobs.size)
    state.jobs.forEach(::writeJobV2)
}

private fun DataOutputStream.writeStateV3(state: StmCoreState) {
    writeCommonState(state)
    writeInt(state.slots.size)
    state.slots.forEach(::writeSlotV2)
    writeActiveSlot(state.activeSlot)
    writeActiveSlot(state.runningSlot)
    writeInt(state.jobs.size)
    state.jobs.forEach(::writeJobV3)
}

/** Reads the exact Stage 1 format and upgrades it without inventing install evidence. */
private fun DataInputStream.readStateV1(): StmCoreState {
    val common = readCommonState()
    val slots = List(readBoundedCount("slots")) {
        StmCoreSlot(
            id = readUTF(),
            state = StmCoreSlotState.valueOf(readUTF()),
            revision = readLong(),
            repository = readNullableString(),
            commitSha = readNullableString(),
        )
    }.map { slot ->
        if (slot.state == StmCoreSlotState.READY) slot.copy(state = StmCoreSlotState.BROKEN) else slot
    }
    val oldActive = readActiveSlot()
    val jobs = List(readBoundedCount("jobs")) {
        val operationId = readUTF()
        val legacyType = readUTF()
        val state = StmCoreJobState.valueOf(readUTF())
        val updatedAt = readLong()
        StmCoreJob(
            operationId = operationId,
            type = runCatching { StmCoreJobType.valueOf(legacyType.uppercase()) }
                .getOrDefault(StmCoreJobType.MIGRATE),
            targetId = "legacy",
            phase = if (state == StmCoreJobState.SUCCEEDED) {
                StmCoreJobPhase.COMPLETE
            } else {
                StmCoreJobPhase.QUEUED
            },
            state = state,
            startedAtEpochMs = updatedAt,
            updatedAtEpochMs = updatedAt,
            progress = readNullableDouble(),
            error = readError(),
        )
    }
    val active = oldActive?.takeIf { pointer ->
        slots.any { slot ->
            slot.id == pointer.slotId &&
                slot.revision == pointer.slotRevision &&
                slot.state == StmCoreSlotState.READY
        }
    }
    return common.toState(
        protocolVersion = STM_CORE_PROTOCOL_VERSION,
        slots = slots,
        activeSlot = active,
        runningSlot = null,
        jobs = jobs,
    )
}

private fun DataInputStream.readStateV2(): StmCoreState {
    val common = readCommonState()
    val slots = List(readBoundedCount("slots")) { readSlotV2() }
    val activeSlot = readActiveSlot()
    val runningSlot = readActiveSlot()
    val jobs = List(readBoundedCount("jobs")) { readJobV2() }
        .map(StmCoreJob::failClosedLegacyVerificationWithoutReceipt)
    return common.toState(
        protocolVersion = STM_CORE_PROTOCOL_VERSION,
        slots = slots,
        activeSlot = activeSlot,
        runningSlot = runningSlot,
        jobs = jobs,
    )
}

private fun StmCoreJob.failClosedLegacyVerificationWithoutReceipt(): StmCoreJob {
    if (type != StmCoreJobType.VERIFY || state != StmCoreJobState.SUCCEEDED) return this
    return copy(
        phase = StmCoreJobPhase.COMPLETE,
        state = StmCoreJobState.FAILED,
        progress = null,
        error = StmCoreError(
            domain = "checkpoint",
            code = "CHECKPOINT_VERIFICATION_RECEIPT_MISSING",
            summary = "A legacy verification result has no durable artifact receipt",
            diagnosticDetail = "Checkpoint format 2 did not retain Core-derived VERIFY evidence",
        ),
    )
}

private fun DataInputStream.readStateV3(): StmCoreState {
    val common = readCommonState()
    val slots = List(readBoundedCount("slots")) { readSlotV2() }
    val activeSlot = readActiveSlot()
    val runningSlot = readActiveSlot()
    val jobs = List(readBoundedCount("jobs")) { readJobV3() }
    return common.toState(
        protocolVersion = STM_CORE_PROTOCOL_VERSION,
        slots = slots,
        activeSlot = activeSlot,
        runningSlot = runningSlot,
        jobs = jobs,
    )
}

private fun DataInputStream.readBoundedCount(label: String): Int = readInt().also { count ->
    require(count in 0..MAX_COLLECTION_SIZE) { "Checkpoint $label count is invalid" }
}

private data class CommonState(
    val protocolVersion: Int,
    val revision: Long,
    val operationId: String?,
    val updatedAtEpochMs: Long,
    val processIdentity: String?,
    val processId: Int?,
    val sessionId: String?,
    val runState: StmCoreRunState,
    val workload: StmCoreWorkload,
    val localBaseUrl: String?,
    val port: Int?,
    val lastHealthyAtEpochMs: Long?,
    val summary: String?,
    val error: StmCoreError?,
    val coreVersion: String,
    val javetArtifact: String,
    val nodeVersion: String?,
) {
    fun toState(
        protocolVersion: Int,
        slots: List<StmCoreSlot>,
        activeSlot: StmCoreActiveSlot?,
        runningSlot: StmCoreActiveSlot?,
        jobs: List<StmCoreJob>,
    ) = StmCoreState(
        protocolVersion = protocolVersion,
        revision = revision,
        operationId = operationId,
        updatedAtEpochMs = updatedAtEpochMs,
        processIdentity = processIdentity,
        processId = processId,
        // Recovery readiness is process-epoch state and must never survive a checkpoint reload.
        installerRecoveryComplete = false,
        sessionId = sessionId,
        runState = runState,
        workload = workload,
        localBaseUrl = localBaseUrl,
        port = port,
        lastHealthyAtEpochMs = lastHealthyAtEpochMs,
        summary = summary,
        error = error,
        coreVersion = coreVersion,
        javetArtifact = javetArtifact,
        nodeVersion = nodeVersion,
        slots = slots,
        activeSlot = activeSlot,
        runningSlot = runningSlot,
        jobs = jobs,
    )
}

private fun DataOutputStream.writeCommonState(state: StmCoreState) {
    writeInt(state.protocolVersion)
    writeLong(state.revision)
    writeNullableString(state.operationId)
    writeLong(state.updatedAtEpochMs)
    writeNullableString(state.processIdentity)
    writeNullableInt(state.processId)
    writeNullableString(state.sessionId)
    writeUTF(state.runState.name)
    writeUTF(state.workload.name)
    writeNullableString(state.localBaseUrl)
    writeNullableInt(state.port)
    writeNullableLong(state.lastHealthyAtEpochMs)
    writeNullableString(state.summary)
    writeError(state.error)
    writeUTF(state.coreVersion)
    writeUTF(state.javetArtifact)
    writeNullableString(state.nodeVersion)
}

private fun DataInputStream.readCommonState() = CommonState(
    protocolVersion = readInt(),
    revision = readLong(),
    operationId = readNullableString(),
    updatedAtEpochMs = readLong(),
    processIdentity = readNullableString(),
    processId = readNullableInt(),
    sessionId = readNullableString(),
    runState = StmCoreRunState.valueOf(readUTF()),
    workload = StmCoreWorkload.valueOf(readUTF()),
    localBaseUrl = readNullableString(),
    port = readNullableInt(),
    lastHealthyAtEpochMs = readNullableLong(),
    summary = readNullableString(),
    error = readError(),
    coreVersion = readUTF(),
    javetArtifact = readUTF(),
    nodeVersion = readNullableString(),
)

private fun DataOutputStream.writeSlotV2(slot: StmCoreSlot) {
    writeUTF(slot.id)
    writeUTF(slot.state.name)
    writeLong(slot.revision)
    writeNullableString(slot.repository)
    writeNullableString(slot.commitSha)
    writeArtifact(slot.artifact)
    writeNullableString(slot.manifestSha256)
    writeNullableInt(slot.manifestFileCount)
    writeNullableLong(slot.manifestTotalBytes)
}

private fun DataInputStream.readSlotV2() = StmCoreSlot(
    id = readUTF(),
    state = StmCoreSlotState.valueOf(readUTF()),
    revision = readLong(),
    repository = readNullableString(),
    commitSha = readNullableString(),
    artifact = readArtifact(),
    manifestSha256 = readNullableString(),
    manifestFileCount = readNullableInt(),
    manifestTotalBytes = readNullableLong(),
)

private fun DataOutputStream.writeArtifact(artifact: StmCoreArtifact?) {
    writeBoolean(artifact != null)
    artifact ?: return
    writeUTF(artifact.kind.name)
    writeUTF(artifact.repository)
    writeUTF(artifact.channel)
    writeUTF(artifact.commitSha)
    writeUTF(artifact.downloadUrl)
    writeLong(artifact.downloadedAtEpochMs)
    writeLong(artifact.archiveLength)
    writeUTF(artifact.archiveSha256)
    writeUTF(artifact.integrity.name)
    writeUTF(artifact.trust.name)
    writeNullableString(artifact.catalogVersion)
    writeNullableString(artifact.archiveRoot)
    writeNullableString(artifact.stVersion)
    writeNullableString(artifact.nodeRequirement)
    writeNullableString(artifact.packageLockSha256)
    writeNullableString(artifact.licenseStatus)
}

private fun DataInputStream.readArtifact(): StmCoreArtifact? {
    if (!readBoolean()) return null
    return StmCoreArtifact(
        kind = StmCoreArtifactKind.valueOf(readUTF()),
        repository = readUTF(),
        channel = readUTF(),
        commitSha = readUTF(),
        downloadUrl = readUTF(),
        downloadedAtEpochMs = readLong(),
        archiveLength = readLong(),
        archiveSha256 = readUTF(),
        integrity = StmCoreArtifactIntegrity.valueOf(readUTF()),
        trust = StmCoreArtifactTrust.valueOf(readUTF()),
        catalogVersion = readNullableString(),
        archiveRoot = readNullableString(),
        stVersion = readNullableString(),
        nodeRequirement = readNullableString(),
        packageLockSha256 = readNullableString(),
        licenseStatus = readNullableString(),
    )
}

private fun DataOutputStream.writeActiveSlot(active: StmCoreActiveSlot?) {
    writeBoolean(active != null)
    active ?: return
    writeUTF(active.slotId)
    writeLong(active.slotRevision)
    writeLong(active.activeRevision)
}

private fun DataInputStream.readActiveSlot(): StmCoreActiveSlot? = if (readBoolean()) {
    StmCoreActiveSlot(
        slotId = readUTF(),
        slotRevision = readLong(),
        activeRevision = readLong(),
    )
} else {
    null
}

private fun DataOutputStream.writeJobV2(job: StmCoreJob) {
    writeUTF(job.operationId)
    writeUTF(job.type.name)
    writeUTF(job.targetId)
    writeUTF(job.phase.name)
    writeUTF(job.state.name)
    writeLong(job.startedAtEpochMs)
    writeLong(job.updatedAtEpochMs)
    writeNullableDouble(job.progress)
    writeError(job.error)
}

private fun DataInputStream.readJobV2() = StmCoreJob(
    operationId = readUTF(),
    type = StmCoreJobType.valueOf(readUTF()),
    targetId = readUTF(),
    phase = StmCoreJobPhase.valueOf(readUTF()),
    state = StmCoreJobState.valueOf(readUTF()),
    startedAtEpochMs = readLong(),
    updatedAtEpochMs = readLong(),
    progress = readNullableDouble(),
    error = readError(),
)

private fun DataOutputStream.writeJobV3(job: StmCoreJob) {
    writeJobV2(job)
    writeArtifact(job.artifact)
}

private fun DataInputStream.readJobV3(): StmCoreJob =
    readJobV2().copy(artifact = readArtifact())

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeUTF)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readUTF() else null

private fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    value?.let(::writeInt)
}

private fun DataInputStream.readNullableInt(): Int? =
    if (readBoolean()) readInt() else null

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    value?.let(::writeLong)
}

private fun DataInputStream.readNullableLong(): Long? =
    if (readBoolean()) readLong() else null

private fun DataOutputStream.writeNullableDouble(value: Double?) {
    writeBoolean(value != null)
    value?.let(::writeDouble)
}

private fun DataInputStream.readNullableDouble(): Double? =
    if (readBoolean()) readDouble() else null

private fun DataOutputStream.writeError(error: StmCoreError?) {
    writeBoolean(error != null)
    error?.let {
        writeUTF(it.domain)
        writeUTF(it.code)
        writeUTF(it.summary)
        writeNullableString(it.diagnosticDetail)
    }
}

private fun DataInputStream.readError(): StmCoreError? = if (readBoolean()) {
    StmCoreError(
        domain = readUTF(),
        code = readUTF(),
        summary = readUTF(),
        diagnosticDetail = readNullableString(),
    )
} else {
    null
}

internal fun Throwable.safeMessage(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)
