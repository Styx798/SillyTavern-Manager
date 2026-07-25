package io.github.styx798.sillytavernmanager.stmcore

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StmCoreCheckpointCodecTest {
    @Test
    fun `v3 checkpoint round trips slot and durable verification receipt`() {
        val state = validState().copy(
            slots = listOf(readySlot()),
            activeSlot = StmCoreActiveSlot("slot-a", slotRevision = 4, activeRevision = 2),
            jobs = listOf(
                StmCoreJob(
                    operationId = UUID.randomUUID().toString(),
                    type = StmCoreJobType.INSTALL,
                    targetId = "slot-b",
                    phase = StmCoreJobPhase.EXTRACTING,
                    state = StmCoreJobState.RUNNING,
                    startedAtEpochMs = 900,
                    updatedAtEpochMs = 1_000,
                    progress = 0.5,
                ),
                StmCoreJob(
                    operationId = UUID.randomUUID().toString(),
                    type = StmCoreJobType.VERIFY,
                    targetId = "st-release-${"d".repeat(40)}",
                    phase = StmCoreJobPhase.COMPLETE,
                    state = StmCoreJobState.SUCCEEDED,
                    startedAtEpochMs = 900,
                    updatedAtEpochMs = 1_000,
                    progress = 1.0,
                    artifact = verifiedSourceArtifact(),
                ),
            ),
        ).requireValidCoreSnapshot()

        val decoded = StmCoreCheckpointCodec.decode(StmCoreCheckpointCodec.encode(state))

        assertEquals(state, decoded)
    }

    @Test
    fun `stage 1 checkpoint migrates to current protocol without inventing ready evidence`() {
        val bytes = legacyV1Checkpoint(includeUnsupportedReadySlot = true)

        val migrated = StmCoreCheckpointCodec.decode(bytes)

        assertEquals(STM_CORE_PROTOCOL_VERSION, migrated.protocolVersion)
        assertEquals(StmCoreSlotState.BROKEN, migrated.slots.single().state)
        assertNull(migrated.slots.single().artifact)
        assertNull(migrated.activeSlot)
    }

    @Test
    fun `process recovery readiness never survives a checkpoint reload`() {
        val state = validState().copy(installerRecoveryComplete = true)

        val decoded = StmCoreCheckpointCodec.decode(StmCoreCheckpointCodec.encode(state))

        assertEquals(false, decoded.installerRecoveryComplete)
    }

    @Test
    fun `v2 successful verification without artifact receipt fails closed`() {
        val operationId = UUID.randomUUID().toString()

        val migrated = StmCoreCheckpointCodec.decode(
            legacyV2CheckpointWithSuccessfulVerification(operationId),
        )
        val verification = migrated.jobs.single()

        assertEquals(operationId, verification.operationId)
        assertEquals(StmCoreJobState.FAILED, verification.state)
        assertEquals(StmCoreJobPhase.COMPLETE, verification.phase)
        assertNull(verification.artifact)
        assertNull(verification.progress)
        assertEquals(
            "CHECKPOINT_VERIFICATION_RECEIPT_MISSING",
            verification.error?.code,
        )
    }

    @Test
    fun `corrupt checkpoint retains a high revision hint for monotonic recovery`() {
        val encoded = StmCoreCheckpointCodec.encode(validState().copy(revision = 73))
        val truncated = encoded.copyOf(encoded.size - 5)

        assertEquals(73L, StmCoreCheckpointCodec.revisionHint(truncated))
        assertThrows(IllegalArgumentException::class.java) {
            StmCoreCheckpointCodec.decode(truncated)
        }
    }

    @Test
    fun `checkpoint decoder rejects trailing bytes`() {
        val encoded = StmCoreCheckpointCodec.encode(validState()) + byteArrayOf(1)

        assertThrows(IllegalArgumentException::class.java) {
            StmCoreCheckpointCodec.decode(encoded)
        }
    }

    private fun validState() = StmCoreState(
        revision = 5,
        updatedAtEpochMs = 1_000,
        processIdentity = "process-1",
        processId = 123,
        runState = StmCoreRunState.STOPPED,
    )

    private fun readySlot(): StmCoreSlot {
        val commit = "a".repeat(40)
        return StmCoreSlot(
            id = "slot-a",
            state = StmCoreSlotState.READY,
            revision = 4,
            repository = "https://github.com/example/synthetic.git",
            commitSha = commit,
            artifact = StmCoreArtifact(
                kind = StmCoreArtifactKind.GATE2_SYNTHETIC,
                repository = "https://github.com/example/synthetic.git",
                channel = "gate2",
                commitSha = commit,
                downloadUrl = "https://github.com/example/synthetic/archive/$commit.zip",
                downloadedAtEpochMs = 800,
                archiveLength = 123,
                archiveSha256 = "b".repeat(64),
                integrity = StmCoreArtifactIntegrity.VERIFIED,
                trust = StmCoreArtifactTrust.TRUSTED_CATALOG,
                catalogVersion = "gate2-v1",
            ),
            manifestSha256 = "c".repeat(64),
            manifestFileCount = 2,
            manifestTotalBytes = 42,
        )
    }

    private fun verifiedSourceArtifact(): StmCoreArtifact {
        val commit = "d".repeat(40)
        return StmCoreArtifact(
            kind = StmCoreArtifactKind.SILLY_TAVERN_SOURCE,
            repository = "https://github.com/SillyTavern/SillyTavern",
            channel = "release",
            commitSha = commit,
            downloadUrl = "https://github.com/SillyTavern/SillyTavern/archive/$commit.zip",
            downloadedAtEpochMs = 800,
            archiveLength = 123,
            archiveSha256 = "e".repeat(64),
            integrity = StmCoreArtifactIntegrity.VERIFIED,
            trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
            archiveRoot = "SillyTavern-$commit",
            stVersion = "1.13.4",
            nodeRequirement = ">=18.0.0",
            packageLockSha256 = "f".repeat(64),
            licenseStatus = "LICENSE_PRESENT",
        )
    }

    private fun legacyV1Checkpoint(includeUnsupportedReadySlot: Boolean): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("STM_CORE_CHECKPOINT")
                output.writeInt(1)
                output.writeInt(1)
                output.writeLong(41)
                output.writeNullableString(null)
                output.writeLong(1_000)
                output.writeNullableString("stage1-process")
                output.writeNullableInt(321)
                output.writeNullableString(null)
                output.writeUTF(StmCoreRunState.STOPPED.name)
                output.writeUTF(StmCoreWorkload.DIAGNOSTIC.name)
                output.writeNullableString(null)
                output.writeNullableInt(null)
                output.writeNullableLong(null)
                output.writeNullableString("Stage 1 checkpoint")
                output.writeBoolean(false)
                output.writeUTF("0.1.0")
                output.writeUTF("javet-node-android")
                output.writeNullableString("24.17.0")
                output.writeInt(if (includeUnsupportedReadySlot) 1 else 0)
                if (includeUnsupportedReadySlot) {
                    output.writeUTF("slot-old")
                    output.writeUTF(StmCoreSlotState.READY.name)
                    output.writeLong(2)
                    output.writeNullableString("https://example.invalid/legacy")
                    output.writeNullableString("d".repeat(40))
                }
                output.writeBoolean(includeUnsupportedReadySlot)
                if (includeUnsupportedReadySlot) {
                    output.writeUTF("slot-old")
                    output.writeLong(2)
                    output.writeLong(1)
                }
                output.writeInt(0)
            }
            bytes.toByteArray()
        }

    private fun legacyV2CheckpointWithSuccessfulVerification(operationId: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("STM_CORE_CHECKPOINT")
                output.writeInt(2)
                output.writeInt(2)
                output.writeLong(51)
                output.writeNullableString(null)
                output.writeLong(1_000)
                output.writeNullableString("stage2-process")
                output.writeNullableInt(654)
                output.writeNullableString(null)
                output.writeUTF(StmCoreRunState.STOPPED.name)
                output.writeUTF(StmCoreWorkload.DIAGNOSTIC.name)
                output.writeNullableString(null)
                output.writeNullableInt(null)
                output.writeNullableLong(null)
                output.writeNullableString("Stage 2 format 2 checkpoint")
                output.writeBoolean(false)
                output.writeUTF("0.1.0")
                output.writeUTF("javet-node-android")
                output.writeNullableString("24.17.0")
                output.writeInt(0)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeInt(1)
                output.writeUTF(operationId)
                output.writeUTF(StmCoreJobType.VERIFY.name)
                output.writeUTF("legacy-verify")
                output.writeUTF(StmCoreJobPhase.COMPLETE.name)
                output.writeUTF(StmCoreJobState.SUCCEEDED.name)
                output.writeLong(900)
                output.writeLong(1_000)
                output.writeNullableDouble(1.0)
                output.writeBoolean(false)
            }
            bytes.toByteArray()
        }
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeUTF)
}

private fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    value?.let(::writeInt)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    value?.let(::writeLong)
}

private fun DataOutputStream.writeNullableDouble(value: Double?) {
    writeBoolean(value != null)
    value?.let(::writeDouble)
}
