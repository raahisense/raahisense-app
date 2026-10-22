package org.visualaid.obstacleguide

import android.content.Context
import org.visualaid.obstacleguide.logic.TorchMode

object SettingsStore {
    private const val PREF = "obstacleguide"
    private const val KEY_LANG = "language"
    private const val KEY_SAVER = "battery_saver"
    private const val KEY_RATE = "speech_rate"
    private const val KEY_VIBE = "vibration"
    private const val KEY_ASKED_CAMERA = "asked_camera"
    private const val KEY_ASKED_MIC = "asked_mic"
    private const val KEY_VOICE = "voice_commands"
    private const val KEY_WAKE = "wake_phrase"
    private const val KEY_VOLUME = "speech_volume"
    private const val KEY_DEPTH_OPEN = "depth_open_raw"
    private const val KEY_DEPTH_WALL = "depth_wall_raw"
    private const val KEY_TORCH = "torch_mode"

    fun load(context: Context): GuideSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return GuideSettings(
            languageTag = prefs.getString(KEY_LANG, "en") ?: "en",
            batterySaver = prefs.getBoolean(KEY_SAVER, false),
            speechRate = prefs.getFloat(KEY_RATE, GuideSettings.RATE_NORMAL),
            vibrationEnabled = prefs.getBoolean(KEY_VIBE, true),
            voiceEnabled = prefs.getBoolean(KEY_VOICE, true),
            wakePhrase = prefs.getString(KEY_WAKE, GuideSettings.DEFAULT_WAKE) ?: GuideSettings.DEFAULT_WAKE,
            speechVolume = prefs.getFloat(KEY_VOLUME, GuideSettings.VOLUME_HIGH),
            depthOpenRaw = prefs.getFloat(KEY_DEPTH_OPEN, 0f),
            depthWallRaw = prefs.getFloat(KEY_DEPTH_WALL, 0f),
            torchMode = TorchMode.fromPersisted(prefs.getString(KEY_TORCH, "auto")),
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
            .putBoolean(KEY_VOICE, normalized.voiceEnabled)
            .putString(KEY_WAKE, normalized.wakePhrase)
            .putFloat(KEY_VOLUME, normalized.speechVolume)
            .putFloat(KEY_DEPTH_OPEN, normalized.depthOpenRaw)
            .putFloat(KEY_DEPTH_WALL, normalized.depthWallRaw)
            .putString(KEY_TORCH, normalized.torchMode.persisted())
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

    fun setAskedMic(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED_MIC, true)
            .apply()
    }

    fun askedMic(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED_MIC, false)
    }
}
