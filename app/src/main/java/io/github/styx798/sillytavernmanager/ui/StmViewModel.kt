package io.github.styx798.sillytavernmanager.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.styx798.sillytavernmanager.app.AppContainer
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadRepository
import io.github.styx798.sillytavernmanager.core.files.AppFileEntry
import io.github.styx798.sillytavernmanager.core.files.AppFileResult
import io.github.styx798.sillytavernmanager.core.files.AppFileRoot
import io.github.styx798.sillytavernmanager.core.files.AppFilesRepository
import io.github.styx798.sillytavernmanager.core.files.AppFilesState
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExportResult
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExportState
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExporter
import io.github.styx798.sillytavernmanager.core.logging.LogLevel
import io.github.styx798.sillytavernmanager.core.logging.LogRepository
import io.github.styx798.sillytavernmanager.core.logging.LogSource
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreController
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import io.github.styx798.sillytavernmanager.stmcore.StmCoreWaitKind
import io.github.styx798.sillytavernmanager.core.settings.AppLanguage
import io.github.styx798.sillytavernmanager.core.settings.SettingsRepository
import io.github.styx798.sillytavernmanager.core.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StmViewModel(container: AppContainer) : ViewModel() {
    private val diagnosticLogExporter: DiagnosticLogExporter = container.diagnosticLogExporter
    private val stmCoreController: StmCoreController = container.stmCoreController
    private val logRepository: LogRepository = container.logRepository
    private val settingsRepository: SettingsRepository = container.settingsRepository
    private val downloadRepository: StDownloadRepository = container.downloadRepository
    private val filesRepository: AppFilesRepository = container.filesRepository
    private val mutableAppFilesState = MutableStateFlow(AppFilesState())
    private val mutableDiagnosticLogExportState = MutableStateFlow(DiagnosticLogExportState())

    val stmCoreState = stmCoreController.state
    val stmCoreConnectionState = stmCoreController.connectionState
    val logEntries = logRepository.entries
    val settings = settingsRepository.settings
    val downloadState = downloadRepository.state
    val appFilesState = mutableAppFilesState.asStateFlow()
    val diagnosticLogExportState = mutableDiagnosticLogExportState.asStateFlow()

    fun startCore() {
        dispatchCoreCommand(stmCoreController::start)
    }

    fun stopCore() {
        dispatchCoreCommand(stmCoreController::stop)
    }

    fun openCore() {
        dispatchCoreCommand(stmCoreController::openCore)
    }

    fun restartCore() {
        dispatchCoreCommand(stmCoreController::restartCore)
    }

    fun closeCore() {
        dispatchCoreCommand(stmCoreController::closeCore)
    }

    fun continueWaiting(operationId: String) {
        dispatchCoreCommand { stmCoreController.continueWaiting(operationId) }
    }

    fun cancelWait(operationId: String, kind: StmCoreWaitKind) {
        if (kind == StmCoreWaitKind.SILLY_TAVERN_START) {
            stopCore()
        } else {
            cancelCoreJob(operationId)
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        settingsRepository.setThemeMode(themeMode)
    }

    fun setLanguage(language: AppLanguage) {
        settingsRepository.setLanguage(language)
    }

    fun startDownload(channel: StDownloadChannel) {
        downloadRepository.start(channel)
    }

    fun cancelDownload() {
        downloadRepository.cancel()
    }

    fun deleteDownload(channel: StDownloadChannel) {
        downloadRepository.delete(channel)
    }

    fun deleteAllDownloads() {
        downloadRepository.deleteAll()
    }

    fun clearDownloadFailure() {
        downloadRepository.clearFailure()
    }

    fun exportDiagnosticLogs(destination: Uri) {
        if (mutableDiagnosticLogExportState.value.exporting) return
        mutableDiagnosticLogExportState.value = DiagnosticLogExportState(exporting = true)
        viewModelScope.launch(Dispatchers.IO) {
            when (
                val result = diagnosticLogExporter.export(
                    destination = destination,
                    coreState = stmCoreState.value,
                    coreConnectionState = stmCoreConnectionState.value,
                    entries = logEntries.value,
                )
            ) {
                DiagnosticLogExportResult.Success -> {
                    logRepository.append(
                        source = LogSource.APP,
                        level = LogLevel.INFO,
                        message = "STM diagnostic report exported",
                    )
                    mutableDiagnosticLogExportState.value =
                        DiagnosticLogExportState(completed = true)
                }

                is DiagnosticLogExportResult.Failure -> {
                    logRepository.append(
                        source = LogSource.APP,
                        level = LogLevel.ERROR,
                        message = "STM diagnostic report export failed: " +
                            result.diagnosticDetail.lineSequence().firstOrNull().orEmpty(),
                    )
                    mutableDiagnosticLogExportState.value =
                        DiagnosticLogExportState(failed = true)
                }
            }
        }
    }

    fun clearDiagnosticLogExportResult() {
        mutableDiagnosticLogExportState.value = DiagnosticLogExportState()
    }

    fun importDownloadedArchive(archive: DownloadedStArchive) {
        val exactCommit = archive.identity.exactCommit
        if (exactCommit == null) {
            logRepository.append(
                source = LogSource.APP,
                level = LogLevel.WARNING,
                message = "Only an exact-commit SillyTavern archive can enter Core preflight",
            )
            return
        }
        val slotId = "st-${archive.channel.branch}-${exactCommit.lowercase()}"
        dispatchCoreCommand {
            stmCoreController.importDownloadedArchive(slotId, archive)
        }
    }

    fun installDownloadedArchive(
        archive: DownloadedStArchive,
        installMode: StmCoreInstallMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
    ) {
        val exactCommit = archive.identity.exactCommit
        if (exactCommit == null) {
            logRepository.append(
                source = LogSource.APP,
                level = LogLevel.WARNING,
                message = "Only an exact-commit SillyTavern archive can enter Core installation",
            )
            return
        }
        val slotId = "st-${archive.channel.branch}-${exactCommit.lowercase()}"
        dispatchCoreCommand {
            stmCoreController.installDownloadedArchive(slotId, archive, installMode)
        }
    }

    fun activateSlot(slotId: String) {
        dispatchCoreCommand { stmCoreController.activate(slotId) }
    }

    fun rollbackActiveSlot() {
        dispatchCoreCommand(stmCoreController::rollback)
    }

    fun removeSlot(slotId: String) {
        dispatchCoreCommand { stmCoreController.remove(slotId) }
    }

    fun verifySlot(slotId: String) {
        dispatchCoreCommand { stmCoreController.verifySlot(slotId) }
    }

    fun cancelCoreJob(operationId: String) {
        dispatchCoreCommand { stmCoreController.cancelJob(operationId) }
    }

    fun openAppFiles() {
        if (mutableAppFilesState.value.listing == null) {
            loadFiles(AppFileRoot.INTERNAL, "")
        }
    }

    fun selectAppFileRoot(root: AppFileRoot) {
        loadFiles(root, "")
    }

    fun openAppFile(entry: AppFileEntry) {
        val listing = mutableAppFilesState.value.listing ?: return
        if (entry.isDirectory) {
            loadFiles(listing.root, entry.relativePath)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = filesRepository.readText(listing.root, entry.relativePath)) {
                is AppFileResult.Success -> mutableAppFilesState.update {
                    it.copy(editor = result.value, error = null)
                }

                is AppFileResult.Failure -> mutableAppFilesState.update {
                    it.copy(error = result.error)
                }
            }
        }
    }

    fun navigateUpInAppFiles() {
        val listing = mutableAppFilesState.value.listing ?: return
        if (listing.relativeDirectory.isBlank()) return
        loadFiles(
            root = listing.root,
            relativeDirectory = listing.relativeDirectory.substringBeforeLast(
                delimiter = '/',
                missingDelimiterValue = "",
            ),
        )
    }

    fun refreshAppFiles() {
        val listing = mutableAppFilesState.value.listing ?: return openAppFiles()
        loadFiles(listing.root, listing.relativeDirectory)
    }

    fun saveAppFile(text: String) {
        val editor = mutableAppFilesState.value.editor ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = filesRepository.writeText(editor.root, editor.relativePath, text)) {
                is AppFileResult.Success -> {
                    mutableAppFilesState.update { it.copy(editor = null, error = null) }
                    refreshAppFiles()
                }

                is AppFileResult.Failure -> mutableAppFilesState.update {
                    it.copy(error = result.error)
                }
            }
        }
    }

    fun closeAppFileEditor() {
        mutableAppFilesState.update { it.copy(editor = null) }
    }

    fun deleteAppFile(entry: AppFileEntry) {
        val listing = mutableAppFilesState.value.listing ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = filesRepository.delete(listing.root, entry.relativePath)) {
                is AppFileResult.Success -> refreshAppFiles()
                is AppFileResult.Failure -> mutableAppFilesState.update {
                    it.copy(error = result.error)
                }
            }
        }
    }

    fun clearAppFileError() {
        mutableAppFilesState.update { it.copy(error = null) }
    }

    private fun loadFiles(root: AppFileRoot, relativeDirectory: String) {
        mutableAppFilesState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = filesRepository.list(root, relativeDirectory)) {
                is AppFileResult.Success -> mutableAppFilesState.update {
                    it.copy(listing = result.value, loading = false, error = null)
                }

                is AppFileResult.Failure -> mutableAppFilesState.update {
                    it.copy(loading = false, error = result.error)
                }
            }
        }
    }

    private fun dispatchCoreCommand(command: suspend () -> StmCoreCommandResult) {
        viewModelScope.launch {
            when (val result = command()) {
                StmCoreCommandResult.Accepted -> Unit
                is StmCoreCommandResult.Rejected -> logRepository.append(
                    source = LogSource.APP,
                    level = LogLevel.WARNING,
                    message = result.reason,
                )
            }
        }
    }

    class Factory(
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(StmViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return StmViewModel(container) as T
        }
    }
}
