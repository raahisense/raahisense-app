package org.visualaid.obstacleguide

import android.content.Context

object SettingsStore {
    private const val PREF = "obstacleguide"
    private const val KEY_LANG = "language"
    private const val KEY_SAVER = "battery_saver"
    private const val KEY_RATE = "speech_rate"
    private const val KEY_VIBE = "vibration"
    private const val KEY_ASKED_CAMERA = "asked_camera"

    fun load(context: Context): GuideSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return GuideSettings(
            languageTag = prefs.getString(KEY_LANG, "en") ?: "en",
            batterySaver = prefs.getBoolean(KEY_SAVER, false),
            speechRate = prefs.getFloat(KEY_RATE, GuideSettings.RATE_NORMAL),
            vibrationEnabled = prefs.getBoolean(KEY_VIBE, true),
        ).normalized()
    }

    fun save(context: Context, settings: GuideSettings) {
        val normalized = settings.normalized()
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, normalized.languageTag)
            .putBoolean(KEY_SAVER, normalized.batterySaver)
            .putFloat(KEY_RATE, normalized.speechRate)
            .putBoolean(KEY_VIBE, normalized.vibrationEnabled)
            .apply()
    }

    fun setAskedCamera(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED_CAMERA, true)
            .apply()
    }

    fun askedCamera(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED_CAMERA, false)
    }
}
