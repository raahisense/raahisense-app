package org.visualaid.obstacleguide.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.visualaid.obstacleguide.logic.CommandGrammar
import org.visualaid.obstacleguide.logic.EarChoice
import org.visualaid.obstacleguide.logic.SpokenEar
import org.visualaid.obstacleguide.logic.VoiceAction
import org.visualaid.obstacleguide.logic.VoiceCommand
import org.visualaid.obstacleguide.logic.VoiceInterpreter
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on wake word while voice commands are enabled, then one command.
 * English is always loaded. Hindi or Spanish is loaded beside it when selected.
 * Stop, collect, missed, and wrong stay on the English recognizer.
 * The microphone is not used when voice commands are turned off.
 */
class VoskEar(
    private val appContext: android.content.Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onCaptureBegan(speakAck: Boolean)
        fun onCaptureEnded()
        fun onAction(action: VoiceAction)
        fun onError()
        fun lexicon(): org.visualaid.obstacleguide.logic.CommandLexicon
        fun wakePhrase(): String
        fun languageTag(): String
    }

    private val main = Handler(Looper.getMainLooper())
    private val alive = AtomicBoolean(false)
    private val captureRequest = AtomicBoolean(false)
    private val reload = AtomicBoolean(false)
    private val ignoreRecognition = AtomicBoolean(false)
    private val dropRecognizer = AtomicBoolean(false)
    private val correction = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var recorder: AudioRecord? = null

    fun start() {
        if (alive.getAndSet(true)) return
        val worker = Thread({ runLoop() }, "obstacle-guide-ear")
        thread = worker
        worker.start()
    }

    fun stop() {
        alive.set(false)
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        val worker = thread
        if (worker != null && Thread.currentThread() !== worker) {
            worker.join(2_000L)
        }
        thread = null
    }

    fun requestCapture() {
        captureRequest.set(true)
    }

    fun reloadGrammar() {
        reload.set(true)
    }

    /**
     * After a warning, listen for "wrong" and "missed" without the wake phrase.
     * The grammar is rebuilt on the ear thread. Only the English recognizer does this.
     */
    fun setCorrectionListening(on: Boolean) {
        if (correction.getAndSet(on) != on) reload.set(true)
    }

    /** Drop microphone audio while the app is speaking, then reset so the tail cannot match. */
    fun setIgnoreRecognition(ignore: Boolean) {
        val previous = ignoreRecognition.getAndSet(ignore)
        if (previous != ignore) dropRecognizer.set(true)
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        var record: AudioRecord? = null
        val lanes = ArrayList<Lane>()
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            openLanes(lanes, listener.languageTag())
            val shorts = (SAMPLE_RATE * 0.2f).toInt()
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                shorts * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("recorder")
            }
            recorder = record
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("not recording")
            }
            main.post { listener.onReady() }
            val buffer = ShortArray(shorts)
            var phase = Phase.WAKE
            var deadline = 0L
            var loadedTag = listener.languageTag()
            while (alive.get()) {
                val tag = listener.languageTag()
                val tagChanged = tag != loadedTag
                if ((reload.getAndSet(false) || tagChanged) && (phase == Phase.WAKE || tagChanged)) {
                    if (tagChanged) {
                        openLanes(lanes, tag)
                        loadedTag = tag
                        if (phase == Phase.COMMAND) {
                            phase = Phase.WAKE
                            main.post { listener.onCaptureEnded() }
                        }
                    } else {
                        for (lane in lanes) applyGrammar(lane, commandPhase = false)
                    }
                }
                if (captureRequest.getAndSet(false) && phase == Phase.WAKE) {
                    phase = Phase.COMMAND
                    deadline = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
                    for (lane in lanes) applyGrammar(lane, commandPhase = true)
                    main.post { listener.onCaptureBegan(speakAck = true) }
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                if (ignoreRecognition.get()) {
                    for (lane in lanes) lane.recognizer.reset()
                    if (phase == Phase.COMMAND && SystemClock.elapsedRealtime() > deadline) {
                        phase = Phase.WAKE
                        for (lane in lanes) applyGrammar(lane, commandPhase = false)
                        main.post { listener.onCaptureEnded() }
                    }
                    continue
                }
                if (dropRecognizer.getAndSet(false)) {
                    for (lane in lanes) lane.recognizer.reset()
                    continue
                }
                if (phase == Phase.COMMAND && SystemClock.elapsedRealtime() > deadline) {
                    val heard = lanes.map { Heard(it, textOf(it.recognizer.finalResult), endpoint = true) }
                    phase = Phase.WAKE
                    for (lane in lanes) applyGrammar(lane, commandPhase = false)
                    deliver(decide(heard, commandPhase = true))
                    continue
                }
                val heard = ArrayList<Heard>()
                for (lane in lanes) {
                    val endpoint = lane.recognizer.acceptWaveForm(buffer, read)
                    val json = if (endpoint) lane.recognizer.result else lane.recognizer.partialResult
                    heard += Heard(lane, textOf(json), endpoint)
                }
                if (heard.none { it.endpoint }) continue
                val commandPhase = phase == Phase.COMMAND
                val action = decide(heard, commandPhase)
                val everyLaneEnded = heard.all { it.endpoint }
                if (phase == Phase.WAKE) {
                    when (action) {
                        VoiceAction.IGNORE -> resetEnded(heard)
                        VoiceAction.WAKE_ONLY -> {
                            phase = Phase.COMMAND
                            deadline = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
                            for (lane in lanes) applyGrammar(lane, commandPhase = true)
                            main.post { listener.onCaptureBegan(speakAck = true) }
                        }
                        else -> {
                            for (lane in lanes) lane.recognizer.reset()
                            main.post {
                                listener.onCaptureBegan(speakAck = false)
                                listener.onCaptureEnded()
                                listener.onAction(action)
                            }
                        }
                    }
                } else if (isSpokenCommand(action) || (action == VoiceAction.UNKNOWN && everyLaneEnded)) {
                    phase = Phase.WAKE
                    for (lane in lanes) applyGrammar(lane, commandPhase = false)
                    deliver(action)
                } else {
                    resetEnded(heard)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Ear failed", error)
            main.post { listener.onError() }
        } finally {
            recorder = null
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            try {
                record?.release()
            } catch (_: Exception) {
            }
            closeLanes(lanes)
            alive.set(false)
        }
    }

    private fun decide(heard: List<Heard>, commandPhase: Boolean): VoiceAction {
        var english = VoiceAction.IGNORE
        var language: VoiceAction? = null
        var englishEnded = false
        for (item in heard) {
            if (!item.endpoint) continue
            englishEnded = englishEnded || item.lane.english
            val action = interpret(item.lane, item.text, commandPhase)
            if (item.lane.english) english = action else language = action
        }
        if (!englishEnded) {
            val partial = heard.firstOrNull { it.lane.english }?.text.orEmpty()
            val lane = heard.firstOrNull { it.lane.english }?.lane
            if (lane != null && partial.isNotEmpty()) {
                val action = interpret(lane, partial, commandPhase)
                if (action == VoiceAction.STOP ||
                    action == VoiceAction.START_COLLECTING ||
                    action == VoiceAction.STOP_COLLECTING ||
                    action == VoiceAction.MISSED ||
                    action == VoiceAction.WRONG
                ) {
                    english = action
                }
            }
        }
        return EarChoice.prefer(language, english)
    }

    private fun resetEnded(heard: List<Heard>) {
        for (item in heard) {
            if (item.endpoint) item.lane.recognizer.reset()
        }
    }

    private fun isSpokenCommand(action: VoiceAction): Boolean = when (action) {
        VoiceAction.IGNORE, VoiceAction.WAKE_ONLY, VoiceAction.UNKNOWN -> false
        else -> true
    }

    private fun interpret(lane: Lane, text: String, commandPhase: Boolean): VoiceAction {
        return VoiceInterpreter.interpret(
            text,
            listener.lexicon(),
            listener.wakePhrase(),
            requireWake = !commandPhase,
            allowBareCorrection = lane.english && correction.get() && !commandPhase,
            extraWakePhrases = if (lane.english) emptyList() else SpokenEar.nativeWakes(lane.tag),
        )
    }

    private fun deliver(action: VoiceAction) {
        main.post {
            listener.onCaptureEnded()
            listener.onAction(action)
        }
    }

    private fun openLanes(lanes: MutableList<Lane>, tag: String) {
        closeLanes(lanes)
        lanes += openLane(EarModel.ENGLISH, english = true)
        val extra = EarModel.forLanguage(tag)
        if (extra != null) {
            try {
                val lane = openLane(extra, english = false)
                if (grammarFor(lane, commandPhase = false) == null) {
                    lane.recognizer.close()
                    lane.model.close()
                } else {
                    lanes += lane
                }
            } catch (error: Exception) {
                Log.e(TAG, "Language model failed", error)
            }
        }
        for (lane in lanes) applyGrammar(lane, commandPhase = false)
    }

    private fun openLane(spec: EarModel, english: Boolean): Lane {
        val directory = VoskModels.modelDirectory(appContext, spec)
        val model = Model(directory.absolutePath)
        val vocab = VoskVocab.words(appContext, spec.wordsAsset)
        val recognizer = Recognizer(model, SAMPLE_RATE, "[\"[unk]\"]")
        return Lane(spec.tag, english, model, recognizer, vocab)
    }

    private fun closeLanes(lanes: MutableList<Lane>) {
        for (lane in lanes) {
            try {
                lane.recognizer.close()
            } catch (_: Exception) {
            }
            try {
                lane.model.close()
            } catch (_: Exception) {
            }
        }
        lanes.clear()
    }

    private fun applyGrammar(lane: Lane, commandPhase: Boolean) {
        val grammar = grammarFor(lane, commandPhase) ?: CommandGrammar.json(emptyList())
        lane.recognizer.setGrammar(grammar)
        tune(lane.recognizer, commandPhase)
        lane.recognizer.reset()
    }

    private fun grammarFor(lane: Lane, commandPhase: Boolean): String? {
        val commands = listener.lexicon().allPhrases()
        if (commandPhase) {
            val phrases = CommandGrammar.commandPhrases(commands, lane.vocab)
            if (phrases.isEmpty()) return null
            return CommandGrammar.json(phrases)
        }
        val phrases = linkedSetOf<String>()
        val wakes = if (lane.english) {
            listOf(listener.wakePhrase())
        } else {
            SpokenEar.nativeWakes(lane.tag) + listener.wakePhrase()
        }
        for (wake in wakes) {
            phrases.addAll(CommandGrammar.wakePhrases(wake, commands, lane.vocab))
        }
        if (lane.english && correction.get()) {
            phrases.addAll(CommandGrammar.commandPhrases(correctionPhrases(), lane.vocab))
        }
        if (phrases.isEmpty()) return null
        return CommandGrammar.json(phrases)
    }

    private fun correctionPhrases(): List<String> {
        val lexicon = listener.lexicon()
        return lexicon.phrases[VoiceCommand.WRONG].orEmpty() +
            lexicon.phrases[VoiceCommand.MISSED].orEmpty()
    }

    private fun tune(recognizer: Recognizer, commandPhase: Boolean) {
        if (commandPhase) {
            recognizer.setEndpointerDelays(4f, 0.7f, 6f)
        } else {
            recognizer.setEndpointerDelays(10f, 0.6f, 8f)
        }
    }

    private fun textOf(json: String): String {
        return try {
            val obj = JSONObject(json)
            when {
                obj.has("text") -> obj.getString("text")
                obj.has("partial") -> obj.getString("partial")
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private class Heard(
        val lane: Lane,
        val text: String,
        val endpoint: Boolean,
    )

    private class Lane(
        val tag: String,
        val english: Boolean,
        val model: Model,
        val recognizer: Recognizer,
        val vocab: Set<String>,
    )

    private enum class Phase { WAKE, COMMAND }

    companion object {
        private const val SAMPLE_RATE = 16_000f
        private const val COMMAND_WINDOW_MS = 5_000L
        private const val TAG = "ObstacleGuideEar"
    }
}
