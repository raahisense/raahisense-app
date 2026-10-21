package org.visualaid.obstacleguide.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.visualaid.obstacleguide.Localized
import java.util.Locale

object TtsVoices {
    fun hasOfflineVoice(tts: TextToSpeech, languageTag: String): Boolean {
        val locale = Localized.localeFor(languageTag)
        val voices = try {
            tts.voices
        } catch (_: Exception) {
            null
        }
        if (!voices.isNullOrEmpty()) {
            return voices.any { voice -> isUsable(voice, locale) }
        }
        return tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    fun bestVoice(tts: TextToSpeech, languageTag: String): Voice? {
        val locale = Localized.localeFor(languageTag)
        val voices = try {
            tts.voices
        } catch (_: Exception) {
            null
        } ?: return null
        return voices
            .filter { voice -> isUsable(voice, locale) }
            .maxByOrNull { voice ->
                var score = 0
                if (voice.locale.country.equals(locale.country, ignoreCase = true)) score += 4
                if (voice.quality >= Voice.QUALITY_NORMAL) score += 2
                if (voice.latency <= Voice.LATENCY_NORMAL) score += 1
                score
            }
    }

    private fun isUsable(voice: Voice, locale: Locale): Boolean {
        if (!voice.locale.language.equals(locale.language, ignoreCase = true)) return false
        if (voice.isNetworkConnectionRequired) return false
        if (voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) return false
        return true
    }
}
