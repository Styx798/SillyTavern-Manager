package io.github.styx798.sillytavernmanager.ui.locale

import android.content.Context
import android.content.res.Configuration
import io.github.styx798.sillytavernmanager.core.settings.AppLanguage
import java.util.Locale

fun Context.withAppLanguage(language: AppLanguage): Context {
    val localeTag = language.localeTag ?: return this
    val locale = Locale.forLanguageTag(localeTag)
    val configuration = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(configuration)
}
