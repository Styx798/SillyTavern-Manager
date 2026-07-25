package io.github.styx798.sillytavernmanager.core.settings

import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setThemeMode(themeMode: ThemeMode)

    fun setLanguage(language: AppLanguage)
}
