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
import org.visualaid.obstacleguide.logic.CommandLexicon
import org.visualaid.obstacleguide.logic.VoiceAction
import org.visualaid.obstacleguide.logic.VoiceInterpreter
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on wake word while voice commands are enabled, then one command.
 * The microphone is not used when voice commands are turned off.
 * Grammar is limited to the wake phrase and the command list.
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
        fun lexicon(): CommandLexicon
        fun wakePhrase(): String
        fun vocab(): Set<String>
    }

    private val main = Handler(Looper.getMainLooper())
    private val alive = AtomicBoolean(false)
    private val captureRequest = AtomicBoolean(false)
    private val reload = AtomicBoolean(false)
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

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val directory = VoskModels.modelDirectory(appContext)
            model = Model(directory.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE, wakeGrammar())
            tune(recognizer, commandPhase = false)
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
            while (alive.get()) {
                if (reload.getAndSet(false) && phase == Phase.WAKE) {
                    recognizer.setGrammar(wakeGrammar())
                    tune(recognizer, commandPhase = false)
                    recognizer.reset()
                }
                if (captureRequest.getAndSet(false) && phase == Phase.WAKE) {
                    phase = Phase.COMMAND
                    deadline = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
                    recognizer.setGrammar(commandGrammar())
                    tune(recognizer, commandPhase = true)
                    recognizer.reset()
                    main.post { listener.onCaptureBegan(speakAck = true) }
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val endpoint = recognizer.acceptWaveForm(buffer, read)
                if (phase == Phase.COMMAND && SystemClock.elapsedRealtime() > deadline) {
                    val heard = textOf(recognizer.finalResult)
                    phase = Phase.WAKE
                    recognizer.setGrammar(wakeGrammar())
                    tune(recognizer, commandPhase = false)
                    recognizer.reset()
                    deliverCommand(heard)
                    continue
                }
                if (!endpoint) continue
                val heard = textOf(recognizer.result)
                if (phase == Phase.WAKE) {
                    when (val action = VoiceInterpreter.interpret(
                        heard,
                        listener.lexicon(),
                        listener.wakePhrase(),
                        requireWake = true,
                    )) {
                        VoiceAction.IGNORE -> Unit
                        VoiceAction.WAKE_ONLY -> {
                            phase = Phase.COMMAND
                            deadline = SystemClock.elapsedRealtime() + COMMAND_WINDOW_MS
                            recognizer.setGrammar(commandGrammar())
                            tune(recognizer, commandPhase = true)
                            recognizer.reset()
                            main.post { listener.onCaptureBegan(speakAck = true) }
                        }
                        else -> {
                            recognizer.reset()
                            main.post {
                                listener.onCaptureBegan(speakAck = false)
                                listener.onCaptureEnded()
                                listener.onAction(action)
                            }
                        }
                    }
                } else {
                    phase = Phase.WAKE
                    recognizer.setGrammar(wakeGrammar())
                    tune(recognizer, commandPhase = false)
                    recognizer.reset()
                    deliverCommand(heard)
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
            try {
                recognizer?.close()
            } catch (_: Exception) {
            }
            try {
                model?.close()
            } catch (_: Exception) {
            }
            alive.set(false)
        }
    }

    private fun deliverCommand(heard: String) {
        val action = VoiceInterpreter.interpret(
            heard,
            listener.lexicon(),
            listener.wakePhrase(),
            requireWake = false,
        )
        main.post {
            listener.onCaptureEnded()
            listener.onAction(action)
        }
    }

    private fun wakeGrammar(): String {
        val commands = listener.lexicon().allPhrases()
        val phrases = CommandGrammar.wakePhrases(listener.wakePhrase(), commands, listener.vocab())
        if (phrases.isEmpty()) {
            throw IllegalStateException("wake phrase is not in the model")
        }
        return CommandGrammar.json(phrases)
    }

    private fun commandGrammar(): String {
        val phrases = CommandGrammar.commandPhrases(listener.lexicon().allPhrases(), listener.vocab())
        if (phrases.isEmpty()) {
            throw IllegalStateException("no command phrases in the model")
        }
        return CommandGrammar.json(phrases)
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

    private enum class Phase { WAKE, COMMAND }

    companion object {
        private const val SAMPLE_RATE = 16_000f
        private const val COMMAND_WINDOW_MS = 5_000L
        private const val TAG = "ObstacleGuideEar"
    }
}
