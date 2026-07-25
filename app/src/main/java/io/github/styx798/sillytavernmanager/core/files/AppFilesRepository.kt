package io.github.styx798.sillytavernmanager.core.files

interface AppFilesRepository {
    fun list(root: AppFileRoot, relativeDirectory: String = ""): AppFileResult<AppFileListing>

    fun readText(root: AppFileRoot, relativePath: String): AppFileResult<AppTextEditor>

    fun writeText(root: AppFileRoot, relativePath: String, text: String): AppFileResult<Unit>

    fun delete(root: AppFileRoot, relativePath: String): AppFileResult<Unit>
}
