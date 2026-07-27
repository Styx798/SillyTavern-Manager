package io.github.styx798.sillytavernmanager.stmcore

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object StmCorePaths {
    fun coreRoot(context: Context): File =
        lexicalChild(context.noBackupFilesDir, CORE_DIRECTORY)

    fun stateDirectory(context: Context): File =
        lexicalChild(coreRoot(context), STATE_DIRECTORY)

    fun checkpointFile(context: Context): File =
        lexicalChild(stateDirectory(context), CHECKPOINT_FILE)

    fun activeSlotFile(context: Context): File =
        lexicalChild(stateDirectory(context), ACTIVE_SLOT_FILE)

    fun installerJournalRoot(context: Context): File =
        lexicalChild(stateDirectory(context), INSTALLER_JOURNAL_DIRECTORY)

    fun slotsRoot(context: Context): File =
        lexicalChild(coreRoot(context), SLOTS_DIRECTORY)

    fun stagingRoot(context: Context): File =
        lexicalChild(coreRoot(context), STAGING_DIRECTORY)

    fun catalogRoot(context: Context): File =
        lexicalChild(coreRoot(context), CATALOG_DIRECTORY)

    fun installerCacheRoot(context: Context): File =
        lexicalChild(coreRoot(context), INSTALLER_CACHE_DIRECTORY)

    fun toolchainsRoot(context: Context): File =
        lexicalChild(coreRoot(context), TOOLCHAINS_DIRECTORY)

    fun logsRoot(context: Context): File =
        lexicalChild(coreRoot(context), LOGS_DIRECTORY)

    fun dataRoot(context: Context): File =
        lexicalChild(context.filesDir, DATA_DIRECTORY)

    fun instancesRoot(context: Context): File =
        lexicalChild(context.filesDir, INSTANCES_DIRECTORY)

    fun backupsRoot(context: Context): File =
        lexicalChild(context.filesDir, BACKUPS_DIRECTORY)

    fun instanceDataRoot(context: Context, instanceId: String): File {
        require(instanceId.matches(UUID_ID)) { "ST instance ID is invalid" }
        return lexicalChild(
            lexicalChild(instancesRoot(context), instanceId),
            INSTANCE_DATA_DIRECTORY,
        )
    }

    fun instanceBackupsRoot(context: Context, instanceId: String): File {
        require(instanceId.matches(UUID_ID)) { "ST instance ID is invalid" }
        return lexicalChild(backupsRoot(context), instanceId)
    }

    fun prepareInstanceDataRoot(context: Context, instanceId: String?): File {
        return prepareInstanceDataRootAt(context.filesDir, instanceId)
    }

    internal fun prepareInstanceDataRootAt(filesDirectory: File, instanceId: String?): File {
        val filesRoot = filesDirectory.toPath().toAbsolutePath().normalize()
        requireRealDirectory(filesRoot, "Android files root")
        if (instanceId == null) {
            return initializeDirectChild(filesRoot, DATA_DIRECTORY).toFile()
        }
        require(instanceId.matches(UUID_ID)) { "ST instance ID is invalid" }
        val instances = initializeDirectChild(filesRoot, INSTANCES_DIRECTORY)
        val instance = initializeDirectChild(instances, instanceId)
        return initializeDirectChild(instance, INSTANCE_DATA_DIRECTORY).toFile()
    }

    fun cacheRoot(context: Context): File =
        lexicalChild(context.cacheDir, CORE_DIRECTORY)

    fun sessionsRoot(context: Context): File =
        lexicalChild(cacheRoot(context), SESSIONS_DIRECTORY)

    internal fun initializeCoreLayout(context: Context) {
        initializeCoreLayoutAt(context.noBackupFilesDir)
        initializeCacheLayoutAt(context.cacheDir)
    }

    internal fun initializeCoreLayoutAt(noBackupRoot: File) {
        val trustedRoot = noBackupRoot.toPath().toAbsolutePath().normalize()
        requireRealDirectory(trustedRoot, "Android no-backup root")
        val core = initializeDirectChild(trustedRoot, CORE_DIRECTORY)
        val state = initializeDirectChild(core, STATE_DIRECTORY)
        initializeDirectChild(state, INSTALLER_JOURNAL_DIRECTORY)
        initializeDirectChild(core, SLOTS_DIRECTORY)
        initializeDirectChild(core, STAGING_DIRECTORY)
        initializeDirectChild(core, CATALOG_DIRECTORY)
        initializeDirectChild(core, INSTALLER_CACHE_DIRECTORY)
        initializeDirectChild(core, TOOLCHAINS_DIRECTORY)
        initializeDirectChild(core, LOGS_DIRECTORY)
        requireSafeOptionalControlFile(state.resolve(CHECKPOINT_FILE), "Core checkpoint")
        requireSafeOptionalControlFile(state.resolve(ACTIVE_SLOT_FILE), "active-slot pointer")
    }

    internal fun initializeCacheLayoutAt(cacheRoot: File) {
        val trustedRoot = cacheRoot.toPath().toAbsolutePath().normalize()
        requireRealDirectory(trustedRoot, "Android cache root")
        val core = initializeDirectChild(trustedRoot, CORE_DIRECTORY)
        initializeDirectChild(core, SESSIONS_DIRECTORY)
    }

    fun reservedRoots(context: Context): List<File> = listOf(
        coreRoot(context),
        dataRoot(context),
        instancesRoot(context),
        backupsRoot(context),
        cacheRoot(context),
    )

    fun isReserved(context: Context, candidate: File): Boolean =
        isInsideAny(candidate.canonicalFile, reservedRoots(context))

    fun isReservedOrAncestor(context: Context, candidate: File): Boolean {
        val canonicalCandidate = candidate.canonicalFile
        return reservedRoots(context).any { root ->
            val canonicalRoot = root.canonicalFile
            isInside(canonicalRoot, canonicalCandidate) || isInside(canonicalCandidate, canonicalRoot)
        }
    }

    internal fun isInsideAny(candidate: File, roots: List<File>): Boolean = roots.any { root ->
        val canonicalRoot = root.canonicalFile
        isInside(candidate, canonicalRoot)
    }

    private fun isInside(candidate: File, root: File): Boolean =
        candidate == root || candidate.path.startsWith(root.path + File.separator)

    private fun lexicalChild(parent: File, child: String): File {
        val parentPath = parent.toPath().toAbsolutePath().normalize()
        val path = parentPath.resolve(child).normalize()
        require(path.parent == parentPath) { "STM path escaped its direct parent" }
        return path.toFile()
    }

    private fun initializeDirectChild(parent: Path, name: String): Path {
        requireRealDirectory(parent, "STM path parent")
        val child = parent.resolve(name).normalize()
        require(child.parent == parent) { "STM directory escaped its parent" }
        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            requireRealDirectory(child, "STM directory $name")
        } else {
            Files.createDirectory(child)
        }
        return child
    }

    private fun requireRealDirectory(path: Path, label: String) {
        require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "$label does not exist" }
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "$label must be a real no-follow directory"
        }
    }

    private fun requireSafeOptionalControlFile(path: Path, label: String) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "$label must be a regular no-follow file"
        }
    }

    private const val CORE_DIRECTORY = "stm_core"
    private const val STATE_DIRECTORY = "state"
    private const val CHECKPOINT_FILE = "core-snapshot"
    private const val ACTIVE_SLOT_FILE = "active-slot"
    private const val INSTALLER_JOURNAL_DIRECTORY = "installer-journals"
    private const val SLOTS_DIRECTORY = "slots"
    private const val STAGING_DIRECTORY = "staging"
    private const val CATALOG_DIRECTORY = "catalog"
    private const val INSTALLER_CACHE_DIRECTORY = "installer-cache"
    private const val TOOLCHAINS_DIRECTORY = "toolchains"
    private const val LOGS_DIRECTORY = "logs"
    private const val DATA_DIRECTORY = "stm_data"
    private const val INSTANCES_DIRECTORY = "stm_instances"
    private const val BACKUPS_DIRECTORY = "stm_backups"
    private const val INSTANCE_DATA_DIRECTORY = "data"
    private const val SESSIONS_DIRECTORY = "sessions"
    private val UUID_ID = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        RegexOption.IGNORE_CASE,
    )
}
