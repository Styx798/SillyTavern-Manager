package io.github.styx798.sillytavernmanager.app

import android.content.Context
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadRepository
import io.github.styx798.sillytavernmanager.core.files.AppFilesRepository
import io.github.styx798.sillytavernmanager.core.instances.StInstanceRepository
import io.github.styx798.sillytavernmanager.core.logging.LogRepository
import io.github.styx798.sillytavernmanager.core.logging.DiagnosticLogExporter
import io.github.styx798.sillytavernmanager.core.logging.SillyTavernLogReader
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreController
import io.github.styx798.sillytavernmanager.core.settings.SettingsRepository
import io.github.styx798.sillytavernmanager.data.downloads.AndroidStDownloadRepository
import io.github.styx798.sillytavernmanager.data.files.AndroidAppFilesRepository
import io.github.styx798.sillytavernmanager.data.instances.SharedPreferencesStInstanceRepository
import io.github.styx798.sillytavernmanager.data.logging.InMemoryLogRepository
import io.github.styx798.sillytavernmanager.data.logging.AndroidDiagnosticLogExporter
import io.github.styx798.sillytavernmanager.data.logging.AndroidSillyTavernLogReader
import io.github.styx798.sillytavernmanager.data.stmcore.AndroidStmCoreController
import io.github.styx798.sillytavernmanager.data.settings.SharedPreferencesSettingsRepository

interface AppContainer {
    val diagnosticLogExporter: DiagnosticLogExporter
    val stmCoreController: StmCoreController
    val logRepository: LogRepository
    val sillyTavernLogReader: SillyTavernLogReader
    val settingsRepository: SettingsRepository
    val instanceRepository: StInstanceRepository
    val downloadRepository: StDownloadRepository
    val filesRepository: AppFilesRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    override val diagnosticLogExporter: DiagnosticLogExporter =
        AndroidDiagnosticLogExporter(context)
    override val stmCoreController: StmCoreController = AndroidStmCoreController(context)
    override val logRepository: LogRepository = InMemoryLogRepository()
    override val sillyTavernLogReader: SillyTavernLogReader =
        AndroidSillyTavernLogReader(context)
    override val settingsRepository: SettingsRepository = SharedPreferencesSettingsRepository(context)
    override val instanceRepository: StInstanceRepository =
        SharedPreferencesStInstanceRepository(context)
    override val downloadRepository: StDownloadRepository = AndroidStDownloadRepository(context)
    override val filesRepository: AppFilesRepository = AndroidAppFilesRepository(context)
}
