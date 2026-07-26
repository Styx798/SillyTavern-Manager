package io.github.styx798.sillytavernmanager

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.styx798.sillytavernmanager.app.StmApplication
import io.github.styx798.sillytavernmanager.core.logging.LogLevel
import io.github.styx798.sillytavernmanager.core.logging.LogSource
import io.github.styx798.sillytavernmanager.data.settings.SharedPreferencesSettingsRepository
import io.github.styx798.sillytavernmanager.ui.StmApp
import io.github.styx798.sillytavernmanager.ui.StmViewModel
import io.github.styx798.sillytavernmanager.ui.locale.withAppLanguage
import io.github.styx798.sillytavernmanager.ui.theme.StmTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val language = SharedPreferencesSettingsRepository.readLanguage(newBase)
        super.attachBaseContext(newBase.withAppLanguage(language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as StmApplication).container
        val viewModel = ViewModelProvider(
            this,
            StmViewModel.Factory(container),
        )[StmViewModel::class.java]

        if (savedInstanceState == null) {
            container.logRepository.append(
                source = LogSource.APP,
                level = LogLevel.INFO,
                message = getString(R.string.log_app_started),
            )
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = settings.themeMode.usesDarkTheme(isSystemInDarkTheme())

            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                )
            }

            StmTheme(darkTheme = darkTheme) {
                StmApp(
                    viewModel = viewModel,
                    settings = settings,
                    onThemeModeSelected = viewModel::setThemeMode,
                    onLanguageSelected = { language ->
                        if (language != settings.language) {
                            viewModel.setLanguage(language)
                            recreate()
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as StmApplication).container.stmCoreController.resumeAppTask()
    }
}
