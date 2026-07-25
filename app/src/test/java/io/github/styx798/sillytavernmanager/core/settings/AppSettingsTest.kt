package io.github.styx798.sillytavernmanager.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `theme modes resolve expected dark state`() {
        assertFalse(ThemeMode.LIGHT.usesDarkTheme(systemInDarkTheme = true))
        assertTrue(ThemeMode.DARK.usesDarkTheme(systemInDarkTheme = false))
        assertTrue(ThemeMode.SYSTEM.usesDarkTheme(systemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.usesDarkTheme(systemInDarkTheme = false))
    }

    @Test
    fun `unknown stored settings fall back to system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageValue("unknown"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorageValue("unknown"))
    }

    @Test
    fun `stored settings round trip`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorageValue(mode.storageValue))
        }
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromStorageValue(language.storageValue))
        }
    }
}
