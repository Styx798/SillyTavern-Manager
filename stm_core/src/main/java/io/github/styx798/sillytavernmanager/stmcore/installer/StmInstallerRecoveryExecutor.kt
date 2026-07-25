package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal enum class StmInstallerRecoveryExecutionStatus {
    SUCCESS,
    NO_OP,
    FAILED,
}

internal enum class StmInstallerRecoveryExecutionCode {
    STAGING_CLEANED,
    ORPHAN_QUARANTINED,
    COMPLETE_JOURNAL_DELETED,
    RECORDED_WITHOUT_MUTATION,
    ALREADY_ABSENT,
    PLAN_ROOT_MISMATCH,
    ACTION_PATH_REJECTED,
    ROOT_REJECTED,
    SYMBOLIC_LINK_REJECTED,
    JOURNAL_REJECTED,
    LIMIT_EXCEEDED,
    IO_FAILURE,
    FAULT_INJECTED,
}

internal data class StmInstallerRecoveryExecutionResult(
    val action: StmInstallerRecoveryAction,
    val status: StmInstallerRecoveryExecutionStatus,
    val code: StmInstallerRecoveryExecutionCode,
    val detail: String,
    val quarantineDestination: File? = null,
)

internal data class StmInstallerRecoveryExecutionReport(
    val results: List<StmInstallerRecoveryExecutionResult>,
) {
    val succeeded: Int
        get() = results.count { it.status != StmInstallerRecoveryExecutionStatus.FAILED }

    val failed: Int
        get() = results.count { it.status == StmInstallerRecoveryExecutionStatus.FAILED }
}

internal enum class StmInstallerRecoveryExecutorFailpoint {
    BEFORE_ACTION,
    AFTER_REVALIDATION,
    BEFORE_MUTATION,
    AFTER_MUTATION,
}

internal fun interface StmInstallerRecoveryExecutorFaultInjector {
    fun hit(failpoint: StmInstallerRecoveryExecutorFailpoint, action: StmInstallerRecoveryAction)
}

/**
 * Executes the narrow mutation subset of a recovery plan. The authorized Core root determines all
 * mutable paths; the plan cannot substitute slots, data, or another staging root.
 *
 * Orphan staging is quarantined rather than deleted so evidence survives recovery. The executor
 * never follows symbolic links and never falls back from a failed atomic quarantine to deletion.
 */
internal class StmInstallerRecoveryExecutor(
    coreRoot: File,
    private val journalStore: StmInstallerJournalStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val faultInjector: StmInstallerRecoveryExecutorFaultInjector =
        StmInstallerRecoveryExecutorFaultInjector { _, _ -> },
) {
    private val core: Path = coreRoot.toPath().toAbsolutePath().normalize()
    private val staging: Path = core.resolve(STAGING_DIRECTORY)
    private val quarantine: Path = core.resolve(QUARANTINE_DIRECTORY)
    private val state: Path = core.resolve(STATE_DIRECTORY)
    private val expectedJournalRoot: Path = state.resolve(JOURNAL_DIRECTORY)

    init {
        require(journalStore.root == expectedJournalRoot) {
            "Recovery executor journal store is outside the authorized Core state directory"
        }
    }

    fun execute(plan: StmInstallerRecoveryPlan): StmInstallerRecoveryExecutionReport {
        val planRoot = plan.stagingRoot.toPath().toAbsolutePath().normalize()
        if (planRoot != staging) {
            return StmInstallerRecoveryExecutionReport(
                plan.actions.map { action ->
                    failed(
                        action,
                        StmInstallerRecoveryExecutionCode.PLAN_ROOT_MISMATCH,
                        "Recovery plan does not target the authorized Core staging root",
                    )
                },
            )
        }
        return StmInstallerRecoveryExecutionReport(
            plan.actions.map { action -> executeOne(action) },
        )
    }

    private fun executeOne(
        action: StmInstallerRecoveryAction,
    ): StmInstallerRecoveryExecutionResult {
        return try {
            faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.BEFORE_ACTION, action)
            validateCoreRoot()
            when (action.kind) {
                StmInstallerRecoveryActionKind.CLEANUP_STAGING -> cleanupStaging(action)
                StmInstallerRecoveryActionKind.QUARANTINE_ORPHAN_STAGING ->
                    quarantineOrphan(action)

                StmInstallerRecoveryActionKind.CLEANUP_COMPLETE_JOURNAL ->
                    cleanupCompleteJournal(action)

                StmInstallerRecoveryActionKind.FAIL_INTERRUPTED,
                StmInstallerRecoveryActionKind.RETAIN_COMPLETE_JOURNAL,
                -> recordWithoutMutation(action)
            }
        } catch (error: StmInstallerRecoveryInjectedFault) {
            failed(
                action,
                StmInstallerRecoveryExecutionCode.FAULT_INJECTED,
                error.safeRecoveryExecutorDetail(),
            )
        } catch (error: RecoveryExecutorException) {
            failed(action, error.code, error.message.orEmpty())
        } catch (error: Exception) {
            failed(
                action,
                StmInstallerRecoveryExecutionCode.IO_FAILURE,
                error.safeRecoveryExecutorDetail(),
            )
        }
    }

    private fun cleanupStaging(
        action: StmInstallerRecoveryAction,
    ): StmInstallerRecoveryExecutionResult {
        val target = validateStagingAction(action, StmInstallerRecoveryActionKind.CLEANUP_STAGING)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return alreadyAbsent(action)
        requireRealDirectory(target, "Staging cleanup target")
        revalidateStagingRoot()
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.AFTER_REVALIDATION, action)
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.BEFORE_MUTATION, action)
        validateCoreRoot()
        revalidateStagingRoot()
        requireRealDirectory(target, "Staging cleanup target")
        deleteTreeNoFollow(target)
        validateCoreRoot()
        revalidateStagingRoot()
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Staging cleanup target still exists"
        }
        bestEffortSyncRecoveryDirectory(staging)
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.AFTER_MUTATION, action)
        return success(
            action,
            StmInstallerRecoveryExecutionCode.STAGING_CLEANED,
            "Operation staging directory was removed without following links",
        )
    }

    private fun quarantineOrphan(
        action: StmInstallerRecoveryAction,
    ): StmInstallerRecoveryExecutionResult {
        val source = validateStagingAction(
            action,
            StmInstallerRecoveryActionKind.QUARANTINE_ORPHAN_STAGING,
        )
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return alreadyAbsent(action)
        requireRealDirectory(source, "Orphan staging target")
        revalidateStagingRoot()
        validateOrCreateQuarantineRoot(create = false)
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.AFTER_REVALIDATION, action)
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.BEFORE_MUTATION, action)

        validateOrCreateQuarantineRoot(create = true)
        validateCoreRoot()
        revalidateStagingRoot()
        requireRealDirectory(source, "Orphan staging target")
        val timestamp = clock()
        if (timestamp <= 0) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Quarantine timestamp must be positive",
            )
        }
        val relativeId = requireNotNull(action.stagingRelativeId)
        val destination = quarantine.resolve("$relativeId-$timestamp").normalize()
        if (destination.parent != quarantine || !destination.startsWith(quarantine)) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Quarantine destination escaped its authorized root",
            )
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Quarantine destination already exists",
            )
        }
        atomicMoveToQuarantine(source, destination)
        validateCoreRoot()
        revalidateStagingRoot()
        validateOrCreateQuarantineRoot(create = false)
        requireRealDirectory(destination, "Quarantined staging target")
        check(!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            "Orphan staging source still exists after quarantine"
        }
        bestEffortSyncRecoveryDirectory(staging)
        bestEffortSyncRecoveryDirectory(quarantine)
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.AFTER_MUTATION, action)
        return success(
            action = action,
            code = StmInstallerRecoveryExecutionCode.ORPHAN_QUARANTINED,
            detail = "Orphan staging was atomically moved into Core quarantine",
            quarantineDestination = destination.toFile(),
        )
    }

    private fun cleanupCompleteJournal(
        action: StmInstallerRecoveryAction,
    ): StmInstallerRecoveryExecutionResult {
        validateActionShape(action, expectsStagingPath = false)
        val operationId = action.operationId?.canonicalRecoveryUuidOrNull()
            ?: throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "Complete journal cleanup requires a canonical operation UUID",
            )
        validateJournalRoot()
        val journal = journalStore.journalFile(operationId).toPath().toAbsolutePath().normalize()
        validateExactJournalPath(journal, operationId)
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return alreadyAbsent(action)
        requireRealRegularFile(journal, "Complete journal")
        val firstRead = when (val result = journalStore.read(operationId)) {
            StmInstallerJournalReadResult.Missing -> return alreadyAbsent(action)
            is StmInstallerJournalReadResult.Corrupt -> throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "Journal is corrupt: ${result.evidence.detail}",
            )

            is StmInstallerJournalReadResult.Loaded -> result.stored
        }
        if (firstRead.record.phase != StmInstallerJournalPhase.COMPLETE) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "Only a verified COMPLETE journal may be removed",
            )
        }
        requireRealRegularFile(journal, "Complete journal")
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.AFTER_REVALIDATION, action)
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.BEFORE_MUTATION, action)

        validateCoreRoot()
        validateJournalRoot()
        validateExactJournalPath(journal, operationId)
        requireRealRegularFile(journal, "Complete journal")
        val secondRead = journalStore.read(operationId)
        if (secondRead !is StmInstallerJournalReadResult.Loaded ||
            secondRead.stored != firstRead ||
            secondRead.stored.record.phase != StmInstallerJournalPhase.COMPLETE
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "Complete journal changed during cleanup validation",
            )
        }
        requireRealRegularFile(journal, "Complete journal")
        Files.delete(journal)
        validateCoreRoot()
        validateJournalRoot()
        check(!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            "Complete journal still exists after deletion"
        }
        bestEffortSyncRecoveryDirectory(expectedJournalRoot)
        faultInjector.hit(StmInstallerRecoveryExecutorFailpoint.AFTER_MUTATION, action)
        return success(
            action,
            StmInstallerRecoveryExecutionCode.COMPLETE_JOURNAL_DELETED,
            "Verified COMPLETE journal was deleted",
        )
    }

    private fun recordWithoutMutation(
        action: StmInstallerRecoveryAction,
    ): StmInstallerRecoveryExecutionResult {
        validateActionShape(action, expectsStagingPath = false)
        if (action.operationId?.canonicalRecoveryUuidOrNull() == null) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Recorded recovery action requires a canonical operation UUID",
            )
        }
        return StmInstallerRecoveryExecutionResult(
            action = action,
            status = StmInstallerRecoveryExecutionStatus.NO_OP,
            code = StmInstallerRecoveryExecutionCode.RECORDED_WITHOUT_MUTATION,
            detail = "Recovery action was recorded without filesystem mutation",
        )
    }

    private fun validateStagingAction(
        action: StmInstallerRecoveryAction,
        expectedKind: StmInstallerRecoveryActionKind,
    ): Path {
        if (action.kind != expectedKind) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Recovery action kind does not match its executor",
            )
        }
        val relativeId = action.stagingRelativeId?.canonicalRecoveryUuidOrNull()
            ?: throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Staging action requires a canonical UUID relative ID",
            )
        val expected = staging.resolve(relativeId).normalize()
        val supplied = action.stagingPath?.toPath()?.toAbsolutePath()?.normalize()
            ?: throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Staging action requires an explicit planned path",
            )
        if (expected.parent != staging || !expected.startsWith(staging) || supplied != expected) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Staging action path is not the exact authorized direct child",
            )
        }
        if (expectedKind == StmInstallerRecoveryActionKind.CLEANUP_STAGING &&
            action.operationId?.canonicalRecoveryUuidOrNull() == null
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Operation staging cleanup requires a canonical operation UUID",
            )
        }
        if (expectedKind == StmInstallerRecoveryActionKind.QUARANTINE_ORPHAN_STAGING &&
            action.operationId != null
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Orphan staging quarantine cannot claim a live operation",
            )
        }
        return expected
    }

    private fun validateActionShape(
        action: StmInstallerRecoveryAction,
        expectsStagingPath: Boolean,
    ) {
        val hasStaging = action.stagingRelativeId != null || action.stagingPath != null
        if (hasStaging != expectsStagingPath) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Recovery action contains an unexpected path",
            )
        }
    }

    private fun validateCoreRoot() {
        if (!Files.exists(core, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(core) ||
            !Files.isDirectory(core, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ROOT_REJECTED,
                "Authorized Core root must be an existing real directory",
            )
        }
    }

    private fun revalidateStagingRoot() {
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(staging) ||
            !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) ||
            staging.parent != core
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ROOT_REJECTED,
                "Authorized staging root must remain a real direct child of Core",
            )
        }
    }

    private fun validateOrCreateQuarantineRoot(create: Boolean) {
        if (!Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return
            Files.createDirectory(quarantine)
        }
        if (Files.isSymbolicLink(quarantine) ||
            !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS) ||
            quarantine.parent != core
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ROOT_REJECTED,
                "Core quarantine root must be a real direct child of Core",
            )
        }
    }

    private fun validateJournalRoot() {
        validateCoreRoot()
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(state) ||
            !Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS) ||
            state.parent != core
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "Installer state root must be a real direct child of Core",
            )
        }
        if (journalStore.root != expectedJournalRoot ||
            !Files.exists(expectedJournalRoot, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(expectedJournalRoot) ||
            !Files.isDirectory(expectedJournalRoot, LinkOption.NOFOLLOW_LINKS) ||
            expectedJournalRoot.parent != state
        ) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "Installer journal root is unavailable or outside Core state",
            )
        }
    }

    private fun validateExactJournalPath(path: Path, operationId: String) {
        val expected = expectedJournalRoot.resolve("$operationId.journal").normalize()
        if (path != expected || path.parent != expectedJournalRoot || !path.startsWith(expectedJournalRoot)) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "Journal cleanup path is not the exact parsed operation record",
            )
        }
    }

    private fun requireRealDirectory(path: Path, label: String) {
        if (Files.isSymbolicLink(path)) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.SYMBOLIC_LINK_REJECTED,
                "$label cannot be a symbolic link",
            )
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "$label must be a real directory",
            )
        }
    }

    private fun requireRealRegularFile(path: Path, label: String) {
        if (Files.isSymbolicLink(path)) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.SYMBOLIC_LINK_REJECTED,
                "$label cannot be a symbolic link",
            )
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.JOURNAL_REJECTED,
                "$label must be a real regular file",
            )
        }
    }

    private fun deleteTreeNoFollow(target: Path) {
        val visited = AtomicInteger()
        Files.walkFileTree(
            target,
            emptySet(),
            MAX_CLEANUP_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    requireCleanupPath(directory, target)
                    countCleanupEntry(visited)
                    if (Files.isSymbolicLink(directory) || !attributes.isDirectory) {
                        throw RecoveryExecutorException(
                            StmInstallerRecoveryExecutionCode.SYMBOLIC_LINK_REJECTED,
                            "Cleanup encountered an invalid directory",
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    requireCleanupPath(file, target)
                    countCleanupEntry(visited)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    throw error
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    error?.let { throw it }
                    requireCleanupPath(directory, target)
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun requireCleanupPath(path: Path, target: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(target) || !target.startsWith(staging) || target.parent != staging) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.ACTION_PATH_REJECTED,
                "Recursive cleanup escaped its planned staging directory",
            )
        }
    }

    private fun countCleanupEntry(counter: AtomicInteger) {
        if (counter.incrementAndGet() > MAX_CLEANUP_ENTRIES) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.LIMIT_EXCEEDED,
                "Staging cleanup exceeded its bounded entry count",
            )
        }
    }

    private fun atomicMoveToQuarantine(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            throw RecoveryExecutorException(
                StmInstallerRecoveryExecutionCode.IO_FAILURE,
                "Atomic staging quarantine is unavailable",
                error,
            )
        }
    }

    private fun success(
        action: StmInstallerRecoveryAction,
        code: StmInstallerRecoveryExecutionCode,
        detail: String,
        quarantineDestination: File? = null,
    ): StmInstallerRecoveryExecutionResult = StmInstallerRecoveryExecutionResult(
        action = action,
        status = StmInstallerRecoveryExecutionStatus.SUCCESS,
        code = code,
        detail = detail.take(MAX_EXECUTION_DETAIL_CHARS),
        quarantineDestination = quarantineDestination,
    )

    private fun alreadyAbsent(
        action: StmInstallerRecoveryAction,
    ): StmInstallerRecoveryExecutionResult = StmInstallerRecoveryExecutionResult(
        action = action,
        status = StmInstallerRecoveryExecutionStatus.NO_OP,
        code = StmInstallerRecoveryExecutionCode.ALREADY_ABSENT,
        detail = "Recovery target is already absent",
    )

    private fun failed(
        action: StmInstallerRecoveryAction,
        code: StmInstallerRecoveryExecutionCode,
        detail: String,
    ): StmInstallerRecoveryExecutionResult = StmInstallerRecoveryExecutionResult(
        action = action,
        status = StmInstallerRecoveryExecutionStatus.FAILED,
        code = code,
        detail = detail.take(MAX_EXECUTION_DETAIL_CHARS),
    )
}

internal class StmInstallerRecoveryInjectedFault(
    failpoint: StmInstallerRecoveryExecutorFailpoint,
) : RuntimeException("Injected recovery fault at $failpoint")

private class RecoveryExecutorException(
    val code: StmInstallerRecoveryExecutionCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private fun String.canonicalRecoveryUuidOrNull(): String? =
    runCatching { UUID.fromString(this).toString() }
        .getOrNull()
        ?.takeIf { it == this && it != RECOVERY_NIL_UUID }

private fun bestEffortSyncRecoveryDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // File mutation remains bounded; directory fsync is not available on every file system.
    } catch (_: UnsupportedOperationException) {
        // No fallback mutation is attempted.
    } catch (_: SecurityException) {
        // No fallback mutation is attempted.
    }
}

private fun Throwable.safeRecoveryExecutorDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(MAX_EXECUTION_DETAIL_CHARS)

private const val STAGING_DIRECTORY = "staging"
private const val QUARANTINE_DIRECTORY = "staging-quarantine"
private const val STATE_DIRECTORY = "state"
private const val JOURNAL_DIRECTORY = "installer-journals"
private const val MAX_CLEANUP_DEPTH = 128
// The extractor permits 100,000 path nodes; recovery also visits its bounded wrapper/control dirs.
private const val MAX_CLEANUP_ENTRIES = 100_256
private const val MAX_EXECUTION_DETAIL_CHARS = 500
private const val RECOVERY_NIL_UUID = "00000000-0000-0000-0000-000000000000"
