package io.github.styx798.sillytavernmanager.stmcore

import android.app.Instrumentation
import android.os.Bundle
import android.os.Environment
import io.github.styx798.sillytavernmanager.app.StmApplication
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentity
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrity
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveTrust
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.data.stmcore.AndroidStmCoreController
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperiment
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentClient
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentListener
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentResult
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreRawImportTestClient
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreRawImportTestListener
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal class StmCoreGate2Scenario(
    private val instrumentation: Instrumentation,
) {
    private val targetContext = instrumentation.targetContext

    suspend fun run(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        normalizeStopped(controller)
        awaitMaintenanceSettled(controller)

        val runId = UUID.randomUUID().toString().replace("-", "").take(12)
        val slotAId = "gate2-a-$runId"
        val slotBId = "gate2-b-$runId"
        val failedInstallSlotId = "gate2-kill-$runId"
        val sentinelRoot = File(
            StmCorePaths.dataRoot(targetContext),
            "gate2-instrumentation/$runId",
        ).canonicalFile
        check(sentinelRoot.mkdirs()) { "Could not create the isolated Gate 2 data sentinel root" }
        val sentinel = File(sentinelRoot, "user-data-sentinel.txt")
        sentinel.writeText(DATA_SENTINEL, Charsets.UTF_8)

        try {
            runProductionImportPreflight(controller, runId, result)

            val fixturesOutcome = runExperiment(StmCoreExperiment.GATE2_FIXTURES)
            val fixtures = requireCompleted(fixturesOutcome, controller, "Gate 2 fixture generation")
            val fixtureA = fixtures.result.values.toFixture("a")
            val fixtureB = fixtures.result.values.toFixture("b")
            verifyUnverifiedFixture(fixtureA)
            verifyUnverifiedFixture(fixtureB)

            val installA = executeAndAwaitJob(
                controller,
                StmCoreJobType.INSTALL,
                slotAId,
            ) { controller.installCachedArtifact(slotAId, fixtureA.cacheFileName, fixtureA.artifact) }
            check(installA.state == StmCoreJobState.SUCCEEDED) {
                "Gate 2 fixture A installation failed: $installA"
            }
            val readyA = requireReadySlot(controller.state.value, slotAId)
            verifyReadyFixture(readyA, fixtureA)

            val installB = executeAndAwaitJob(
                controller,
                StmCoreJobType.INSTALL,
                slotBId,
            ) { controller.installCachedArtifact(slotBId, fixtureB.cacheFileName, fixtureB.artifact) }
            check(installB.state == StmCoreJobState.SUCCEEDED) {
                "Gate 2 fixture B installation failed: $installB"
            }
            val readyB = requireReadySlot(controller.state.value, slotBId)
            verifyReadyFixture(readyB, fixtureB)
            verifySlotMarker(slotAId)
            verifySlotMarker(slotBId)
            verifySentinel(sentinel)

            val activateA = executeAndAwaitJob(
                controller,
                StmCoreJobType.ACTIVATE,
                slotAId,
            ) { controller.activate(slotAId) }
            check(activateA.state == StmCoreJobState.SUCCEEDED)
            check(controller.state.value.activeSlot?.slotId == slotAId) {
                "Fixture A did not become active"
            }

            val activateB = executeAndAwaitJob(
                controller,
                StmCoreJobType.ACTIVATE,
                slotBId,
            ) { controller.activate(slotBId) }
            check(activateB.state == StmCoreJobState.SUCCEEDED)
            check(controller.state.value.activeSlot?.slotId == slotBId) {
                "Fixture B did not replace fixture A"
            }

            val rollbackA = executeAndAwaitJob(
                controller,
                StmCoreJobType.ROLLBACK,
                slotAId,
            ) { controller.rollback() }
            check(rollbackA.state == StmCoreJobState.SUCCEEDED)
            val rolledBack = requireNotNull(controller.state.value.activeSlot)
            check(rolledBack.slotId == slotAId && rolledBack.activeRevision >= 3) {
                "Rollback did not restore fixture A: $rolledBack"
            }
            verifySentinel(sentinel)

            result.putString("gate2_slots", "$slotAId,$slotBId")
            result.putString("gate2_ab_switch", "A->B->A")
            result.putString("gate2_identity", "exact_commit_and_archive_sha256")
            result.putString("gate2_integrity", "core_verified")
            result.putString("gate2_trust", "degraded_unsigned_catalog")
            result.putLong("gate2_active_revision", rolledBack.activeRevision)

            val interruptedFixtureOutcome = runExperiment(
                StmCoreExperiment.GATE2_INTERRUPT_FIXTURE,
            )
            val interruptedFixtureResult = requireCompleted(
                interruptedFixtureOutcome,
                controller,
                "Gate 2 interrupted fixture generation",
            )
            val interruptedFixture = interruptedFixtureResult.result.values.toFixture("interrupt")
            runInterruptedInstall(
                controller = controller,
                fixture = interruptedFixture,
                targetSlotId = failedInstallSlotId,
                expectedActiveSlotId = slotAId,
                sentinel = sentinel,
                result = result,
            )
            runInterruptedActivationBeforeAtomicReplace(
                controller = controller,
                targetSlotId = slotBId,
                expectedActiveSlotId = slotAId,
                sentinel = sentinel,
                result = result,
            )
            runInterruptedActivationAfterAtomicReplace(
                controller = controller,
                targetSlotId = slotBId,
                expectedActiveSlotId = slotAId,
                sentinel = sentinel,
                result = result,
            )

            // The READY slots and their artifact evidence must survive both active-pointer kills.
            val recovered = controller.state.value
            verifyRecoveredReadySlot(requireReadySlot(recovered, slotAId), fixtureA)
            verifyRecoveredReadySlot(requireReadySlot(recovered, slotBId), fixtureB)
            verifySlotMarker(slotAId)
            verifySlotMarker(slotBId)
            verifySentinel(sentinel)
        } finally {
            runCatching { runExperiment(StmCoreExperiment.CLEAR_INSTALLER_KILL_FAILPOINT) }
            runCatching { sentinel.delete() }
            runCatching { sentinelRoot.delete() }
        }
    }

    private suspend fun runProductionImportPreflight(
        controller: AndroidStmCoreController,
        runId: String,
        result: Bundle,
    ) {
        val channel = StDownloadChannel.STABLE
        val commitSha = (runId + "0123456789abcdef0123456789abcdef0123456789abcdef")
            .take(EXACT_COMMIT_LENGTH)
        val archiveRoot = "SillyTavern-$commitSha"
        val targetSlotId = "gate2-verify-$runId"
        val downloadDirectory = requireNotNull(
            targetContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        ).absoluteFile
        check(downloadDirectory.isDirectory || downloadDirectory.mkdirs()) {
            "The App external download directory is unavailable"
        }
        val archiveFile = File(downloadDirectory, channel.exactArchiveFileName(commitSha))
        check(!archiveFile.exists()) {
            "The unique Gate 2 production preflight archive already exists"
        }
        createMinimalSillyTavernArchive(archiveFile, archiveRoot)
        val archiveLength = archiveFile.length()
        val archiveSha256 = sha256(archiveFile)
        val downloadedAt = System.currentTimeMillis()
        val requestedArtifact = StmCoreArtifact(
            kind = StmCoreArtifactKind.SILLY_TAVERN_SOURCE,
            repository = StDownloadChannel.GITHUB_REPOSITORY_URL,
            channel = channel.branch,
            commitSha = commitSha,
            downloadUrl = channel.exactArchiveUrl(commitSha),
            downloadedAtEpochMs = downloadedAt,
            archiveLength = archiveLength,
            archiveSha256 = archiveSha256,
            integrity = StmCoreArtifactIntegrity.PENDING,
            trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
        )
        val downloadedArchive = DownloadedStArchive(
            channel = channel,
            fileName = archiveFile.name,
            sizeBytes = archiveLength,
            downloadedAtEpochMillis = downloadedAt,
            identity = StArchiveIdentity(
                classification = StArchiveIdentityClassification.EXACT_COMMIT,
                repository = StDownloadChannel.REPOSITORY,
                channelRef = channel.branch,
                exactCommit = commitSha,
                archiveUrl = channel.exactArchiveUrl(commitSha),
            ),
            integrity = StArchiveIntegrity(
                classification = StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED,
                byteLength = archiveLength,
                sha256 = archiveSha256,
                hasZipFormatHint = true,
            ),
            trust = StArchiveTrust.DEGRADED_UNSIGNED_CATALOG,
        )
        val slotsBefore = controller.state.value.slots
        val stagingBefore = StmCorePaths.stagingRoot(targetContext).safeChildNames()
        try {
            val verified = executeAndAwaitJob(
                controller,
                StmCoreJobType.VERIFY,
                targetSlotId,
            ) { controller.importDownloadedArchive(targetSlotId, downloadedArchive) }
            check(verified.state == StmCoreJobState.SUCCEEDED && verified.error == null) {
                "Production PFD preflight failed: $verified"
            }
            val receipt = requireNotNull(verified.artifact) {
                "Successful production preflight omitted its Core-derived artifact receipt"
            }
            check(receipt.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE)
            check(receipt.repository == requestedArtifact.repository)
            check(receipt.channel == channel.branch)
            check(receipt.commitSha == commitSha)
            check(receipt.downloadUrl == requestedArtifact.downloadUrl)
            check(receipt.downloadedAtEpochMs == downloadedAt)
            check(receipt.archiveLength == archiveLength)
            check(receipt.archiveSha256 == archiveSha256)
            check(receipt.integrity == StmCoreArtifactIntegrity.VERIFIED)
            check(receipt.trust == StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG)
            check(receipt.catalogVersion == null)
            check(receipt.archiveRoot == archiveRoot)
            check(receipt.stVersion == ST_FIXTURE_VERSION)
            check(receipt.nodeRequirement == ST_FIXTURE_NODE_REQUIREMENT)
            check(
                receipt.packageLockSha256 ==
                    sha256(ST_FIXTURE_PACKAGE_LOCK.toByteArray(Charsets.UTF_8)),
            )
            check(receipt.licenseStatus == "LICENSE_PRESENT")
            awaitMaintenanceSettled(controller)
            awaitStagingRestored(stagingBefore)
            awaitVerifiedTemporaryRemoved(verified.operationId)
            verifyPreflightCreatedNoSlot(controller, targetSlotId, slotsBefore)
            check(archiveFile.length() == archiveLength && sha256(archiveFile) == archiveSha256) {
                "The read-only App-to-Core PFD path changed the external source archive"
            }
            result.putString("gate2_production_import", "app_read_only_pfd_to_core_verify")
            result.putString("gate2_production_verify_terminal", verified.state.name)
            result.putString("gate2_production_archive_root", receipt.archiveRoot)
            result.putString("gate2_production_st_version", receipt.stVersion)
            result.putString("gate2_production_node_requirement", receipt.nodeRequirement)
            result.putString("gate2_production_package_lock_sha256", receipt.packageLockSha256)
            result.putString("gate2_production_license_status", receipt.licenseStatus)
            result.putString("gate2_production_ready_slot_created", "false")

            runTerminalJournalCommitProcessKillRecovery(
                controller = controller,
                downloadedArchive = downloadedArchive,
                archiveFile = archiveFile,
                archiveLength = archiveLength,
                archiveSha256 = archiveSha256,
                targetSlotId = targetSlotId,
                expectedReceipt = receipt,
                slotsBefore = slotsBefore,
                stagingBefore = stagingBefore,
                result = result,
            )

            tamperSameLength(archiveFile)
            check(archiveFile.length() == archiveLength && sha256(archiveFile) != archiveSha256) {
                "The Gate 2 tamper fixture did not preserve length while changing content"
            }
            val tampered = executeAndAwaitJob(
                controller,
                StmCoreJobType.VERIFY,
                targetSlotId,
            ) { controller.importDownloadedArchive(targetSlotId, downloadedArchive) }
            check(
                tampered.state == StmCoreJobState.FAILED &&
                    tampered.error?.code == "SHA256_MISMATCH" &&
                    tampered.artifact == null,
            ) {
                "Same-length archive tamper was not rejected by the Core SHA-256 check: $tampered"
            }
            awaitMaintenanceSettled(controller)
            awaitStagingRestored(stagingBefore)
            awaitVerifiedTemporaryRemoved(tampered.operationId)
            verifyPreflightCreatedNoSlot(controller, targetSlotId, slotsBefore)
            result.putString("gate2_production_same_length_tamper", tampered.error?.code)

            runInvalidImportClosureChecks(
                controller = controller,
                source = archiveFile,
                requestedArtifact = requestedArtifact,
                targetSlotId = targetSlotId,
                slotsBefore = slotsBefore,
                result = result,
            )
        } finally {
            runCatching { Files.deleteIfExists(archiveFile.toPath()) }
        }
    }

    private suspend fun runTerminalJournalCommitProcessKillRecovery(
        controller: AndroidStmCoreController,
        downloadedArchive: DownloadedStArchive,
        archiveFile: File,
        archiveLength: Long,
        archiveSha256: String,
        targetSlotId: String,
        expectedReceipt: StmCoreArtifact,
        slotsBefore: List<StmCoreSlot>,
        stagingBefore: Set<String>,
        result: Bundle,
    ) {
        val armed = requireCompleted(
            runExperiment(
                StmCoreExperiment
                    .ARM_AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT_KILL,
            ),
            controller,
            "Terminal-journal process-kill failpoint arming",
        )
        check(armed.result.values["armed"] == "true")
        check(
            armed.result.values["failpoint"] ==
                "AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT",
        )

        val before = controller.state.value
        val beforePid = requireNotNull(before.processId)
        val beforeActive = before.activeSlot
        val beforeRunState = before.runState
        val knownOperationIds = before.jobs.mapTo(mutableSetOf(), StmCoreJob::operationId)
        check(
            controller.importDownloadedArchive(targetSlotId, downloadedArchive) ==
                StmCoreCommandResult.Accepted,
        ) { "Terminal-journal process-kill VERIFY command was not delivered" }

        val replacement = awaitCoreProcessReplacement(controller, beforePid, before.revision)
        val recovered = awaitState(controller) { snapshot ->
            snapshot.installerRecoveryComplete &&
                snapshot.jobs.none { !it.state.isTerminal } &&
                snapshot.jobs.any { job ->
                    job.operationId !in knownOperationIds &&
                        job.type == StmCoreJobType.VERIFY &&
                        job.targetId == targetSlotId &&
                        job.state == StmCoreJobState.SUCCEEDED &&
                        job.error == null &&
                        job.artifact == expectedReceipt
                }
        }
        val recoveredJob = requireNotNull(
            recovered.jobs.lastOrNull { job ->
                job.operationId !in knownOperationIds &&
                    job.type == StmCoreJobType.VERIFY &&
                    job.targetId == targetSlotId &&
                    job.state == StmCoreJobState.SUCCEEDED
            },
        )
        check(recoveredJob.error == null)
        check(recoveredJob.artifact == expectedReceipt) {
            "Recovered VERIFY did not retain its complete Core-derived receipt"
        }
        check(recovered.runState == beforeRunState) {
            "Terminal-journal process-kill recovery changed Feather Engine run state"
        }
        check(recovered.activeSlot == beforeActive) {
            "Terminal-journal process-kill recovery changed the active slot"
        }
        awaitMaintenanceSettled(controller)
        awaitStagingRestored(stagingBefore)
        awaitVerifiedTemporaryRemoved(recoveredJob.operationId)
        verifyNoInstallerJournalTemporaryFiles()
        verifyPreflightCreatedNoSlot(controller, targetSlotId, slotsBefore)
        check(archiveFile.length() == archiveLength && sha256(archiveFile) == archiveSha256) {
            "Terminal-journal process-kill recovery changed the read-only source archive"
        }

        result.putString(
            "gate2_terminal_journal_process_kill_recovery",
            recoveredJob.state.name,
        )
        result.putInt(
            "gate2_terminal_journal_process_kill_replacement_pid",
            requireNotNull(replacement.processId),
        )
        result.putString("gate2_terminal_journal_receipt_recovered", "true")
        result.putString("gate2_terminal_journal_ready_slot_created", "false")
        result.putString("gate2_terminal_journal_temporary_cleanup", "true")
    }

    private suspend fun runInvalidImportClosureChecks(
        controller: AndroidStmCoreController,
        source: File,
        requestedArtifact: StmCoreArtifact,
        targetSlotId: String,
        slotsBefore: List<StmCoreSlot>,
        result: Bundle,
    ) {
        val ready = CompletableDeferred<Unit>()
        lateinit var rawClient: StmCoreRawImportTestClient
        val listener = object : StmCoreRawImportTestListener {
            override fun onRawImportServiceReady() {
                ready.complete(Unit)
            }

            override fun onRawImportServiceDisconnected() {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(
                        IllegalStateException("Raw import test client disconnected before binding"),
                    )
                }
            }
        }
        onMain {
            rawClient = StmCoreRawImportTestClient(targetContext, listener)
            check(rawClient.connect()) { "Could not bind the raw Core import test client" }
        }
        try {
            withTimeout(EXPERIMENT_TIMEOUT_MILLIS) { ready.await() }
            val baseline = countCoreFileDescriptors(controller)
            repeat(INVALID_IMPORT_BATCH_SIZE) {
                sendAndAwaitInvalidSchema(
                    rawClient,
                    controller,
                    targetSlotId,
                    source,
                    requestedArtifact,
                )
                sendAndAwaitInvalidDescriptor(
                    rawClient,
                    controller,
                    targetSlotId,
                    requestedArtifact,
                )
            }
            val afterFirstBatch = countCoreFileDescriptors(controller)
            repeat(INVALID_IMPORT_BATCH_SIZE) {
                sendAndAwaitInvalidSchema(
                    rawClient,
                    controller,
                    targetSlotId,
                    source,
                    requestedArtifact,
                )
                sendAndAwaitInvalidDescriptor(
                    rawClient,
                    controller,
                    targetSlotId,
                    requestedArtifact,
                )
            }
            val afterSecondBatch = countCoreFileDescriptors(controller)
            check(afterSecondBatch <= afterFirstBatch + FD_COUNT_DRIFT_TOLERANCE) {
                "Repeated invalid imports grew Core FDs: baseline=$baseline, " +
                    "first=$afterFirstBatch, second=$afterSecondBatch"
            }
            check(afterSecondBatch <= baseline + FD_COUNT_BASELINE_TOLERANCE) {
                "Invalid import descriptors were not closed near baseline: baseline=$baseline, " +
                    "second=$afterSecondBatch"
            }
            verifyPreflightCreatedNoSlot(controller, targetSlotId, slotsBefore)
            result.putString("gate2_invalid_import_schema", "INVALID_IMPORT_REQUEST")
            result.putString("gate2_invalid_import_descriptor", "INVALID_IMPORT_DESCRIPTOR")
            result.putInt("gate2_invalid_import_fd_baseline", baseline)
            result.putInt("gate2_invalid_import_fd_after_first_batch", afterFirstBatch)
            result.putInt("gate2_invalid_import_fd_after_second_batch", afterSecondBatch)
            result.putString("gate2_invalid_import_fd_sustained_growth", "false")
        } finally {
            onMain { rawClient.disconnect() }
        }
    }

    private suspend fun sendAndAwaitInvalidSchema(
        rawClient: StmCoreRawImportTestClient,
        controller: AndroidStmCoreController,
        targetSlotId: String,
        source: File,
        requestedArtifact: StmCoreArtifact,
    ) {
        val operationId = UUID.randomUUID().toString()
        val revision = controller.state.value.revision
        check(onMain {
            rawClient.sendMissingArtifactSchema(
                operationId,
                targetSlotId,
                source,
                requestedArtifact,
            )
        }) { "Invalid import schema fixture was not delivered"
        }
        awaitRejectedImport(controller, operationId, revision, "INVALID_IMPORT_REQUEST")
    }

    private suspend fun sendAndAwaitInvalidDescriptor(
        rawClient: StmCoreRawImportTestClient,
        controller: AndroidStmCoreController,
        targetSlotId: String,
        requestedArtifact: StmCoreArtifact,
    ) {
        val operationId = UUID.randomUUID().toString()
        val revision = controller.state.value.revision
        check(onMain {
            rawClient.sendPipeDescriptor(operationId, targetSlotId, requestedArtifact)
        }) { "Invalid import descriptor fixture was not delivered"
        }
        awaitRejectedImport(controller, operationId, revision, "INVALID_IMPORT_DESCRIPTOR")
    }

    private suspend fun awaitRejectedImport(
        controller: AndroidStmCoreController,
        operationId: String,
        previousRevision: Long,
        expectedCode: String,
    ) {
        awaitState(controller) { snapshot ->
            snapshot.revision > previousRevision && snapshot.jobs.any { job ->
                job.operationId == operationId &&
                    job.type == StmCoreJobType.VERIFY &&
                    job.state == StmCoreJobState.FAILED &&
                    job.error?.code == expectedCode
            }
        }
    }

    private suspend fun countCoreFileDescriptors(
        controller: AndroidStmCoreController,
    ): Int {
        val completed = requireCompleted(
            runExperiment(StmCoreExperiment.COUNT_OPEN_FILE_DESCRIPTORS),
            controller,
            "Core file-descriptor count",
        )
        check(
            completed.result.values["process_id"]?.toInt() == controller.state.value.processId,
        ) { "File-descriptor count did not execute in the current Core process" }
        return requireNotNull(completed.result.values["open_file_descriptors"]).toInt()
    }

    private fun verifyPreflightCreatedNoSlot(
        controller: AndroidStmCoreController,
        targetSlotId: String,
        slotsBefore: List<StmCoreSlot>,
    ) {
        val current = controller.state.value
        check(current.slots == slotsBefore) {
            "Source-only VERIFY changed the public slot set"
        }
        check(current.slots.none { it.id == targetSlotId }) {
            "Source-only VERIFY exposed its target as a slot"
        }
        check(!File(StmCorePaths.slotsRoot(targetContext), targetSlotId).exists()) {
            "Source-only VERIFY created a committed slot on disk"
        }
    }

    private fun createMinimalSillyTavernArchive(archive: File, archiveRoot: String) {
        Files.newOutputStream(
            archive.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            ZipOutputStream(output, Charsets.UTF_8).use { zip ->
                writeZipEntry(zip, "$archiveRoot/server.js", ST_FIXTURE_SERVER)
                writeZipEntry(zip, "$archiveRoot/package.json", ST_FIXTURE_PACKAGE_JSON)
                writeZipEntry(zip, "$archiveRoot/package-lock.json", ST_FIXTURE_PACKAGE_LOCK)
                writeZipEntry(zip, "$archiveRoot/LICENSE", ST_FIXTURE_LICENSE)
            }
        }
        FileChannel.open(archive.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ZIP_TIME_EPOCH_MS })
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun tamperSameLength(archive: File) {
        RandomAccessFile(archive, "rw").use { file ->
            val position = file.length() / 2
            file.seek(position)
            val original = file.read()
            check(original >= 0) { "The preflight archive cannot be empty" }
            file.seek(position)
            file.write(original xor 0x01)
            file.fd.sync()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        Files.newInputStream(file.toPath(), StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private suspend fun runInterruptedInstall(
        controller: AndroidStmCoreController,
        fixture: Gate2CachedFixture,
        targetSlotId: String,
        expectedActiveSlotId: String,
        sentinel: File,
        result: Bundle,
    ) {
        val armed = requireCompleted(
            runExperiment(StmCoreExperiment.ARM_BEFORE_INSTALL_EXTRACTION_KILL),
            controller,
            "Install kill failpoint arming",
        )
        check(armed.result.values["armed"] == "true")
        check(armed.result.values["failpoint"] == "BEFORE_INSTALL_EXTRACTION")

        val before = controller.state.value
        val beforePid = requireNotNull(before.processId)
        val stagingBefore = StmCorePaths.stagingRoot(targetContext).safeChildNames()
        check(
            controller.installCachedArtifact(
                targetSlotId,
                fixture.cacheFileName,
                fixture.artifact,
            ) == StmCoreCommandResult.Accepted,
        ) { "Interrupted install command was not delivered" }

        val replacement = awaitCoreProcessReplacement(controller, beforePid, before.revision)
        val interruptedJob = awaitInterruptedJob(
            controller,
            StmCoreJobType.INSTALL,
            targetSlotId,
        )
        awaitMaintenanceSettled(controller)
        awaitStagingRestored(stagingBefore)
        awaitVerifiedTemporaryRemoved(interruptedJob.operationId)
        val recovered = controller.state.value
        check(recovered.runState == StmCoreRunState.STOPPED) {
            "Installer interruption incorrectly changed Feather Engine run state: ${recovered.runState}"
        }
        check(recovered.activeSlot?.slotId == expectedActiveSlotId) {
            "Interrupted install changed the active slot: ${recovered.activeSlot}"
        }
        check(recovered.slots.none { it.id == targetSlotId && it.state == StmCoreSlotState.READY }) {
            "Interrupted install exposed an uncommitted READY slot"
        }
        check(!File(StmCorePaths.slotsRoot(targetContext), targetSlotId).exists()) {
            "Interrupted install left a committed slot on disk"
        }
        verifySentinel(sentinel)
        result.putString("gate2_install_kill", "before_zip_extraction")
        result.putString("gate2_install_recovery_error", interruptedJob.error?.code)
        result.putInt("gate2_install_replacement_pid", requireNotNull(replacement.processId))
    }

    private suspend fun runInterruptedActivationBeforeAtomicReplace(
        controller: AndroidStmCoreController,
        targetSlotId: String,
        expectedActiveSlotId: String,
        sentinel: File,
        result: Bundle,
    ) {
        val armed = requireCompleted(
            runExperiment(
                StmCoreExperiment.ARM_ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE_KILL,
            ),
            controller,
            "Pre-replace activation kill failpoint arming",
        )
        check(armed.result.values["armed"] == "true")
        check(
            armed.result.values["failpoint"] ==
                "ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE",
        )

        val before = controller.state.value
        val beforeActive = requireNotNull(before.activeSlot)
        check(beforeActive.slotId == expectedActiveSlotId)
        val beforePid = requireNotNull(before.processId)
        val knownOperationIds = before.jobs.mapTo(mutableSetOf(), StmCoreJob::operationId)
        check(controller.activate(targetSlotId) == StmCoreCommandResult.Accepted) {
            "Pre-replace interrupted activation command was not delivered"
        }

        val replacementPid = requireNotNull(
            awaitCoreProcessReplacement(controller, beforePid, before.revision).processId,
        )
        val recovered = awaitState(controller) { snapshot ->
            snapshot.installerRecoveryComplete &&
                snapshot.activeSlot == beforeActive &&
                snapshot.jobs.none { !it.state.isTerminal } &&
                snapshot.jobs.any { job ->
                    job.operationId !in knownOperationIds &&
                        job.type == StmCoreJobType.ACTIVATE &&
                        job.targetId == targetSlotId &&
                        job.state == StmCoreJobState.FAILED &&
                        job.error?.code == "CORE_PROCESS_INTERRUPTED"
                }
        }
        val interruptedJob = requireNotNull(
            recovered.jobs.lastOrNull { job ->
                job.operationId !in knownOperationIds &&
                    job.type == StmCoreJobType.ACTIVATE &&
                    job.targetId == targetSlotId &&
                    job.state == StmCoreJobState.FAILED &&
                    job.error?.code == "CORE_PROCESS_INTERRUPTED"
            },
        )
        check(recovered.runState == StmCoreRunState.STOPPED) {
            "Activation interruption incorrectly changed Feather Engine run state"
        }
        check(recovered.activeSlot == beforeActive) {
            "Active pointer or revision changed before atomic replacement: ${recovered.activeSlot}"
        }
        requireReadySlot(recovered, expectedActiveSlotId)
        requireReadySlot(recovered, targetSlotId)
        verifyNoActiveSlotTemporaryFiles()
        verifySentinel(sentinel)

        result.putString(
            "gate2_activation_pre_replace_kill",
            "after_temp_sync_before_atomic_replace",
        )
        result.putString(
            "gate2_activation_pre_replace_recovery_error",
            interruptedJob.error?.code,
        )
        result.putInt(
            "gate2_activation_pre_replace_replacement_pid",
            replacementPid,
        )
        result.putLong(
            "gate2_activation_pre_replace_active_revision",
            beforeActive.activeRevision,
        )
        result.putString("gate2_activation_pre_replace_temp_cleanup", "true")

        // A recovered coordinator must accept the same activation and rollback normally.
        val activate = executeAndAwaitJob(
            controller,
            StmCoreJobType.ACTIVATE,
            targetSlotId,
        ) { controller.activate(targetSlotId) }
        check(activate.state == StmCoreJobState.SUCCEEDED) {
            "Activation did not recover after the pre-replace Core death: $activate"
        }
        val rollback = executeAndAwaitJob(
            controller,
            StmCoreJobType.ROLLBACK,
            expectedActiveSlotId,
        ) { controller.rollback() }
        check(rollback.state == StmCoreJobState.SUCCEEDED) {
            "Rollback did not recover after the pre-replace Core death: $rollback"
        }
        check(controller.state.value.activeSlot?.slotId == expectedActiveSlotId)
        verifySentinel(sentinel)
    }

    private suspend fun runInterruptedActivationAfterAtomicReplace(
        controller: AndroidStmCoreController,
        targetSlotId: String,
        expectedActiveSlotId: String,
        sentinel: File,
        result: Bundle,
    ) {
        val armed = requireCompleted(
            runExperiment(
                StmCoreExperiment.ARM_ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC_KILL,
            ),
            controller,
            "Post-replace activation kill failpoint arming",
        )
        check(armed.result.values["armed"] == "true")
        check(
            armed.result.values["failpoint"] ==
                "ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC",
        )

        val before = controller.state.value
        val beforeActive = requireNotNull(before.activeSlot)
        check(beforeActive.slotId == expectedActiveSlotId)
        check(beforeActive.activeRevision < Long.MAX_VALUE) {
            "The active-slot revision cannot advance for the post-replace kill scenario"
        }
        val targetBefore = requireReadySlot(before, targetSlotId)
        val expectedActiveRevision = beforeActive.activeRevision + 1L
        val beforePid = requireNotNull(before.processId)
        val knownOperationIds = before.jobs.mapTo(mutableSetOf(), StmCoreJob::operationId)
        check(controller.activate(targetSlotId) == StmCoreCommandResult.Accepted) {
            "Post-replace interrupted activation command was not delivered"
        }

        val replacementPid = requireNotNull(
            awaitCoreProcessReplacement(controller, beforePid, before.revision).processId,
        )
        val recovered = awaitState(controller) { snapshot ->
            val active = snapshot.activeSlot
            snapshot.installerRecoveryComplete &&
                active != null &&
                active.slotId == targetSlotId &&
                active.slotRevision == targetBefore.revision &&
                active.activeRevision == expectedActiveRevision &&
                snapshot.jobs.none { !it.state.isTerminal } &&
                snapshot.jobs.any { job ->
                    job.operationId !in knownOperationIds &&
                        job.type == StmCoreJobType.ACTIVATE &&
                        job.targetId == targetSlotId &&
                        job.state == StmCoreJobState.SUCCEEDED &&
                        job.error == null
                }
        }
        val recoveredJob = requireNotNull(
            recovered.jobs.lastOrNull { job ->
                job.operationId !in knownOperationIds &&
                    job.type == StmCoreJobType.ACTIVATE &&
                    job.targetId == targetSlotId &&
                    job.state == StmCoreJobState.SUCCEEDED &&
                    job.error == null
            },
        )
        val recoveredActive = requireNotNull(recovered.activeSlot)
        check(
            recoveredActive.slotId == targetSlotId &&
                recoveredActive.slotRevision == targetBefore.revision &&
                recoveredActive.activeRevision == expectedActiveRevision,
        ) {
            "Post-replace recovery did not expose the exact committed active revision: " +
                recoveredActive
        }
        check(recovered.runState == StmCoreRunState.STOPPED) {
            "Post-replace activation recovery changed Feather Engine run state"
        }
        requireReadySlot(recovered, expectedActiveSlotId)
        requireReadySlot(recovered, targetSlotId)
        verifySentinel(sentinel)

        result.putString(
            "gate2_activation_post_replace_kill",
            "after_atomic_replace_before_directory_sync",
        )
        result.putString("gate2_activation_post_replace_terminal", recoveredJob.state.name)
        result.putString(
            "gate2_activation_post_replace_final_consistency",
            "succeeded_with_new_active_revision",
        )
        result.putLong(
            "gate2_activation_post_replace_active_revision",
            recoveredActive.activeRevision,
        )
        result.putInt(
            "gate2_activation_post_replace_replacement_pid",
            replacementPid,
        )
        result.putString("gate2_activation_kill", "two_atomic_write_boundaries")

        val rollback = executeAndAwaitJob(
            controller,
            StmCoreJobType.ROLLBACK,
            expectedActiveSlotId,
        ) { controller.rollback() }
        check(rollback.state == StmCoreJobState.SUCCEEDED) {
            "Rollback did not recover after the post-replace Core death: $rollback"
        }
        check(controller.state.value.activeSlot?.slotId == expectedActiveSlotId)
        verifySentinel(sentinel)
    }

    private fun verifyUnverifiedFixture(fixture: Gate2CachedFixture) {
        check(fixture.artifact.kind == StmCoreArtifactKind.GATE2_SYNTHETIC)
        check(fixture.artifact.integrity == StmCoreArtifactIntegrity.PENDING)
        check(fixture.artifact.trust == StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG)
        check(fixture.artifact.catalogVersion == null)
        val cacheFile = File(StmCorePaths.installerCacheRoot(targetContext), fixture.cacheFileName)
        check(cacheFile.isFile && cacheFile.length() == fixture.artifact.archiveLength) {
            "Debug fixture was not generated in the Core installer cache"
        }
    }

    private fun verifyReadyFixture(slot: StmCoreSlot, fixture: Gate2CachedFixture) {
        val artifact = requireNotNull(slot.artifact)
        check(artifact.repository == fixture.artifact.repository)
        check(artifact.channel == fixture.artifact.channel)
        check(artifact.commitSha == fixture.artifact.commitSha)
        check(artifact.downloadUrl == fixture.artifact.downloadUrl)
        check(artifact.downloadedAtEpochMs == fixture.artifact.downloadedAtEpochMs)
        check(artifact.archiveLength == fixture.artifact.archiveLength)
        check(artifact.archiveSha256 == fixture.artifact.archiveSha256)
        check(artifact.integrity == StmCoreArtifactIntegrity.VERIFIED)
        check(artifact.trust == StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG)
        check(artifact.trust != StmCoreArtifactTrust.TRUSTED_CATALOG)
        check(artifact.catalogVersion == fixture.artifact.catalogVersion)
        check(artifact.archiveRoot == fixture.artifact.archiveRoot)
        check(artifact.stVersion == fixture.artifact.stVersion)
        check(artifact.nodeRequirement == fixture.artifact.nodeRequirement)
        check(artifact.packageLockSha256 == fixture.artifact.packageLockSha256)
        check(artifact.licenseStatus == fixture.artifact.licenseStatus)
        check(slot.manifestSha256?.length == 64)
        check((slot.manifestFileCount ?: 0) >= 2)
        check((slot.manifestTotalBytes ?: -1) >= 0)
    }

    private fun verifyRecoveredReadySlot(slot: StmCoreSlot, fixture: Gate2CachedFixture) {
        val artifact = requireNotNull(slot.artifact)
        check(artifact.repository == fixture.artifact.repository)
        check(artifact.channel == fixture.artifact.channel)
        check(artifact.commitSha == fixture.artifact.commitSha)
        check(artifact.downloadUrl == fixture.artifact.downloadUrl)
        check(artifact.archiveSha256 == fixture.artifact.archiveSha256)
        check(artifact.downloadedAtEpochMs == fixture.artifact.downloadedAtEpochMs)
        check(artifact.archiveLength == fixture.artifact.archiveLength)
        check(artifact.integrity == StmCoreArtifactIntegrity.VERIFIED)
        check(artifact.trust == StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG)
        check(artifact.stVersion == fixture.artifact.stVersion)
        check(artifact.licenseStatus == fixture.artifact.licenseStatus)
    }

    private fun requireReadySlot(state: StmCoreState, slotId: String): StmCoreSlot =
        requireNotNull(state.slots.singleOrNull { it.id == slotId }) {
            "READY slot $slotId is missing from $state"
        }.also { slot ->
            check(slot.state == StmCoreSlotState.READY) { "Slot $slotId is not READY: $slot" }
        }

    private fun verifySlotMarker(slotId: String) {
        val marker = File(StmCorePaths.slotsRoot(targetContext), "$slotId/gate2-fixture.txt")
        check(marker.isFile && marker.readText(Charsets.UTF_8) == GATE2_MARKER) {
            "Slot $slotId did not preserve the exact Gate 2 marker"
        }
    }

    private fun verifySentinel(sentinel: File) {
        check(sentinel.isFile && sentinel.readText(Charsets.UTF_8) == DATA_SENTINEL) {
            "Core maintenance changed the isolated user-data sentinel"
        }
    }

    private fun verifyNoActiveSlotTemporaryFiles() {
        val activeFile = StmCorePaths.activeSlotFile(targetContext)
        val prefixes = listOf(
            ".${activeFile.name}.tmp-",
            ".${activeFile.name}.previous.tmp-",
        )
        val temporaryNames = activeFile.parentFile?.listFiles().orEmpty()
            .map(File::getName)
            .filter { name -> prefixes.any(name::startsWith) }
        check(temporaryNames.isEmpty()) {
            "Core recovery retained active-slot temporary files: $temporaryNames"
        }
    }

    private fun verifyNoInstallerJournalTemporaryFiles() {
        val temporaryNames = StmCorePaths.installerJournalRoot(targetContext)
            .safeChildNames()
            .filter { name -> name.startsWith('.') && ".journal.tmp-" in name }
        check(temporaryNames.isEmpty()) {
            "Core recovery retained installer-journal temporary files: $temporaryNames"
        }
    }

    private suspend fun normalizeStopped(controller: AndroidStmCoreController) {
        var state = awaitState(controller) {
            it.revision > 0 && it.processId != null && it.installerRecoveryComplete
        }
        when (state.runState) {
            StmCoreRunState.STOPPED -> Unit
            StmCoreRunState.CRASHED -> {
                check(controller.start() == StmCoreCommandResult.Accepted)
                awaitState(controller) { it.runState == StmCoreRunState.RUNNING }
                check(controller.stop() == StmCoreCommandResult.Accepted)
                awaitState(controller) { it.runState == StmCoreRunState.STOPPED }
            }

            StmCoreRunState.STARTING -> {
                awaitState(controller) { it.runState == StmCoreRunState.RUNNING }
                check(controller.stop() == StmCoreCommandResult.Accepted)
                awaitState(controller) { it.runState == StmCoreRunState.STOPPED }
            }

            StmCoreRunState.RUNNING -> {
                check(controller.stop() == StmCoreCommandResult.Accepted)
                awaitState(controller) { it.runState == StmCoreRunState.STOPPED }
            }

            StmCoreRunState.DRAINING -> awaitState(controller) {
                it.runState == StmCoreRunState.STOPPED
            }
        }
        state = controller.state.value
        check(state.runState == StmCoreRunState.STOPPED)
    }

    private suspend fun executeAndAwaitJob(
        controller: AndroidStmCoreController,
        type: StmCoreJobType,
        targetId: String,
        command: suspend () -> StmCoreCommandResult,
    ): StmCoreJob {
        val knownOperationIds = controller.state.value.jobs.mapTo(mutableSetOf()) {
            it.operationId
        }
        check(command() == StmCoreCommandResult.Accepted) {
            "Core rejected $type for $targetId"
        }
        val state = awaitState(controller) { snapshot ->
            snapshot.jobs.any { job ->
                job.operationId !in knownOperationIds &&
                    job.type == type &&
                    job.targetId == targetId &&
                    job.state.isTerminal
            }
        }
        return requireNotNull(
            state.jobs.lastOrNull { job ->
                job.operationId !in knownOperationIds &&
                    job.type == type &&
                    job.targetId == targetId &&
                    job.state.isTerminal
            },
        )
    }

    private suspend fun awaitMaintenanceSettled(controller: AndroidStmCoreController): StmCoreState {
        delay(RECOVERY_SETTLE_MILLIS)
        return awaitState(controller) { state ->
            state.installerRecoveryComplete && state.jobs.none { !it.state.isTerminal }
        }
    }

    private suspend fun awaitInterruptedJob(
        controller: AndroidStmCoreController,
        type: StmCoreJobType,
        targetId: String,
    ): StmCoreJob {
        val state = awaitState(controller) { snapshot ->
            snapshot.jobs.any { job ->
                job.type == type &&
                    job.targetId == targetId &&
                    job.state == StmCoreJobState.FAILED &&
                    job.error?.code == "CORE_PROCESS_INTERRUPTED"
            }
        }
        return requireNotNull(
            state.jobs.lastOrNull { job ->
                job.type == type &&
                    job.targetId == targetId &&
                    job.state == StmCoreJobState.FAILED &&
                    job.error?.code == "CORE_PROCESS_INTERRUPTED"
            },
        )
    }

    private suspend fun awaitCoreProcessReplacement(
        controller: AndroidStmCoreController,
        previousProcessId: Int,
        previousRevision: Long,
    ): StmCoreState = awaitState(controller) { state ->
        state.processId != null &&
            state.processId != previousProcessId &&
            state.revision > previousRevision
    }

    private suspend fun awaitStagingRestored(before: Set<String>) {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MILLIS
        while (
            StmCorePaths.stagingRoot(targetContext).safeChildNames() != before &&
            System.currentTimeMillis() < deadline
        ) {
            delay(POLL_MILLIS)
        }
        check(StmCorePaths.stagingRoot(targetContext).safeChildNames() == before) {
            "Interrupted install staging was not cleaned during recovery"
        }
    }

    private suspend fun awaitVerifiedTemporaryRemoved(operationId: String) {
        val verifiedTemporary = File(
            StmCorePaths.installerCacheRoot(targetContext),
            "$operationId.verified.part",
        )
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MILLIS
        while (verifiedTemporary.exists() && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
        }
        check(!verifiedTemporary.exists()) {
            "Interrupted install left a Core-owned verified temporary artifact"
        }
    }

    private suspend fun awaitState(
        controller: AndroidStmCoreController,
        predicate: (StmCoreState) -> Boolean,
    ): StmCoreState {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MILLIS
        while (!predicate(controller.state.value) && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
        }
        check(predicate(controller.state.value)) {
            "Timed out waiting for Gate 2 Core state; current=${controller.state.value}"
        }
        return controller.state.value
    }

    private suspend fun runExperiment(experiment: StmCoreExperiment): ExperimentOutcome {
        val deferred = CompletableDeferred<ExperimentOutcome>()
        val teardown = CompletableDeferred<Unit>()
        lateinit var client: StmCoreExperimentClient
        val listener = object : StmCoreExperimentListener {
            private var processId: Int? = null

            override fun onExperimentServiceReady(processId: Int) {
                this.processId = processId
            }

            override fun onExperimentResult(result: StmCoreExperimentResult) {
                if (result.experiment != experiment) {
                    deferred.completeExceptionally(
                        IllegalStateException(
                            "Expected $experiment but received ${result.experiment}",
                        ),
                    )
                    return
                }
                deferred.complete(
                    ExperimentOutcome.Completed(
                        result,
                        requireNotNull(processId) { "Experiment process ID was not reported" },
                    ),
                )
            }

            override fun onExperimentTeardown(requestId: String) {
                teardown.complete(Unit)
            }

            override fun onExperimentServiceDisconnected() {
                deferred.complete(
                    ExperimentOutcome.Disconnected(
                        requireNotNull(processId) { "Experiment process ID was not reported" },
                    ),
                )
            }
        }
        onMain {
            client = StmCoreExperimentClient(targetContext, listener)
            check(client.run(experiment)) { "Could not bind the debug experiment service" }
        }
        var teardownWaitStarted = false

        suspend fun cancelAndAwaitTeardown() {
            teardownWaitStarted = true
            val cancellationAccepted = onMain { client.cancelPending() }
            check(cancellationAccepted || teardown.isCompleted) {
                "Could not request $experiment teardown"
            }
            check(
                withTimeoutOrNull(EXPERIMENT_TEARDOWN_TIMEOUT_MILLIS) {
                    teardown.await()
                } != null,
            ) {
                "Timed out waiting for $experiment teardown acknowledgement"
            }
        }

        return try {
            withTimeout(EXPERIMENT_TIMEOUT_MILLIS) { deferred.await() }.also { outcome ->
                if (
                    outcome is ExperimentOutcome.Completed &&
                    !outcome.result.teardownComplete
                ) {
                    cancelAndAwaitTeardown()
                }
            }
        } finally {
            if (!teardown.isCompleted && !teardownWaitStarted) {
                cancelAndAwaitTeardown()
            }
            if (teardown.isCompleted) {
                onMain { client.disconnect() }
            }
        }
    }

    private fun requireCompleted(
        outcome: ExperimentOutcome,
        controller: AndroidStmCoreController,
        label: String,
    ): ExperimentOutcome.Completed {
        check(outcome is ExperimentOutcome.Completed) {
            "$label disconnected Core process ${outcome.processId}"
        }
        check(outcome.processId == controller.state.value.processId) {
            "$label did not run inside the current private Core process"
        }
        check(outcome.result.values["result"] != "java_exception") {
            "$label failed: ${outcome.result.values}"
        }
        return outcome
    }

    private fun Map<String, String>.toFixture(prefix: String): Gate2CachedFixture {
        fun required(name: String): String = requireNotNull(this["${prefix}_$name"]) {
            "Gate 2 fixture metadata omitted ${prefix}_$name"
        }
        fun optional(name: String): String? = required(name).ifBlank { null }
        return Gate2CachedFixture(
            cacheFileName = required("cache_file_name"),
            artifact = StmCoreArtifact(
                kind = StmCoreArtifactKind.valueOf(required("kind")),
                repository = required("repository"),
                channel = required("channel"),
                commitSha = required("commit_sha"),
                downloadUrl = required("download_url"),
                downloadedAtEpochMs = required("downloaded_at_epoch_ms").toLong(),
                archiveLength = required("archive_length").toLong(),
                archiveSha256 = required("archive_sha256"),
                integrity = StmCoreArtifactIntegrity.valueOf(required("integrity")),
                trust = StmCoreArtifactTrust.valueOf(required("trust")),
                catalogVersion = optional("catalog_version"),
                archiveRoot = optional("archive_root"),
                stVersion = optional("st_version"),
                nodeRequirement = optional("node_requirement"),
                packageLockSha256 = optional("package_lock_sha256"),
                licenseStatus = optional("license_status"),
            ),
        )
    }

    private fun File.safeChildNames(): Set<String> =
        if (!isDirectory) emptySet() else listFiles().orEmpty().mapTo(mutableSetOf()) { it.name }

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                value.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return value.get()
    }

    private val StmCoreJobState.isTerminal: Boolean
        get() = this == StmCoreJobState.SUCCEEDED ||
            this == StmCoreJobState.FAILED ||
            this == StmCoreJobState.CANCELLED

    private data class Gate2CachedFixture(
        val cacheFileName: String,
        val artifact: StmCoreArtifact,
    )

    private sealed interface ExperimentOutcome {
        val processId: Int

        data class Completed(
            val result: StmCoreExperimentResult,
            override val processId: Int,
        ) : ExperimentOutcome

        data class Disconnected(override val processId: Int) : ExperimentOutcome
    }

    private companion object {
        // Four deliberate Core deaths can make Android's bound-service restart backoff reach 64s.
        const val STATE_TIMEOUT_MILLIS = 90_000L
        const val EXPERIMENT_TIMEOUT_MILLIS = 20_000L
        const val EXPERIMENT_TEARDOWN_TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 50L
        const val RECOVERY_SETTLE_MILLIS = 500L
        const val EXACT_COMMIT_LENGTH = 40
        const val INVALID_IMPORT_BATCH_SIZE = 4
        const val FD_COUNT_DRIFT_TOLERANCE = 1
        const val FD_COUNT_BASELINE_TOLERANCE = 3
        const val FIXED_ZIP_TIME_EPOCH_MS = 315_532_800_000L
        const val GATE2_MARKER = "STM_GATE2_SYNTHETIC_V1\n"
        const val DATA_SENTINEL = "gate2-user-data-must-survive\n"
        const val ST_FIXTURE_VERSION = "9.9.9-gate2"
        const val ST_FIXTURE_NODE_REQUIREMENT = ">=20.0.0"
        const val ST_FIXTURE_SERVER = "'use strict';\nconsole.log('gate2-preflight-only');\n"
        const val ST_FIXTURE_PACKAGE_JSON =
            "{\"name\":\"sillytavern\",\"version\":\"$ST_FIXTURE_VERSION\"," +
                "\"engines\":{\"node\":\"$ST_FIXTURE_NODE_REQUIREMENT\"}}\n"
        const val ST_FIXTURE_PACKAGE_LOCK =
            "{\"name\":\"sillytavern\",\"version\":\"$ST_FIXTURE_VERSION\"," +
                "\"lockfileVersion\":3,\"requires\":true,\"packages\":{}}\n"
        const val ST_FIXTURE_LICENSE = "Synthetic Gate 2 inspection fixture.\n"
    }
}
