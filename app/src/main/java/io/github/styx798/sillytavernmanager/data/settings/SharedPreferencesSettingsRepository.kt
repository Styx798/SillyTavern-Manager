package io.github.styx798.sillytavernmanager.data.settings

import android.content.Context
import androidx.core.content.edit
import io.github.styx798.sillytavernmanager.core.settings.AppLanguage
import io.github.styx798.sillytavernmanager.core.settings.AppSettings
import io.github.styx798.sillytavernmanager.core.settings.SettingsRepository
import io.github.styx798.sillytavernmanager.core.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(readSettings(context))

    override val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    override fun setThemeMode(themeMode: ThemeMode) {
        if (mutableSettings.value.themeMode == themeMode) return

        preferences.edit {
            putString(KEY_THEME_MODE, themeMode.storageValue)
        }
        mutableSettings.value = mutableSettings.value.copy(themeMode = themeMode)
    }

    override fun setLanguage(language: AppLanguage) {
        if (mutableSettings.value.language == language) return

        preferences.edit {
            putString(KEY_LANGUAGE, language.storageValue)
        }
        mutableSettings.value = mutableSettings.value.copy(language = language)
    }

    companion object {
        private const val PREFERENCES_NAME = "stm_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "language"

        fun readLanguage(context: Context): AppLanguage = AppLanguage.fromStorageValue(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null),
        )

        private fun readSettings(context: Context): AppSettings {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return AppSettings(
                themeMode = ThemeMode.fromStorageValue(preferences.getString(KEY_THEME_MODE, null)),
                language = AppLanguage.fromStorageValue(preferences.getString(KEY_LANGUAGE, null)),
            )
        }
    }
}
