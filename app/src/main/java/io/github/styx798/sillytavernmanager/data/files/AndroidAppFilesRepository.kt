package io.github.styx798.sillytavernmanager.data.files

import android.content.Context
import android.util.AtomicFile
import io.github.styx798.sillytavernmanager.core.files.AppFileEntry
import io.github.styx798.sillytavernmanager.core.files.AppFileError
import io.github.styx798.sillytavernmanager.core.files.AppFileListing
import io.github.styx798.sillytavernmanager.core.files.AppFileResult
import io.github.styx798.sillytavernmanager.core.files.AppFileRoot
import io.github.styx798.sillytavernmanager.core.files.AppFilesRepository
import io.github.styx798.sillytavernmanager.core.files.AppTextEditor
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class AndroidAppFilesRepository(context: Context) : AppFilesRepository {
    private val appContext = context.applicationContext

    override fun list(
        root: AppFileRoot,
        relativeDirectory: String,
    ): AppFileResult<AppFileListing> {
        val rootDirectory = rootDirectory(root)
            ?: return AppFileResult.Failure(AppFileError.ROOT_UNAVAILABLE)
        val directory = resolve(rootDirectory, relativeDirectory)
            ?: return AppFileResult.Failure(AppFileError.PATH_OUTSIDE_ROOT)
        if (StmCorePaths.isReserved(appContext, directory)) {
            return AppFileResult.Failure(AppFileError.RESERVED_CORE_PATH)
        }
        if (!directory.isDirectory) return AppFileResult.Failure(AppFileError.READ_FAILED)

        val children = try {
            directory.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return AppFileResult.Failure(AppFileError.READ_FAILED)

        val entries = children
            .mapNotNull { child ->
                val canonicalChild = try {
                    child.canonicalFile
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                if (!isInside(rootDirectory, canonicalChild) || canonicalChild == rootDirectory) {
                    return@mapNotNull null
                }
                if (StmCorePaths.isReserved(appContext, canonicalChild)) {
                    return@mapNotNull null
                }
                AppFileEntry(
                    name = canonicalChild.name,
                    relativePath = canonicalChild.relativeTo(rootDirectory).invariantSeparatorsPath,
                    isDirectory = canonicalChild.isDirectory,
                    sizeBytes = if (canonicalChild.isFile) canonicalChild.length() else 0L,
                    editable = canonicalChild.isFile &&
                        isEditableTextFile(canonicalChild.name, canonicalChild.length()),
                )
            }
            .sortedWith(
                compareBy<AppFileEntry> { !it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )

        return AppFileResult.Success(
            AppFileListing(
                root = root,
                rootPath = rootDirectory.absolutePath,
                relativeDirectory = directory.relativeTo(rootDirectory).invariantSeparatorsPath,
                entries = entries,
            ),
        )
    }

    override fun readText(
        root: AppFileRoot,
        relativePath: String,
    ): AppFileResult<AppTextEditor> {
        val rootDirectory = rootDirectory(root)
            ?: return AppFileResult.Failure(AppFileError.ROOT_UNAVAILABLE)
        val file = resolve(rootDirectory, relativePath)
            ?: return AppFileResult.Failure(AppFileError.PATH_OUTSIDE_ROOT)
        if (StmCorePaths.isReserved(appContext, file)) {
            return AppFileResult.Failure(AppFileError.RESERVED_CORE_PATH)
        }
        if (!file.isFile) return AppFileResult.Failure(AppFileError.READ_FAILED)
        if (file.length() > MAX_EDITABLE_FILE_BYTES) {
            return AppFileResult.Failure(AppFileError.FILE_TOO_LARGE)
        }
        if (!isEditableTextFile(file.name, file.length())) {
            return AppFileResult.Failure(AppFileError.UNSUPPORTED_FILE)
        }

        return try {
            AppFileResult.Success(
                AppTextEditor(
                    root = root,
                    relativePath = relativePath,
                    name = file.name,
                    text = file.readText(Charsets.UTF_8),
                ),
            )
        } catch (_: Exception) {
            AppFileResult.Failure(AppFileError.READ_FAILED)
        }
    }

    override fun writeText(
        root: AppFileRoot,
        relativePath: String,
        text: String,
    ): AppFileResult<Unit> {
        val rootDirectory = rootDirectory(root)
            ?: return AppFileResult.Failure(AppFileError.ROOT_UNAVAILABLE)
        val file = resolve(rootDirectory, relativePath)
            ?: return AppFileResult.Failure(AppFileError.PATH_OUTSIDE_ROOT)
        if (StmCorePaths.isReserved(appContext, file)) {
            return AppFileResult.Failure(AppFileError.RESERVED_CORE_PATH)
        }
        if (!file.isFile) return AppFileResult.Failure(AppFileError.WRITE_FAILED)
        val byteContent = text.toByteArray(Charsets.UTF_8)
        if (!isEditableTextFile(file.name, byteContent.size.toLong())) {
            return AppFileResult.Failure(AppFileError.FILE_TOO_LARGE)
        }

        val atomicFile = AtomicFile(file)
        val output = try {
            atomicFile.startWrite()
        } catch (_: Exception) {
            return AppFileResult.Failure(AppFileError.WRITE_FAILED)
        }

        return try {
            output.write(byteContent)
            atomicFile.finishWrite(output)
            AppFileResult.Success(Unit)
        } catch (_: Exception) {
            atomicFile.failWrite(output)
            AppFileResult.Failure(AppFileError.WRITE_FAILED)
        }
    }

    override fun delete(
        root: AppFileRoot,
        relativePath: String,
    ): AppFileResult<Unit> {
        val rootDirectory = rootDirectory(root)
            ?: return AppFileResult.Failure(AppFileError.ROOT_UNAVAILABLE)
        val target = resolve(rootDirectory, relativePath)
            ?: return AppFileResult.Failure(AppFileError.PATH_OUTSIDE_ROOT)
        if (target == rootDirectory || relativePath.isBlank()) {
            return AppFileResult.Failure(AppFileError.PATH_OUTSIDE_ROOT)
        }
        if (StmCorePaths.isReservedOrAncestor(appContext, target)) {
            return AppFileResult.Failure(AppFileError.RESERVED_CORE_PATH)
        }

        val deleted = try {
            deleteTreeNoFollow(target) { entry ->
                StmCorePaths.isReservedOrAncestor(appContext, entry)
            }
        } catch (_: Exception) {
            false
        }
        return if (deleted) {
            AppFileResult.Success(Unit)
        } else {
            AppFileResult.Failure(AppFileError.DELETE_FAILED)
        }
    }

    private fun rootDirectory(root: AppFileRoot): File? = try {
        when (root) {
            AppFileRoot.INTERNAL -> appContext.filesDir.parentFile
            AppFileRoot.EXTERNAL -> appContext.getExternalFilesDir(null)?.parentFile
        }?.canonicalFile
    } catch (_: Exception) {
        null
    }

    private fun resolve(rootDirectory: File, relativePath: String): File? = try {
        val candidate = if (relativePath.isBlank()) {
            rootDirectory
        } else {
            File(rootDirectory, relativePath).canonicalFile
        }
        candidate.takeIf { isInside(rootDirectory, it) }
    } catch (_: Exception) {
        null
    }
}

internal fun deleteTreeNoFollow(
    target: File,
    isReservedOrAncestor: (File) -> Boolean,
): Boolean {
    val root = target.toPath().toAbsolutePath().normalize()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
    if (Files.isSymbolicLink(root)) {
        Files.delete(root)
        return true
    }
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        if (isReservedOrAncestor(root.toFile())) return false
        Files.delete(root)
        return true
    }

    var visited = 0
    Files.walkFileTree(
        root,
        emptySet(),
        MAX_DELETE_DEPTH,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                validateVisitedEntry(root, directory, attributes, expectDirectory = true)
                if (isReservedOrAncestor(directory.toFile())) {
                    throw IOException("Deletion encountered a reserved STM path")
                }
                visited += 1
                require(visited <= MAX_DELETE_ENTRIES) { "Deletion tree contains too many entries" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                validateVisitedEntry(root, file, attributes, expectDirectory = false)
                if (!attributes.isSymbolicLink && isReservedOrAncestor(file.toFile())) {
                    throw IOException("Deletion encountered a reserved STM path")
                }
                Files.delete(file)
                visited += 1
                require(visited <= MAX_DELETE_ENTRIES) { "Deletion tree contains too many entries" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                throw error
            }

            override fun postVisitDirectory(
                directory: Path,
                error: IOException?,
            ): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
    return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
}

private fun validateVisitedEntry(
    root: Path,
    entry: Path,
    attributes: BasicFileAttributes,
    expectDirectory: Boolean,
) {
    val normalized = entry.toAbsolutePath().normalize()
    require(normalized == root || normalized.startsWith(root)) { "Deletion escaped its target root" }
    if (expectDirectory) {
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "Deletion encountered an unsafe directory entry"
        }
    } else {
        require(!attributes.isDirectory) { "Deletion file callback received a directory" }
    }
}

internal fun isEditableTextFile(fileName: String, sizeBytes: Long): Boolean {
    if (sizeBytes < 0 || sizeBytes > MAX_EDITABLE_FILE_BYTES) return false
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in EDITABLE_TEXT_EXTENSIONS
}

private fun isInside(rootDirectory: File, candidate: File): Boolean =
    candidate == rootDirectory || candidate.path.startsWith(rootDirectory.path + File.separator)

internal const val MAX_EDITABLE_FILE_BYTES = 1024L * 1024L
private const val MAX_DELETE_DEPTH = 128
private const val MAX_DELETE_ENTRIES = 100_000

private val EDITABLE_TEXT_EXTENSIONS = setOf(
    "cjs",
    "conf",
    "css",
    "env",
    "gitignore",
    "html",
    "htm",
    "ini",
    "js",
    "json",
    "log",
    "md",
    "mjs",
    "properties",
    "toml",
    "txt",
    "xml",
    "yaml",
    "yml",
)
