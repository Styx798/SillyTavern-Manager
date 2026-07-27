package io.github.styx798.sillytavernmanager.stmcore.userdata

import io.github.styx798.sillytavernmanager.stmcore.installer.StmSafeZipExtractor
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipExtractionMode
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipExtractionPolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class StmUserDataBackupResult(
    val fileName: String,
    val sizeBytes: Long,
)

internal class StmUserDataManager(
    private val legacyDataRoot: File,
    private val instancesRoot: File,
    private val backupsRoot: File,
    private val cacheRoot: File,
    private val zipExtractor: StmSafeZipExtractor = StmSafeZipExtractor(),
) {
    fun recoverInterruptedOperations() {
        recoverDataRoot(legacyDataRoot)
        safeChildren(instancesRoot).forEach { instance ->
            recoverDataRoot(instance.resolve(DATA_DIRECTORY))
        }
        safeChildren(instancesRoot).forEach { instance ->
            recoverLegacyMigration(instance)
        }
        safeChildren(backupsRoot)
            .filter { it.isDirectory }
            .forEach { instanceBackups ->
                safeChildren(instanceBackups)
                    .filter { it.name.startsWith(".") && it.name.endsWith(".partial") }
                    .forEach { deleteTreeIfExists(it.toPath()) }
            }
        deleteMatchingChildren(cacheRoot, IMPORT_CACHE_PREFIX)
    }

    fun createBackup(
        instanceId: String,
        displayName: String,
        operationId: String,
    ): StmUserDataBackupResult {
        val dataRoot = requireInstanceDataRoot(instanceId)
        val source = requireRealDirectory(dataRoot.resolve(DEFAULT_USER_DIRECTORY), "ST user data")
        val instanceBackups = initializeDirectory(backupsRoot, instanceId)
        val fileName = backupFileName(displayName, operationId)
        val target = instanceBackups.resolve(fileName)
        val temporary = instanceBackups.resolve(".$fileName.partial")
        require(!target.exists() && !temporary.exists()) { "Backup target already exists" }
        try {
            writeOfficialCompatibleZip(source.toPath(), temporary.toPath())
            moveAtomically(temporary.toPath(), target.toPath())
            return StmUserDataBackupResult(fileName, target.length())
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary.toPath())
            throw error
        }
    }

    fun replaceFromArchive(
        instanceId: String,
        displayName: String,
        operationId: String,
        source: InputStream,
        backupFirst: Boolean,
    ): StmUserDataBackupResult? {
        val backup = if (backupFirst) {
            createBackup(instanceId, displayName, operationId)
        } else {
            null
        }
        val cachedArchive = copyImportToCache(operationId, source)
        try {
            replaceFromFile(instanceId, operationId, cachedArchive)
        } finally {
            Files.deleteIfExists(cachedArchive.toPath())
        }
        return backup
    }

    fun restoreBackup(instanceId: String, operationId: String, backupFileName: String) {
        requireSafeBackupFileName(backupFileName)
        val backup = requireRegularFile(
            backupsRoot.resolve(instanceId).resolve(backupFileName),
            "STM user-data backup",
        )
        replaceFromFile(instanceId, operationId, backup)
    }

    fun deleteBackup(instanceId: String, backupFileName: String) {
        requireSafeBackupFileName(backupFileName)
        val backupRoot = backupsRoot.resolve(instanceId)
        val backup = backupRoot.resolve(backupFileName)
        requireRegularFile(backup, "STM user-data backup")
        Files.delete(backup.toPath())
    }

    fun migrateLegacyData(instanceId: String, operationId: String) {
        val source = requireRealDirectory(legacyDataRoot, "Legacy ST data")
        val instanceRoot = initializeDirectory(instancesRoot, instanceId)
        val destination = instanceRoot.resolve(DATA_DIRECTORY)
        if (destination.exists()) {
            requireRealDirectory(destination, "Isolated ST data")
            val completedMarker = destination.resolve(MIGRATION_COMPLETE_MARKER)
            if (isRegularFileNoFollow(completedMarker) &&
                completedMarker.readText().trim() == instanceId
            ) {
                return
            }
            require(isDirectoryEmpty(destination.toPath())) {
                "Isolated ST data already contains files"
            }
            Files.delete(destination.toPath())
        }
        val staging = instanceRoot.resolve("$MIGRATION_PREFIX$operationId")
        require(!staging.exists()) { "Legacy migration staging already exists" }
        try {
            copyTreeNoFollow(source.toPath(), staging.toPath())
            val marker = staging.resolve(MIGRATION_COMPLETE_MARKER)
            FileOutputStream(marker).use { output ->
                output.write("$instanceId\n".toByteArray())
                output.fd.sync()
            }
            moveAtomically(staging.toPath(), destination.toPath())
        } catch (error: Throwable) {
            deleteTreeIfExists(staging.toPath())
            throw error
        }
    }

    fun finalizeLegacyMigration(instanceId: String) {
        val destination = requireInstanceDataRoot(instanceId)
        val marker = destination.resolve(MIGRATION_COMPLETE_MARKER)
        require(isRegularFileNoFollow(marker) && marker.readText().trim() == instanceId) {
            "Legacy migration completion marker is missing"
        }
        deleteTreeIfExists(legacyDataRoot.toPath())
        Files.deleteIfExists(marker.toPath())
    }

    private fun replaceFromFile(instanceId: String, operationId: String, archive: File) {
        val dataRoot = requireInstanceDataRoot(instanceId)
        recoverDataRoot(dataRoot)
        val live = dataRoot.resolve(DEFAULT_USER_DIRECTORY)
        val operationRoot = dataRoot.resolve("$IMPORT_ROOT_PREFIX$operationId")
        val previous = dataRoot.resolve("$PREVIOUS_PREFIX$operationId")
        require(!operationRoot.exists() && !previous.exists()) {
            "User-data replacement staging already exists"
        }
        val extraction = zipExtractor.extract(
            artifact = archive,
            operationStagingRoot = operationRoot,
            policy = USER_BACKUP_POLICY,
            mode = StmZipExtractionMode.STRICT,
        )
        val payload = extraction.payloadDirectory
        try {
            if (live.exists()) {
                requireRealDirectory(live, "ST user data")
                moveAtomically(live.toPath(), previous.toPath())
            }
            moveAtomically(payload.toPath(), live.toPath())
            deleteTreeIfExists(previous.toPath())
            deleteTreeIfExists(operationRoot.toPath())
        } catch (error: Throwable) {
            if (!live.exists() && previous.exists()) {
                moveAtomically(previous.toPath(), live.toPath())
            }
            throw error
        }
    }

    private fun recoverDataRoot(dataRoot: File) {
        if (!dataRoot.exists()) return
        requireRealDirectory(dataRoot, "ST data root")
        val live = dataRoot.resolve(DEFAULT_USER_DIRECTORY)
        val previous = safeChildren(dataRoot)
            .filter { it.name.startsWith(PREVIOUS_PREFIX) }
            .sortedByDescending(File::lastModified)
        if (!live.exists() && previous.isNotEmpty()) {
            moveAtomically(previous.first().toPath(), live.toPath())
        }
        previous.filter(File::exists).forEach { deleteTreeIfExists(it.toPath()) }
        deleteMatchingChildren(dataRoot, IMPORT_ROOT_PREFIX)
    }

    private fun recoverLegacyMigration(instanceRoot: File) {
        if (!instanceRoot.exists()) return
        requireRealDirectory(instanceRoot, "ST instance root")
        deleteMatchingChildren(instanceRoot, MIGRATION_PREFIX)
    }

    private fun copyImportToCache(operationId: String, source: InputStream): File {
        if (!cacheRoot.exists()) Files.createDirectories(cacheRoot.toPath())
        requireRealDirectory(cacheRoot, "STM Core cache")
        val target = cacheRoot.resolve("$IMPORT_CACHE_PREFIX$operationId.zip")
        require(!target.exists()) { "Import cache already exists" }
        var total = 0L
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= USER_BACKUP_POLICY.maxArchiveBytes) {
                        "User-data archive exceeds the import limit"
                    }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            require(total > 0L) { "User-data archive is empty" }
            return target
        } catch (error: Throwable) {
            Files.deleteIfExists(target.toPath())
            throw error
        }
    }

    private fun requireInstanceDataRoot(instanceId: String): File {
        require(instanceId.matches(UUID_ID)) { "ST instance ID is invalid" }
        val root = instancesRoot.resolve(instanceId).resolve(DATA_DIRECTORY)
        return requireRealDirectory(root, "Isolated ST data")
    }

    private fun writeOfficialCompatibleZip(source: Path, target: Path) {
        requireRealDirectory(source.toFile(), "ST user data")
        FileOutputStream(target.toFile()).use { fileOutput ->
            val zip = ZipOutputStream(
                SizeLimitedOutputStream(
                    delegate = fileOutput,
                    limit = USER_BACKUP_POLICY.maxArchiveBytes,
                ).buffered(),
            )
            try {
                Files.walkFileTree(
                    source,
                    setOf(),
                    64,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            require(!attributes.isSymbolicLink) {
                                "User-data backup rejected a symbolic-link directory"
                            }
                            val relative = normalizedRelative(source, directory)
                            if (relative.isNotEmpty()) {
                                zip.putNextEntry(
                                    ZipEntry("$relative/").apply {
                                        method = ZipEntry.STORED
                                        size = 0L
                                        compressedSize = 0L
                                        crc = 0L
                                    },
                                )
                                zip.closeEntry()
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                                "User-data backup accepts only regular files"
                            }
                            val relative = normalizedRelative(source, file)
                            if (isOfficialSecretExclusion(relative)) return FileVisitResult.CONTINUE
                            zip.putNextEntry(ZipEntry(relative))
                            FileInputStream(file.toFile()).use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                zip.finish()
                zip.flush()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        }
    }

    private fun normalizedRelative(root: Path, child: Path): String =
        root.relativize(child).joinToString("/") { segment ->
            Normalizer.normalize(segment.toString(), Normalizer.Form.NFC)
        }

    private fun isOfficialSecretExclusion(relative: String): Boolean =
        relative == "secrets.json" ||
            (relative.startsWith("backups/secrets_migration_") && relative.endsWith(".json"))

    private fun backupFileName(displayName: String, operationId: String): String {
        val slug = Normalizer.normalize(displayName, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .map { character ->
                when {
                    character.isLetterOrDigit() -> character
                    character == '-' || character == '_' -> character
                    else -> '-'
                }
            }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "sillytavern" }
        val timestamp = BACKUP_TIMESTAMP.format(Instant.now())
        return "$slug-$timestamp-${operationId.take(8)}.zip"
    }

    private fun requireSafeBackupFileName(fileName: String) {
        require(fileName.matches(SAFE_BACKUP_NAME)) { "Backup file name is invalid" }
    }

    private fun initializeDirectory(parent: File, name: String): File {
        require(name.matches(UUID_ID)) { "ST instance ID is invalid" }
        if (!parent.exists()) Files.createDirectories(parent.toPath())
        requireRealDirectory(parent, "STM directory")
        val child = parent.resolve(name)
        if (!child.exists()) Files.createDirectory(child.toPath())
        return requireRealDirectory(child, "STM instance directory")
    }

    private fun requireRealDirectory(file: File, label: String): File {
        val path = file.toPath()
        require(Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
        ) { "$label must be a real directory" }
        return file
    }

    private fun requireRegularFile(file: File, label: String): File {
        val path = file.toPath()
        require(Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
        ) { "$label must be a regular file" }
        return file
    }

    private fun isRegularFileNoFollow(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())

    private fun safeChildren(parent: File): List<File> {
        if (!parent.exists()) return emptyList()
        requireRealDirectory(parent, "STM directory")
        return parent.listFiles()?.toList().orEmpty()
    }

    private fun deleteMatchingChildren(parent: File, prefix: String) {
        safeChildren(parent)
            .filter { it.name.startsWith(prefix) }
            .forEach { deleteTreeIfExists(it.toPath()) }
    }

    private fun copyTreeNoFollow(source: Path, destination: Path) {
        Files.walkFileTree(
            source,
            setOf(),
            64,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    require(attributes.isDirectory && !attributes.isSymbolicLink) {
                        "Legacy data contains an unsupported directory"
                    }
                    val relative = source.relativize(directory)
                    Files.createDirectory(destination.resolve(relative))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                        "Legacy data contains an unsupported file type"
                    }
                    Files.copy(
                        file,
                        destination.resolve(source.relativize(file)),
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun isDirectoryEmpty(path: Path): Boolean =
        Files.newDirectoryStream(path).use { !it.iterator().hasNext() }

    private fun moveAtomically(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun deleteTreeIfExists(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) {
            Files.delete(root)
            return
        }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        const val DATA_DIRECTORY = "data"
        const val DEFAULT_USER_DIRECTORY = "default-user"
        const val IMPORT_ROOT_PREFIX = ".stm-user-data-import-"
        const val PREVIOUS_PREFIX = ".stm-user-data-previous-"
        const val MIGRATION_PREFIX = ".stm-legacy-migration-"
        const val MIGRATION_COMPLETE_MARKER = ".stm-legacy-migration-complete"
        const val IMPORT_CACHE_PREFIX = "stm-user-data-import-"
        val UUID_ID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            RegexOption.IGNORE_CASE,
        )
        val SAFE_BACKUP_NAME = Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]{0,119}\\.zip")
        val BACKUP_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        val USER_BACKUP_POLICY = StmZipExtractionPolicy(
            maxArchiveBytes = 2L * 1_024 * 1_024 * 1_024,
            maxEntries = 100_000,
            maxSingleFileBytes = 2L * 1_024 * 1_024 * 1_024,
            maxTotalUncompressedBytes = 16L * 1_024 * 1_024 * 1_024,
            maxCompressionRatio = 500,
        )
    }
}

private class SizeLimitedOutputStream(
    private val delegate: OutputStream,
    private val limit: Long,
) : OutputStream() {
    private var written = 0L

    override fun write(value: Int) {
        requireCapacity(1)
        delegate.write(value)
        written += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        requireCapacity(length)
        delegate.write(buffer, offset, length)
        written += length
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun requireCapacity(additional: Int) {
        if (additional < 0 || written > limit - additional) {
            throw java.io.IOException("User-data backup exceeds the archive size limit")
        }
    }
}
