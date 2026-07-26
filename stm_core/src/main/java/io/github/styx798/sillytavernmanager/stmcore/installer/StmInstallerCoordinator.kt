package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.StmCoreActiveSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import io.github.styx798.sillytavernmanager.stmcore.StmCoreError
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJob
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobPhase
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlot
import io.github.styx798.sillytavernmanager.stmcore.StmCoreSlotState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreTransferProgress
import io.github.styx798.sillytavernmanager.stmcore.StmCoreWaitKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreWaitPrompt
import io.github.styx798.sillytavernmanager.stmcore.requireValidArtifact
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal sealed interface StmInstallerEvent {
    data class JobChanged(val job: StmCoreJob) : StmInstallerEvent

    /** A replay from durable installer evidence; terminal checkpoints must not be reversed. */
    data class RecoveredTerminalJob(val job: StmCoreJob) : StmInstallerEvent

    data class SlotChanged(val slot: StmCoreSlot) : StmInstallerEvent

    data class SlotRemoved(val slotId: String) : StmInstallerEvent

    data class SlotsReconciled(val slots: List<StmCoreSlot>) : StmInstallerEvent

    data class ActiveChanged(val active: StmCoreActiveSlot?) : StmInstallerEvent

    data class RecoveryEvidence(val error: StmCoreError) : StmInstallerEvent

    data class RecoveryComplete(val successful: Boolean) : StmInstallerEvent

    data class WaitPromptChanged(val prompt: StmCoreWaitPrompt?) : StmInstallerEvent

    data class RuntimeTransferChanged(val progress: StmCoreTransferProgress?) : StmInstallerEvent

    data class ArtifactVerified(
        val targetId: String,
        val artifact: StmCoreArtifact,
    ) : StmInstallerEvent
}

internal sealed interface StmInstallerSubmission {
    data object Accepted : StmInstallerSubmission

    data class Rejected(val code: String, val detail: String) : StmInstallerSubmission
}

internal enum class StmInstallerCoordinatorFailpoint {
    BEFORE_INSTALL_EXTRACTION,
    BEFORE_INSTALL_COMMIT_POINT,
    BEFORE_ACTIVE_POINTER_WRITE,
    ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
    ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC,
    BEFORE_REMOVE_COMMIT_POINT,
    AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT,
}

internal fun interface StmInstallerCoordinatorFaultInjector {
    fun hit(failpoint: StmInstallerCoordinatorFailpoint)
}

/**
 * Serializes all maintenance writes while Feather Engine keeps exclusive ownership of Javet.
 * The event sink must transfer immutable events to the Core state owner; coordinator I/O never
 * mutates the public snapshot directly.
 */
internal class StmInstallerCoordinator(
    private val installerCacheRoot: File,
    private val stagingRoot: File,
    slotsRoot: File,
    activeFile: File,
    journalRoot: File,
    private val eventSink: (StmInstallerEvent) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "STM-Core-Installer").apply { isDaemon = true }
    },
    private val artifactVerifier: StmArtifactVerifier = StmArtifactVerifier(),
    private val zipExtractor: StmSafeZipExtractor = StmSafeZipExtractor(),
    private val sourceInspector: StmSillyTavernSourceInspector =
        StmSillyTavernSourceInspector(),
    private val runtimeSlotPreparer: StmRuntimeSlotPreparer? = null,
    private val faultInjector: StmInstallerCoordinatorFaultInjector =
        StmInstallerCoordinatorFaultInjector { },
    private val checkpointTerminalOperationIds: Set<String> = emptySet(),
) : AutoCloseable {
    private val slotStore = StmSlotStore(slotsRoot, stagingRoot)
    private val activeStore = StmActiveSlotStore(
        activeFile = activeFile,
        faultInjector = StmActiveSlotFaultInjector { failpoint ->
            when (failpoint) {
                StmActiveSlotFailpoint.BEFORE_WRITE -> Unit
                StmActiveSlotFailpoint.ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
                StmActiveSlotFailpoint.ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC,
                -> faultInjector.hit(StmInstallerCoordinatorFailpoint.valueOf(failpoint.name))
            }
        },
    )
    private val journalStore = StmInstallerJournalStore(journalRoot)
    private val recoveryExecutor = StmInstallerRecoveryExecutor(
        coreRoot = requireNotNull(stagingRoot.parentFile) { "Staging root must have a Core parent" },
        journalStore = journalStore,
    )
    private val lock = Any()
    private var activeOperation: ActiveOperation? = null
    @Volatile
    private var recoveryComplete = false
    private var recoveryStarted = false

    init {
        checkpointTerminalOperationIds.forEach(::requireCanonicalUuid)
        initializeOwnedDirectory(installerCacheRoot)
        initializeOwnedDirectory(stagingRoot)
    }

    fun installCachedArtifact(
        operationId: String,
        slotId: String,
        slotRevision: Long,
        cacheFileName: String,
        requestedArtifact: StmCoreArtifact,
        installMode: StmCoreInstallMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
    ): StmInstallerSubmission {
        val problem = runCatching {
            requireCanonicalUuid(operationId)
            requireSafeId(slotId)
            require(slotRevision > 0) { "Slot revision must be positive" }
            require(cacheFileName.matches(CACHE_FILE_PATTERN)) { "Cache file name is invalid" }
            requestedArtifact.requireValidArtifact()
        }.exceptionOrNull()
        if (problem != null) {
            return StmInstallerSubmission.Rejected("INVALID_INSTALL_REQUEST", problem.safeDetail())
        }
        return submit(operationId) { control ->
            runInstall(
                control = control,
                slotId = slotId,
                slotRevision = slotRevision,
                cacheFileName = cacheFileName,
                importedSource = null,
                requestedArtifact = requestedArtifact,
                preflightOnly = false,
                installMode = installMode,
            )
        }
    }

    fun verifyImportedArtifact(
        operationId: String,
        targetId: String,
        source: InputStream,
        requestedArtifact: StmCoreArtifact,
    ): StmInstallerSubmission {
        val problem = runCatching {
            requireCanonicalUuid(operationId)
            requireSafeId(targetId)
            requestedArtifact.requireValidArtifact()
        }.exceptionOrNull()
        if (problem != null) {
            runCatching { source.close() }
            return StmInstallerSubmission.Rejected("INVALID_IMPORT_REQUEST", problem.safeDetail())
        }
        val submission = submit(operationId) { control ->
            try {
                runInstall(
                    control = control,
                    slotId = targetId,
                    slotRevision = 1,
                    cacheFileName = null,
                    importedSource = source,
                    requestedArtifact = requestedArtifact,
                    preflightOnly = true,
                    installMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
                )
            } finally {
                runCatching { source.close() }
            }
        }
        if (submission is StmInstallerSubmission.Rejected) runCatching { source.close() }
        return submission
    }

    fun installImportedArtifact(
        operationId: String,
        slotId: String,
        slotRevision: Long,
        source: InputStream,
        requestedArtifact: StmCoreArtifact,
        installMode: StmCoreInstallMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
    ): StmInstallerSubmission {
        val problem = runCatching {
            requireCanonicalUuid(operationId)
            requireSafeId(slotId)
            require(slotRevision > 0) { "Slot revision must be positive" }
            requestedArtifact.requireValidArtifact()
        }.exceptionOrNull()
        if (problem != null) {
            runCatching { source.close() }
            return StmInstallerSubmission.Rejected(
                "INVALID_INSTALL_REQUEST",
                problem.safeDetail(),
            )
        }
        val submission = submit(operationId) { control ->
            try {
                runInstall(
                    control = control,
                    slotId = slotId,
                    slotRevision = slotRevision,
                    cacheFileName = null,
                    importedSource = source,
                    requestedArtifact = requestedArtifact,
                    preflightOnly = false,
                    installMode = installMode,
                )
            } finally {
                runCatching { source.close() }
            }
        }
        if (submission is StmInstallerSubmission.Rejected) runCatching { source.close() }
        return submission
    }

    fun activate(
        operationId: String,
        target: StmCoreSlot,
        checkpointActive: StmCoreActiveSlot?,
    ): StmInstallerSubmission {
        if (target.state != StmCoreSlotState.READY || target.artifact == null) {
            return StmInstallerSubmission.Rejected("SLOT_NOT_READY", "Only a READY slot may activate")
        }
        return submit(operationId) { control ->
            runActivate(control, target, checkpointActive, rollback = false)
        }
    }

    fun rollback(
        operationId: String,
        slots: List<StmCoreSlot>,
        checkpointActive: StmCoreActiveSlot?,
    ): StmInstallerSubmission = submit(operationId) { control ->
        val stored = when (val read = activeStore.read()) {
            is StmActiveSlotReadResult.Loaded -> read.stored.pointer
            StmActiveSlotReadResult.Missing -> throw InstallerFailure(
                "ROLLBACK_UNAVAILABLE",
                "No active-slot record exists",
            )

            is StmActiveSlotReadResult.Corrupt -> throw InstallerFailure(
                "ACTIVE_POINTER_CORRUPT",
                read.detail,
            )
        }
        ensureCheckpointMatches(checkpointActive, stored)
        val previous = stored.previous ?: throw InstallerFailure(
            "ROLLBACK_UNAVAILABLE",
            "The active-slot record has no previous READY slot",
        )
        val target = slots.singleOrNull {
            it.id == previous.slotId &&
                it.revision == previous.slotRevision &&
                it.state == StmCoreSlotState.READY
        } ?: throw InstallerFailure("ROLLBACK_SLOT_MISSING", "Previous READY slot is unavailable")
        runActivate(control, target, checkpointActive, rollback = true)
    }

    fun remove(
        operationId: String,
        target: StmCoreSlot,
        active: StmCoreActiveSlot?,
        running: StmCoreActiveSlot?,
    ): StmInstallerSubmission = submit(operationId) { control ->
        runRemove(control, target, active, running)
    }

    fun verifySlot(
        operationId: String,
        target: StmCoreSlot,
        markBrokenOnFailure: Boolean = true,
    ): StmInstallerSubmission = submit(operationId) { control ->
        runVerifySlot(control, target, markBrokenOnFailure)
    }

    fun cancel(targetOperationId: String): Boolean = synchronized(lock) {
        val operation = activeOperation ?: return@synchronized false
        if (operation.operationId != targetOperationId) return@synchronized false
        operation.requestCancel()
    }

    fun continueWaiting(targetOperationId: String): Boolean = synchronized(lock) {
        val operation = activeOperation ?: return@synchronized false
        if (operation.operationId != targetOperationId) return@synchronized false
        operation.continueWaiting()
    }

    fun hasActiveOperation(): Boolean = synchronized(lock) { activeOperation != null }

    /**
     * Read-only post-run verification for the frozen slot lease. Callers must keep it off the
     * Service state thread; the full immutable manifest is intentionally re-read from disk.
     */
    fun verifyCommittedSlot(slotId: String): StmSlotVerificationResult =
        slotStore.verifyCommitted(slotId)

    fun readCommittedSlot(slotId: String): StmSlotVerificationResult =
        slotStore.readCommitted(slotId)

    /** Runs disk reconciliation off the Service main thread. */
    fun recoverAsync() {
        synchronized(lock) {
            check(!recoveryStarted) { "Installer recovery may only start once" }
            recoveryStarted = true
        }
        executor.execute {
            val slotsRecovered = runRecoveryStep("SLOT_RECOVERY_FAILED", ::recoverSlotsAndActive)
            val journalsRecovered = runRecoveryStep("JOURNAL_RECOVERY_FAILED", ::recoverJournals)
            recoveryComplete = journalsRecovered && slotsRecovered
            eventSink(StmInstallerEvent.RecoveryComplete(recoveryComplete))
        }
    }

    override fun close() {
        synchronized(lock) { activeOperation?.requestCancel() }
        executor.shutdownNow()
    }

    private fun runRecoveryStep(code: String, action: () -> Unit): Boolean =
        try {
            action()
            true
        } catch (error: Exception) {
            eventSink(
                StmInstallerEvent.RecoveryEvidence(
                    StmCoreError(
                        "installer_recovery",
                        code,
                        "Core installer recovery failed closed",
                        error.safeDetail(),
                    ),
                ),
            )
            false
        }

    private fun submit(
        operationId: String,
        action: (ActiveOperation) -> Unit,
    ): StmInstallerSubmission {
        val problem = runCatching { requireCanonicalUuid(operationId) }.exceptionOrNull()
        if (problem != null) {
            return StmInstallerSubmission.Rejected("INVALID_OPERATION_ID", problem.safeDetail())
        }
        if (!recoveryComplete) {
            return StmInstallerSubmission.Rejected(
                "CORE_RECOVERING",
                "Core installer recovery has not completed",
            )
        }
        val operation = synchronized(lock) {
            if (activeOperation != null) return StmInstallerSubmission.Rejected(
                "MAINTENANCE_BUSY",
                "Another Core maintenance operation is active",
            )
            ActiveOperation(operationId, eventSink).also { activeOperation = it }
        }
        executor.execute {
            try {
                action(operation)
            } finally {
                operation.finishEphemeralState()
                synchronized(lock) {
                    if (activeOperation === operation) activeOperation = null
                }
            }
        }
        return StmInstallerSubmission.Accepted
    }

    private fun runInstall(
        control: ActiveOperation,
        slotId: String,
        slotRevision: Long,
        cacheFileName: String?,
        importedSource: InputStream?,
        requestedArtifact: StmCoreArtifact,
        preflightOnly: Boolean,
        installMode: StmCoreInstallMode,
    ) {
        val startedAt = System.currentTimeMillis()
        var artifact = requestedArtifact.copy(
            integrity = StmCoreArtifactIntegrity.PENDING,
            trust = when (requestedArtifact.trust) {
                StmCoreArtifactTrust.REJECTED -> StmCoreArtifactTrust.REJECTED
                StmCoreArtifactTrust.TRUSTED_CATALOG,
                StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
                -> StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG
            },
            archiveRoot = requestedArtifact.archiveRoot.takeUnless {
                requestedArtifact.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
            },
            stVersion = requestedArtifact.stVersion.takeUnless {
                requestedArtifact.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
            },
            nodeRequirement = requestedArtifact.nodeRequirement.takeUnless {
                requestedArtifact.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
            },
            packageLockSha256 = requestedArtifact.packageLockSha256.takeUnless {
                requestedArtifact.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
            },
            licenseStatus = requestedArtifact.licenseStatus.takeUnless {
                requestedArtifact.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
            },
        )
        var job = newJob(
            control.operationId,
            if (preflightOnly) StmCoreJobType.VERIFY else StmCoreJobType.INSTALL,
            slotId,
            startedAt,
        )
        val source = cacheFileName?.let(::safeCacheChild)
        val verifiedTemporary = safeCacheChild("${control.operationId}.verified.part")
        val operationRoot = safeStagingChild(control.operationId)
        var ownsTransientSlot = false
        var runtimeEvidence: StmRuntimeSlotAdmissionEvidence? = null

        fun advance(
            journalPhase: StmInstallerJournalPhase,
            jobPhase: StmCoreJobPhase,
            progress: Double?,
            slotState: StmCoreSlotState? = null,
        ) {
            control.throwIfCancelled()
            val now = System.currentTimeMillis()
            journalStore.write(
                StmInstallerJournalRecord(
                    operationId = control.operationId,
                    type = if (preflightOnly) {
                        StmInstallerOperationType.VERIFY
                    } else {
                        StmInstallerOperationType.INSTALL
                    },
                    targetSlotId = slotId,
                    artifactSha256 = requestedArtifact.archiveSha256.lowercase(),
                    phase = journalPhase,
                    stagingRelativeId = control.operationId,
                    startedAtEpochMs = startedAt,
                    updatedAtEpochMs = now,
                    cancelRequested = control.isCancelRequested,
                ),
            )
            job = job.copy(
                phase = jobPhase,
                state = StmCoreJobState.RUNNING,
                updatedAtEpochMs = now,
                progress = progress,
                error = null,
            )
            eventSink(StmInstallerEvent.JobChanged(job))
            slotState?.let { state ->
                eventSink(
                    StmInstallerEvent.SlotChanged(
                        slotSnapshot(slotId, slotRevision, state, artifact, manifest = null),
                    ),
                )
            }
        }

        try {
            if (!preflightOnly) {
                when (val existing = slotStore.readCommitted(slotId)) {
                    StmSlotVerificationResult.Missing -> Unit
                    is StmSlotVerificationResult.Valid -> throw InstallerFailure(
                        "SLOT_ALREADY_EXISTS",
                        "The target slot already exists and is immutable",
                    )

                    is StmSlotVerificationResult.Invalid -> throw InstallerFailure(
                        "SLOT_TARGET_INVALID",
                        "The target slot path already exists but failed verification: ${existing.detail}",
                    )
                }
                ownsTransientSlot = true
            }
            advance(
                StmInstallerJournalPhase.COPYING_ARTIFACT,
                StmCoreJobPhase.COPYING_ARTIFACT,
                0.05,
                StmCoreSlotState.STAGING.takeUnless { preflightOnly },
            )
            if (requestedArtifact.trust == StmCoreArtifactTrust.REJECTED) {
                throw InstallerFailure("ARTIFACT_TRUST_REJECTED", "Artifact trust was rejected")
            }
            if (requestedArtifact.trust == StmCoreArtifactTrust.TRUSTED_CATALOG) {
                throw InstallerFailure(
                    "CATALOG_PROOF_REQUIRED",
                    "IPC trust claims are not accepted without Core-side catalog proof",
                )
            }
            source?.let { requireRegularNoFollow(it, "Cached artifact") }
            val identity = requestedArtifact.toVerifierIdentity()
            val identityResult = artifactVerifier.validateIdentity(identity)
            if (identityResult is ArtifactIdentityValidation.Invalid) {
                throw InstallerFailure(identityResult.code.name, identityResult.detail)
            }
            val artifactInput = importedSource
                ?: Files.newInputStream(requireNotNull(source).toPath(), LinkOption.NOFOLLOW_LINKS)
            artifactInput.use { input ->
                when (
                    val integrity = artifactVerifier.verifyAndCopy(
                        identity,
                        input,
                        verifiedTemporary,
                    )
                ) {
                    is ArtifactIntegrityResult.Verified -> {
                        artifact = artifact.copy(
                            archiveLength = integrity.archiveLength,
                            archiveSha256 = integrity.archiveSha256,
                            integrity = StmCoreArtifactIntegrity.VERIFIED,
                            trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
                        )
                    }

                    is ArtifactIntegrityResult.Rejected -> throw InstallerFailure(
                        integrity.code.name,
                        integrity.detail,
                    )
                }
            }

            advance(
                StmInstallerJournalPhase.PREFLIGHT,
                StmCoreJobPhase.PREFLIGHT,
                0.2,
                StmCoreSlotState.VERIFYING.takeUnless { preflightOnly },
            )
            faultInjector.hit(StmInstallerCoordinatorFailpoint.BEFORE_INSTALL_EXTRACTION)
            val extraction = zipExtractor.extract(
                artifact = verifiedTemporary,
                operationStagingRoot = operationRoot,
                cancellation = StmExtractionCancellation {
                    control.isCancelRequested || Thread.currentThread().isInterrupted
                },
            )
            check(extraction.payloadDirectory == slotStore.operationPayloadDirectory(control.operationId)) {
                "Extractor and slot store disagreed about the operation payload"
            }

            if (artifact.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE) {
                artifact = when (
                    val inspection = sourceInspector.inspect(
                        payloadDirectory = extraction.payloadDirectory,
                        expectedExactCommit = artifact.commitSha,
                    )
                ) {
                    is StmSillyTavernSourceInspectionResult.Accepted -> artifact.copy(
                        archiveRoot = inspection.evidence.archiveRoot,
                        stVersion = inspection.evidence.stVersion,
                        nodeRequirement = inspection.evidence.nodeRequirement,
                        packageLockSha256 = inspection.evidence.packageLockSha256,
                        licenseStatus = inspection.evidence.licenseStatus,
                    )

                    is StmSillyTavernSourceInspectionResult.Rejected -> throw InstallerFailure(
                        code = "ST_SOURCE_${inspection.code.name}",
                        message = buildString {
                            append(inspection.detail)
                            inspection.relativePath?.let { append(" [").append(it).append(']') }
                        },
                    )
                }
            }

            if (!preflightOnly &&
                artifact.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE &&
                runtimeSlotPreparer != null
            ) {
                val archiveRoot = requireNotNull(artifact.archiveRoot)
                val stVersion = requireNotNull(artifact.stVersion)
                val packageLockSha256 = requireNotNull(artifact.packageLockSha256)
                runtimeEvidence = runtimeSlotPreparer.prepare(
                    request = StmRuntimeSlotPreparationRequest(
                        operationId = control.operationId,
                        operationRoot = operationRoot,
                        payloadDirectory = extraction.payloadDirectory,
                        archiveRoot = archiveRoot,
                        repository = artifact.repository,
                        commitSha = artifact.commitSha.lowercase(),
                        stVersion = stVersion,
                        packageLockSha256 = packageLockSha256,
                        installMode = installMode,
                        sourceEntries = extraction.entries,
                        observer = control.observer,
                    ),
                    cancellation = StmExtractionCancellation {
                        control.isCancelRequested || Thread.currentThread().isInterrupted
                    },
                    onPhase = { phase ->
                        val (journalPhase, jobPhase, progress) = when (phase) {
                            StmRuntimeSlotPreparationPhase.DOWNLOADING_RUNTIME_LAYER ->
                                Triple(
                                    StmInstallerJournalPhase.DOWNLOADING_RUNTIME_LAYER,
                                    StmCoreJobPhase.DOWNLOADING_RUNTIME_LAYER,
                                    0.28,
                                )

                            StmRuntimeSlotPreparationPhase.VERIFYING_RUNTIME_LAYER ->
                                Triple(
                                    StmInstallerJournalPhase.VERIFYING_RUNTIME_LAYER,
                                    StmCoreJobPhase.VERIFYING_RUNTIME_LAYER,
                                    0.42,
                                )

                            StmRuntimeSlotPreparationPhase.PREPARING_TOOLCHAIN ->
                                Triple(
                                    StmInstallerJournalPhase.PREPARING_TOOLCHAIN,
                                    StmCoreJobPhase.PREPARING_TOOLCHAIN,
                                    0.28,
                                )

                            StmRuntimeSlotPreparationPhase.INSTALLING_DEPENDENCIES ->
                                Triple(
                                    StmInstallerJournalPhase.INSTALLING_DEPENDENCIES,
                                    StmCoreJobPhase.INSTALLING_DEPENDENCIES,
                                    0.38,
                                )

                            StmRuntimeSlotPreparationPhase.BUILDING_BUNDLE ->
                                Triple(
                                    StmInstallerJournalPhase.BUILDING_BUNDLE,
                                    StmCoreJobPhase.BUILDING_BUNDLE,
                                    0.55,
                                )

                            StmRuntimeSlotPreparationPhase.ASSEMBLING_RUNTIME ->
                                Triple(
                                    StmInstallerJournalPhase.ASSEMBLING_RUNTIME,
                                    StmCoreJobPhase.ASSEMBLING_RUNTIME,
                                    0.68,
                                )

                            StmRuntimeSlotPreparationPhase.RUNNABLE_ACCEPTANCE ->
                                Triple(
                                    StmInstallerJournalPhase.RUNNABLE_ACCEPTANCE,
                                    StmCoreJobPhase.RUNNABLE_ACCEPTANCE,
                                    0.78,
                                )
                        }
                        advance(
                            journalPhase = journalPhase,
                            jobPhase = jobPhase,
                            progress = progress,
                            slotState = StmCoreSlotState.VERIFYING,
                        )
                    },
                )
            }

            advance(
                StmInstallerJournalPhase.VERIFYING,
                StmCoreJobPhase.VALIDATING,
                0.82,
                StmCoreSlotState.VERIFYING.takeUnless { preflightOnly },
            )
            if (preflightOnly) {
                when (val outcome = slotStore.prepareAndCommit(artifact.toSlotCommitRequest(
                    operationId = control.operationId,
                    slotId = slotId,
                    slotRevision = slotRevision,
                ))) {
                    is StmSlotCommitOutcome.VerifiedNotReady -> {
                        control.enterCommitPoint()
                        job = job.copy(artifact = artifact)
                        val verifiedDurably = finishJournal(
                            control,
                            job,
                            StmInstallerJournalPhase.COMPLETE,
                            StmCoreJobState.SUCCEEDED,
                        )
                        if (verifiedDurably) {
                            eventSink(StmInstallerEvent.ArtifactVerified(slotId, artifact))
                        }
                        return
                    }

                    is StmSlotCommitOutcome.Ready -> throw InstallerFailure(
                        "VERIFY_UNEXPECTED_READY",
                        "A source-only verification cannot create a READY slot",
                    )

                    is StmSlotCommitOutcome.Blocked -> throw InstallerFailure(
                        outcome.code.name,
                        outcome.detail,
                    )
                }
            }
            faultInjector.hit(StmInstallerCoordinatorFailpoint.BEFORE_INSTALL_COMMIT_POINT)
            control.enterCommitPoint()
            advance(
                StmInstallerJournalPhase.COMMITTING,
                StmCoreJobPhase.COMMITTING_SLOT,
                0.9,
            )
            val outcome = slotStore.prepareAndCommit(
                artifact.toSlotCommitRequest(
                    control.operationId,
                    slotId,
                    slotRevision,
                    runtimeEvidence,
                ),
            )
            val ready = when (outcome) {
                is StmSlotCommitOutcome.Ready -> outcome.slot
                is StmSlotCommitOutcome.VerifiedNotReady -> throw InstallerFailure(
                    "STAGE3_DEPENDENCIES_REQUIRED",
                    outcome.reason,
                )

                is StmSlotCommitOutcome.Blocked -> throw InstallerFailure(
                    outcome.code.name,
                    outcome.detail,
                )
            }
            val readySlot = slotSnapshot(
                slotId = slotId,
                slotRevision = slotRevision,
                state = StmCoreSlotState.READY,
                artifact = artifact,
                manifest = ready.manifest,
            )
            eventSink(StmInstallerEvent.SlotChanged(readySlot))
            finishJournal(control, job, StmInstallerJournalPhase.COMPLETE, StmCoreJobState.SUCCEEDED)
        } catch (cancelled: OperationCancelled) {
            failInstall(
                control,
                job,
                slotId,
                slotRevision,
                artifact,
                "OPERATION_CANCELLED",
                "Installation was cancelled",
                cancelled = true,
                reconcileTransientSlot = ownsTransientSlot,
            )
        } catch (error: Exception) {
            val runtimeCancelled =
                (error as? StmRuntimeSlotPreparationException)?.code ==
                    StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED
            if (control.isCancelRequested &&
                (
                    (error as? StmZipExtractionException)?.code ==
                        StmZipErrorCode.OPERATION_CANCELLED ||
                        runtimeCancelled
                    )
            ) {
                failInstall(
                    control,
                    job,
                    slotId,
                    slotRevision,
                    artifact,
                    "OPERATION_CANCELLED",
                    "Installation or verification was cancelled",
                    cancelled = true,
                    reconcileTransientSlot = ownsTransientSlot,
                )
                return
            }
            val failure = error as? InstallerFailure
            val runtimeFailure = error as? StmRuntimeSlotPreparationException
            failInstall(
                control,
                job,
                slotId,
                slotRevision,
                artifact,
                failure?.code ?: runtimeFailure?.code?.name ?: "INSTALL_IO_FAILURE",
                failure?.message ?: error.safeDetail(),
                cancelled = false,
                reconcileTransientSlot = ownsTransientSlot,
            )
        } finally {
            runCatching { importedSource?.close() }.onFailure { error ->
                emitCleanupFailure(
                    "IMPORT_SOURCE_CLOSE_FAILED",
                    "The imported source descriptor did not close cleanly",
                    error,
                )
            }
            runCatching { deleteVerifiedCopy(control.operationId) }.onFailure { error ->
                emitCleanupFailure(
                    "VERIFIED_COPY_CLEANUP_FAILED",
                    "Verified temporary bytes were retained for recovery",
                    error,
                )
            }
            runCatching { cleanupStagingOperation(operationRoot) }.onFailure { error ->
                emitCleanupFailure(
                    "STAGING_CLEANUP_FAILED",
                    "Installer staging was retained for recovery",
                    error,
                )
            }
        }
    }

    private fun runActivate(
        control: ActiveOperation,
        target: StmCoreSlot,
        checkpointActive: StmCoreActiveSlot?,
        rollback: Boolean,
    ) {
        val startedAt = System.currentTimeMillis()
        var job = newJob(
            operationId = control.operationId,
            type = if (rollback) StmCoreJobType.ROLLBACK else StmCoreJobType.ACTIVATE,
            targetId = target.id,
            startedAt = startedAt,
        )
        try {
            job = beginSimpleJournal(control, job, target, startedAt)
            val committed = when (val verification = slotStore.readCommitted(target.id)) {
                is StmSlotVerificationResult.Valid -> verification.slot
                StmSlotVerificationResult.Missing -> throw InstallerFailure(
                    "SLOT_MISSING",
                    "The READY slot is missing from disk",
                )

                is StmSlotVerificationResult.Invalid -> throw InstallerFailure(
                    "SLOT_INVALID",
                    verification.detail,
                )
            }
            if (committed.metadata.slotRevision != target.revision ||
                committed.manifest.manifestSha256 != target.manifestSha256
            ) {
                throw InstallerFailure(
                    "SLOT_SNAPSHOT_DIVERGED",
                    "The READY slot no longer matches the public Core snapshot",
                )
            }
            val current = when (val read = activeStore.read()) {
                StmActiveSlotReadResult.Missing -> null
                is StmActiveSlotReadResult.Loaded -> read.stored.pointer
                is StmActiveSlotReadResult.Corrupt -> throw InstallerFailure(
                    "ACTIVE_POINTER_CORRUPT",
                    read.detail,
                )
            }
            if (current != null) {
                ensureCheckpointMatches(checkpointActive, current)
            } else if (checkpointActive != null) {
                throw InstallerFailure(
                    "ACTIVE_POINTER_DIVERGED",
                    "Checkpoint references an active slot but the authoritative record is missing",
                )
            }
            if (current?.current?.slotId == target.id &&
                current.current.slotRevision == target.revision
            ) {
                throw InstallerFailure("SLOT_ALREADY_ACTIVE", "The target slot is already active")
            }
            val next = StmActiveSlotPointer(
                current = StmActiveSlotRef(target.id, target.revision),
                previous = current?.current,
                activeRevision = current?.activeRevision?.plus(1) ?: 1,
                operationId = control.operationId,
            )
            control.enterCommitPoint()
            faultInjector.hit(StmInstallerCoordinatorFailpoint.BEFORE_ACTIVE_POINTER_WRITE)
            activeStore.write(next)
            val active = StmCoreActiveSlot(
                slotId = target.id,
                slotRevision = target.revision,
                activeRevision = next.activeRevision,
            )
            eventSink(StmInstallerEvent.ActiveChanged(active))
            finishJournal(
                control,
                job,
                StmInstallerJournalPhase.COMPLETE,
                StmCoreJobState.SUCCEEDED,
                activeRevision = next.activeRevision,
            )
        } catch (error: Exception) {
            failSimple(control, job, error)
        }
    }

    private fun runRemove(
        control: ActiveOperation,
        target: StmCoreSlot,
        active: StmCoreActiveSlot?,
        running: StmCoreActiveSlot?,
    ) {
        val startedAt = System.currentTimeMillis()
        var job = newJob(control.operationId, StmCoreJobType.REMOVE, target.id, startedAt)
        try {
            job = beginSimpleJournal(control, job, target, startedAt)
            val stored = when (val read = activeStore.read()) {
                StmActiveSlotReadResult.Missing -> null
                is StmActiveSlotReadResult.Loaded -> read.stored.pointer
                is StmActiveSlotReadResult.Corrupt -> throw InstallerFailure(
                    "ACTIVE_POINTER_CORRUPT",
                    read.detail,
                )
            }
            if (stored != null) {
                ensureCheckpointMatches(active, stored)
            } else if (active != null) {
                throw InstallerFailure(
                    "ACTIVE_POINTER_DIVERGED",
                    "Checkpoint references an active slot but the authoritative record is missing",
                )
            }
            val previous = stored?.previous
            if (previous?.slotId == target.id && previous.slotRevision == target.revision) {
                throw InstallerFailure(
                    "SLOT_REFERENCED",
                    "The slot is retained as the rollback target",
                )
            }
            faultInjector.hit(StmInstallerCoordinatorFailpoint.BEFORE_REMOVE_COMMIT_POINT)
            control.enterCommitPoint()
            when (
                val result = slotStore.deleteSlot(
                    target.id,
                    stored?.current?.let { StmSlotReference(it.slotId, it.slotRevision) },
                    running?.let { StmSlotReference(it.slotId, it.slotRevision) },
                )
            ) {
                StmSlotDeleteResult.Deleted -> eventSink(StmInstallerEvent.SlotRemoved(target.id))
                StmSlotDeleteResult.Missing -> throw InstallerFailure(
                    "SLOT_MISSING",
                    "The slot no longer exists",
                )

                is StmSlotDeleteResult.RejectedReferenced -> throw InstallerFailure(
                    "SLOT_REFERENCED",
                    "The slot is still ${result.reference}",
                )
            }
            finishJournal(control, job, StmInstallerJournalPhase.COMPLETE, StmCoreJobState.SUCCEEDED)
        } catch (error: Exception) {
            failSimple(control, job, error)
        }
    }

    private fun runVerifySlot(
        control: ActiveOperation,
        target: StmCoreSlot,
        markBrokenOnFailure: Boolean,
    ) {
        val startedAt = System.currentTimeMillis()
        var job = newJob(control.operationId, StmCoreJobType.VERIFY, target.id, startedAt)
            .copy(artifact = target.artifact)
        try {
            job = beginSimpleJournal(control, job, target, startedAt)
            val committed = when (val verification = slotStore.verifyCommitted(target.id)) {
                is StmSlotVerificationResult.Valid -> verification.slot
                StmSlotVerificationResult.Missing -> throw InstallerFailure(
                    "SLOT_MISSING",
                    "The selected slot is missing from disk",
                )

                is StmSlotVerificationResult.Invalid -> {
                    if (markBrokenOnFailure) {
                        eventSink(
                            StmInstallerEvent.SlotChanged(
                                target.copy(state = StmCoreSlotState.BROKEN),
                            ),
                        )
                    }
                    throw InstallerFailure("SLOT_CONTENT_INVALID", verification.detail)
                }
            }
            if (
                committed.metadata.slotRevision != target.revision ||
                committed.manifest.manifestSha256 != target.manifestSha256
            ) {
                throw InstallerFailure(
                    "SLOT_SNAPSHOT_DIVERGED",
                    "The verified slot no longer matches the public Core snapshot",
                )
            }
            eventSink(StmInstallerEvent.SlotChanged(committed.toCoreSlot()))
            finishJournal(
                control,
                job,
                StmInstallerJournalPhase.COMPLETE,
                StmCoreJobState.SUCCEEDED,
            )
        } catch (error: Exception) {
            failSimple(control, job, error)
        }
    }

    private fun beginSimpleJournal(
        control: ActiveOperation,
        job: StmCoreJob,
        target: StmCoreSlot,
        startedAt: Long,
    ): StmCoreJob {
        control.throwIfCancelled()
        val now = System.currentTimeMillis()
        journalStore.write(
            StmInstallerJournalRecord(
                operationId = control.operationId,
                type = job.type.toJournalType(),
                targetSlotId = target.id,
                artifactSha256 = target.artifact?.archiveSha256?.lowercase() ?: ZERO_SHA256,
                phase = StmInstallerJournalPhase.RUNNING,
                stagingRelativeId = control.operationId,
                startedAtEpochMs = startedAt,
                updatedAtEpochMs = now,
                cancelRequested = false,
            ),
        )
        return job.copy(
            phase = when (job.type) {
                StmCoreJobType.REMOVE -> StmCoreJobPhase.REMOVING_SLOT
                StmCoreJobType.VERIFY -> StmCoreJobPhase.VALIDATING
                else -> StmCoreJobPhase.SWITCHING_ACTIVE
            },
            state = StmCoreJobState.RUNNING,
            updatedAtEpochMs = now,
            progress = null,
        ).also { eventSink(StmInstallerEvent.JobChanged(it)) }
    }

    private fun finishJournal(
        control: ActiveOperation,
        job: StmCoreJob,
        phase: StmInstallerJournalPhase,
        state: StmCoreJobState,
        activeRevision: Long? = null,
    ): Boolean {
        val now = System.currentTimeMillis()
        val journalFailure = runCatching {
            val current = when (val read = journalStore.read(control.operationId)) {
                is StmInstallerJournalReadResult.Loaded -> read.stored.record
                StmInstallerJournalReadResult.Missing -> throw IOException(
                    "Installer journal disappeared before completion",
                )

                is StmInstallerJournalReadResult.Corrupt -> throw IOException(
                    "Installer journal became corrupt before completion: ${read.evidence.detail}",
                )
            }
            journalStore.write(
                current.copy(
                    phase = phase,
                    updatedAtEpochMs = now,
                    terminalReceipt = StmInstallerTerminalReceipt(
                        jobPhase = StmCoreJobPhase.COMPLETE,
                        jobState = state,
                        artifact = if (job.type == StmCoreJobType.VERIFY) {
                            requireNotNull(job.artifact) {
                                "Completed verification is missing its Core-derived artifact receipt"
                            }
                        } else {
                            null
                        },
                        activeRevision = activeRevision,
                    ),
                ),
            )
        }.exceptionOrNull()
        if (journalFailure == null) {
            faultInjector.hit(
                StmInstallerCoordinatorFailpoint
                    .AFTER_TERMINAL_JOURNAL_COMMIT_BEFORE_JOB_EVENT,
            )
        }
        val terminalState = if (journalFailure == null) state else StmCoreJobState.FAILED
        eventSink(
            StmInstallerEvent.JobChanged(
                job.copy(
                    phase = StmCoreJobPhase.COMPLETE,
                    state = terminalState,
                    updatedAtEpochMs = now,
                    progress = if (terminalState == StmCoreJobState.SUCCEEDED) 1.0 else null,
                    error = if (terminalState == StmCoreJobState.FAILED) {
                        StmCoreError(
                            "installer_journal",
                            "JOURNAL_FINALIZE_FAILED",
                            "The maintenance result was not durable in its terminal journal",
                            journalFailure?.safeDetail(),
                        )
                    } else {
                        null
                    },
                ),
            ),
        )
        journalFailure?.let { error ->
            eventSink(
                StmInstallerEvent.RecoveryEvidence(
                    StmCoreError(
                        "installer_journal",
                        "JOURNAL_FINALIZE_FAILED",
                        "The terminal maintenance result could not be persisted",
                        error.safeDetail(),
                    ),
                ),
            )
        }
        return journalFailure == null && terminalState == StmCoreJobState.SUCCEEDED
    }

    private fun failInstall(
        control: ActiveOperation,
        job: StmCoreJob,
        slotId: String,
        slotRevision: Long,
        artifact: StmCoreArtifact,
        code: String,
        detail: String,
        cancelled: Boolean,
        reconcileTransientSlot: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val error = StmCoreError("installer", code, detail.take(200), detail.take(500))
        if (reconcileTransientSlot) {
            when (val committed = slotStore.readCommitted(slotId)) {
                StmSlotVerificationResult.Missing ->
                    eventSink(StmInstallerEvent.SlotRemoved(slotId))

                is StmSlotVerificationResult.Invalid -> eventSink(
                    StmInstallerEvent.SlotChanged(
                        slotSnapshot(
                            slotId,
                            slotRevision,
                            StmCoreSlotState.BROKEN,
                            artifact,
                            manifest = null,
                        ),
                    ),
                )

                is StmSlotVerificationResult.Valid ->
                    eventSink(StmInstallerEvent.SlotChanged(committed.slot.toCoreSlot()))
            }
        }
        val terminalJob = job.copy(
            phase = StmCoreJobPhase.CLEANING_UP,
            state = if (cancelled) StmCoreJobState.CANCELLED else StmCoreJobState.FAILED,
            updatedAtEpochMs = now,
            progress = null,
            error = if (cancelled) null else error,
        )
        writeTerminalJournal(
            control,
            if (cancelled) StmInstallerJournalPhase.CANCELLED else StmInstallerJournalPhase.FAILED,
            terminalJob,
        )
        eventSink(StmInstallerEvent.JobChanged(terminalJob))
    }

    private fun failSimple(control: ActiveOperation, job: StmCoreJob, error: Exception) {
        val failure = error as? InstallerFailure
        val code = failure?.code ?: "MAINTENANCE_IO_FAILURE"
        val detail = failure?.message ?: error.safeDetail()
        val terminalJob = job.copy(
            phase = StmCoreJobPhase.COMPLETE,
            state = StmCoreJobState.FAILED,
            updatedAtEpochMs = System.currentTimeMillis(),
            progress = null,
            error = StmCoreError("installer", code, detail.take(200), detail.take(500)),
        )
        writeTerminalJournal(control, StmInstallerJournalPhase.FAILED, terminalJob)
        eventSink(StmInstallerEvent.JobChanged(terminalJob))
    }

    private fun writeTerminalJournal(
        control: ActiveOperation,
        phase: StmInstallerJournalPhase,
        terminalJob: StmCoreJob,
    ) {
        runCatching {
            val current = when (val read = journalStore.read(control.operationId)) {
                is StmInstallerJournalReadResult.Loaded -> read.stored.record
                StmInstallerJournalReadResult.Missing -> throw IOException(
                    "Installer journal disappeared before terminal failure was recorded",
                )

                is StmInstallerJournalReadResult.Corrupt -> throw IOException(
                    "Installer journal became corrupt before terminal failure was recorded: " +
                        read.evidence.detail,
                )
            }
            journalStore.write(
                current.copy(
                    phase = phase,
                    updatedAtEpochMs = maxOf(System.currentTimeMillis(), current.updatedAtEpochMs),
                    cancelRequested = current.cancelRequested || control.isCancelRequested,
                    terminalReceipt = StmInstallerTerminalReceipt(
                        jobPhase = terminalJob.phase,
                        jobState = terminalJob.state,
                        error = terminalJob.error,
                    ),
                ),
            )
        }.onFailure { error ->
            eventSink(
                StmInstallerEvent.RecoveryEvidence(
                    StmCoreError(
                        "installer_journal",
                        "TERMINAL_JOURNAL_WRITE_FAILED",
                        "A failed or cancelled operation could not persist its terminal journal",
                        error.safeDetail(),
                    ),
                ),
            )
        }
    }

    private fun recoverJournals() {
        val scan = journalStore.scan()
        val plan = StmInstallerRecoveryPlanner(stagingRoot).plan(scan)
        val byOperation = scan.journals.associateBy { it.record.operationId }
        val journalHistory = scan.journals.map(StmStoredInstallerJournal::record)
        val recoveryFailures = mutableListOf<String>()
        val reconciledCompleteOperationIds = mutableSetOf<String>()

        journalHistory
            .filter { it.phase in TERMINAL_RECOVERY_PHASES }
            .filterNot { it.operationId in checkpointTerminalOperationIds }
            .sortedWith(
                compareByDescending<StmInstallerJournalRecord> { it.updatedAtEpochMs }
                    .thenByDescending { it.startedAtEpochMs }
                    .thenByDescending { it.operationId },
            )
            .forEach { record ->
                val receipt = record.terminalReceipt
                if (receipt == null && record.phase != StmInstallerJournalPhase.COMPLETE) {
                    val code = "JOURNAL_TERMINAL_RECEIPT_MISSING"
                    val detail = "Legacy terminal journal has no immutable terminal job receipt"
                    recoveryFailures += "${record.operationId}:$code"
                    eventSink(
                        StmInstallerEvent.RecoveredTerminalJob(
                            recoveredFailedJob(record, code, detail),
                        ),
                    )
                    eventSink(
                        StmInstallerEvent.RecoveryEvidence(
                            StmCoreError(
                                "installer_journal",
                                code,
                                "A terminal installer journal lacked terminal job evidence",
                                detail,
                            ),
                        ),
                    )
                    return@forEach
                }
                if (record.phase != StmInstallerJournalPhase.COMPLETE) {
                    eventSink(recoveredTerminalJob(record, requireNotNull(receipt)))
                    return@forEach
                }
                when (
                    val completion = reconcileDurableCompletion(
                        record,
                        journalHistory,
                        reconciledCompleteOperationIds,
                    )
                ) {
                    is DurableCompletionReconciliation.Confirmed -> {
                        reconciledCompleteOperationIds += record.operationId
                        eventSink(recoveredTerminalJob(record, completion.receipt))
                    }

                    is DurableCompletionReconciliation.Unproven -> {
                        recoveryFailures += "${record.operationId}:${completion.code}"
                        eventSink(
                            StmInstallerEvent.RecoveredTerminalJob(
                                recoveredFailedJob(record, completion.code, completion.detail),
                            ),
                        )
                        eventSink(
                            StmInstallerEvent.RecoveryEvidence(
                                StmCoreError(
                                    "installer_journal",
                                    completion.code,
                                    "A COMPLETE installer journal contradicted durable Core state",
                                    completion.detail,
                                ),
                            ),
                        )
                    }
                }
            }

        plan.actions.filter { it.kind == StmInstallerRecoveryActionKind.FAIL_INTERRUPTED }
            .filterNot { it.operationId in checkpointTerminalOperationIds }
            .forEach { action ->
                val record = action.operationId?.let(byOperation::get)?.record ?: return@forEach
                when (
                    val completion = reconcileDurableCompletion(
                        record,
                        journalHistory,
                        reconciledCompleteOperationIds,
                    )
                ) {
                    is DurableCompletionReconciliation.Confirmed -> {
                        val finalizeFailure = runCatching {
                            journalStore.write(
                                record.copy(
                                    phase = StmInstallerJournalPhase.COMPLETE,
                                    updatedAtEpochMs = maxOf(
                                        System.currentTimeMillis(),
                                        record.updatedAtEpochMs,
                                    ),
                                    terminalReceipt = completion.receipt,
                                ),
                            )
                        }.exceptionOrNull()
                        if (finalizeFailure == null) {
                            eventSink(recoveredTerminalJob(record, completion.receipt))
                        } else {
                            recoveryFailures += "${record.operationId}:RECOVERY_JOURNAL_FINALIZE_FAILED"
                            eventSink(
                                StmInstallerEvent.RecoveredTerminalJob(
                                    recoveredFailedJob(
                                        record,
                                        "RECOVERY_JOURNAL_FINALIZE_FAILED",
                                        finalizeFailure.safeDetail(),
                                    ),
                                ),
                            )
                            eventSink(
                                StmInstallerEvent.RecoveryEvidence(
                                    StmCoreError(
                                        "installer_journal",
                                        "RECOVERY_JOURNAL_FINALIZE_FAILED",
                                        "A recovered durable operation could not finalize its journal",
                                        finalizeFailure.safeDetail(),
                                    ),
                                ),
                            )
                        }
                    }

                    is DurableCompletionReconciliation.Unproven -> eventSink(
                        StmInstallerEvent.RecoveredTerminalJob(
                            recoveredFailedJob(
                                record,
                                "CORE_PROCESS_INTERRUPTED",
                                "The maintenance operation was interrupted before durable completion",
                            ),
                        ),
                    )
                }
            }
        val recoveryResults = recoveryExecutor.execute(plan).results
        recoveryResults.forEach { result ->
            if (result.status == StmInstallerRecoveryExecutionStatus.FAILED) {
                eventSink(
                    StmInstallerEvent.RecoveryEvidence(
                        StmCoreError(
                            "installer_recovery",
                            result.code.name,
                            "Installer recovery action failed closed",
                            result.detail,
                        ),
                    ),
                )
            } else if (result.code == StmInstallerRecoveryExecutionCode.ORPHAN_QUARANTINED) {
                eventSink(
                    StmInstallerEvent.RecoveryEvidence(
                        StmCoreError(
                            "installer_recovery",
                            result.code.name,
                            "Orphan installer staging was quarantined",
                            result.quarantineDestination?.name,
                        ),
                    ),
                )
            }
        }
        plan.corruptEvidence.forEach { evidence ->
            recoveryFailures += "${evidence.relativeName}:${evidence.code.name}"
            eventSink(
                StmInstallerEvent.RecoveryEvidence(
                    StmCoreError(
                        "installer_recovery",
                        evidence.code.name,
                        "Installer recovery found inconsistent state",
                        evidence.detail,
                    ),
                ),
            )
        }
        scan.journals
            .map { stored -> stored.record }
            .filter { record ->
                record.type == StmInstallerOperationType.INSTALL ||
                    record.type == StmInstallerOperationType.VERIFY
            }
            .forEach { record ->
                deleteVerifiedCopy(record.operationId)
                if (record.phase == StmInstallerJournalPhase.COMPLETE ||
                    record.phase == StmInstallerJournalPhase.FAILED ||
                    record.phase == StmInstallerJournalPhase.CANCELLED
                ) {
                    cleanupStagingOperation(safeStagingChild(record.operationId))
                }
            }
        val failedActions = recoveryResults.filter {
            it.status == StmInstallerRecoveryExecutionStatus.FAILED
        }
        if (failedActions.isNotEmpty() || recoveryFailures.isNotEmpty()) {
            throw IOException(
                buildString {
                    append(failedActions.size + recoveryFailures.size)
                    append(" required installer recovery check(s) failed: ")
                    append(
                        (failedActions.map { it.code.name } + recoveryFailures)
                            .joinToString()
                            .take(500),
                    )
                },
            )
        }
    }

    private fun reconcileDurableCompletion(
        record: StmInstallerJournalRecord,
        journalHistory: List<StmInstallerJournalRecord>,
        reconciledCompleteOperationIds: Set<String>,
    ): DurableCompletionReconciliation {
        val successReceipt = record.terminalReceipt ?: StmInstallerTerminalReceipt(
            jobPhase = StmCoreJobPhase.COMPLETE,
            jobState = StmCoreJobState.SUCCEEDED,
        )
        return when (record.type) {
            StmInstallerOperationType.INSTALL -> {
                val slot = slotStore.readCommitted(record.targetSlotId)
                if (slot is StmSlotVerificationResult.Valid &&
                    slot.slot.metadata.archiveSha256 == record.artifactSha256
                ) {
                    DurableCompletionReconciliation.Confirmed(successReceipt)
                } else if (journalHistory.hasLaterCompleteMutation(
                        record,
                        StmInstallerOperationType.REMOVE,
                        reconciledCompleteOperationIds,
                    )
                ) {
                    DurableCompletionReconciliation.Confirmed(successReceipt)
                } else {
                    DurableCompletionReconciliation.Unproven(
                        "JOURNAL_COMPLETE_INSTALL_MISMATCH",
                        "The committed slot is missing, invalid, or has a different archive SHA-256",
                    )
                }
            }

            StmInstallerOperationType.ACTIVATE,
            StmInstallerOperationType.ROLLBACK,
            -> {
                val active = activeStore.read()
                val pointer = (active as? StmActiveSlotReadResult.Loaded)?.stored?.pointer
                val recordedRevision = successReceipt.activeRevision
                if (pointer != null && recordedRevision != null &&
                    ((pointer.activeRevision == recordedRevision &&
                        pointer.operationId == record.operationId &&
                        pointer.current.slotId == record.targetSlotId) ||
                        pointer.activeRevision > recordedRevision)
                ) {
                    DurableCompletionReconciliation.Confirmed(successReceipt)
                } else if (pointer != null && recordedRevision == null &&
                    pointer.operationId == record.operationId &&
                    pointer.current.slotId == record.targetSlotId
                ) {
                    DurableCompletionReconciliation.Confirmed(
                        successReceipt.copy(activeRevision = pointer.activeRevision),
                    )
                } else {
                    DurableCompletionReconciliation.Unproven(
                        "JOURNAL_COMPLETE_ACTIVE_MISMATCH",
                        "The active pointer does not match the journal operation and target",
                    )
                }
            }

            StmInstallerOperationType.REMOVE -> {
                if (slotStore.readCommitted(record.targetSlotId) == StmSlotVerificationResult.Missing) {
                    DurableCompletionReconciliation.Confirmed(successReceipt)
                } else if (journalHistory.hasLaterCompleteMutation(
                        record,
                        StmInstallerOperationType.INSTALL,
                        reconciledCompleteOperationIds,
                    )
                ) {
                    DurableCompletionReconciliation.Confirmed(successReceipt)
                } else {
                    DurableCompletionReconciliation.Unproven(
                        "JOURNAL_COMPLETE_REMOVE_MISMATCH",
                        "The removed slot is still present or cannot be proven absent",
                    )
                }
            }

            StmInstallerOperationType.VERIFY -> {
                val artifact = record.terminalReceipt?.artifact
                if (artifact == null) {
                    return DurableCompletionReconciliation.Unproven(
                        "JOURNAL_TERMINAL_RECEIPT_MISSING",
                        "A legacy VERIFY completion has no immutable Core-derived artifact receipt",
                    )
                }
                val receiptFailure = runCatching {
                    artifact.requireValidArtifact()
                    require(artifact.integrity == StmCoreArtifactIntegrity.VERIFIED) {
                        "The VERIFY receipt integrity is not VERIFIED"
                    }
                    require(artifact.trust != StmCoreArtifactTrust.REJECTED) {
                        "The VERIFY receipt trust is rejected"
                    }
                    require(artifact.archiveSha256 == record.artifactSha256) {
                        "The VERIFY receipt SHA-256 differs from the journal identity"
                    }
                }.exceptionOrNull()
                if (receiptFailure == null) {
                    DurableCompletionReconciliation.Confirmed(
                        successReceipt.copy(artifact = artifact),
                    )
                } else {
                    DurableCompletionReconciliation.Unproven(
                        "JOURNAL_COMPLETE_VERIFY_RECEIPT_INVALID",
                        receiptFailure.safeDetail(),
                    )
                }
            }

            StmInstallerOperationType.DOWNLOAD,
            StmInstallerOperationType.MIGRATE,
            -> DurableCompletionReconciliation.Unproven(
                "JOURNAL_COMPLETE_TYPE_UNSUPPORTED",
                "This operation type has no durable completion reconciliation rule",
            )
        }
    }

    private fun recoveredTerminalJob(
        record: StmInstallerJournalRecord,
        receipt: StmInstallerTerminalReceipt,
    ) = StmInstallerEvent.RecoveredTerminalJob(
        StmCoreJob(
            operationId = record.operationId,
            type = record.type.toCoreType(),
            targetId = record.targetSlotId,
            phase = receipt.jobPhase,
            state = receipt.jobState,
            startedAtEpochMs = record.startedAtEpochMs,
            updatedAtEpochMs = maxOf(System.currentTimeMillis(), record.updatedAtEpochMs),
            progress = if (receipt.jobState == StmCoreJobState.SUCCEEDED) 1.0 else null,
            error = receipt.error,
            artifact = receipt.artifact,
        ),
    )

    private fun recoveredFailedJob(
        record: StmInstallerJournalRecord,
        code: String,
        detail: String,
    ) = StmCoreJob(
        operationId = record.operationId,
        type = record.type.toCoreType(),
        targetId = record.targetSlotId,
        phase = StmCoreJobPhase.CLEANING_UP,
        state = StmCoreJobState.FAILED,
        startedAtEpochMs = record.startedAtEpochMs,
        updatedAtEpochMs = maxOf(System.currentTimeMillis(), record.updatedAtEpochMs),
        error = StmCoreError("installer_recovery", code, detail.take(200), detail.take(500)),
    )

    private fun deleteVerifiedCopy(operationId: String) {
        val temporary = safeCacheChild("$operationId.verified.part")
        val path = temporary.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Interrupted verified copy is not a regular no-follow file")
        }
        Files.delete(path)
    }

    private fun emitCleanupFailure(code: String, summary: String, error: Throwable) {
        eventSink(
            StmInstallerEvent.RecoveryEvidence(
                StmCoreError(
                    "installer_recovery",
                    code,
                    summary,
                    error.safeDetail(),
                ),
            ),
        )
    }

    private fun recoverSlotsAndActive() {
        val removedActiveTemps = activeStore.cleanupTemporaryFilesForRecovery()
        if (removedActiveTemps > 0) {
            eventSink(
                StmInstallerEvent.RecoveryEvidence(
                    StmCoreError(
                        "active_slot",
                        "ACTIVE_POINTER_TEMP_CLEANED",
                        "Recovered $removedActiveTemps interrupted active-pointer temporary file(s)",
                    ),
                ),
            )
        }
        val validSlots = linkedMapOf<String, StmCoreSlot>()
        slotStore.scanCommitted().forEach { entry ->
            when (val verification = entry.verification) {
                is StmSlotVerificationResult.Valid -> {
                    val slot = verification.slot.toCoreSlot()
                    validSlots[slot.id] = slot
                    eventSink(StmInstallerEvent.SlotChanged(slot))
                }

                StmSlotVerificationResult.Missing -> Unit
                is StmSlotVerificationResult.Invalid -> {
                    if (entry.entryName.matches(SAFE_ID_PATTERN)) {
                        validSlots[entry.entryName] = StmCoreSlot(
                            id = entry.entryName,
                            state = StmCoreSlotState.BROKEN,
                            revision = 1,
                        )
                    }
                    eventSink(
                        StmInstallerEvent.RecoveryEvidence(
                            StmCoreError(
                                "slot_recovery",
                                "SLOT_INVALID",
                                "A committed slot failed verification",
                                "${entry.entryName}: ${verification.detail}",
                            ),
                        ),
                    )
                }
            }
        }
        eventSink(StmInstallerEvent.SlotsReconciled(validSlots.values.toList()))
        when (val active = activeStore.read()) {
            StmActiveSlotReadResult.Missing -> eventSink(StmInstallerEvent.ActiveChanged(null))
            is StmActiveSlotReadResult.Corrupt -> {
                val recoveryId = UUID.randomUUID().toString()
                val quarantined = activeStore.quarantineForRecovery(recoveryId)
                eventSink(StmInstallerEvent.ActiveChanged(null))
                eventSink(
                    StmInstallerEvent.RecoveryEvidence(
                        StmCoreError(
                            "active_slot",
                            "ACTIVE_POINTER_CORRUPT",
                            "The corrupt active-slot pointer was quarantined",
                            "${active.detail}; evidence=${quarantined.joinToString { it.name }}",
                        ),
                    ),
                )
            }

            is StmActiveSlotReadResult.Loaded -> {
                val pointer = active.stored.pointer
                val slot = validSlots[pointer.current.slotId]
                if (slot?.revision == pointer.current.slotRevision &&
                    slot.state == StmCoreSlotState.READY
                ) {
                    if (active.source == StmActiveSlotRecordSource.PREVIOUS) {
                        activeStore.write(pointer)
                    }
                    eventSink(
                        StmInstallerEvent.ActiveChanged(
                            StmCoreActiveSlot(
                                pointer.current.slotId,
                                pointer.current.slotRevision,
                                pointer.activeRevision,
                            ),
                        ),
                    )
                    if (active.source == StmActiveSlotRecordSource.PREVIOUS) {
                        eventSink(
                            StmInstallerEvent.RecoveryEvidence(
                                StmCoreError(
                                    "active_slot",
                                    "ACTIVE_POINTER_RECOVERED_PREVIOUS",
                                    "Recovered and repaired the previous active-slot record",
                                ),
                            ),
                        )
                    }
                } else {
                    val previous = pointer.previous
                    val previousSlot = previous?.let { validSlots[it.slotId] }
                    if (previous != null &&
                        previousSlot != null &&
                        previousSlot.revision == previous.slotRevision &&
                        previousSlot.state == StmCoreSlotState.READY &&
                        pointer.activeRevision < Long.MAX_VALUE
                    ) {
                        val recovered = StmActiveSlotPointer(
                            current = previous,
                            previous = pointer.current,
                            activeRevision = pointer.activeRevision + 1,
                            operationId = UUID.randomUUID().toString(),
                        )
                        activeStore.write(recovered)
                        eventSink(
                            StmInstallerEvent.ActiveChanged(
                                StmCoreActiveSlot(
                                    previous.slotId,
                                    previous.slotRevision,
                                    recovered.activeRevision,
                                ),
                            ),
                        )
                        eventSink(
                            StmInstallerEvent.RecoveryEvidence(
                                StmCoreError(
                                    "active_slot",
                                    "ACTIVE_SLOT_ROLLED_BACK_INVALID_CURRENT",
                                    "Recovered the previous READY slot after the current target failed verification",
                                ),
                            ),
                        )
                    } else {
                        val recoveryId = UUID.randomUUID().toString()
                        val quarantined = activeStore.quarantineForRecovery(recoveryId)
                        eventSink(StmInstallerEvent.ActiveChanged(null))
                        eventSink(
                            StmInstallerEvent.RecoveryEvidence(
                                StmCoreError(
                                    "active_slot",
                                    "ACTIVE_SLOT_TARGET_INVALID",
                                    "The invalid active pointer was quarantined",
                                    "evidence=${quarantined.joinToString { it.name }}",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun StmCommittedSlot.toCoreSlot(): StmCoreSlot {
        val artifact = metadata.toCoreArtifact()
        return slotSnapshot(
            metadata.slotId,
            metadata.slotRevision,
            StmCoreSlotState.READY,
            artifact,
            manifest,
        )
    }

    private fun ensureCheckpointMatches(
        checkpoint: StmCoreActiveSlot?,
        stored: StmActiveSlotPointer,
    ) {
        if (checkpoint == null ||
            checkpoint.slotId != stored.current.slotId ||
            checkpoint.slotRevision != stored.current.slotRevision ||
            checkpoint.activeRevision != stored.activeRevision
        ) {
            throw InstallerFailure(
                "ACTIVE_POINTER_DIVERGED",
                "Checkpoint and authoritative active-slot record differ",
            )
        }
    }

    private fun slotSnapshot(
        slotId: String,
        slotRevision: Long,
        state: StmCoreSlotState,
        artifact: StmCoreArtifact,
        manifest: StmSlotContentManifest?,
    ) = StmCoreSlot(
        id = slotId,
        state = state,
        revision = slotRevision,
        repository = artifact.repository,
        commitSha = artifact.commitSha,
        artifact = artifact,
        manifestSha256 = manifest?.manifestSha256,
        manifestFileCount = manifest?.fileCount,
        manifestTotalBytes = manifest?.totalFileBytes,
    )

    private fun newJob(
        operationId: String,
        type: StmCoreJobType,
        targetId: String,
        startedAt: Long,
    ) = StmCoreJob(
        operationId = operationId,
        type = type,
        targetId = targetId,
        phase = StmCoreJobPhase.QUEUED,
        state = StmCoreJobState.QUEUED,
        startedAtEpochMs = startedAt,
        updatedAtEpochMs = startedAt,
    ).also { eventSink(StmInstallerEvent.JobChanged(it)) }

    private fun StmCoreArtifact.toVerifierIdentity() = ArtifactIdentity(
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

    private fun StmCoreArtifact.toSlotPayloadKind() = when (kind) {
        StmCoreArtifactKind.GATE2_SYNTHETIC -> StmSlotPayloadKind.GATE2_SYNTHETIC
        StmCoreArtifactKind.SILLY_TAVERN_SOURCE -> StmSlotPayloadKind.SILLY_TAVERN_SOURCE
    }

    private fun StmCoreArtifact.toSlotCommitRequest(
        operationId: String,
        slotId: String,
        slotRevision: Long,
        runtimeEvidence: StmRuntimeSlotAdmissionEvidence? = null,
    ) = StmSlotCommitRequest(
        operationId = operationId,
        slotId = slotId,
        slotRevision = slotRevision,
        payloadKind = toSlotPayloadKind(),
        repository = repository,
        channel = channel,
        commitSha = commitSha.lowercase(),
        downloadUrl = downloadUrl,
        downloadedAtEpochMs = downloadedAtEpochMs,
        archiveLength = archiveLength,
        archiveSha256 = archiveSha256.lowercase(),
        integrity = integrity,
        trust = trust,
        catalogVersion = catalogVersion,
        archiveRoot = archiveRoot,
        stVersion = stVersion,
        nodeRequirement = nodeRequirement,
        packageLockSha256 = packageLockSha256,
        licenseStatus = licenseStatus,
        runtimeEvidence = runtimeEvidence,
    )

    private fun StmCoreJobType.toJournalType() = StmInstallerOperationType.valueOf(name)

    private fun StmInstallerOperationType.toCoreType() = StmCoreJobType.valueOf(name)

    private fun safeCacheChild(name: String): File {
        require(name.matches(CACHE_FILE_OR_PART_PATTERN)) { "Cache file name is invalid" }
        val root = installerCacheRoot.toPath().toRealPath()
        val child = root.resolve(name).normalize()
        require(child.parent == root && child.startsWith(root)) { "Cache path escaped its root" }
        return child.toFile()
    }

    private fun safeStagingChild(operationId: String): File {
        requireCanonicalUuid(operationId)
        val root = stagingRoot.toPath().toRealPath()
        val child = root.resolve(operationId).normalize()
        require(child.parent == root && child.startsWith(root)) { "Staging path escaped its root" }
        return child.toFile()
    }

    private fun cleanupStagingOperation(operationRoot: File) {
        val root = stagingRoot.toPath().toRealPath()
        val target = operationRoot.toPath().toAbsolutePath().normalize()
        if (target.parent != root || !target.startsWith(root)) return
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(target, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(
                file: java.nio.file.Path,
                attributes: java.nio.file.attribute.BasicFileAttributes,
            ): java.nio.file.FileVisitResult {
                Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: java.nio.file.Path,
                error: IOException?,
            ): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private class ActiveOperation(
        val operationId: String,
        private val eventSink: (StmInstallerEvent) -> Unit,
        val controlState: AtomicReference<OperationControlState> =
            AtomicReference(OperationControlState.CANCELLABLE),
    ) {
        private val waitLock = Any()
        private var waitState: SoftWaitState? = null
        private var transferActive = false

        val observer: StmRuntimePreparationObserver = object : StmRuntimePreparationObserver {
            override fun beginSoftWait(
                kind: StmCoreWaitKind,
                intervalMillis: Long,
                summary: String,
            ) {
                require(intervalMillis > 0L)
                synchronized(waitLock) {
                    waitState = SoftWaitState(
                        kind = kind,
                        intervalMillis = intervalMillis,
                        summary = summary,
                        deadlineNanos = nextDeadline(intervalMillis),
                    )
                }
                eventSink(StmInstallerEvent.WaitPromptChanged(null))
            }

            override fun pollSoftWait() {
                val prompt = synchronized(waitLock) {
                    val current = waitState ?: return@synchronized null
                    if (current.promptVisible || System.nanoTime() < current.deadlineNanos) {
                        return@synchronized null
                    }
                    current.promptVisible = true
                    StmCoreWaitPrompt(
                        operationId = operationId,
                        kind = current.kind,
                        intervalMillis = current.intervalMillis,
                        triggeredAtEpochMs = System.currentTimeMillis(),
                        summary = current.summary,
                    )
                }
                prompt?.let { eventSink(StmInstallerEvent.WaitPromptChanged(it)) }
            }

            override fun endSoftWait() {
                synchronized(waitLock) { waitState = null }
                eventSink(StmInstallerEvent.WaitPromptChanged(null))
            }

            override fun onRuntimeTransfer(
                transferredBytes: Long,
                totalBytes: Long,
                bytesPerSecond: Long,
            ) {
                synchronized(waitLock) { transferActive = true }
                eventSink(
                    StmInstallerEvent.RuntimeTransferChanged(
                        StmCoreTransferProgress(
                            operationId = operationId,
                            transferredBytes = transferredBytes,
                            totalBytes = totalBytes,
                            bytesPerSecond = bytesPerSecond,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    ),
                )
            }

            override fun endRuntimeTransfer() {
                val hadTransfer = synchronized(waitLock) {
                    transferActive.also { transferActive = false }
                }
                if (hadTransfer) eventSink(StmInstallerEvent.RuntimeTransferChanged(null))
            }
        }

        val isCancelRequested: Boolean
            get() = controlState.get() == OperationControlState.CANCEL_REQUESTED

        fun requestCancel(): Boolean {
            while (true) {
                when (controlState.get()) {
                    OperationControlState.CANCELLABLE -> if (
                        controlState.compareAndSet(
                            OperationControlState.CANCELLABLE,
                            OperationControlState.CANCEL_REQUESTED,
                        )
                    ) {
                        return true
                    }

                    OperationControlState.CANCEL_REQUESTED -> return true
                    OperationControlState.COMMITTING -> return false
                }
            }
        }

        fun enterCommitPoint() {
            while (true) {
                when (controlState.get()) {
                    OperationControlState.CANCELLABLE -> if (
                        controlState.compareAndSet(
                            OperationControlState.CANCELLABLE,
                            OperationControlState.COMMITTING,
                        )
                    ) {
                        return
                    }

                    OperationControlState.CANCEL_REQUESTED -> throw OperationCancelled()
                    OperationControlState.COMMITTING -> return
                }
            }
        }

        fun throwIfCancelled() {
            if (isCancelRequested || Thread.currentThread().isInterrupted) throw OperationCancelled()
        }

        fun continueWaiting(): Boolean {
            val continued = synchronized(waitLock) {
                val current = waitState ?: return@synchronized false
                if (!current.promptVisible) return@synchronized false
                current.promptVisible = false
                current.deadlineNanos = nextDeadline(current.intervalMillis)
                true
            }
            if (continued) eventSink(StmInstallerEvent.WaitPromptChanged(null))
            return continued
        }

        fun finishEphemeralState() {
            val hadTransfer = synchronized(waitLock) {
                waitState = null
                transferActive.also { transferActive = false }
            }
            eventSink(StmInstallerEvent.WaitPromptChanged(null))
            if (hadTransfer) eventSink(StmInstallerEvent.RuntimeTransferChanged(null))
        }

        private fun nextDeadline(intervalMillis: Long): Long =
            System.nanoTime() +
                intervalMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L

        private data class SoftWaitState(
            val kind: StmCoreWaitKind,
            val intervalMillis: Long,
            val summary: String,
            var deadlineNanos: Long,
            var promptVisible: Boolean = false,
        )
    }

    private enum class OperationControlState {
        CANCELLABLE,
        CANCEL_REQUESTED,
        COMMITTING,
    }

    private class OperationCancelled : IOException("Operation cancelled")

    private class InstallerFailure(val code: String, message: String) : IOException(message)

    private companion object {
        val CACHE_FILE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.zip")
        val CACHE_FILE_OR_PART_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
        val SAFE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

private fun initializeOwnedDirectory(directory: File) {
    val path = directory.toPath().toAbsolutePath().normalize()
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
        throw IllegalArgumentException("Core installer directory cannot be a symbolic link")
    }
    Files.createDirectories(path)
    require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        "Core installer directory is unavailable"
    }
}

private sealed interface DurableCompletionReconciliation {
    data class Confirmed(val receipt: StmInstallerTerminalReceipt) :
        DurableCompletionReconciliation

    data class Unproven(val code: String, val detail: String) :
        DurableCompletionReconciliation
}

private val TERMINAL_RECOVERY_PHASES = setOf(
    StmInstallerJournalPhase.COMPLETE,
    StmInstallerJournalPhase.FAILED,
    StmInstallerJournalPhase.CANCELLED,
)

private fun List<StmInstallerJournalRecord>.hasLaterCompleteMutation(
    earlier: StmInstallerJournalRecord,
    laterType: StmInstallerOperationType,
    reconciledCompleteOperationIds: Set<String>,
): Boolean = any { candidate ->
    candidate.operationId != earlier.operationId &&
        candidate.targetSlotId == earlier.targetSlotId &&
        candidate.type == laterType &&
        candidate.phase == StmInstallerJournalPhase.COMPLETE &&
        (candidate.terminalReceipt?.jobState == StmCoreJobState.SUCCEEDED ||
            candidate.operationId in reconciledCompleteOperationIds) &&
        (candidate.startedAtEpochMs > earlier.startedAtEpochMs ||
            candidate.updatedAtEpochMs > earlier.updatedAtEpochMs)
}

private fun requireRegularNoFollow(file: File, label: String) {
    val path = file.toPath()
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "$label must be a regular file"
    }
}

private fun requireCanonicalUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "Operation ID must be a canonical UUID"
    }
}

private fun requireSafeId(value: String) {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) {
        "Identifier is invalid"
    }
}

private fun Throwable.safeDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)
