package io.github.styx798.sillytavernmanager.core.files

enum class AppFileRoot {
    INTERNAL,
    EXTERNAL,
}

data class AppFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val editable: Boolean,
)

data class AppFileListing(
    val root: AppFileRoot,
    val rootPath: String,
    val relativeDirectory: String,
    val entries: List<AppFileEntry>,
)

data class AppTextEditor(
    val root: AppFileRoot,
    val relativePath: String,
    val name: String,
    val text: String,
)

enum class AppFileError {
    ROOT_UNAVAILABLE,
    PATH_OUTSIDE_ROOT,
    RESERVED_CORE_PATH,
    READ_FAILED,
    WRITE_FAILED,
    DELETE_FAILED,
    FILE_TOO_LARGE,
    UNSUPPORTED_FILE,
}

data class AppFilesState(
    val listing: AppFileListing? = null,
    val editor: AppTextEditor? = null,
    val loading: Boolean = false,
    val error: AppFileError? = null,
)

sealed interface AppFileResult<out T> {
    data class Success<T>(val value: T) : AppFileResult<T>

    data class Failure(val error: AppFileError) : AppFileResult<Nothing>
}
