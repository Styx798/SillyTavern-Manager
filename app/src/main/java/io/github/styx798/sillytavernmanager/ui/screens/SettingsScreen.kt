package io.github.styx798.sillytavernmanager.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.core.settings.AppLanguage
import io.github.styx798.sillytavernmanager.core.settings.AppSettings
import io.github.styx798.sillytavernmanager.core.settings.ThemeMode

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAdvancedSt: () -> Unit,
    onOpenCompleteRemoval: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmCompleteRemoval by rememberSaveable { mutableStateOf(false) }
    if (confirmCompleteRemoval) {
        AlertDialog(
            onDismissRequest = { confirmCompleteRemoval = false },
            title = { Text(stringResource(R.string.settings_complete_removal_confirm_title)) },
            text = { Text(stringResource(R.string.settings_complete_removal_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCompleteRemoval = false
                        onOpenCompleteRemoval()
                    },
                ) {
                    Text(stringResource(R.string.settings_complete_removal_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCompleteRemoval = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.settings_intro),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = stringResource(R.string.settings_theme_title)) {
            SettingsChoice(
                titleRes = R.string.settings_theme_system,
                selected = settings.themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeModeSelected(ThemeMode.SYSTEM) },
            )
            HorizontalDivider()
            SettingsChoice(
                titleRes = R.string.settings_theme_light,
                selected = settings.themeMode == ThemeMode.LIGHT,
                onClick = { onThemeModeSelected(ThemeMode.LIGHT) },
            )
            HorizontalDivider()
            SettingsChoice(
                titleRes = R.string.settings_theme_dark,
                selected = settings.themeMode == ThemeMode.DARK,
                onClick = { onThemeModeSelected(ThemeMode.DARK) },
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        SettingsSection(title = stringResource(R.string.settings_language_title)) {
            SettingsChoice(
                titleRes = R.string.settings_language_system,
                selected = settings.language == AppLanguage.SYSTEM,
                onClick = { onLanguageSelected(AppLanguage.SYSTEM) },
            )
            HorizontalDivider()
            SettingsChoice(
                titleRes = R.string.settings_language_chinese,
                selected = settings.language == AppLanguage.SIMPLIFIED_CHINESE,
                onClick = { onLanguageSelected(AppLanguage.SIMPLIFIED_CHINESE) },
            )
            HorizontalDivider()
            SettingsChoice(
                titleRes = R.string.settings_language_english,
                selected = settings.language == AppLanguage.ENGLISH,
                onClick = { onLanguageSelected(AppLanguage.ENGLISH) },
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        SettingsSection(title = stringResource(R.string.settings_diagnostics_title)) {
            SettingsAction(
                title = stringResource(R.string.settings_diagnostics_entry_title),
                summary = stringResource(R.string.settings_diagnostics_entry_summary),
                onClick = onOpenDiagnostics,
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        SettingsSection(title = stringResource(R.string.settings_advanced_title)) {
            SettingsAction(
                title = stringResource(R.string.settings_advanced_st_entry_title),
                summary = stringResource(R.string.settings_advanced_st_entry_summary),
                onClick = onOpenAdvancedSt,
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        SettingsSection(title = stringResource(R.string.settings_files_title)) {
            SettingsAction(
                title = stringResource(R.string.settings_files_entry_title),
                summary = stringResource(R.string.settings_files_entry_summary),
                onClick = onOpenFiles,
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        SettingsSection(title = stringResource(R.string.settings_complete_removal_title)) {
            SettingsAction(
                title = stringResource(R.string.settings_complete_removal_action),
                summary = stringResource(R.string.settings_complete_removal_summary),
                onClick = { confirmCompleteRemoval = true },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsAction(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = summary,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "›",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column {
            Text(
                text = title,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun SettingsChoice(
    @StringRes titleRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
