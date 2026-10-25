package org.visualaid.obstacleguide.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.visualaid.obstacleguide.Localized
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline speech. If the chosen language has no installed offline voice,
 * [languageReady] is false and the caller should speak the fixed English
 * explanation from strings.xml.
 */
enum class VoiceStatus {
    UNKNOWN,
    AVAILABLE,
    MISSING,
}

class GuideSpeaker(
    context: Context,
    private val onReady: () -> Unit = {},
    private val onFailed: () -> Unit = {},
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var languageTag = "en"
    private var speechRate = 1.1f
    private var speechVolume = 1f
    private var activeId: String? = null
    private var seq = 0
    private var onIdle: (() -> Unit)? = null
    private val pending = ArrayDeque<Pending>()
    val speaking = AtomicBoolean(false)
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    private var speechEpoch = 0

    /** True when warnings can be spoken in [languageTag] itself. */
    @Volatile
    var languageReady: Boolean = false
        private set

    private data class Pending(val text: String, val flush: Boolean)

    fun start() {
        if (tts != null) return
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            ready = false
            languageReady = false
            val idle = onIdle
            onIdle = null
            markSpeaking(false)
            idle?.invoke()
            main.post { onFailed() }
            return
        }
        val engine = tts ?: return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = complete(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = complete(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)
        })
        ready = true
        applyVoice(engine)
        main.post { onReady() }
        var first = true
        val queued = pending.toList()
        pending.clear()
        for (item in queued) {
            speakNow(engine, item.text, flush = first || item.flush)
            first = false
        }
        if (queued.isEmpty()) {
            markSpeaking(false)
        }
    }

    fun setLanguage(languageTag: String, speechRate: Float, speechVolume: Float = 1f): VoiceStatus {
        this.languageTag = languageTag
        this.speechRate = speechRate
        this.speechVolume = speechVolume.coerceIn(0.2f, 1f)
        val engine = tts
        if (!ready || engine == null) return VoiceStatus.UNKNOWN
        applyVoice(engine)
        return if (languageReady) VoiceStatus.AVAILABLE else VoiceStatus.MISSING
    }

    fun voiceStatus(): VoiceStatus {
        if (failed) return VoiceStatus.MISSING
        if (!ready) return VoiceStatus.UNKNOWN
        return if (languageReady) VoiceStatus.AVAILABLE else VoiceStatus.MISSING
    }

    fun speak(text: String, flush: Boolean, onIdle: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onIdle?.invoke()
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { speak(text, flush, onIdle) }
            return
        }
        if (onIdle != null) this.onIdle = onIdle
        markSpeaking(true)
        val engine = tts
        if (!ready || engine == null) {
            if (failed) {
                markSpeaking(false)
                val idle = this.onIdle
                this.onIdle = null
                idle?.invoke()
                return
            }
            if (flush) pending.clear()
            pending.add(Pending(text, flush))
            return
        }
        speakNow(engine, text, flush)
    }

    fun stopSpeaking() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stopSpeaking() }
            return
        }
        pending.clear()
        activeId = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        markSpeaking(false)
        val idle = onIdle
        onIdle = null
        idle?.invoke()
    }

    fun shutdown() {
        pending.clear()
        activeId = null
        onIdle = null
        markSpeaking(false)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }

    private fun markSpeaking(active: Boolean) {
        val was = speaking.getAndSet(active)
        if (was == active) return
        if (active) speechEpoch += 1
        val epoch = speechEpoch
        val notify = Runnable {
            if (speechEpoch == epoch) onSpeakingChanged?.invoke(active)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else main.post(notify)
    }

    private fun applyVoice(engine: TextToSpeech) {
        val voice = TtsVoices.bestVoice(engine, languageTag)
        languageReady = voice != null || (
            engine.voices.isNullOrEmpty() &&
                TtsVoices.hasOfflineVoice(engine, languageTag)
            )
        if (!languageReady && languageTag != "en") {
            val english = TtsVoices.bestVoice(engine, "en")
            if (english != null) {
                engine.voice = english
            } else {
                engine.language = Localized.localeFor("en")
            }
        } else if (voice != null) {
            engine.voice = voice
            languageReady = true
        } else {
            val result = engine.setLanguage(Localized.localeFor(languageTag))
            languageReady = result >= TextToSpeech.LANG_AVAILABLE
        }
        engine.setSpeechRate(speechRate)
    }

    private fun speakNow(engine: TextToSpeech, text: String, flush: Boolean) {
        val id = "u${++seq}"
        activeId = id
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)
        engine.setSpeechRate(speechRate)
        val result = engine.speak(text, mode, params, id)
        if (result == TextToSpeech.ERROR) {
            complete(id)
        }
    }

    private fun complete(utteranceId: String?) {
        if (utteranceId == null || utteranceId != activeId) return
        activeId = null
        markSpeaking(false)
        val idle = onIdle
        onIdle = null
        idle?.invoke()
    }
}
