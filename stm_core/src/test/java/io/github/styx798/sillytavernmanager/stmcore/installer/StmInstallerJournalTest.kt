package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreError
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StmInstallerJournalTest {
    @Test
    fun `v2 completed VERIFY round trip retains the complete Core artifact receipt`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val artifact = verifiedArtifact()
        val record = record(
            type = StmInstallerOperationType.VERIFY,
            artifactSha256 = artifact.archiveSha256,
            phase = StmInstallerJournalPhase.COMPLETE,
            terminalReceipt = successfulReceipt(artifact),
        )

        val written = store.write(record)
        val loaded = store.read(record.operationId) as StmInstallerJournalReadResult.Loaded

        assertEquals(2, written.formatVersion)
        assertEquals(record, loaded.stored.record)
        assertEquals(artifact, loaded.stored.record.terminalReceipt?.artifact)
    }

    @Test
    fun `v1 nonterminal journal remains readable and can upgrade while v1 terminal stays immutable`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val running = record(phase = StmInstallerJournalPhase.RUNNING)
        writeLegacyV1(store.journalFile(running.operationId), running)

        val legacy = store.read(running.operationId) as StmInstallerJournalReadResult.Loaded
        assertEquals(1, legacy.stored.formatVersion)
        assertEquals(running, legacy.stored.record)

        val upgradedRecord = running.copy(
            phase = StmInstallerJournalPhase.EXTRACTING,
            updatedAtEpochMs = running.updatedAtEpochMs + 1,
        )
        assertEquals(2, store.write(upgradedRecord).formatVersion)

        val terminal = record(
            operationId = uuid(44),
            stagingRelativeId = uuid(144),
            phase = StmInstallerJournalPhase.COMPLETE,
            terminalReceipt = null,
        )
        writeLegacyV1(store.journalFile(terminal.operationId), terminal)
        val loadedTerminal = store.read(terminal.operationId) as StmInstallerJournalReadResult.Loaded
        assertEquals(1, loadedTerminal.stored.formatVersion)
        assertThrows(IllegalArgumentException::class.java) {
            store.write(terminal.copy(updatedAtEpochMs = terminal.updatedAtEpochMs + 1))
        }
    }

    @Test
    fun `completed VERIFY rejects a missing mismatched or unverified receipt`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val artifact = verifiedArtifact()
        val base = record(
            type = StmInstallerOperationType.VERIFY,
            artifactSha256 = artifact.archiveSha256,
            phase = StmInstallerJournalPhase.COMPLETE,
            terminalReceipt = successfulReceipt(artifact),
        )

        assertThrows(IllegalArgumentException::class.java) {
            store.write(base.copy(terminalReceipt = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(
                base.copy(
                    operationId = uuid(45),
                    stagingRelativeId = uuid(145),
                    terminalReceipt = successfulReceipt(
                        artifact.copy(archiveSha256 = "b".repeat(64)),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(
                base.copy(
                    operationId = uuid(46),
                    stagingRelativeId = uuid(146),
                    terminalReceipt = successfulReceipt(
                        artifact.copy(integrity = StmCoreArtifactIntegrity.PENDING),
                    ),
                ),
            )
        }
    }

    @Test
    fun `journal round trip is bounded checksummed and scannable`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val record = record(phase = StmInstallerJournalPhase.EXTRACTING)

        val written = store.write(record)
        val loaded = store.read(record.operationId) as StmInstallerJournalReadResult.Loaded
        val scan = store.scan()

        assertEquals(record, written.record)
        assertTrue(written.checksumSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(written, loaded.stored)
        assertEquals(listOf(written), scan.journals)
        assertTrue(scan.corruptEvidence.isEmpty())
        assertTrue(store.journalFile(record.operationId).length() in 1..(16 * 1024L))
    }

    @Test
    fun `checksum mutation and truncation return explicit corrupt evidence`() {
        val checksumRoots = roots()
        val checksumStore = StmInstallerJournalStore(checksumRoots.journals)
        val record = record()
        checksumStore.write(record)
        val checksumFile = checksumStore.journalFile(record.operationId)
        val mutated = checksumFile.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        Files.write(checksumFile.toPath(), mutated, StandardOpenOption.TRUNCATE_EXISTING)

        val checksumResult = checksumStore.read(record.operationId)
            as StmInstallerJournalReadResult.Corrupt
        assertEquals(StmInstallerEvidenceCode.CHECKSUM_MISMATCH, checksumResult.evidence.code)

        val truncatedRoots = roots()
        val truncatedStore = StmInstallerJournalStore(truncatedRoots.journals)
        truncatedStore.write(record)
        val truncatedFile = truncatedStore.journalFile(record.operationId)
        val full = truncatedFile.readBytes()
        Files.write(
            truncatedFile.toPath(),
            full.copyOf(full.size - 5),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        val truncatedResult = truncatedStore.read(record.operationId)
            as StmInstallerJournalReadResult.Corrupt
        assertEquals(
            StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD,
            truncatedResult.evidence.code,
        )
    }

    @Test
    fun `write failpoints preserve either no commit or the unique atomic commit`() {
        StmInstallerJournalFailpoint.entries.forEach { failpoint ->
            val roots = roots()
            val store = StmInstallerJournalStore(
                roots.journals,
                StmInstallerJournalFaultInjector { actual ->
                    if (actual == failpoint) throw SimulatedJournalInterruption(actual)
                },
            )
            val record = record()

            assertThrows(SimulatedJournalInterruption::class.java) { store.write(record) }

            val read = store.read(record.operationId)
            if (failpoint == StmInstallerJournalFailpoint.AFTER_ATOMIC_MOVE) {
                assertEquals(
                    record,
                    (read as StmInstallerJournalReadResult.Loaded).stored.record,
                )
            } else {
                assertEquals(StmInstallerJournalReadResult.Missing, read)
            }
            val scan = store.scan()
            if (failpoint == StmInstallerJournalFailpoint.AFTER_TEMP_SYNC ||
                failpoint == StmInstallerJournalFailpoint.BEFORE_ATOMIC_MOVE
            ) {
                assertTrue(
                    scan.corruptEvidence.any {
                        it.code == StmInstallerEvidenceCode.TEMPORARY_RECORD
                    },
                )
            }
        }
    }

    @Test
    fun `update failpoints expose either the old or uniquely committed new journal`() {
        listOf(
            StmInstallerJournalFailpoint.BEFORE_ATOMIC_MOVE,
            StmInstallerJournalFailpoint.AFTER_ATOMIC_MOVE,
        ).forEach { failpoint ->
            val roots = roots()
            val initial = record(phase = StmInstallerJournalPhase.RUNNING)
            val next = initial.copy(
                phase = StmInstallerJournalPhase.EXTRACTING,
                updatedAtEpochMs = initial.updatedAtEpochMs + 1,
            )
            StmInstallerJournalStore(roots.journals).write(initial)
            val faulting = StmInstallerJournalStore(
                roots.journals,
                StmInstallerJournalFaultInjector { actual ->
                    if (actual == failpoint) throw SimulatedJournalInterruption(actual)
                },
            )

            assertThrows(SimulatedJournalInterruption::class.java) { faulting.write(next) }

            val loaded = faulting.read(initial.operationId) as StmInstallerJournalReadResult.Loaded
            assertEquals(
                if (failpoint == StmInstallerJournalFailpoint.AFTER_ATOMIC_MOVE) next else initial,
                loaded.stored.record,
            )
        }
    }

    @Test
    fun `interrupted phases plan failure and staging cleanup without touching ready or data`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val phases = listOf(
            StmInstallerJournalPhase.RUNNING,
            StmInstallerJournalPhase.EXTRACTING,
            StmInstallerJournalPhase.VERIFYING,
            StmInstallerJournalPhase.COMMITTING,
        )
        phases.forEachIndexed { index, phase ->
            val record = record(
                operationId = uuid(index + 1),
                stagingRelativeId = uuid(index + 101),
                phase = phase,
            )
            roots.staging.resolve(record.stagingRelativeId).mkdirs()
            store.write(record)
        }
        val readySentinel = roots.base.resolve("slots/slot-a/content.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("ready")
        }
        val dataSentinel = roots.base.resolve("data/user.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("data")
        }

        val plan = StmInstallerRecoveryPlanner(roots.staging).plan(store.scan())

        phases.forEachIndexed { index, _ ->
            val operationId = uuid(index + 1)
            assertTrue(
                plan.actions.any {
                    it.operationId == operationId &&
                        it.kind == StmInstallerRecoveryActionKind.FAIL_INTERRUPTED
                },
            )
            assertTrue(
                plan.actions.any {
                    it.operationId == operationId &&
                        it.kind == StmInstallerRecoveryActionKind.CLEANUP_STAGING
                },
            )
        }
        assertEquals("ready", readySentinel.readText())
        assertEquals("data", dataSentinel.readText())
    }

    @Test
    fun `complete journals are retained or explicitly planned for journal cleanup`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val complete = record(phase = StmInstallerJournalPhase.COMPLETE)
        store.write(complete)

        val retained = StmInstallerRecoveryPlanner(roots.staging).plan(store.scan())
        val cleaned = StmInstallerRecoveryPlanner(
            roots.staging,
            StmInstallerCompleteDisposition.CLEANUP_JOURNAL,
        ).plan(store.scan())

        assertTrue(
            retained.actions.any {
                it.kind == StmInstallerRecoveryActionKind.RETAIN_COMPLETE_JOURNAL
            },
        )
        assertTrue(
            cleaned.actions.any {
                it.kind == StmInstallerRecoveryActionKind.CLEANUP_COMPLETE_JOURNAL
            },
        )
        assertTrue(store.journalFile(complete.operationId).exists())
    }

    @Test
    fun `only UUID orphan staging directories receive quarantine actions`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val live = record(phase = StmInstallerJournalPhase.EXTRACTING)
        store.write(live)
        roots.staging.resolve(live.stagingRelativeId).mkdirs()
        val orphanId = uuid(999)
        roots.staging.resolve(orphanId).mkdirs()
        roots.staging.resolve("not-a-uuid").mkdirs()

        val plan = StmInstallerRecoveryPlanner(roots.staging).plan(store.scan())

        val orphanActions = plan.actions.filter {
            it.kind == StmInstallerRecoveryActionKind.QUARANTINE_ORPHAN_STAGING
        }
        assertEquals(listOf(orphanId), orphanActions.map { it.stagingRelativeId })
        assertTrue(
            plan.corruptEvidence.any {
                it.relativeName == "not-a-uuid" &&
                    it.code == StmInstallerEvidenceCode.STAGING_ENTRY_REJECTED
            },
        )
    }

    @Test
    fun `journal and staging symlinks are evidence and never cleanup actions`() {
        val journalRoots = roots()
        journalRoots.journals.mkdirs()
        val store = StmInstallerJournalStore(journalRoots.journals)
        val record = record()
        val outsideJournal = journalRoots.base.resolve("outside-journal").apply { writeText("x") }
        Files.createSymbolicLink(
            store.journalFile(record.operationId).toPath(),
            outsideJournal.toPath(),
        )

        val read = store.read(record.operationId) as StmInstallerJournalReadResult.Corrupt
        assertEquals(StmInstallerEvidenceCode.SYMBOLIC_LINK, read.evidence.code)

        val rootLinkRoots = roots()
        requireNotNull(rootLinkRoots.journals.parentFile).mkdirs()
        val outsideJournalRoot = rootLinkRoots.base.resolve("outside-journal-root").apply { mkdirs() }
        Files.createSymbolicLink(
            rootLinkRoots.journals.toPath(),
            outsideJournalRoot.toPath(),
        )
        val linkedRootScan = StmInstallerJournalStore(rootLinkRoots.journals).scan()
        assertEquals(StmInstallerEvidenceCode.SYMBOLIC_LINK, linkedRootScan.corruptEvidence.single().code)

        val stagingRoots = roots()
        val stagingStore = StmInstallerJournalStore(stagingRoots.journals)
        val stagingRecord = record(phase = StmInstallerJournalPhase.EXTRACTING)
        stagingStore.write(stagingRecord)
        stagingRoots.staging.mkdirs()
        val outsideDirectory = stagingRoots.base.resolve("outside").apply { mkdirs() }
        Files.createSymbolicLink(
            stagingRoots.staging.resolve(stagingRecord.stagingRelativeId).toPath(),
            outsideDirectory.toPath(),
        )

        val plan = StmInstallerRecoveryPlanner(stagingRoots.staging).plan(stagingStore.scan())
        assertTrue(
            plan.corruptEvidence.any {
                it.relativeName == stagingRecord.stagingRelativeId &&
                    it.code == StmInstallerEvidenceCode.STAGING_ENTRY_REJECTED
            },
        )
        assertFalse(plan.actions.any { it.stagingRelativeId == stagingRecord.stagingRelativeId })
    }

    @Test
    fun `invalid fields and oversized journal entries return bounded evidence`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        assertThrows(IllegalArgumentException::class.java) {
            store.write(record(operationId = "../operation"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(record(operationId = "00000000-0000-0000-0000-000000000000"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(record(stagingRelativeId = "../staging"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(record(targetSlotId = "../slot"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(record(artifactSha256 = "A".repeat(64)))
        }

        roots.journals.mkdirs()
        val oversizedName = "${uuid(88)}.journal"
        Files.write(roots.journals.resolve(oversizedName).toPath(), ByteArray(16 * 1024 + 1))
        val scan = store.scan()
        val oversized = scan.corruptEvidence.single { it.relativeName == oversizedName }
        assertEquals(StmInstallerEvidenceCode.OVERSIZED_RECORD, oversized.code)
        assertTrue(oversized.detail.length <= 500)
    }

    @Test
    fun `every recovery action path is an immediate child of staging root`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val interrupted = record(
            operationId = uuid(71),
            stagingRelativeId = uuid(171),
            phase = StmInstallerJournalPhase.VERIFYING,
        )
        store.write(interrupted)
        roots.staging.resolve(interrupted.stagingRelativeId).mkdirs()
        roots.staging.resolve(uuid(272)).mkdirs()

        val plan = StmInstallerRecoveryPlanner(roots.staging).plan(store.scan())
        val normalizedRoot = roots.staging.toPath().toAbsolutePath().normalize()

        plan.actions.mapNotNull(StmInstallerRecoveryAction::stagingPath).forEach { file ->
            val path = file.toPath().toAbsolutePath().normalize()
            assertTrue(path.startsWith(normalizedRoot))
            assertEquals(normalizedRoot, path.parent)
        }
        assertFalse(
            plan.actions.mapNotNull(StmInstallerRecoveryAction::stagingPath).any { file ->
                file.toPath().normalize().startsWith(roots.base.resolve("slots").toPath()) ||
                    file.toPath().normalize().startsWith(roots.base.resolve("data").toPath())
            },
        )
    }

    @Test
    fun `unbounded payload length is rejected before allocation`() {
        val roots = roots()
        val store = StmInstallerJournalStore(roots.journals)
        val record = record()
        store.write(record)
        val file = store.journalFile(record.operationId)
        val bytes = file.readBytes()
        ByteBuffer.wrap(bytes).putInt(8, Int.MAX_VALUE)
        Files.write(file.toPath(), bytes, StandardOpenOption.TRUNCATE_EXISTING)

        val result = store.read(record.operationId) as StmInstallerJournalReadResult.Corrupt
        assertEquals(StmInstallerEvidenceCode.TRUNCATED_OR_TRAILING_RECORD, result.evidence.code)
    }

    private fun roots(): TestRoots {
        val base = Files.createTempDirectory("stm-installer-journal-").toFile()
        return TestRoots(
            base = base,
            journals = base.resolve("core/state/installer-journals"),
            staging = base.resolve("core/staging"),
        )
    }

    private fun record(
        operationId: String = uuid(1),
        type: StmInstallerOperationType = StmInstallerOperationType.INSTALL,
        targetSlotId: String = "slot-a",
        artifactSha256: String = "a".repeat(64),
        phase: StmInstallerJournalPhase = StmInstallerJournalPhase.RUNNING,
        stagingRelativeId: String = uuid(101),
        startedAtEpochMs: Long = 1_000,
        updatedAtEpochMs: Long = 2_000,
        cancelRequested: Boolean = false,
        terminalReceipt: StmInstallerTerminalReceipt? = when (phase) {
            StmInstallerJournalPhase.COMPLETE -> successfulReceipt()
            StmInstallerJournalPhase.FAILED -> StmInstallerTerminalReceipt(
                jobPhase = StmCoreJobPhase.COMPLETE,
                jobState = StmCoreJobState.FAILED,
                error = StmCoreError("test", "FAILED", "failed"),
            )
            StmInstallerJournalPhase.CANCELLED -> StmInstallerTerminalReceipt(
                jobPhase = StmCoreJobPhase.CLEANING_UP,
                jobState = StmCoreJobState.CANCELLED,
            )
            else -> null
        },
    ) = StmInstallerJournalRecord(
        operationId = operationId,
        type = type,
        targetSlotId = targetSlotId,
        artifactSha256 = artifactSha256,
        phase = phase,
        stagingRelativeId = stagingRelativeId,
        startedAtEpochMs = startedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        cancelRequested = cancelRequested,
        terminalReceipt = terminalReceipt,
    )

    private fun successfulReceipt(
        artifact: StmCoreArtifact? = null,
    ) = StmInstallerTerminalReceipt(
        jobPhase = StmCoreJobPhase.COMPLETE,
        jobState = StmCoreJobState.SUCCEEDED,
        artifact = artifact,
    )

    private fun verifiedArtifact() = StmCoreArtifact(
        kind = StmCoreArtifactKind.SILLY_TAVERN_SOURCE,
        repository = "https://github.com/SillyTavern/SillyTavern",
        channel = "release",
        commitSha = "a".repeat(40),
        downloadUrl = "https://github.com/SillyTavern/SillyTavern/archive/${"a".repeat(40)}.zip",
        downloadedAtEpochMs = 1_000,
        archiveLength = 1_024,
        archiveSha256 = "a".repeat(64),
        integrity = StmCoreArtifactIntegrity.VERIFIED,
        trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
        archiveRoot = "SillyTavern-${"a".repeat(40)}",
        stVersion = "1.13.4",
        nodeRequirement = ">=18",
        packageLockSha256 = "c".repeat(64),
        licenseStatus = "LICENSE present",
    )

    private fun writeLegacyV1(file: File, record: StmInstallerJournalRecord) {
        requireNotNull(file.parentFile).mkdirs()
        val payload = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                fun writeUuid(value: String) = UUID.fromString(value).let { uuid ->
                    output.writeLong(uuid.mostSignificantBits)
                    output.writeLong(uuid.leastSignificantBits)
                }
                fun writeString(value: String) {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                writeUuid(record.operationId)
                writeString(record.type.name)
                writeString(record.targetSlotId)
                output.write(record.artifactSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                writeString(record.phase.name)
                writeUuid(record.stagingRelativeId)
                output.writeLong(record.startedAtEpochMs)
                output.writeLong(record.updatedAtEpochMs)
                output.writeByte(if (record.cancelRequested) 1 else 0)
            }
            buffer.toByteArray()
        }
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x53544D4A)
                output.writeInt(1)
                output.writeInt(payload.size)
                output.write(payload)
                output.write(checksum)
            }
            buffer.toByteArray()
        }
        Files.write(file.toPath(), bytes)
    }

    private fun uuid(value: Int): String = UUID.fromString(
        "00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}",
    ).toString()

    private data class TestRoots(
        val base: File,
        val journals: File,
        val staging: File,
    )

    private class SimulatedJournalInterruption(failpoint: StmInstallerJournalFailpoint) :
        RuntimeException("Interrupted at $failpoint")
}
