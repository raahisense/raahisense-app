package org.visualaid.obstacleguide

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Localized {
    fun localeFor(languageTag: String): Locale = when (languageTag) {
        "hi" -> Locale("hi", "IN")
        "ta" -> Locale("ta", "IN")
        "bn" -> Locale("bn", "IN")
        "sw" -> Locale("sw")
        "es" -> Locale("es")
        else -> Locale.ENGLISH
    }

    fun wrap(base: Context, languageTag: String): Context {
        val locale = localeFor(languageTag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
