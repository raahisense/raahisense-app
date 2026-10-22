package org.visualaid.obstacleguide

import org.visualaid.obstacleguide.logic.DepthCalibration

data class GuideSettings(
    val languageTag: String = "en",
    val batterySaver: Boolean = false,
    val speechRate: Float = RATE_NORMAL,
    val vibrationEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val wakePhrase: String = DEFAULT_WAKE,
    val speechVolume: Float = VOLUME_HIGH,
    val depthOpenRaw: Float = 0f,
    val depthWallRaw: Float = 0f,
) {
    fun normalized(): GuideSettings {
        val tag = if (languageTag in LANGUAGE_TAGS) languageTag else "en"
        val rate = when (speechRate) {
            RATE_SLOW, RATE_FAST -> speechRate
            else -> RATE_NORMAL
        }
        val volume = when (speechVolume) {
            VOLUME_LOW, VOLUME_MID -> speechVolume
            else -> VOLUME_HIGH
        }
        val phrase = wakePhrase.trim().replace(Regex("\\s+"), " ").take(48).ifBlank { DEFAULT_WAKE }
        val open = if (depthOpenRaw.isFinite() && depthOpenRaw > 0f) depthOpenRaw else 0f
        val wall = if (depthWallRaw.isFinite() && depthWallRaw > 0f) depthWallRaw else 0f
        return copy(
            languageTag = tag,
            speechRate = rate,
            speechVolume = volume,
            wakePhrase = phrase,
            depthOpenRaw = open,
            depthWallRaw = wall,
        )
    }

    /** Saved open-path and one-step wall readings. Null until both are usable. */
    fun depthCalibration(): DepthCalibration? {
        val saved = DepthCalibration(depthOpenRaw, depthWallRaw)
        return if (saved.usable()) saved else null
    }

    fun louder(): GuideSettings = when (speechVolume) {
        VOLUME_LOW -> copy(speechVolume = VOLUME_MID)
        VOLUME_MID -> copy(speechVolume = VOLUME_HIGH)
        else -> this
    }

    fun quieter(): GuideSettings = when (speechVolume) {
        VOLUME_HIGH -> copy(speechVolume = VOLUME_MID)
        VOLUME_MID -> copy(speechVolume = VOLUME_LOW)
        else -> this
    }

    fun faster(): GuideSettings = when (speechRate) {
        RATE_SLOW -> copy(speechRate = RATE_NORMAL)
        RATE_NORMAL -> copy(speechRate = RATE_FAST)
        else -> this
    }

    fun slower(): GuideSettings = when (speechRate) {
        RATE_FAST -> copy(speechRate = RATE_NORMAL)
        RATE_NORMAL -> copy(speechRate = RATE_SLOW)
        else -> this
    }

    fun nextLanguage(): GuideSettings {
        val index = LANGUAGE_TAGS.indexOf(languageTag).let { if (it < 0) 0 else it }
        return copy(languageTag = LANGUAGE_TAGS[(index + 1) % LANGUAGE_TAGS.size])
    }

    companion object {
        const val RATE_SLOW = 0.8f
        const val RATE_NORMAL = 1.1f
        const val RATE_FAST = 1.35f

        const val VOLUME_LOW = 0.55f
        const val VOLUME_MID = 0.8f
        const val VOLUME_HIGH = 1.0f

        const val DEFAULT_WAKE = "Hey Guide"

        /** Matches the web prototype: about three looks a second, or slower to save battery. */
        const val INTERVAL_NORMAL_MS = 300L
        const val INTERVAL_SAVER_MS = 1_500L
        const val DEPTH_INTERVAL_NORMAL_MS = 500L
        const val DEPTH_INTERVAL_SAVER_MS = 2_000L

        val LANGUAGE_TAGS = listOf("en", "hi", "ta", "bn", "sw", "es")

        fun frameIntervalMs(batterySaver: Boolean): Long {
            return if (batterySaver) INTERVAL_SAVER_MS else INTERVAL_NORMAL_MS
        }
    }
}
