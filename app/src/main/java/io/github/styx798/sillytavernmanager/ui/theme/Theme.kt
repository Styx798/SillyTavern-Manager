package io.github.styx798.sillytavernmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5558B9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E1FF),
    onPrimaryContainer = Color(0xFF17174D),
    secondary = Color(0xFF46664D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8EDCF),
    onSecondaryContainer = Color(0xFF08210F),
    tertiary = Color(0xFF7C5635),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E2EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC1C1FF),
    onPrimary = Color(0xFF262867),
    primaryContainer = Color(0xFF3D3F82),
    onPrimaryContainer = Color(0xFFE2E1FF),
    secondary = Color(0xFFACD0B4),
    onSecondary = Color(0xFF173722),
    secondaryContainer = Color(0xFF2F4E36),
    onSecondaryContainer = Color(0xFFC8EDCF),
    tertiary = Color(0xFFEDBD94),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F99),
)

@Composable
fun StmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
