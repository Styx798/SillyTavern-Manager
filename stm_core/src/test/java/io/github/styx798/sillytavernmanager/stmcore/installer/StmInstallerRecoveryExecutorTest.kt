package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StmInstallerRecoveryExecutorTest {
    @Test
    fun `cleanup removes only the planned staging tree without following links`() {
        val fixture = fixture()
        val operationId = uuid(1)
        val stagingId = uuid(101)
        val target = fixture.staging.resolve(stagingId).apply {
            resolve("nested").mkdirs()
            resolve("nested/content.txt").writeText("temporary")
        }
        val outside = fixture.base.resolve("outside.txt").apply { writeText("outside") }
        Files.createSymbolicLink(target.resolve("nested/outside-link").toPath(), outside.toPath())
        val sentinels = fixture.createSentinels()
        val action = stagingAction(
            StmInstallerRecoveryActionKind.CLEANUP_STAGING,
            operationId,
            stagingId,
            target,
        )

        val report = fixture.executor().execute(plan(fixture.staging, action))

        assertEquals(StmInstallerRecoveryExecutionStatus.SUCCESS, report.results.single().status)
        assertEquals(StmInstallerRecoveryExecutionCode.STAGING_CLEANED, report.results.single().code)
        assertFalse(target.exists())
        assertEquals("outside", outside.readText())
        sentinels.assertUntouched()
    }

    @Test
    fun `orphan staging is atomically quarantined and evidence is preserved`() {
        val fixture = fixture()
        val orphanId = uuid(202)
        val orphan = fixture.staging.resolve(orphanId).apply {
            mkdirs()
            resolve("evidence.txt").writeText("preserve-me")
        }
        val sentinels = fixture.createSentinels()
        val action = stagingAction(
            StmInstallerRecoveryActionKind.QUARANTINE_ORPHAN_STAGING,
            operationId = null,
            stagingId = orphanId,
            target = orphan,
        )

        val result = fixture.executor(clock = { 123_456L })
            .execute(plan(fixture.staging, action))
            .results
            .single()

        assertEquals(StmInstallerRecoveryExecutionCode.ORPHAN_QUARANTINED, result.code)
        assertFalse(orphan.exists())
        val destination = requireNotNull(result.quarantineDestination)
        assertEquals("$orphanId-123456", destination.name)
        assertEquals(fixture.core.resolve("staging-quarantine"), destination.parentFile)
        assertEquals("preserve-me", destination.resolve("evidence.txt").readText())
        sentinels.assertUntouched()
    }

    @Test
    fun `complete journal cleanup deletes only an exact verified complete journal`() {
        val fixture = fixture()
        val complete = record(operationId = uuid(3), phase = StmInstallerJournalPhase.COMPLETE)
        val running = record(operationId = uuid(4), phase = StmInstallerJournalPhase.RUNNING)
        fixture.store.write(complete)
        fixture.store.write(running)
        val sentinels = fixture.createSentinels()
        val completeAction = journalAction(
            StmInstallerRecoveryActionKind.CLEANUP_COMPLETE_JOURNAL,
            complete.operationId,
        )
        val runningAction = journalAction(
            StmInstallerRecoveryActionKind.CLEANUP_COMPLETE_JOURNAL,
            running.operationId,
        )

        val report = fixture.executor().execute(
            plan(fixture.staging, completeAction, runningAction),
        )

        assertEquals(
            StmInstallerRecoveryExecutionCode.COMPLETE_JOURNAL_DELETED,
            report.results[0].code,
        )
        assertFalse(fixture.store.journalFile(complete.operationId).exists())
        assertEquals(StmInstallerRecoveryExecutionStatus.FAILED, report.results[1].status)
        assertEquals(StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED, report.results[1].code)
        assertTrue(fixture.store.journalFile(running.operationId).exists())
        sentinels.assertUntouched()
    }

    @Test
    fun `fail interrupted and retain complete are recorded without deletion`() {
        val fixture = fixture()
        val interrupted = journalAction(
            StmInstallerRecoveryActionKind.FAIL_INTERRUPTED,
            uuid(5),
        )
        val retained = journalAction(
            StmInstallerRecoveryActionKind.RETAIN_COMPLETE_JOURNAL,
            uuid(6),
        )
        val sentinels = fixture.createSentinels()

        val report = fixture.executor().execute(plan(fixture.staging, interrupted, retained))

        assertTrue(report.results.all { it.status == StmInstallerRecoveryExecutionStatus.NO_OP })
        assertTrue(
            report.results.all {
                it.code == StmInstallerRecoveryExecutionCode.RECORDED_WITHOUT_MUTATION
            },
        )
        sentinels.assertUntouched()
    }

    @Test
    fun `plan cannot substitute slots as its staging root`() {
        val fixture = fixture()
        val slotId = uuid(707)
        val slotTarget = fixture.core.resolve("slots/$slotId").apply {
            mkdirs()
            resolve("content.txt").writeText("immutable")
        }
        val maliciousAction = stagingAction(
            StmInstallerRecoveryActionKind.CLEANUP_STAGING,
            operationId = uuid(7),
            stagingId = slotId,
            target = slotTarget,
        )
        val maliciousPlan = plan(fixture.core.resolve("slots"), maliciousAction)

        val result = fixture.executor().execute(maliciousPlan).results.single()

        assertEquals(StmInstallerRecoveryExecutionStatus.FAILED, result.status)
        assertEquals(StmInstallerRecoveryExecutionCode.PLAN_ROOT_MISMATCH, result.code)
        assertEquals("immutable", slotTarget.resolve("content.txt").readText())
    }

    @Test
    fun `mismatched action path and top-level symlink are rejected`() {
        val mismatchFixture = fixture()
        val stagingId = uuid(808)
        val actual = mismatchFixture.staging.resolve(stagingId).apply { mkdirs() }
        val other = mismatchFixture.staging.resolve(uuid(809)).apply { mkdirs() }
        val mismatched = stagingAction(
            StmInstallerRecoveryActionKind.CLEANUP_STAGING,
            operationId = uuid(8),
            stagingId = stagingId,
            target = other,
        )

        val mismatch = mismatchFixture.executor()
            .execute(plan(mismatchFixture.staging, mismatched))
            .results
            .single()
        assertEquals(StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED, mismatch.code)
        assertTrue(actual.exists())
        assertTrue(other.exists())

        val linkFixture = fixture()
        val linkId = uuid(810)
        val outside = linkFixture.base.resolve("outside-directory").apply {
            mkdirs()
            resolve("sentinel.txt").writeText("outside")
        }
        val link = linkFixture.staging.resolve(linkId)
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val linkedAction = stagingAction(
            StmInstallerRecoveryActionKind.CLEANUP_STAGING,
            operationId = uuid(9),
            stagingId = linkId,
            target = link,
        )

        val linked = linkFixture.executor()
            .execute(plan(linkFixture.staging, linkedAction))
            .results
            .single()
        assertEquals(StmInstallerRecoveryExecutionCode.SYMBOLIC_LINK_REJECTED, linked.code)
        assertEquals("outside", outside.resolve("sentinel.txt").readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `journal symlink is rejected without deleting its target`() {
        val fixture = fixture()
        val operationId = uuid(10)
        val outside = fixture.base.resolve("outside-journal").apply { writeText("outside") }
        requireNotNull(fixture.store.journalFile(operationId).parentFile).mkdirs()
        Files.createSymbolicLink(
            fixture.store.journalFile(operationId).toPath(),
            outside.toPath(),
        )
        val action = journalAction(
            StmInstallerRecoveryActionKind.CLEANUP_COMPLETE_JOURNAL,
            operationId,
        )

        val result = fixture.executor().execute(plan(fixture.staging, action)).results.single()

        assertEquals(StmInstallerRecoveryExecutionStatus.FAILED, result.status)
        assertEquals(StmInstallerRecoveryExecutionCode.SYMBOLIC_LINK_REJECTED, result.code)
        assertEquals("outside", outside.readText())
    }

    @Test
    fun `symlinked state parent is rejected before journal mutation`() {
        val fixture = fixture()
        val complete = record(operationId = uuid(14), phase = StmInstallerJournalPhase.COMPLETE)
        fixture.store.write(complete)
        val originalState = fixture.core.resolve("state")
        val outsideState = fixture.base.resolve("outside-state")
        Files.move(originalState.toPath(), outsideState.toPath())
        Files.createSymbolicLink(originalState.toPath(), outsideState.toPath())
        val outsideJournal = outsideState.resolve("installer-journals/${complete.operationId}.journal")
        val action = journalAction(
            StmInstallerRecoveryActionKind.CLEANUP_COMPLETE_JOURNAL,
            complete.operationId,
        )

        val result = fixture.executor().execute(plan(fixture.staging, action)).results.single()

        assertEquals(StmInstallerRecoveryExecutionStatus.FAILED, result.status)
        assertEquals(StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED, result.code)
        assertTrue(Files.isSymbolicLink(originalState.toPath()))
        assertTrue(outsideJournal.isFile)
    }

    @Test
    fun `staging root swapped to a symlink at failpoint is rejected before cleanup`() {
        val fixture = fixture()
        val operationId = uuid(15)
        val stagingId = uuid(115)
        val target = fixture.staging.resolve(stagingId).apply {
            mkdirs()
            resolve("original.txt").writeText("original")
        }
        val outsideStaging = fixture.base.resolve("outside-staging").apply { mkdirs() }
        val outsideTarget = outsideStaging.resolve(stagingId).apply {
            mkdirs()
            resolve("sentinel.txt").writeText("outside")
        }
        val movedStaging = fixture.core.resolve("staging-original")
        val action = stagingAction(
            StmInstallerRecoveryActionKind.CLEANUP_STAGING,
            operationId,
            stagingId,
            target,
        )
        val executor = fixture.executor(
            faultInjector = StmInstallerRecoveryExecutorFaultInjector { failpoint, _ ->
                if (failpoint == StmInstallerRecoveryExecutorFailpoint.AFTER_REVALIDATION) {
                    Files.move(fixture.staging.toPath(), movedStaging.toPath())
                    Files.createSymbolicLink(fixture.staging.toPath(), outsideStaging.toPath())
                }
            },
        )

        val result = executor.execute(plan(fixture.staging, action)).results.single()

        assertEquals(StmInstallerRecoveryExecutionStatus.FAILED, result.status)
        assertEquals(StmInstallerRecoveryExecutionCode.ROOT_REJECTED, result.code)
        assertTrue(movedStaging.resolve("$stagingId/original.txt").isFile)
        assertEquals("outside", outsideTarget.resolve("sentinel.txt").readText())
    }

    @Test
    fun `fault before mutation leaves staging and sentinels unchanged`() {
        val fixture = fixture()
        val operationId = uuid(11)
        val stagingId = uuid(111)
        val target = fixture.staging.resolve(stagingId).apply {
            mkdirs()
            resolve("partial.txt").writeText("partial")
        }
        val sentinels = fixture.createSentinels()
        val action = stagingAction(
            StmInstallerRecoveryActionKind.CLEANUP_STAGING,
            operationId,
            stagingId,
            target,
        )
        val executor = fixture.executor(
            faultInjector = StmInstallerRecoveryExecutorFaultInjector { failpoint, _ ->
                if (failpoint == StmInstallerRecoveryExecutorFailpoint.BEFORE_MUTATION) {
                    throw StmInstallerRecoveryInjectedFault(failpoint)
                }
            },
        )

        val result = executor.execute(plan(fixture.staging, action)).results.single()

        assertEquals(StmInstallerRecoveryExecutionCode.FAULT_INJECTED, result.code)
        assertEquals("partial", target.resolve("partial.txt").readText())
        sentinels.assertUntouched()
    }

    @Test
    fun `one failed action does not widen or prevent an independent safe action`() {
        val fixture = fixture()
        val badId = uuid(121)
        val goodId = uuid(122)
        val outside = fixture.base.resolve("outside-action").apply { mkdirs() }
        val bad = fixture.staging.resolve(badId)
        Files.createSymbolicLink(bad.toPath(), outside.toPath())
        val good = fixture.staging.resolve(goodId).apply {
            mkdirs()
            resolve("temporary.txt").writeText("temporary")
        }
        val actions = arrayOf(
            stagingAction(
                StmInstallerRecoveryActionKind.CLEANUP_STAGING,
                uuid(21),
                badId,
                bad,
            ),
            stagingAction(
                StmInstallerRecoveryActionKind.CLEANUP_STAGING,
                uuid(22),
                goodId,
                good,
            ),
        )

        val report = fixture.executor().execute(plan(fixture.staging, *actions))

        assertEquals(1, report.failed)
        assertEquals(1, report.succeeded)
        assertTrue(Files.isSymbolicLink(bad.toPath()))
        assertFalse(good.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun `after-mutation failpoint reports uncertainty without fallback mutation`() {
        val fixture = fixture()
        val orphanId = uuid(130)
        val orphan = fixture.staging.resolve(orphanId).apply { mkdirs() }
        val sentinels = fixture.createSentinels()
        val action = stagingAction(
            StmInstallerRecoveryActionKind.QUARANTINE_ORPHAN_STAGING,
            null,
            orphanId,
            orphan,
        )
        val executor = fixture.executor(
            clock = { 777L },
            faultInjector = StmInstallerRecoveryExecutorFaultInjector { failpoint, _ ->
                if (failpoint == StmInstallerRecoveryExecutorFailpoint.AFTER_MUTATION) {
                    throw StmInstallerRecoveryInjectedFault(failpoint)
                }
            },
        )

        val result = executor.execute(plan(fixture.staging, action)).results.single()

        assertEquals(StmInstallerRecoveryExecutionCode.FAULT_INJECTED, result.code)
        assertFalse(orphan.exists())
        assertTrue(fixture.core.resolve("staging-quarantine/$orphanId-777").isDirectory)
        sentinels.assertUntouched()
    }

    private fun fixture(): Fixture {
        val base = Files.createTempDirectory("stm-recovery-executor-").toFile()
        val core = base.resolve("core").apply { mkdirs() }
        val staging = core.resolve("staging").apply { mkdirs() }
        val journals = core.resolve("state/installer-journals")
        return Fixture(
            base = base,
            core = core,
            staging = staging,
            store = StmInstallerJournalStore(journals),
        )
    }

    private fun plan(
        stagingRoot: File,
        vararg actions: StmInstallerRecoveryAction,
    ) = StmInstallerRecoveryPlan(
        stagingRoot = stagingRoot,
        actions = actions.toList(),
        corruptEvidence = emptyList(),
    )

    private fun stagingAction(
        kind: StmInstallerRecoveryActionKind,
        operationId: String?,
        stagingId: String,
        target: File,
    ) = StmInstallerRecoveryAction(
        kind = kind,
        operationId = operationId,
        stagingRelativeId = stagingId,
        stagingPath = target,
    )

    private fun journalAction(
        kind: StmInstallerRecoveryActionKind,
        operationId: String,
    ) = StmInstallerRecoveryAction(kind = kind, operationId = operationId)

    private fun record(
        operationId: String,
        phase: StmInstallerJournalPhase,
    ) = StmInstallerJournalRecord(
        operationId = operationId,
        type = StmInstallerOperationType.INSTALL,
        targetSlotId = "slot-a",
        artifactSha256 = "a".repeat(64),
        phase = phase,
        stagingRelativeId = uuid(900),
        startedAtEpochMs = 1_000,
        updatedAtEpochMs = 2_000,
        cancelRequested = false,
        terminalReceipt = if (phase == StmInstallerJournalPhase.COMPLETE) {
            StmInstallerTerminalReceipt(
                jobPhase = StmCoreJobPhase.COMPLETE,
                jobState = StmCoreJobState.SUCCEEDED,
            )
        } else {
            null
        },
    )

    private fun uuid(value: Int): String = UUID.fromString(
        "00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}",
    ).toString()

    private data class Fixture(
        val base: File,
        val core: File,
        val staging: File,
        val store: StmInstallerJournalStore,
    ) {
        fun executor(
            clock: () -> Long = { 1_000L },
            faultInjector: StmInstallerRecoveryExecutorFaultInjector =
                StmInstallerRecoveryExecutorFaultInjector { _, _ -> },
        ) = StmInstallerRecoveryExecutor(core, store, clock, faultInjector)

        fun createSentinels(): Sentinels {
            val slot = core.resolve("slots/slot-a/content.txt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("slot")
            }
            val data = base.resolve("data/user.txt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("data")
            }
            return Sentinels(slot, data)
        }
    }

    private data class Sentinels(
        val slot: File,
        val data: File,
    ) {
        fun assertUntouched() {
            assertEquals("slot", slot.readText())
            assertEquals("data", data.readText())
        }
    }
}
