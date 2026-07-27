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
import io.github.styx798.sillytavernmanager.core.instances.StInstance
import io.github.styx798.sillytavernmanager.core.instances.StInstanceDataMode
import io.github.styx798.sillytavernmanager.core.instances.StInstanceInstallCoordinator
import io.github.styx798.sillytavernmanager.core.instances.StInstanceRepository
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExportResult
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExportState
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExporter
import io.github.styx798.sillytavernmanager.core.logging.LogLevel
import io.github.styx798.sillytavernmanager.core.logging.LogRepository
import io.github.styx798.sillytavernmanager.core.logging.LogSource
import io.github.styx798.sillytavernmanager.core.logging.SillyTavernLogReader
import io.github.styx798.sillytavernmanager.core.logging.SillyTavernLogSnapshot
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreController
import io.github.styx798.sillytavernmanager.stmcore.StmCoreInstallMode
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreWaitKind
import io.github.styx798.sillytavernmanager.core.settings.AppLanguage
import io.github.styx798.sillytavernmanager.core.settings.SettingsRepository
import io.github.styx798.sillytavernmanager.core.settings.ThemeMode
import io.github.styx798.sillytavernmanager.core.userdata.UserDataBackupRepository
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobState
import io.github.styx798.sillytavernmanager.stmcore.StmCoreJobType
import io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class StmViewModel(container: AppContainer) : ViewModel() {
    private val diagnosticLogExporter: DiagnosticLogExporter = container.diagnosticLogExporter
    private val stmCoreController: StmCoreController = container.stmCoreController
    private val logRepository: LogRepository = container.logRepository
    private val sillyTavernLogReader: SillyTavernLogReader = container.sillyTavernLogReader
    private val settingsRepository: SettingsRepository = container.settingsRepository
    private val downloadRepository: StDownloadRepository = container.downloadRepository
    private val filesRepository: AppFilesRepository = container.filesRepository
    private val instanceRepository: StInstanceRepository = container.instanceRepository
    private val userDataBackupRepository: UserDataBackupRepository =
        container.userDataBackupRepository
    private val mutableAppFilesState = MutableStateFlow(AppFilesState())
    private val mutableDiagnosticLogExportState = MutableStateFlow(DiagnosticLogExportState())
    private val mutableSillyTavernLogSnapshot = MutableStateFlow(SillyTavernLogSnapshot())
    private val instanceInstallCoordinator = StInstanceInstallCoordinator(
        scope = viewModelScope,
        downloadRepository = downloadRepository,
        instanceRepository = instanceRepository,
        stmCoreController = stmCoreController,
        logRepository = logRepository,
    )
    private var sillyTavernLogPollingJob: Job? = null
    private var pendingSelectionInstanceId: String? = null
    private val attemptedLegacyMigrations = mutableSetOf<String>()
    private val observedUserDataTerminalJobs = mutableSetOf<String>()

    val stmCoreState = stmCoreController.state
    val stmCoreConnectionState = stmCoreController.connectionState
    val logEntries = logRepository.entries
    val settings = settingsRepository.settings
    val downloadState = downloadRepository.state
    val appFilesState = mutableAppFilesState.asStateFlow()
    val diagnosticLogExportState = mutableDiagnosticLogExportState.asStateFlow()
    val sillyTavernLogSnapshot = mutableSillyTavernLogSnapshot.asStateFlow()
    val instanceState = instanceRepository.state
    val instanceInstallState = instanceInstallCoordinator.state
    val userDataBackupState = userDataBackupRepository.state

    init {
        viewModelScope.launch {
            stmCoreState.collect { state ->
                adoptLegacyInstanceIfNeeded(state)
                reconcileActiveInstance(state)
                completePendingInstanceSelection(state)
                reconcileLegacyDataMigration(state)
                refreshAfterUserDataOperation(state)
            }
        }
        refreshUserDataBackups()
    }

    fun setSillyTavernLogsVisible(visible: Boolean) {
        if (!visible) {
            sillyTavernLogPollingJob?.cancel()
            sillyTavernLogPollingJob = null
            return
        }
        if (sillyTavernLogPollingJob?.isActive == true) return
        sillyTavernLogPollingJob = viewModelScope.launch {
            while (true) {
                mutableSillyTavernLogSnapshot.value = sillyTavernLogReader.readTail()
                delay(SILLY_TAVERN_LOG_REFRESH_MILLIS)
            }
        }
    }

    fun startCore() {
        val instance = instanceRepository.state.value.activeInstance
        val active = stmCoreState.value.activeSlot
        if (instance == null ||
            active?.slotId != instance.slotId ||
            active.slotRevision != instance.slotRevision
        ) {
            logRepository.append(
                source = LogSource.APP,
                level = LogLevel.WARNING,
                message = "Select an available ST instance before starting SillyTavern",
            )
            return
        }
        dispatchCoreCommand { stmCoreController.start(instance.coreDataInstanceId) }
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

    fun renameInstance(instanceId: String, displayName: String) {
        instanceRepository.rename(instanceId, displayName)
    }

    fun clearInstanceError() {
        instanceRepository.clearError()
    }

    fun refreshUserDataBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            userDataBackupRepository.refresh()
        }
    }

    fun clearUserDataBackupResult() {
        userDataBackupRepository.clearResult()
    }

    fun createUserDataBackup(instanceId: String) {
        val instance = instanceRepository.state.value.instances
            .singleOrNull { it.id == instanceId }
            ?: return
        dispatchCoreCommand {
            stmCoreController.createUserDataBackup(instance.id, instance.displayName)
        }
    }

    fun replaceUserData(instanceId: String, source: Uri, backupFirst: Boolean) {
        val instance = instanceRepository.state.value.instances
            .singleOrNull { it.id == instanceId }
            ?: return
        dispatchCoreCommand {
            stmCoreController.replaceUserData(
                instance.id,
                instance.displayName,
                source,
                backupFirst,
            )
        }
    }

    fun restoreUserDataBackup(instanceId: String, backupFileName: String) {
        dispatchCoreCommand {
            stmCoreController.restoreUserDataBackup(instanceId, backupFileName)
        }
    }

    fun deleteUserDataBackup(instanceId: String, backupFileName: String) {
        dispatchCoreCommand {
            stmCoreController.deleteUserDataBackup(instanceId, backupFileName)
        }
    }

    fun exportUserDataBackup(
        instanceId: String,
        backupFileName: String,
        destination: Uri,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = userDataBackupRepository.export(
                instanceId,
                backupFileName,
                destination,
            )
            if (result.isFailure) {
                logRepository.append(
                    source = LogSource.APP,
                    level = LogLevel.ERROR,
                    message = result.exceptionOrNull()?.message ?: "User-data backup export failed",
                )
            }
        }
    }

    fun retryLegacyDataMigration(instanceId: String) {
        attemptedLegacyMigrations.remove(instanceId)
        reconcileLegacyDataMigration(stmCoreState.value)
    }

    fun selectInstance(instanceId: String) {
        val instance = instanceRepository.state.value.instances
            .singleOrNull { it.id == instanceId }
            ?: return
        val core = stmCoreState.value
        if (core.runState != io.github.styx798.sillytavernmanager.stmcore.StmCoreRunState.STOPPED) {
            logRepository.append(
                source = LogSource.APP,
                level = LogLevel.WARNING,
                message = "Stop SillyTavern before selecting another instance",
            )
            return
        }
        if (core.activeSlot?.slotId == instance.slotId &&
            core.activeSlot?.slotRevision == instance.slotRevision
        ) {
            instanceRepository.select(instance.id)
            return
        }
        pendingSelectionInstanceId = instance.id
        viewModelScope.launch {
            when (val result = stmCoreController.activate(instance.slotId)) {
                StmCoreCommandResult.Accepted -> Unit
                is StmCoreCommandResult.Rejected -> {
                    pendingSelectionInstanceId = null
                    logRepository.append(
                        source = LogSource.APP,
                        level = LogLevel.WARNING,
                        message = result.reason,
                    )
                }
            }
        }
    }

    fun installNewInstance(
        displayName: String,
        channel: StDownloadChannel = StDownloadChannel.STABLE,
    ) = instanceInstallCoordinator.install(displayName, channel)

    fun cancelInstanceInstall() = instanceInstallCoordinator.cancel()

    fun dismissInstanceInstall() = instanceInstallCoordinator.dismiss()

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
        val instance = instanceRepository.state.value.instances
            .singleOrNull { it.slotId == slotId }
        if (instance == null) {
            logRepository.append(
                source = LogSource.APP,
                level = LogLevel.WARNING,
                message = "Only a slot registered to an ST instance can be activated",
            )
            return
        }
        selectInstance(instance.id)
    }

    fun rollbackActiveSlot() {
        dispatchCoreCommand(stmCoreController::rollback)
    }

    fun removeSlot(slotId: String) {
        if (instanceRepository.state.value.instances.any { it.slotId == slotId }) {
            logRepository.append(
                source = LogSource.APP,
                level = LogLevel.WARNING,
                message = "A slot used by an ST instance cannot be removed",
            )
            return
        }
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

    private fun completePendingInstanceSelection(coreState: StmCoreState) {
        val instanceId = pendingSelectionInstanceId ?: return
        val instance = instanceRepository.state.value.instances
            .singleOrNull { it.id == instanceId }
            ?: run {
                pendingSelectionInstanceId = null
                return
            }
        val active = coreState.activeSlot
        if (active?.slotId == instance.slotId &&
            active.slotRevision == instance.slotRevision
        ) {
            instanceRepository.select(instance.id)
            pendingSelectionInstanceId = null
        }
    }

    private fun reconcileActiveInstance(coreState: StmCoreState) {
        val active = coreState.activeSlot ?: return
        val matching = instanceRepository.state.value.instances.singleOrNull {
            it.slotId == active.slotId && it.slotRevision == active.slotRevision
        } ?: return
        if (instanceRepository.state.value.activeInstanceId != matching.id) {
            instanceRepository.select(matching.id)
        }
    }

    private fun adoptLegacyInstanceIfNeeded(coreState: StmCoreState) {
        if (instanceRepository.state.value.instances.isNotEmpty()) return
        val active = coreState.activeSlot ?: return
        val slot = coreState.slots.singleOrNull {
            it.id == active.slotId &&
                it.revision == active.slotRevision &&
                it.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
        } ?: return
        val version = slot.artifact?.stVersion ?: return
        instanceRepository.add(
            StInstance(
                id = UUID.randomUUID().toString(),
                displayName = "SillyTavern $version",
                slotId = slot.id,
                slotRevision = slot.revision,
                stVersion = version,
                createdAtEpochMs = System.currentTimeMillis(),
                dataMode = StInstanceDataMode.LEGACY_SHARED_ROOT,
            ),
            makeActive = true,
        )
    }

    private fun reconcileLegacyDataMigration(coreState: StmCoreState) {
        val legacy = instanceRepository.state.value.instances
            .singleOrNull { it.dataMode == StInstanceDataMode.LEGACY_SHARED_ROOT }
            ?: return
        val migrationJob = coreState.jobs
            .filter {
                it.type == StmCoreJobType.USER_DATA_MIGRATE &&
                    it.targetId == legacy.id
            }
            .maxByOrNull { it.updatedAtEpochMs }
        if (migrationJob?.state == StmCoreJobState.SUCCEEDED) {
            if (instanceRepository.updateDataMode(
                    legacy.id,
                    StInstanceDataMode.ISOLATED,
                ).isSuccess
            ) {
                dispatchCoreCommand {
                    stmCoreController.finalizeLegacyUserDataMigration(legacy.id)
                }
            }
            return
        }
        if (migrationJob?.state in setOf(
                StmCoreJobState.QUEUED,
                StmCoreJobState.RUNNING,
                StmCoreJobState.CANCELLING,
            )
        ) {
            return
        }
        if (!coreState.installerRecoveryComplete ||
            coreState.runState != StmCoreRunState.STOPPED ||
            coreState.jobs.any {
                it.state in setOf(
                    StmCoreJobState.QUEUED,
                    StmCoreJobState.RUNNING,
                    StmCoreJobState.CANCELLING,
                )
            } ||
            !attemptedLegacyMigrations.add(legacy.id)
        ) {
            return
        }
        dispatchCoreCommand {
            stmCoreController.migrateLegacyUserData(legacy.id)
        }
    }

    private fun refreshAfterUserDataOperation(coreState: StmCoreState) {
        val completed = coreState.jobs.filter {
            it.type in USER_DATA_JOB_TYPES &&
                it.state in setOf(
                    StmCoreJobState.SUCCEEDED,
                    StmCoreJobState.FAILED,
                    StmCoreJobState.CANCELLED,
                )
        }
        if (completed.none { observedUserDataTerminalJobs.add(it.operationId) }) return
        refreshUserDataBackups()
    }

    private companion object {
        const val SILLY_TAVERN_LOG_REFRESH_MILLIS = 1_000L
        val USER_DATA_JOB_TYPES = setOf(
            StmCoreJobType.USER_DATA_BACKUP,
            StmCoreJobType.USER_DATA_IMPORT,
            StmCoreJobType.USER_DATA_RESTORE,
            StmCoreJobType.USER_DATA_DELETE_BACKUP,
            StmCoreJobType.USER_DATA_MIGRATE,
            StmCoreJobType.USER_DATA_FINALIZE_MIGRATION,
        )
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
