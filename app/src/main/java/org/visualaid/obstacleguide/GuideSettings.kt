package org.visualaid.obstacleguide

data class GuideSettings(
    val languageTag: String = "en",
    val batterySaver: Boolean = false,
    val speechRate: Float = RATE_NORMAL,
    val vibrationEnabled: Boolean = true,
) {
    fun normalized(): GuideSettings {
        val tag = if (languageTag in LANGUAGE_TAGS) languageTag else "en"
        val rate = when (speechRate) {
            RATE_SLOW, RATE_FAST -> speechRate
            else -> RATE_NORMAL
        }
        return copy(languageTag = tag, speechRate = rate)
    }

    companion object {
        const val RATE_SLOW = 0.8f
        const val RATE_NORMAL = 1.1f
        const val RATE_FAST = 1.35f

        /** Matches the web prototype: about three looks a second, or slower to save battery. */
        const val INTERVAL_NORMAL_MS = 300L
        const val INTERVAL_SAVER_MS = 1_500L

        val LANGUAGE_TAGS = listOf("en", "hi", "ta", "bn", "sw", "es")

        fun frameIntervalMs(batterySaver: Boolean): Long {
            return if (batterySaver) INTERVAL_SAVER_MS else INTERVAL_NORMAL_MS
        }
    }
}
