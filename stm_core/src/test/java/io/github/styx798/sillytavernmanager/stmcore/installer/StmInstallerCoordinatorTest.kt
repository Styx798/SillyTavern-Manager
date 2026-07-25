package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.StmCoreActiveSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJob
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmInstallerCoordinatorTest {
    @Test
    fun `COMPLETE recovery rebuilds v1 mutations and v2 VERIFY from durable evidence`() {
        val first = fixture()
        val slotA = first.install(
            "slot-receipt-a",
            1,
            first.createSyntheticArtifact("receipt-a.zip", "1".repeat(40), "a"),
        )
        val installAId = first.latestSucceededJob(StmCoreJobType.INSTALL, slotA.id).operationId
        val slotB = first.install(
            "slot-receipt-b",
            2,
            first.createSyntheticArtifact("receipt-b.zip", "2".repeat(40), "b"),
        )
        val installBId = first.latestSucceededJob(StmCoreJobType.INSTALL, slotB.id).operationId
        val active = first.activate(slotA, checkpoint = null)
        val activateId = first.latestSucceededJob(StmCoreJobType.ACTIVATE, slotA.id).operationId

        Thread.sleep(2)
        val removeId = UUID.randomUUID().toString()
        assertEquals(
            StmInstallerSubmission.Accepted,
            first.coordinator.remove(removeId, slotB, active, running = null),
        )
        assertEquals(StmCoreJobState.SUCCEEDED, first.awaitTerminalJob(removeId).state)

        val commit = "3".repeat(40)
        val source = first.createSillyTavernSourceArchive("receipt-source.zip", commit)
        val verifyId = UUID.randomUUID().toString()
        assertEquals(
            StmInstallerSubmission.Accepted,
            first.coordinator.verifyImportedArtifact(
                verifyId,
                "st-release-$commit",
                source.file.inputStream(),
                source.artifact,
            ),
        )
        val verifiedBeforeRestart = first.awaitTerminalJob(verifyId)
        assertEquals(StmCoreJobState.SUCCEEDED, verifiedBeforeRestart.state)
        first.coordinator.close()

        val journalStore = StmInstallerJournalStore(
            first.root.resolve("core/state/installer-journals"),
        )
        val legacyBytes = mutableMapOf<String, ByteArray>()
        listOf(installAId, installBId, activateId, removeId).forEach { operationId ->
            val loaded = journalStore.read(operationId) as StmInstallerJournalReadResult.Loaded
            writeLegacyV1(journalStore.journalFile(operationId), loaded.stored.record)
            legacyBytes[operationId] = journalStore.journalFile(operationId).readBytes()
        }

        val recovered = openFixture(first.root)
        val recoveredJobs = recovered.events
            .filterIsInstance<StmInstallerEvent.RecoveredTerminalJob>()
            .associate { it.job.operationId to it.job }

        listOf(installAId, installBId, activateId, removeId, verifyId).forEach { operationId ->
            assertEquals(StmCoreJobState.SUCCEEDED, recoveredJobs[operationId]?.state)
        }
        assertEquals(verifiedBeforeRestart.artifact, recoveredJobs[verifyId]?.artifact)
        assertEquals("SillyTavern-$commit", recoveredJobs[verifyId]?.artifact?.archiveRoot)
        listOf(installAId, installBId, activateId, removeId).forEach { operationId ->
            val loaded = journalStore.read(operationId) as StmInstallerJournalReadResult.Loaded
            assertEquals(1, loaded.stored.formatVersion)
            assertTrue(
                legacyBytes.getValue(operationId)
                    .contentEquals(journalStore.journalFile(operationId).readBytes()),
            )
        }
    }

    @Test
    fun `legacy v1 COMPLETE VERIFY fails closed without an artifact receipt`() {
        val first = fixture()
        val commit = "5".repeat(40)
        val source = first.createSillyTavernSourceArchive("legacy-verify.zip", commit)
        val operationId = UUID.randomUUID().toString()
        assertEquals(
            StmInstallerSubmission.Accepted,
            first.coordinator.verifyImportedArtifact(
                operationId,
                "st-release-$commit",
                source.file.inputStream(),
                source.artifact,
            ),
        )
        assertEquals(StmCoreJobState.SUCCEEDED, first.awaitTerminalJob(operationId).state)
        first.coordinator.close()

        val journalStore = StmInstallerJournalStore(
            first.root.resolve("core/state/installer-journals"),
        )
        val loaded = journalStore.read(operationId) as StmInstallerJournalReadResult.Loaded
        writeLegacyV1(journalStore.journalFile(operationId), loaded.stored.record)

        val recovered = openFixture(first.root, expectedRecoverySuccessful = false)
        val replay = recovered.events.filterIsInstance<StmInstallerEvent.RecoveredTerminalJob>()
            .single { it.job.operationId == operationId }
        assertEquals(StmCoreJobState.FAILED, replay.job.state)
        assertEquals("JOURNAL_TERMINAL_RECEIPT_MISSING", replay.job.error?.code)
        val afterRecovery = journalStore.read(operationId) as StmInstallerJournalReadResult.Loaded
        assertEquals(1, afterRecovery.stored.formatVersion)
    }

    @Test
    fun `legacy v1 COMPLETE ROLLBACK recovers from an exact active pointer`() {
        val first = fixture()
        val slotA = first.install(
            "slot-legacy-rollback-a",
            1,
            first.createSyntheticArtifact("legacy-rollback-a.zip", "a".repeat(40), "a"),
        )
        val slotB = first.install(
            "slot-legacy-rollback-b",
            2,
            first.createSyntheticArtifact("legacy-rollback-b.zip", "b".repeat(40), "b"),
        )
        val activeA = first.activate(slotA, checkpoint = null)
        val activeB = first.activate(slotB, checkpoint = activeA)
        val rolledBack = first.rollback(listOf(slotA, slotB), checkpoint = activeB)
        assertEquals(slotA.id, rolledBack.slotId)
        val operationId = first.latestSucceededJob(StmCoreJobType.ROLLBACK, slotA.id).operationId
        first.coordinator.close()

        val journalStore = StmInstallerJournalStore(
            first.root.resolve("core/state/installer-journals"),
        )
        val loaded = journalStore.read(operationId) as StmInstallerJournalReadResult.Loaded
        writeLegacyV1(journalStore.journalFile(operationId), loaded.stored.record)
        val legacyBytes = journalStore.journalFile(operationId).readBytes()

        val recovered = openFixture(first.root)
        val replay = recovered.events.filterIsInstance<StmInstallerEvent.RecoveredTerminalJob>()
            .single { it.job.operationId == operationId }
        assertEquals(StmCoreJobState.SUCCEEDED, replay.job.state)
        assertEquals(slotA.id, replay.job.targetId)
        val afterRecovery = journalStore.read(operationId) as StmInstallerJournalReadResult.Loaded
        assertEquals(1, afterRecovery.stored.formatVersion)
        assertTrue(legacyBytes.contentEquals(journalStore.journalFile(operationId).readBytes()))
    }

    @Test
    fun `process death after terminal journal commit recovers VERIFY receipt before job event`() {
        val killed = CountDownLatch(1)
        val first = fixture(StmInstallerCoordinatorFaultInjector { failpoint ->
            if (failpoint ==
                StmInstallerCoordinatorFailpoint
                    .AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT
            ) {
                killed.countDown()
                throw SimulatedCoreProcessDeath
            }
        })
        val commit = "6".repeat(40)
        val source = first.createSillyTavernSourceArchive("terminal-kill.zip", commit)
        val targetId = "st-release-$commit"
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            first.coordinator.verifyImportedArtifact(
                operationId,
                targetId,
                source.file.inputStream(),
                source.artifact,
            ),
        )
        assertTrue(
            "Terminal-journal process-death point was not reached",
            killed.await(10, TimeUnit.SECONDS),
        )

        assertFalse(
            first.events.filterIsInstance<StmInstallerEvent.JobChanged>()
                .map(StmInstallerEvent.JobChanged::job)
                .any {
                    it.operationId == operationId &&
                        it.state in setOf(
                            StmCoreJobState.SUCCEEDED,
                            StmCoreJobState.FAILED,
                            StmCoreJobState.CANCELLED,
                        )
                },
        )
        val journalStore = StmInstallerJournalStore(
            first.root.resolve("core/state/installer-journals"),
        )
        val loaded = journalStore.read(operationId) as StmInstallerJournalReadResult.Loaded
        assertEquals(2, loaded.stored.formatVersion)
        assertEquals(StmInstallerJournalPhase.COMPLETE, loaded.stored.record.phase)
        val durableReceipt = requireNotNull(loaded.stored.record.terminalReceipt)
        assertEquals(StmCoreJobPhase.COMPLETE, durableReceipt.jobPhase)
        assertEquals(StmCoreJobState.SUCCEEDED, durableReceipt.jobState)
        val durableArtifact = requireNotNull(durableReceipt.artifact)
        assertEquals("SillyTavern-$commit", durableArtifact.archiveRoot)
        assertEquals(StmCoreArtifactIntegrity.VERIFIED, durableArtifact.integrity)

        first.awaitMissing(first.root.resolve("core/staging/$operationId"))
        first.awaitMissing(first.root.resolve("core/installer-cache/$operationId.verified.part"))
        first.coordinator.close()

        val recovered = openFixture(first.root)
        val recoveredJob = recovered.events
            .filterIsInstance<StmInstallerEvent.RecoveredTerminalJob>()
            .map(StmInstallerEvent.RecoveredTerminalJob::job)
            .single { it.operationId == operationId }
        assertEquals(StmCoreJobState.SUCCEEDED, recoveredJob.state)
        assertEquals(StmCoreJobPhase.COMPLETE, recoveredJob.phase)
        assertEquals(targetId, recoveredJob.targetId)
        assertEquals(durableArtifact, recoveredJob.artifact)
        assertFalse(first.root.resolve("core/slots/$targetId").exists())
    }

    @Test
    fun `COMPLETE journal contradiction emits failed evidence and keeps recovery barrier closed`() {
        val first = fixture()
        val slot = first.install(
            "slot-contradiction",
            1,
            first.createSyntheticArtifact("contradiction.zip", "4".repeat(40), "original"),
        )
        val operationId = first.latestSucceededJob(StmCoreJobType.INSTALL, slot.id).operationId
        first.root.resolve("core/slots/${slot.id}/content.txt").writeText("tampered")
        first.coordinator.close()

        val recovered = openFixture(first.root, expectedRecoverySuccessful = false)
        val replay = recovered.events
            .filterIsInstance<StmInstallerEvent.RecoveredTerminalJob>()
            .single { it.job.operationId == operationId }

        assertEquals(StmCoreJobState.FAILED, replay.job.state)
        assertEquals("JOURNAL_COMPLETE_INSTALL_MISMATCH", replay.job.error?.code)
        assertTrue(recovered.events.filterIsInstance<StmInstallerEvent.RecoveryEvidence>().any {
            it.error.code == "JOURNAL_COMPLETE_INSTALL_MISMATCH"
        })
        assertFalse(
            recovered.events.filterIsInstance<StmInstallerEvent.RecoveryComplete>().last().successful,
        )
    }

    @Test
    fun `cancel before verification commit point is accepted and ends CANCELLED`() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = fixture(StmInstallerCoordinatorFaultInjector { point ->
            if (point == StmInstallerCoordinatorFailpoint.BEFORE_INSTALL_EXTRACTION) {
                reached.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test did not release verification" }
            }
        })
        val commit = "7".repeat(40)
        val cached = fixture.createSillyTavernSourceArchive("st-cancel.zip", commit)
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.verifyImportedArtifact(
                operationId,
                "st-release-$commit",
                cached.file.inputStream(),
                cached.artifact,
            ),
        )
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        assertTrue(fixture.coordinator.cancel(operationId))
        release.countDown()

        val terminal = fixture.awaitTerminalJob(operationId)
        assertEquals(StmCoreJobState.CANCELLED, terminal.state)
        assertFalse(fixture.root.resolve("core/slots/st-release-$commit").exists())
    }

    @Test
    fun `cancel after active pointer commit point is rejected and activation succeeds`() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = fixture(StmInstallerCoordinatorFaultInjector { point ->
            if (point == StmInstallerCoordinatorFailpoint.BEFORE_ACTIVE_POINTER_WRITE) {
                reached.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test did not release activation" }
            }
        })
        val slot = fixture.install(
            "slot-commit-race",
            1,
            fixture.createSyntheticArtifact("commit-race.zip", "6".repeat(40), "ready"),
        )
        val operationId = UUID.randomUUID().toString()

        fixture.awaitAccepted {
            fixture.coordinator.activate(operationId, slot, checkpointActive = null)
        }
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        assertFalse(fixture.coordinator.cancel(operationId))
        release.countDown()

        assertEquals(StmCoreJobState.SUCCEEDED, fixture.awaitTerminalJob(operationId).state)
        assertEquals(
            "slot-commit-race",
            fixture.events.filterIsInstance<StmInstallerEvent.ActiveChanged>()
                .mapNotNull(StmInstallerEvent.ActiveChanged::active)
                .last()
                .slotId,
        )
    }

    @Test
    fun `recovery rolls back an invalid current target and permits a new activation`() {
        val first = fixture()
        val slotA = first.install(
            "slot-recovery-a",
            1,
            first.createSyntheticArtifact("recovery-a.zip", "a".repeat(40), "a"),
        )
        val slotB = first.install(
            "slot-recovery-b",
            2,
            first.createSyntheticArtifact("recovery-b.zip", "b".repeat(40), "b"),
        )
        val slotC = first.install(
            "slot-recovery-c",
            3,
            first.createSyntheticArtifact("recovery-c.zip", "c".repeat(40), "c"),
        )
        val activeA = first.activate(slotA, null)
        first.activate(slotB, activeA)
        first.root.resolve("core/slots/slot-recovery-b/content.txt").writeText("tampered")
        first.coordinator.close()

        val recovered = openFixture(
            first.root,
            checkpointTerminalOperationIds = first.terminalOperationIds(),
        )
        val recoveredActive = recovered.events.filterIsInstance<StmInstallerEvent.ActiveChanged>()
            .mapNotNull(StmInstallerEvent.ActiveChanged::active)
            .last()
        assertEquals("slot-recovery-a", recoveredActive.slotId)
        assertEquals(3L, recoveredActive.activeRevision)
        assertTrue(recovered.events.filterIsInstance<StmInstallerEvent.RecoveryEvidence>().any {
            it.error.code == "ACTIVE_SLOT_ROLLED_BACK_INVALID_CURRENT"
        })

        val activeC = recovered.activate(slotC, recoveredActive)
        assertEquals("slot-recovery-c", activeC.slotId)
        assertEquals(4L, activeC.activeRevision)
    }

    @Test
    fun `recovery quarantines an invalid pointer without previous and permits fresh activation`() {
        val first = fixture()
        val slotA = first.install(
            "slot-quarantine-a",
            1,
            first.createSyntheticArtifact("quarantine-a.zip", "d".repeat(40), "a"),
        )
        val slotB = first.install(
            "slot-quarantine-b",
            2,
            first.createSyntheticArtifact("quarantine-b.zip", "e".repeat(40), "b"),
        )
        first.activate(slotA, null)
        first.root.resolve("core/slots/slot-quarantine-a/content.txt").writeText("tampered")
        first.coordinator.close()

        val recovered = openFixture(
            first.root,
            checkpointTerminalOperationIds = first.terminalOperationIds(),
        )
        val activeEvents = recovered.events.filterIsInstance<StmInstallerEvent.ActiveChanged>()
        assertTrue(
            "Expected a cleared active pointer; activeEvents=$activeEvents events=${recovered.events}",
            activeEvents.last().active == null,
        )
        assertFalse(first.root.resolve("core/state/active-slot").exists())
        assertTrue(
            first.root.resolve("core/state/active-slot-quarantine")
                .listFiles()
                .orEmpty()
                .any { it.name.startsWith("active-slot-") },
        )

        val activeB = recovered.activate(slotB, checkpoint = null)
        assertEquals("slot-quarantine-b", activeB.slotId)
        assertEquals(1L, activeB.activeRevision)
    }

    @Test
    fun `imported exact SillyTavern archive completes VERIFY with Core-derived evidence and no slot`() {
        val fixture = fixture()
        val commit = "9".repeat(40)
        val cached = fixture.createSillyTavernSourceArchive("st-source.zip", commit)
        val operationId = UUID.randomUUID().toString()
        val forgedHints = cached.artifact.copy(
            archiveRoot = "forged-root",
            stVersion = "forged-version",
            nodeRequirement = "forged-node",
            packageLockSha256 = "f".repeat(64),
            licenseStatus = "forged-license",
        )

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.verifyImportedArtifact(
                operationId,
                "st-release-$commit",
                cached.file.inputStream(),
                forgedHints,
            ),
        )
        val terminal = fixture.awaitTerminalJob(operationId)
        val verified = fixture.events.filterIsInstance<StmInstallerEvent.ArtifactVerified>()
            .single { it.targetId == "st-release-$commit" }

        assertEquals(io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType.VERIFY, terminal.type)
        assertEquals(StmCoreJobState.SUCCEEDED, terminal.state)
        assertEquals("SillyTavern-$commit", verified.artifact.archiveRoot)
        assertEquals("1.13.4", verified.artifact.stVersion)
        assertEquals(">=18.0.0", verified.artifact.nodeRequirement)
        assertEquals(sha256("{\"lockfileVersion\":3}".toByteArray()), verified.artifact.packageLockSha256)
        assertTrue(verified.artifact.licenseStatus?.contains("LICENSE") == true)
        assertFalse(fixture.root.resolve("core/slots/st-release-$commit").exists())
        fixture.awaitMissing(fixture.root.resolve("core/staging/$operationId"))
        assertTrue(fixture.slotEvents("st-release-$commit").isEmpty())
    }

    @Test
    fun `imported exact source follows local preparation phases and commits READY`() {
        val observedPhases = CopyOnWriteArrayList<StmRuntimeSlotPreparationPhase>()
        val preparer = StmRuntimeSlotPreparer { request, _, onPhase ->
            StmRuntimeSlotPreparationPhase.entries.forEach {
                observedPhases += it
                onPhase(it)
            }
            val program = request.payloadDirectory.resolve(request.archiveRoot)
            val adapterBytes = "export const localInstall = true;\n".toByteArray()
            val adapter = program.resolve("src/middleware/webpack-serve.js")
            requireNotNull(adapter.parentFile).mkdirs()
            adapter.writeBytes(adapterBytes)
            val dependency = program.resolve("node_modules/example/index.js")
            requireNotNull(dependency.parentFile).mkdirs()
            dependency.writeText("export default 'ready';\n")
            val runtime = request.payloadDirectory
                .resolve(StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY)
                .apply { mkdirs() }
            StmRuntimeSlotAdmissionEvidence.DEVICE_LOCAL_BUILD_RUNTIME_FILES.forEach { name ->
                runtime.resolve(name).writeBytes(
                    if (name == StmRuntimeSlotAdmissionEvidence.ADAPTER_FILE) {
                        adapterBytes
                    } else {
                        "local-$name\n".toByteArray()
                    },
                )
            }
            val programEntries = scanCoordinatorTree(program)
            val bindings =
                StmRuntimeSlotAdmissionEvidence.DEVICE_LOCAL_BUILD_RUNTIME_FILES.associateWith {
                    val file = runtime.resolve(it)
                    StmRuntimeFileBinding(file.length(), sha256(file.readBytes()))
                }
            StmRuntimeSlotAdmissionEvidence(
                supplyKind = StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD,
                repository = request.repository,
                commitSha = request.commitSha,
                packageLockSha256 = request.packageLockSha256,
                dependencyTreeSha256 = stmTreeIdentitySha256(
                    programEntries.filter {
                        it.relativePath == "node_modules" ||
                            it.relativePath.startsWith("node_modules/")
                    },
                ),
                postAdapterProgramTreeSha256 = stmTreeIdentitySha256(programEntries),
                runtimeFiles = bindings,
            )
        }
        val fixture = fixture(runtimeSlotPreparer = preparer)
        val commit = "7".repeat(40)
        val cached = fixture.createSillyTavernSourceArchive("st-install.zip", commit)
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.installImportedArtifact(
                operationId = operationId,
                slotId = "st-release-$commit",
                slotRevision = 1,
                source = cached.file.inputStream(),
                requestedArtifact = cached.artifact,
            ),
        )
        val terminal = fixture.awaitTerminalJob(operationId)

        assertEquals(StmCoreJobState.SUCCEEDED, terminal.state)
        assertEquals(StmRuntimeSlotPreparationPhase.entries, observedPhases)
        val preparationJobs = fixture.events
            .filterIsInstance<StmInstallerEvent.JobChanged>()
            .map(StmInstallerEvent.JobChanged::job)
            .filter {
                it.operationId == operationId &&
                    it.phase in setOf(
                        StmCoreJobPhase.PREPARING_TOOLCHAIN,
                        StmCoreJobPhase.INSTALLING_DEPENDENCIES,
                        StmCoreJobPhase.BUILDING_BUNDLE,
                        StmCoreJobPhase.ASSEMBLING_RUNTIME,
                        StmCoreJobPhase.RUNNABLE_ACCEPTANCE,
                        StmCoreJobPhase.VALIDATING,
                    )
            }
        assertEquals(
            listOf(
                StmCoreJobPhase.PREPARING_TOOLCHAIN,
                StmCoreJobPhase.INSTALLING_DEPENDENCIES,
                StmCoreJobPhase.BUILDING_BUNDLE,
                StmCoreJobPhase.ASSEMBLING_RUNTIME,
                StmCoreJobPhase.RUNNABLE_ACCEPTANCE,
                StmCoreJobPhase.VALIDATING,
            ),
            preparationJobs.map(StmCoreJob::phase),
        )
        assertEquals(
            listOf(0.28, 0.38, 0.55, 0.68, 0.78, 0.82),
            preparationJobs.map(StmCoreJob::progress),
        )
        val ready = fixture.slotEvents("st-release-$commit").last()
        assertEquals(StmCoreSlotState.READY, ready.state)
        assertTrue(
            fixture.coordinator.verifyCommittedSlot(ready.id) is StmSlotVerificationResult.Valid,
        )
        fixture.awaitMissing(fixture.root.resolve("core/staging/$operationId"))
    }

    @Test
    fun `same-length tampering of an imported archive fails Core hash verification`() {
        val fixture = fixture()
        val commit = "8".repeat(40)
        val cached = fixture.createSillyTavernSourceArchive("st-tamper.zip", commit)
        val tampered = cached.file.readBytes().also { bytes ->
            val index = bytes.size / 2
            bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        }
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.verifyImportedArtifact(
                operationId,
                "st-release-$commit",
                ByteArrayInputStream(tampered),
                cached.artifact,
            ),
        )
        val terminal = fixture.awaitTerminalJob(operationId)

        assertEquals(StmCoreJobState.FAILED, terminal.state)
        assertEquals("SHA256_MISMATCH", terminal.error?.code)
        assertTrue(fixture.events.none { it is StmInstallerEvent.ArtifactVerified })
        assertFalse(fixture.root.resolve("core/slots/st-release-$commit").exists())
    }

    @Test
    fun `two synthetic artifacts install activate switch and rollback without touching data`() {
        val fixture = fixture()
        val dataSentinel = fixture.root.resolve("files/stm_data/user.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("user-data")
        }
        val first = fixture.createSyntheticArtifact("fixture-a.zip", "a".repeat(40), "first")
        val second = fixture.createSyntheticArtifact("fixture-b.zip", "b".repeat(40), "second")

        val slotA = fixture.install("slot-a", 1, first)
        val slotB = fixture.install("slot-b", 2, second)
        assertEquals(StmCoreSlotState.READY, slotA.state)
        assertEquals(StmCoreSlotState.READY, slotB.state)

        val activeA = fixture.activate(slotA, checkpoint = null)
        val activeB = fixture.activate(slotB, checkpoint = activeA)
        val rolledBack = fixture.rollback(listOf(slotA, slotB), checkpoint = activeB)

        assertEquals("slot-a", rolledBack.slotId)
        assertEquals(3L, rolledBack.activeRevision)
        assertEquals("user-data", dataSentinel.readText())
        assertTrue(fixture.root.resolve("core/slots/slot-a").isDirectory)
        assertTrue(fixture.root.resolve("core/slots/slot-b").isDirectory)
    }

    @Test
    fun `path traversal install fails and preserves prior ready slot and data`() {
        val fixture = fixture()
        val good = fixture.createSyntheticArtifact("good.zip", "c".repeat(40), "good")
        val existing = fixture.install("slot-good", 1, good)
        val existingManifest = existing.manifestSha256
        val dataSentinel = fixture.root.resolve("files/stm_data/user.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("keep")
        }
        val malicious = fixture.createArchive(
            "bad.zip",
            "d".repeat(40),
            mapOf("../escape.txt" to "bad"),
        )
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.installCachedArtifact(
                operationId,
                "slot-bad",
                2,
                malicious.file.name,
                malicious.artifact,
            ),
        )
        val failed = fixture.awaitTerminalJob(operationId)

        assertEquals(StmCoreJobState.FAILED, failed.state)
        assertFalse(fixture.root.resolve("core/slots/slot-bad").exists())
        assertEquals("keep", dataSentinel.readText())
        val verified = StmSlotStore(
            fixture.root.resolve("core/slots"),
            fixture.root.resolve("core/staging"),
        ).verifyCommitted("slot-good") as StmSlotVerificationResult.Valid
        assertEquals(existingManifest, verified.slot.manifest.manifestSha256)
    }

    @Test
    fun `IPC trusted claim is rejected without Core catalog proof`() {
        val fixture = fixture()
        val cached = fixture.createSyntheticArtifact("claimed.zip", "e".repeat(40), "claim")
        val operationId = UUID.randomUUID().toString()
        val claimed = cached.artifact.copy(
            integrity = StmCoreArtifactIntegrity.VERIFIED,
            trust = StmCoreArtifactTrust.TRUSTED_CATALOG,
            catalogVersion = "unproven-v1",
        )

        val submission = fixture.coordinator.installCachedArtifact(
            operationId,
            "slot-claimed",
            1,
            cached.file.name,
            claimed,
        )

        assertEquals(StmInstallerSubmission.Accepted, submission)
        val failed = fixture.awaitTerminalJob(operationId)
        assertEquals(StmCoreJobState.FAILED, failed.state)
        assertEquals("CATALOG_PROOF_REQUIRED", failed.error?.code)
        assertFalse(fixture.root.resolve("core/slots/slot-claimed").exists())
    }

    @Test
    fun `reinstall cannot replace an immutable ready slot or publish it broken`() {
        val fixture = fixture()
        val original = fixture.createSyntheticArtifact("original.zip", "f".repeat(40), "original")
        val ready = fixture.install("slot-fixed", 1, original)
        val replacement = fixture.createSyntheticArtifact("replacement.zip", "0".repeat(40), "new")
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.installCachedArtifact(
                operationId,
                "slot-fixed",
                2,
                replacement.file.name,
                replacement.artifact,
            ),
        )
        val failure = fixture.awaitTerminalJob(operationId)

        assertEquals(StmCoreJobState.FAILED, failure.state)
        assertEquals("SLOT_ALREADY_EXISTS", failure.error?.code)
        val laterSnapshots = fixture.slotEvents("slot-fixed").dropWhile { it != ready }.drop(1)
        assertTrue(laterSnapshots.none { it.state == StmCoreSlotState.BROKEN })
        val stored = StmSlotStore(
            fixture.root.resolve("core/slots"),
            fixture.root.resolve("core/staging"),
        ).verifyCommitted("slot-fixed") as StmSlotVerificationResult.Valid
        assertEquals(ready.manifestSha256, stored.slot.manifest.manifestSha256)
        assertEquals(original.artifact.archiveSha256, stored.slot.metadata.archiveSha256)
    }

    @Test
    fun `remove refuses current and previous rollback slots`() {
        val fixture = fixture()
        val slotA = fixture.install(
            "slot-a",
            1,
            fixture.createSyntheticArtifact("a.zip", "1".repeat(40), "a"),
        )
        val slotB = fixture.install(
            "slot-b",
            2,
            fixture.createSyntheticArtifact("b.zip", "2".repeat(40), "b"),
        )
        val activeA = fixture.activate(slotA, null)
        val activeB = fixture.activate(slotB, activeA)

        val currentRemove = UUID.randomUUID().toString()
        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.remove(currentRemove, slotB, activeB, null),
        )
        assertEquals(StmCoreJobState.FAILED, fixture.awaitTerminalJob(currentRemove).state)

        val previousRemove = UUID.randomUUID().toString()
        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.remove(previousRemove, slotA, activeB, null),
        )
        assertEquals(StmCoreJobState.FAILED, fixture.awaitTerminalJob(previousRemove).state)
        assertTrue(fixture.root.resolve("core/slots/slot-a").exists())
        assertTrue(fixture.root.resolve("core/slots/slot-b").exists())
    }

    @Test
    fun `activation re-verifies immutable slot content before changing active pointer`() {
        val fixture = fixture()
        val slot = fixture.install(
            "slot-tampered",
            1,
            fixture.createSyntheticArtifact("tamper.zip", "3".repeat(40), "original"),
        )
        fixture.root.resolve("core/slots/slot-tampered/content.txt").writeText("tampered")
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.activate(operationId, slot, checkpointActive = null),
        )
        val failure = fixture.awaitTerminalJob(operationId)

        assertEquals(StmCoreJobState.FAILED, failure.state)
        assertEquals("SLOT_INVALID", failure.error?.code)
        assertFalse(fixture.root.resolve("core/state/active-slot").exists())
    }

    @Test
    fun `remove fails closed when checkpoint active pointer has lost its disk authority`() {
        val fixture = fixture()
        val slotA = fixture.install(
            "slot-authority-a",
            1,
            fixture.createSyntheticArtifact("authority-a.zip", "4".repeat(40), "a"),
        )
        val slotB = fixture.install(
            "slot-authority-b",
            2,
            fixture.createSyntheticArtifact("authority-b.zip", "5".repeat(40), "b"),
        )
        val activeA = fixture.activate(slotA, null)
        assertTrue(fixture.root.resolve("core/state/active-slot").delete())
        val operationId = UUID.randomUUID().toString()

        assertEquals(
            StmInstallerSubmission.Accepted,
            fixture.coordinator.remove(operationId, slotB, activeA, running = null),
        )
        val failure = fixture.awaitTerminalJob(operationId)

        assertEquals(StmCoreJobState.FAILED, failure.state)
        assertEquals("ACTIVE_POINTER_DIVERGED", failure.error?.code)
        assertTrue(fixture.root.resolve("core/slots/slot-authority-b").isDirectory)
    }

    private fun fixture(
        faultInjector: StmInstallerCoordinatorFaultInjector =
            StmInstallerCoordinatorFaultInjector { },
        runtimeSlotPreparer: StmRuntimeSlotPreparer? = null,
    ): Fixture = openFixture(
        java.nio.file.Files.createTempDirectory("stm-coordinator-").toFile(),
        faultInjector,
        runtimeSlotPreparer = runtimeSlotPreparer,
    )

    private fun openFixture(
        root: File,
        faultInjector: StmInstallerCoordinatorFaultInjector =
            StmInstallerCoordinatorFaultInjector { },
        expectedRecoverySuccessful: Boolean = true,
        checkpointTerminalOperationIds: Set<String> = emptySet(),
        runtimeSlotPreparer: StmRuntimeSlotPreparer? = null,
    ): Fixture {
        val events = CopyOnWriteArrayList<StmInstallerEvent>()
        val coordinator = StmInstallerCoordinator(
            installerCacheRoot = root.resolve("core/installer-cache"),
            stagingRoot = root.resolve("core/staging"),
            slotsRoot = root.resolve("core/slots"),
            activeFile = root.resolve("core/state/active-slot"),
            journalRoot = root.resolve("core/state/installer-journals"),
            eventSink = events::add,
            runtimeSlotPreparer = runtimeSlotPreparer,
            faultInjector = faultInjector,
            checkpointTerminalOperationIds = checkpointTerminalOperationIds,
        )
        coordinator.recoverAsync()
        val recoveryDeadline = System.currentTimeMillis() + 10_000
        while (events.none { it is StmInstallerEvent.RecoveryComplete }) {
            check(System.currentTimeMillis() < recoveryDeadline) {
                "Timed out waiting for installer recovery"
            }
            Thread.sleep(10)
        }
        assertEquals(
            expectedRecoverySuccessful,
            (events.filterIsInstance<StmInstallerEvent.RecoveryComplete>().last()).successful,
        )
        return Fixture(root, events, coordinator)
    }

    private data class CachedArtifact(val file: File, val artifact: StmCoreArtifact)

    private class Fixture(
        val root: File,
        val events: CopyOnWriteArrayList<StmInstallerEvent>,
        val coordinator: StmInstallerCoordinator,
    ) {
        fun createSyntheticArtifact(fileName: String, commit: String, content: String): CachedArtifact =
            createArchive(
                fileName,
                commit,
                mapOf(
                    "gate2-fixture.txt" to "STM_GATE2_SYNTHETIC_V1\n",
                    "content.txt" to content,
                ),
            )

        fun createSillyTavernSourceArchive(fileName: String, commit: String): CachedArtifact {
            val rootName = "SillyTavern-$commit"
            val cached = createArchive(
                fileName,
                commit,
                mapOf(
                    "$rootName/LICENSE" to "AGPL fixture",
                    "$rootName/server.js" to "console.log('fixture')",
                    "$rootName/package.json" to
                        "{\"version\":\"1.13.4\",\"engines\":{\"node\":\">=18.0.0\"}}",
                    "$rootName/package-lock.json" to "{\"lockfileVersion\":3}",
                ),
            )
            return cached.copy(
                artifact = cached.artifact.copy(
                    kind = StmCoreArtifactKind.SILLY_TAVERN_SOURCE,
                    repository = "https://github.com/SillyTavern/SillyTavern",
                    channel = "release",
                    downloadUrl =
                        "https://github.com/SillyTavern/SillyTavern/archive/$commit.zip",
                ),
            )
        }

        fun createArchive(
            fileName: String,
            commit: String,
            entries: Map<String, String>,
        ): CachedArtifact {
            val file = root.resolve("core/installer-cache/$fileName")
            requireNotNull(file.parentFile).mkdirs()
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            val hash = sha256(file.readBytes())
            return CachedArtifact(
                file,
                StmCoreArtifact(
                    kind = StmCoreArtifactKind.GATE2_SYNTHETIC,
                    repository = "https://github.com/example/stm-gate2-fixture.git",
                    channel = "gate2",
                    commitSha = commit,
                    downloadUrl =
                        "https://github.com/example/stm-gate2-fixture/archive/$commit.zip",
                    downloadedAtEpochMs = System.currentTimeMillis(),
                    archiveLength = file.length(),
                    archiveSha256 = hash,
                    integrity = StmCoreArtifactIntegrity.VERIFIED,
                    trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
                ),
            )
        }

        fun install(slotId: String, revision: Long, cached: CachedArtifact): StmCoreSlot {
            val operationId = UUID.randomUUID().toString()
            awaitAccepted {
                coordinator.installCachedArtifact(
                    operationId,
                    slotId,
                    revision,
                    cached.file.name,
                    cached.artifact,
                )
            }
            val terminal = awaitTerminalJob(operationId)
            assertEquals(StmCoreJobState.SUCCEEDED, terminal.state)
            return events.filterIsInstance<StmInstallerEvent.SlotChanged>()
                .map(StmInstallerEvent.SlotChanged::slot)
                .last { it.id == slotId && it.state == StmCoreSlotState.READY }
        }

        fun activate(target: StmCoreSlot, checkpoint: StmCoreActiveSlot?): StmCoreActiveSlot {
            val operationId = UUID.randomUUID().toString()
            awaitAccepted { coordinator.activate(operationId, target, checkpoint) }
            assertEquals(StmCoreJobState.SUCCEEDED, awaitTerminalJob(operationId).state)
            return events.filterIsInstance<StmInstallerEvent.ActiveChanged>()
                .mapNotNull(StmInstallerEvent.ActiveChanged::active)
                .last()
        }

        fun rollback(
            slots: List<StmCoreSlot>,
            checkpoint: StmCoreActiveSlot,
        ): StmCoreActiveSlot {
            val operationId = UUID.randomUUID().toString()
            awaitAccepted { coordinator.rollback(operationId, slots, checkpoint) }
            assertEquals(StmCoreJobState.SUCCEEDED, awaitTerminalJob(operationId).state)
            return events.filterIsInstance<StmInstallerEvent.ActiveChanged>()
                .mapNotNull(StmInstallerEvent.ActiveChanged::active)
                .last()
        }

        fun awaitTerminalJob(operationId: String): io.github.styx798.sillytavernmanager.stmcore.StmCoreJob {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                events.filterIsInstance<StmInstallerEvent.JobChanged>()
                    .map(StmInstallerEvent.JobChanged::job)
                    .lastOrNull {
                        it.operationId == operationId &&
                            it.state in setOf(
                                StmCoreJobState.SUCCEEDED,
                                StmCoreJobState.FAILED,
                                StmCoreJobState.CANCELLED,
                            )
                    }
                    ?.let { return it }
                Thread.sleep(10)
            }
            error("Timed out waiting for terminal installer job $operationId; events=$events")
        }

        fun slotEvents(slotId: String): List<StmCoreSlot> =
            events.filterIsInstance<StmInstallerEvent.SlotChanged>()
                .map(StmInstallerEvent.SlotChanged::slot)
                .filter { it.id == slotId }

        fun latestSucceededJob(type: StmCoreJobType, targetId: String) =
            events.filterIsInstance<StmInstallerEvent.JobChanged>()
                .map(StmInstallerEvent.JobChanged::job)
                .last { it.type == type && it.targetId == targetId && it.state == StmCoreJobState.SUCCEEDED }

        fun terminalOperationIds(): Set<String> =
            events.filterIsInstance<StmInstallerEvent.JobChanged>()
                .map(StmInstallerEvent.JobChanged::job)
                .filter {
                    it.state in setOf(
                        StmCoreJobState.SUCCEEDED,
                        StmCoreJobState.FAILED,
                        StmCoreJobState.CANCELLED,
                    )
                }
                .map(StmCoreJob::operationId)
                .toSet()

        fun awaitMissing(file: File) {
            val deadline = System.currentTimeMillis() + 10_000
            while (file.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertFalse("Timed out waiting for cleanup of $file", file.exists())
        }

        fun awaitAccepted(submit: () -> StmInstallerSubmission) {
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                when (val result = submit()) {
                    StmInstallerSubmission.Accepted -> return
                    is StmInstallerSubmission.Rejected -> {
                        if (result.code != "MAINTENANCE_BUSY") error(result)
                    }
                }
                Thread.sleep(10)
            }
            error("Installer remained busy")
        }
    }

    private data object SimulatedCoreProcessDeath : Error(
        "Simulated Core process death after durable terminal journal commit",
    )
}

private fun scanCoordinatorTree(root: File): List<StmZipManifestEntry> {
    val rootPath = root.toPath()
    return Files.walk(rootPath).use { stream ->
        stream.iterator().asSequence()
            .filter { it != rootPath }
            .map { path ->
                val relative = rootPath.relativize(path)
                    .joinToString("/") { it.toString() }
                if (Files.isDirectory(path)) {
                    StmZipManifestEntry(
                        relativePath = relative,
                        type = StmZipManifestEntryType.DIRECTORY,
                        sizeBytes = 0,
                        sha256 = null,
                    )
                } else {
                    val bytes = Files.readAllBytes(path)
                    StmZipManifestEntry(
                        relativePath = relative,
                        type = StmZipManifestEntryType.FILE,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = sha256(bytes),
                    )
                }
            }
            .sortedBy(StmZipManifestEntry::relativePath)
            .toList()
    }
}

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
            output.write(
                record.artifactSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            )
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

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
