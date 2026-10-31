package org.visualaid.obstacleguide

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.visualaid.obstacleguide.collect.CollectLocation
import org.visualaid.obstacleguide.collect.CollectSnapshot
import org.visualaid.obstacleguide.StabilityLog
import org.visualaid.obstacleguide.collect.CollectEncoder
import org.visualaid.obstacleguide.collect.CollectStore
import org.visualaid.obstacleguide.detect.ArcoreDepthCamera
import org.visualaid.obstacleguide.detect.ObstacleDetector
import org.visualaid.obstacleguide.detect.TfliteDepth
import org.visualaid.obstacleguide.detect.captureExposure
import org.visualaid.obstacleguide.detect.meanLuma
import org.visualaid.obstacleguide.detect.meanLumaY
import org.visualaid.obstacleguide.detect.toUprightBitmap
import org.visualaid.obstacleguide.logic.CollectCapture
import org.visualaid.obstacleguide.logic.CollectEvent
import org.visualaid.obstacleguide.logic.CollectScheduler
import org.visualaid.obstacleguide.logic.CommandLexicon
import org.visualaid.obstacleguide.logic.DepthCalibration
import org.visualaid.obstacleguide.logic.DepthField
import org.visualaid.obstacleguide.logic.DepthTracker
import org.visualaid.obstacleguide.logic.DepthWarnings
import org.visualaid.obstacleguide.logic.DetectionBox
import org.visualaid.obstacleguide.logic.LowLight
import org.visualaid.obstacleguide.logic.LowLightController
import org.visualaid.obstacleguide.logic.SceneLight
import org.visualaid.obstacleguide.logic.SpokenWarning
import org.visualaid.obstacleguide.logic.TorchMode
import org.visualaid.obstacleguide.logic.ObstacleWarning
import org.visualaid.obstacleguide.logic.RecognitionGate
import org.visualaid.obstacleguide.logic.VoiceAction
import org.visualaid.obstacleguide.logic.VoiceCommand
import org.visualaid.obstacleguide.logic.ShakeWatch
import org.visualaid.obstacleguide.logic.WarningLogic
import org.visualaid.obstacleguide.logic.WarningThrottle
import org.visualaid.obstacleguide.speech.GuideSpeaker
import org.visualaid.obstacleguide.speech.PhraseBook
import org.visualaid.obstacleguide.speech.VoiceStatus
import org.visualaid.obstacleguide.voice.VoskEar
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the rear camera, the detector, and speech running when the screen is
 * locked. A persistent notification shows that warnings are on.
 */
class GuideService : LifecycleService() {
    interface Listener {
        fun onStatus(text: String)
        fun onRunningChanged(running: Boolean)
        fun onVoiceSettingsChanged(languageChanged: Boolean)
        fun onNeedCameraPermission()
        fun onNeedMicPermission()
        fun onQuit()
        fun onCollectChanged(photos: Int, bytes: Long)
    }

    inner class LocalBinder : Binder() {
        fun service(): GuideService = this@GuideService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "obstacle-guide-camera").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val depthExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "obstacle-guide-depth").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val collectExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "obstacle-guide-collect").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private lateinit var collectEncoder: CollectEncoder
    private val collectScheduler = CollectScheduler()
    private val speechBusy = AtomicBoolean(false)
    private val throttle = WarningThrottle()

    private lateinit var speaker: GuideSpeaker
    private var detector: ObstacleDetector? = null
    private var depthEstimator: TfliteDepth? = null
    private val depthTracker = DepthTracker()
    private val depthBusy = AtomicBoolean(false)
    private val depthGeneration = AtomicInteger(0)
    private var cameraToken = 0
    private var cameraBound = false
    private var lastDepthAt = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var listener: Listener? = null
    @Volatile
    private var settings = GuideSettings()
    @Volatile
    private var effectiveLanguage = "en"
    private var announcedMissingFor: String? = null
    private var modelLoading = false
    private var cameraReady = false
    private var stopping = false
    private var quitting = false
    private val quitFinished = AtomicBoolean(false)
    private var quietUntilMs = 0L
    private var unmuteRunnable: Runnable? = null
    private var correctionUntilMs = 0L
    private var correctionToken = 0
    private var openCorrectionOnIdle = false
    private var closeCorrection: Runnable? = null
    @Volatile
    private var lastSpokenWarning: SpokenWarning? = null
    private var inForeground = false
    private var stopRunnable: Runnable? = null
    private var lastFrameAt = 0L
    private var frameErrors = 0
    private var toldFrameError = false
    private var ear: VoskEar? = null
    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var lightListener: SensorEventListener? = null
    private val shakeWatch = ShakeWatch()
    private val lowLight = LowLightController()
    @Volatile
    private var ambientLux: Float? = null
    @Volatile
    private var scoreThreshold = WarningLogic.SCORE_THRESHOLD
    private var boundCamera: Camera? = null
    @Volatile
    private var flashUnit = false
    @Volatile
    private var lastScene: SceneLight? = null
    private var torchWanted = false
    private var exposureBoost = false
    private var appliedTorch: Boolean? = null
    private var appliedExposureBoost: Boolean? = null
    @Volatile
    private var commandCapturing = false
    @Volatile
    private var lastRanked: List<ObstacleWarning> = emptyList()
    @Volatile
    private var lastWarningPhrase: String = ""
    @Volatile
    private var latestDepth: List<ObstacleWarning> = emptyList()
    @Volatile
    private var latestAheadMedian = Float.NaN
    @Volatile
    private var latestBoxes: List<DetectionBox> = emptyList()
    @Volatile
    private var latestDepthField: DepthField? = null
    private var imageCapture: ImageCapture? = null
    private val captureBusy = AtomicBoolean(false)
    @Volatile
    private var pendingSnapshot: CollectSnapshot? = null
    private var collectTicking = false
    private var collectHot = false
    private var boundCollectMode = false
    private var cameraBindTries = 0
    private val collectTick: Runnable = object : Runnable {
        override fun run() {
            if (!running || stopping) {
                collectTicking = false
                return
            }
            maybeCapture()
            if (running && settings.collectEnabled) {
                handler.postDelayed(this, CollectCapture.POLL_MS)
            } else {
                collectTicking = false
            }
        }
    }

    fun aheadMedian(): Float = latestAheadMedian

    @Volatile
    var lastStatus: String = ""
        private set

    override fun onCreate() {
        super.onCreate()
        collectEncoder = CollectEncoder(applicationContext)
        speaker = GuideSpeaker(
            applicationContext,
            onReady = {
                if (running && !stopping) {
                    applySettings(settings, announceMissing = false)
                    maybeAnnounceMissingVoice()
                }
            },
            onFailed = {
                val message = speechContext().getString(R.string.status_tts_error)
                postStatus(message)
            },
        )
        speaker.onSpeakingChanged = { active -> onSpeechActive(active) }
        speaker.start()
        ensureChannel()
    }

    private fun onSpeechActive(active: Boolean) {
        if (active) {
            quietUntilMs = Long.MAX_VALUE
            unmuteRunnable?.let { handler.removeCallbacks(it) }
            unmuteRunnable = null
            ear?.setIgnoreRecognition(true)
            return
        }
        val ended = SystemClock.elapsedRealtime()
        if (openCorrectionOnIdle) {
            // A warning just finished. Listen now for "wrong" or "missed".
            // Other commands still wait out the usual tail.
            openCorrectionOnIdle = false
            correctionUntilMs = RecognitionGate.correctionUntil(ended)
            quietUntilMs = RecognitionGate.quietUntil(ended)
            val token = ++correctionToken
            ear?.setCorrectionListening(true)
            ear?.setIgnoreRecognition(false)
            closeCorrection?.let { handler.removeCallbacks(it) }
            val close = Runnable {
                closeCorrection = null
                if (token == correctionToken) ear?.setCorrectionListening(false)
            }
            closeCorrection = close
            handler.postDelayed(close, RecognitionGate.CORRECTION_WINDOW_MS)
            return
        }
        correctionToken += 1
        correctionUntilMs = 0L
        ear?.setCorrectionListening(false)
        closeCorrection?.let { handler.removeCallbacks(it) }
        closeCorrection = null
        quietUntilMs = RecognitionGate.quietUntil(ended)
        val runnable = Runnable {
            unmuteRunnable = null
            if (!speaker.speaking.get() && SystemClock.elapsedRealtime() >= quietUntilMs) {
                ear?.setIgnoreRecognition(false)
            }
        }
        unmuteRunnable = runnable
        handler.postDelayed(runnable, RecognitionGate.TAIL_MS)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        settings = SettingsStore.load(this)
        // A shortcut can ask the screen to listen again while a full stop is
        // still speaking. Do not bring the camera or the microphone back.
        if (quitting) return START_NOT_STICKY
        if (intent == null) {
            if (settings.voiceEnabled) startSpotting()
            return stickyResult()
        }
        when (intent.action) {
            ACTION_QUIT -> {
                quitApp()
                return START_NOT_STICKY
            }
            ACTION_STOP -> stopGuide()
            ACTION_VOICE_OFF -> disableVoice()
            ACTION_SPOT -> startSpotting()
            else -> startGuiding()
        }
        return stickyResult()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        if (listener != null && lastStatus.isNotBlank()) {
            handler.post { listener.onStatus(lastStatus) }
        }
    }

    fun requestCommandCapture() {
        if (!settings.voiceEnabled) {
            speakTracked(speechContext().getString(R.string.status_voice_off), flush = true)
            return
        }
        ensureEar()
        ear?.requestCapture()
    }

    fun reloadEar() {
        settings = SettingsStore.load(this)
        ear?.reloadGrammar()
    }

    private fun startGuiding() {
        if (quitting) return
        cancelDelayedStop()
        stopping = false
        settings = SettingsStore.load(this)
        if (!promoteForeground(guiding = true)) return
        running = true
        acquireWakeLock()
        applySettings(settings, announceMissing = false)
        handler.post { listener?.onRunningChanged(true) }
        if (settings.voiceEnabled) ensureEar()
        registerLight()
        if (detector == null) {
            loadModelThenCamera()
        } else if (!cameraBound) {
            bindCamera()
        }
    }

    private fun startSpotting() {
        if (quitting) return
        cancelDelayedStop()
        stopping = false
        settings = SettingsStore.load(this)
        if (!settings.voiceEnabled) {
            if (!running) stopSelf()
            return
        }
        if (!promoteForeground(guiding = running)) return
        acquireWakeLock()
        applySettings(settings, announceMissing = false)
        ensureEar()
        if (!running) {
            val waiting = speechContext().getString(R.string.status_spotting, settings.wakePhrase)
            postStatus(waiting)
        }
    }

    private fun disableVoice() {
        settings = settings.copy(voiceEnabled = false)
        SettingsStore.save(this, settings)
        commandCapturing = false
        stopEar()
        if (running) {
            refreshForeground()
        } else {
            releaseWakeLock()
            inForeground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        handler.post { listener?.onVoiceSettingsChanged(languageChanged = false) }
    }

    private fun ensureEar() {
        if (!settings.voiceEnabled || ear != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            handler.post { listener?.onNeedMicPermission() }
            return
        }
        val created = VoskEar(applicationContext, earListener)
        ear = created
        if (!RecognitionGate.accepts(speaker.speaking.get(), SystemClock.elapsedRealtime(), quietUntilMs)) {
            created.setIgnoreRecognition(true)
        }
        created.start()
        registerShake()
    }

    private fun stopEar() {
        unregisterShake()
        val current = ear
        ear = null
        current?.stop()
    }

    private val earListener = object : VoskEar.Listener {
        override fun onReady() {
            if (!running) {
                val waiting = speechContext().getString(R.string.status_spotting, settings.wakePhrase)
                postStatus(waiting)
            }
        }

        override fun onCaptureBegan(speakAck: Boolean) {
            commandCapturing = true
            speaker.stopSpeaking()
            speechBusy.set(false)
            if (speakAck) {
                val ack = speechContext().getString(R.string.wake_ack)
                postStatus(uiContext().getString(R.string.status_capturing))
                speakTracked(ack, flush = true)
            }
        }

        override fun onCaptureEnded() {
            commandCapturing = false
        }

        override fun onAction(action: VoiceAction) {
            performVoice(action)
        }

        override fun onError() {
            val message = speechContext().getString(R.string.status_mic_unavailable)
            postStatus(message)
            speakTracked(message, flush = true)
        }

        override fun lexicon(): CommandLexicon = commandLexicon()

        override fun wakePhrase(): String = settings.wakePhrase

        override fun languageTag(): String = settings.languageTag
    }

    private fun performVoice(action: VoiceAction) {
        if (quitting) return
        val now = SystemClock.elapsedRealtime()
        val speaking = speaker.speaking.get()
        val correction = (action == VoiceAction.WRONG || action == VoiceAction.MISSED) &&
            RecognitionGate.acceptsCorrection(speaking, now, correctionUntilMs)
        if (!correction && !RecognitionGate.accepts(speaking, now, quietUntilMs)) return
        val speech = speechContext()
        when (action) {
            VoiceAction.IGNORE, VoiceAction.WAKE_ONLY -> Unit
            VoiceAction.UNKNOWN -> speakTracked(speech.getString(R.string.confirm_unknown), flush = true)
            VoiceAction.START, VoiceAction.RESUME -> {
                if (running) {
                    speakTracked(speech.getString(R.string.confirm_started_already), flush = true)
                } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    speakTracked(speech.getString(R.string.prompt_allow_camera), flush = true)
                    handler.post { listener?.onNeedCameraPermission() }
                } else {
                    startGuiding()
                }
            }
            VoiceAction.STOP -> quitApp()
            VoiceAction.PAUSE -> {
                if (running) stopGuide() else speakTracked(speech.getString(R.string.confirm_stopped_already), flush = true)
            }
            VoiceAction.WHAT_IS_AHEAD -> speakAhead()
            VoiceAction.REPEAT -> {
                val last = lastWarningPhrase
                if (last.isBlank()) {
                    speakTracked(speech.getString(R.string.confirm_nothing_to_repeat), flush = true)
                } else {
                    speakSequence(listOf(speech.getString(R.string.confirm_repeat), last))
                }
            }
            VoiceAction.LOUDER -> stepVolume(louder = true)
            VoiceAction.QUIETER -> stepVolume(louder = false)
            VoiceAction.FASTER -> stepRate(faster = true)
            VoiceAction.SLOWER -> stepRate(faster = false)
            VoiceAction.CHANGE_LANGUAGE -> {
                settings = settings.nextLanguage().normalized()
                SettingsStore.save(this, settings)
                applySettings(settings, announceMissing = true)
                ear?.reloadGrammar()
                val phrase = speechContext().getString(R.string.confirm_language)
                speakTracked(phrase, flush = true)
                handler.post { listener?.onVoiceSettingsChanged(languageChanged = true) }
            }
            VoiceAction.HELP -> {
                val help = speech.getString(R.string.help_commands, settings.wakePhrase)
                speakTracked(help, flush = true)
            }
            VoiceAction.START_COLLECTING -> setCollectingFromVoice(on = true)
            VoiceAction.STOP_COLLECTING -> setCollectingFromVoice(on = false)
            VoiceAction.MISSED -> markMissed()
            VoiceAction.WRONG -> markWrong()
        }
    }

    private fun setCollectingFromVoice(on: Boolean) {
        val speech = speechContext()
        if (settings.collectEnabled == on) {
            val phrase = if (on) R.string.confirm_collect_already_on else R.string.confirm_collect_already_off
            speakTracked(speech.getString(phrase), flush = true)
            if (on && !running) startGuidingIfCameraAllowed()
            return
        }
        settings = settings.copy(collectEnabled = on).normalized()
        SettingsStore.save(this, settings)
        applySettings(settings, announceMissing = false)
        handler.post { listener?.onVoiceSettingsChanged(languageChanged = false) }
        val phrase = if (on) R.string.confirm_collect_on else R.string.confirm_collect_off
        speakTracked(speech.getString(phrase), flush = true)
        if (on && !running) startGuidingIfCameraAllowed()
    }

    private fun markMissed() {
        val speech = speechContext()
        if (!settings.collectEnabled) {
            speakTracked(speech.getString(R.string.confirm_missed_off), flush = true)
            return
        }
        collectScheduler.onMissed()
        handler.post { maybeCapture() }
        speakTracked(speech.getString(R.string.confirm_noted), flush = true)
        if (!running) startGuidingIfCameraAllowed()
    }

    private fun markWrong() {
        val speech = speechContext()
        if (!settings.collectEnabled) {
            speakTracked(speech.getString(R.string.confirm_missed_off), flush = true)
            return
        }
        collectScheduler.onWrong()
        handler.post { maybeCapture() }
        speakTracked(speech.getString(R.string.confirm_noted), flush = true)
        if (!running) startGuidingIfCameraAllowed()
    }

    private fun startGuidingIfCameraAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            speakTracked(speechContext().getString(R.string.prompt_allow_camera), flush = true)
            handler.post { listener?.onNeedCameraPermission() }
        } else {
            startGuiding()
        }
    }

    private fun speakAhead() {
        val speech = speechContext()
        val lines = lastRanked.take(3).map { PhraseBook.warning(speech, it) }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            speakTracked(speech.getString(R.string.ahead_clear), flush = true)
        } else {
            speakSequence(lines)
        }
    }

    private fun stepVolume(louder: Boolean) {
        val next = if (louder) settings.louder() else settings.quieter()
        val speech = speechContext()
        val phrase = when {
            next.speechVolume == settings.speechVolume && louder -> speech.getString(R.string.confirm_louder_max)
            next.speechVolume == settings.speechVolume -> speech.getString(R.string.confirm_quieter_min)
            louder -> speech.getString(R.string.confirm_louder)
            else -> speech.getString(R.string.confirm_quieter)
        }
        settings = next
        SettingsStore.save(this, settings)
        applySettings(settings, announceMissing = false)
        speakTracked(phrase, flush = true)
        handler.post { listener?.onVoiceSettingsChanged(languageChanged = false) }
    }

    private fun stepRate(faster: Boolean) {
        val next = if (faster) settings.faster() else settings.slower()
        val speech = speechContext()
        val phrase = when {
            next.speechRate == settings.speechRate && faster -> speech.getString(R.string.confirm_faster_max)
            next.speechRate == settings.speechRate -> speech.getString(R.string.confirm_slower_min)
            faster -> speech.getString(R.string.confirm_faster)
            else -> speech.getString(R.string.confirm_slower)
        }
        settings = next
        SettingsStore.save(this, settings)
        applySettings(settings, announceMissing = false)
        speakTracked(phrase, flush = true)
        handler.post { listener?.onVoiceSettingsChanged(languageChanged = false) }
    }

    private fun speakSequence(lines: List<String>) {
        lines.forEachIndexed { index, line ->
            if (index == 0) speakTracked(line, flush = true) else speaker.speak(line, flush = false)
        }
    }

    private fun commandLexicon(): CommandLexicon {
        val speech = Localized.wrap(this, settings.languageTag)
        fun phrases(name: Int) = CommandLexicon.pipes(speech.getString(name))
        return CommandLexicon.ENGLISH.withExtra(
            mapOf(
                VoiceCommand.START to phrases(R.string.cmd_start),
                VoiceCommand.STOP to phrases(R.string.cmd_stop),
                VoiceCommand.PAUSE to phrases(R.string.cmd_pause),
                VoiceCommand.RESUME to phrases(R.string.cmd_resume),
                VoiceCommand.WHAT_IS_AHEAD to phrases(R.string.cmd_ahead),
                VoiceCommand.REPEAT to phrases(R.string.cmd_repeat),
                VoiceCommand.LOUDER to phrases(R.string.cmd_louder),
                VoiceCommand.QUIETER to phrases(R.string.cmd_quieter),
                VoiceCommand.FASTER to phrases(R.string.cmd_faster),
                VoiceCommand.SLOWER to phrases(R.string.cmd_slower),
                VoiceCommand.CHANGE_LANGUAGE to phrases(R.string.cmd_language),
                VoiceCommand.HELP to phrases(R.string.cmd_help),
                VoiceCommand.START_COLLECTING to phrases(R.string.cmd_collect_start),
                VoiceCommand.STOP_COLLECTING to phrases(R.string.cmd_collect_stop),
                VoiceCommand.MISSED to phrases(R.string.cmd_missed),
                VoiceCommand.WRONG to phrases(R.string.cmd_wrong),
            ),
        )
    }

    private fun registerShake() {
        if (shakeListener != null) return
        val manager = getSystemService(SensorManager::class.java) ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val fired = shakeWatch.onSample(
                    SystemClock.elapsedRealtime(),
                    event.values[0],
                    event.values[1],
                    event.values[2],
                )
                if (fired) ear?.requestCapture()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager = manager
        shakeListener = listener
    }

    private fun unregisterShake() {
        val listener = shakeListener ?: return
        sensorManager?.unregisterListener(listener)
        shakeListener = null
    }

    private fun registerLight() {
        if (lightListener != null) return
        val manager = sensorManager ?: getSystemService(SensorManager::class.java) ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values.firstOrNull() ?: return
                if (lux.isFinite() && lux >= 0f) ambientLux = lux
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager = manager
        lightListener = listener
    }

    private fun unregisterLight() {
        val listener = lightListener ?: return
        sensorManager?.unregisterListener(listener)
        lightListener = null
        ambientLux = null
    }

    private fun updateLight(scene: SceneLight, torchAvailable: Boolean) {
        val sample = when {
            scene.measured -> scene.also { lastScene = it }
            lastScene != null -> lastScene ?: scene
            else -> scene
        }
        val decision = synchronized(lowLight) {
            lowLight.step(sample, settings.torchMode, torchAvailable, SystemClock.elapsedRealtime())
        }
        scoreThreshold = LowLight.scoreThreshold(decision.lowLight)
        val changed = decision.torchOn != torchWanted || decision.lowLight != exposureBoost || appliedTorch == null
        torchWanted = decision.torchOn
        exposureBoost = decision.lowLight
        if (changed) handler.post { applyCameraControls() }
        val name = decision.phraseResName ?: return
        val text = when (name) {
            LowLight.PHRASE_TURNING_ON -> speechContext().getString(R.string.light_turning_on)
            LowLight.PHRASE_TOO_DARK -> speechContext().getString(R.string.light_too_dark)
            else -> return
        }
        handler.post {
            if (!running || stopping || quitting) return@post
            postStatus(text)
            speakTracked(text, flush = false)
        }
    }

    private fun applyCameraControls() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { applyCameraControls() }
            return
        }
        val camera = boundCamera ?: return
        if (!running) return
        val wantTorch = torchWanted && flashUnit
        val wantBoost = exposureBoost
        try {
            if (appliedTorch != wantTorch) {
                camera.cameraControl.enableTorch(wantTorch)
                appliedTorch = wantTorch
            }
        } catch (error: Exception) {
            Log.w(TAG, "Torch failed", error)
            appliedTorch = null
        }
        try {
            if (appliedExposureBoost != wantBoost) {
                val state = camera.cameraInfo.exposureState
                if (state.isExposureCompensationSupported) {
                    val step = state.exposureCompensationStep.toFloat()
                    val raw = if (wantBoost && step > 0f) (1f / step).roundToInt() else 0
                    val index = raw.coerceIn(
                        state.exposureCompensationRange.lower,
                        state.exposureCompensationRange.upper,
                    )
                    camera.cameraControl.setExposureCompensationIndex(index)
                }
                appliedExposureBoost = wantBoost
            }
        } catch (error: Exception) {
            Log.w(TAG, "Exposure compensation failed", error)
            appliedExposureBoost = null
        }
    }

    private fun stickyResult(): Int {
        return if (settings.voiceEnabled || running) START_STICKY else START_NOT_STICKY
    }

    private fun promoteForeground(guiding: Boolean): Boolean {
        val notification = buildNotification(foregroundText())
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, foregroundTypes(guiding))
            } else if (Build.VERSION.SDK_INT >= 29 && guiding) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not start foreground service", error)
            if (!running) stopSelf()
            false
        }
    }

    private fun refreshForeground() {
        promoteForeground(guiding = running)
    }

    private fun foregroundTypes(guiding: Boolean): Int {
        var types = 0
        if (guiding) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (settings.voiceEnabled) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (guiding && settings.collectEnabled && settings.collectGps && Build.VERSION.SDK_INT >= 29) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return types
    }

    private fun foregroundText(): String {
        if (lastStatus.isNotBlank() && running) return lastStatus
        return if (settings.voiceEnabled) {
            uiContext().getString(R.string.status_spotting, settings.wakePhrase)
        } else {
            uiContext().getString(R.string.notif_listening)
        }
    }

    fun applySettings(newSettings: GuideSettings, announceMissing: Boolean = true) {
        val previousCollect = settings.collectEnabled
        val previousGps = settings.collectGps
        val previousLanguage = settings.languageTag
        settings = newSettings.normalized()
        when (speaker.setLanguage(settings.languageTag, settings.speechRate, settings.speechVolume)) {
            VoiceStatus.AVAILABLE -> {
                effectiveLanguage = settings.languageTag
                announcedMissingFor = null
            }
            VoiceStatus.MISSING -> {
                effectiveLanguage = "en"
                if (announceMissing) maybeAnnounceMissingVoice()
            }
            VoiceStatus.UNKNOWN -> {
                effectiveLanguage = settings.languageTag
            }
        }
        if (!settings.collectEnabled) {
            pendingSnapshot = null
            collectHot = false
        }
        collectScheduler.setEnabled(settings.collectEnabled)
        if (running && settings.collectEnabled) {
            ensureCollectTicker()
        } else if (!settings.collectEnabled) {
            stopCollectTicker()
        }
        CollectLocation.setActive(this, running && settings.collectEnabled && settings.collectGps)
        if (running && (previousCollect != settings.collectEnabled || previousGps != settings.collectGps)) {
            refreshForeground()
        }
        if (cameraBound && running && settings.collectEnabled != boundCollectMode) {
            restartCameraForCollect()
        }
        if (running) {
            val scene = lastScene
            if (scene != null) {
                updateLight(scene, flashUnit)
            } else if (settings.torchMode != TorchMode.AUTO) {
                updateLight(SceneLight(Float.NaN), flashUnit)
            }
        }
        if (previousLanguage != settings.languageTag) {
            ear?.reloadGrammar()
        }
    }

    fun speakStatus(text: String) {
        if (text.isBlank()) return
        speakTracked(text, flush = false)
    }

    private fun maybeAnnounceMissingVoice() {
        if (!cameraReady || stopping) return
        if (speaker.voiceStatus() != VoiceStatus.MISSING) return
        if (settings.languageTag == "en") return
        if (announcedMissingFor == settings.languageTag) return
        announcedMissingFor = settings.languageTag
        effectiveLanguage = "en"
        val spoken = englishContext().getString(missingVoiceRes(settings.languageTag))
        postStatus(uiContext().getString(R.string.voice_missing_screen))
        speakTracked(spoken, flush = false)
    }

    private fun loadModelThenCamera() {
        if (modelLoading || detector != null) return
        modelLoading = true
        cameraExecutor.execute {
            try {
                val created = ObstacleDetector(applicationContext)
                val createdDepth = try {
                    TfliteDepth(applicationContext)
                } catch (error: Exception) {
                    Log.w(TAG, "Depth model unavailable", error)
                    null
                }
                handler.post {
                    modelLoading = false
                    if (!running || stopping) {
                        created.close()
                        createdDepth?.close()
                        return@post
                    }
                    detector = created
                    depthEstimator = createdDepth
                    synchronized(depthTracker) { depthTracker.reset() }
                    latestDepth = emptyList()
                    latestAheadMedian = Float.NaN
                    bindCamera()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Model load failed", error)
                modelLoading = false
                handler.post {
                    val message = speechContext().getString(R.string.status_model_error)
                    failAndStop(message)
                }
            }
        }
    }

    private fun bindCamera() {
        if (cameraBound) return
        cameraBound = true
        boundCollectMode = settings.collectEnabled
        val token = ++cameraToken
        if (settings.collectEnabled) {
            handler.post {
                if (running && !stopping && token == cameraToken) bindCameraX(fullResolution = true)
            }
            return
        }
        cameraExecutor.execute {
            if (!running || stopping || token != cameraToken) return@execute
            val ar = ArcoreDepthCamera(applicationContext)
            val started = try {
                ar.start(displayRotation())
            } catch (error: Exception) {
                Log.w(TAG, "ARCore depth not used", error)
                false
            }
            if (!started || !running || stopping || token != cameraToken) {
                ar.close()
                handler.post {
                    if (running && !stopping && token == cameraToken) bindCameraX(fullResolution = false)
                }
                return@execute
            }
            Log.i(TAG, "Using ARCore depth")
            handler.post {
                if (running && !stopping && token == cameraToken) onCameraReady()
            }
            runArcore(ar, token)
        }
    }

    private fun runArcore(ar: ArcoreDepthCamera, token: Int) {
        var errors = 0
        var fallback = false
        try {
            while (running && !stopping && token == cameraToken) {
                val shot = try {
                    ar.poll()
                } catch (error: Exception) {
                    errors += 1
                    Log.w(TAG, "ARCore frame failed", error)
                    if (errors >= 8) {
                        fallback = true
                        break
                    }
                    Thread.sleep(40)
                    continue
                }
                if (shot == null) {
                    Thread.sleep(20)
                    continue
                }
                errors = 0
                val nowElapsed = SystemClock.elapsedRealtime()
                if (nowElapsed - lastFrameAt < GuideSettings.frameIntervalMs(settings.batterySaver)) {
                    shot.bitmap.recycle()
                    continue
                }
                lastFrameAt = nowElapsed
                try {
                    val scene = SceneLight(
                        meanLuma = shot.bitmap.meanLuma(),
                        lux = ambientLux,
                    )
                    considerFrame(shot.bitmap, shot.depth, allowMonocular = false, scene = scene)
                } catch (error: Exception) {
                    onFrameError(error)
                } finally {
                    shot.bitmap.recycle()
                }
            }
        } finally {
            ar.close()
        }
        if (fallback && running && !stopping && token == cameraToken) {
            Log.w(TAG, "ARCore depth failed, using the monocular model")
            handler.post {
                if (running && !stopping && token == cameraToken) bindCameraX(fullResolution = settings.collectEnabled)
            }
        }
    }

    private fun displayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    private fun bindCameraX(fullResolution: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            failAndStop(uiContext().getString(R.string.status_camera_needed))
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    if (!running || stopping) return@addListener
                    val provider = future.get()
                    cameraProvider = provider
                    val resolution = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setTargetRotation(android.view.Surface.ROTATION_0)
                        .build()
                    imageAnalysis.setAnalyzer(cameraExecutor) { image -> analyze(image) }
                    analysis = imageAnalysis
                    val still = if (fullResolution) fullResCapture() else null
                    imageCapture = still
                    provider.unbindAll()
                    val camera = if (still != null) {
                        try {
                            provider.bindToLifecycle(
                                this,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                imageAnalysis,
                                still,
                            )
                        } catch (error: Exception) {
                            Log.w(TAG, "Full-resolution capture unavailable, warnings continue", error)
                            imageCapture = null
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                this,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                imageAnalysis,
                            )
                        }
                    } else {
                        provider.bindToLifecycle(
                            this,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            imageAnalysis,
                        )
                    }
                    boundCamera = camera
                    flashUnit = try {
                        camera.cameraInfo.hasFlashUnit()
                    } catch (error: Exception) {
                        Log.w(TAG, "Flash check failed", error)
                        false
                    }
                    appliedTorch = null
                    appliedExposureBoost = null
                    cameraBindTries = 0
                    onCameraReady()
                    if (settings.torchMode == TorchMode.ON) {
                        updateLight(lastScene ?: SceneLight(Float.NaN), flashUnit)
                    } else {
                        applyCameraControls()
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Camera bind failed", error)
                    if (cameraBindTries < 2 && running && !stopping) {
                        cameraBindTries += 1
                        releaseCamera()
                        handler.postDelayed({
                            if (running && !stopping && !cameraBound) bindCamera()
                        }, 600)
                    } else {
                        cameraBindTries = 0
                        failAndStop(speechContext().getString(R.string.status_camera_error))
                    }
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun fullResCapture(): ImageCapture {
        val highest = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(CollectCapture.JPEG_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(highest)
            .setTargetRotation(displayRotation())
            .build()
    }

    private fun onCameraReady() {
        if (cameraReady) return
        cameraReady = true
        val started = speechContext().getString(R.string.status_started)
        postStatus(uiContext().getString(R.string.status_started))
        speakTracked(started, flush = true)
        maybeAnnounceMissingVoice()
    }

    private fun analyze(image: ImageProxy) {
        if (!running || stopping) {
            image.close()
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val interval = GuideSettings.frameIntervalMs(settings.batterySaver)
        if (nowElapsed - lastFrameAt < interval) {
            image.close()
            return
        }
        lastFrameAt = nowElapsed
        if (detector == null) {
            image.close()
            return
        }
        val scene = try {
            val (iso, exposureNs) = image.captureExposure()
            SceneLight(
                meanLuma = image.meanLumaY(),
                lux = ambientLux,
                iso = iso,
                exposureTimeNs = exposureNs,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Brightness read failed", error)
            SceneLight(Float.NaN, ambientLux)
        }
        var bitmap: android.graphics.Bitmap? = null
        try {
            bitmap = image.toUprightBitmap()
            considerFrame(bitmap, depthField = null, allowMonocular = true, scene = scene)
        } catch (error: Exception) {
            onFrameError(error)
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    private fun considerFrame(
        bitmap: android.graphics.Bitmap,
        depthField: DepthField?,
        allowMonocular: Boolean,
        scene: SceneLight,
    ) {
        val current = detector ?: return
        updateLight(scene, flashUnit)
        val threshold = scoreThreshold
        val boxes = current.detect(bitmap, threshold)
        frameErrors = 0
        latestBoxes = boxes
        saveFallbackFrame(bitmap)
        if (depthField != null) {
            publishDepth(depthField, boxes, settings.depthCalibration(), threshold)
        } else if (allowMonocular) {
            scheduleDepth(bitmap, boxes, threshold)
        }
        val ranked = rankFrame(boxes, threshold)
        lastRanked = ranked
        if (commandCapturing) return
        val choice = WarningLogic.selectAnnouncement(
            ranked,
            throttle,
            speechBusy.get() || speaker.speaking.get(),
            System.currentTimeMillis(),
        ) ?: return
        val phrase = PhraseBook.warning(speechContext(), choice)
        if (phrase.isBlank()) return
        lastWarningPhrase = phrase
        val warningBoxes = boxes.toList()
        if (settings.collectEnabled) {
            collectScheduler.onWarning()
            handler.post { maybeCapture() }
        }
        speechBusy.set(true)
        if (choice.urgent && settings.vibrationEnabled) {
            handler.post { vibrateUrgent() }
        }
        handler.post {
            if (quitting || stopping || !running) {
                speechBusy.set(false)
                return@post
            }
            lastSpokenWarning = CollectCapture.spokenWarning(
                choice,
                warningBoxes,
                CollectCapture.isoTimestamp(System.currentTimeMillis()),
            )
            postStatus(phrase)
            openCorrectionOnIdle = true
            speaker.speak(phrase, flush = true) { speechBusy.set(false) }
        }
        Log.i(TAG, "warn ${choice.label} ${choice.position} ${choice.closeness}")
    }

    private fun rankFrame(boxes: List<DetectionBox>, threshold: Float): List<ObstacleWarning> {
        return (WarningLogic.rank(boxes, threshold) + latestDepth).sortedWith(
            compareByDescending<ObstacleWarning> { it.priority }.thenBy { it.label },
        )
    }

    private fun scheduleDepth(
        bitmap: android.graphics.Bitmap,
        boxes: List<DetectionBox>,
        scoreThreshold: Float,
    ) {
        val estimator = depthEstimator ?: return
        val now = SystemClock.elapsedRealtime()
        val interval = if (settings.batterySaver) {
            GuideSettings.DEPTH_INTERVAL_SAVER_MS
        } else {
            GuideSettings.DEPTH_INTERVAL_NORMAL_MS
        }
        if (now - lastDepthAt < interval) return
        if (!depthBusy.compareAndSet(false, true)) return
        lastDepthAt = now
        val copy = try {
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } catch (error: Exception) {
            Log.w(TAG, "Depth copy failed", error)
            null
        }
        if (copy == null) {
            depthBusy.set(false)
            return
        }
        val generation = depthGeneration.get()
        val calibration = settings.depthCalibration()
        val detections = boxes.toList()
        depthExecutor.execute {
            try {
                if (generation != depthGeneration.get() || !running || stopping) return@execute
                publishDepth(estimator.estimate(copy), detections, calibration, scoreThreshold)
            } catch (error: Exception) {
                Log.w(TAG, "Depth frame failed", error)
            } finally {
                copy.recycle()
                depthBusy.set(false)
            }
        }
    }

    private fun publishDepth(
        field: DepthField,
        boxes: List<DetectionBox>,
        calibration: DepthCalibration?,
        scoreThreshold: Float,
    ) {
        val warnings = synchronized(depthTracker) {
            depthTracker.update(field, boxes, calibration, scoreThreshold)
        }
        latestAheadMedian = DepthWarnings.aheadMedian(field)
        latestDepthField = field
        latestDepth = warnings
    }

    private fun onFrameError(error: Exception) {
        frameErrors += 1
        Log.w(TAG, "Frame failed", error)
        if (frameErrors >= 8 && !toldFrameError) {
            toldFrameError = true
            val message = speechContext().getString(R.string.status_camera_error)
            handler.post {
                postStatus(message)
                speakTracked(message, flush = true)
            }
        }
    }

    private fun speakTracked(text: String, flush: Boolean) {
        if (quitting) return
        speechBusy.set(true)
        speaker.speak(text, flush = flush) { speechBusy.set(false) }
    }

    private fun failAndStop(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { failAndStop(message) }
            return
        }
        postStatus(message)
        speakTracked(message, flush = true)
        if (quitting) return
        stopGuide(speakGoodbye = false)
    }

    private fun quitApp() {
        if (quitting) return
        quitting = true
        stopping = true
        running = false
        commandCapturing = false
        stopCollectWork()
        cancelDelayedStop()
        ear?.setIgnoreRecognition(true)
        stopEar()
        unregisterLight()
        releaseCamera()
        releaseDetector()
        cancelVibration()
        handler.post { listener?.onRunningChanged(false) }
        val spoken = speechContext().getString(R.string.confirm_app_stopped)
        val shown = uiContext().getString(R.string.confirm_app_stopped)
        postStatus(shown)
        if (inForeground) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(shown))
            } catch (error: Exception) {
                Log.w(TAG, "goodbye notification", error)
            }
        }
        speechBusy.set(true)
        speaker.speak(spoken, flush = true) {
            speechBusy.set(false)
            finishQuit()
        }
        handler.postDelayed({ finishQuit() }, 8_000L)
    }

    private fun finishQuit() {
        if (!quitFinished.compareAndSet(false, true)) return
        handler.postDelayed({
            releaseWakeLock()
            inForeground = false
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            try {
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            } catch (_: Exception) {
            }
            try {
                listener?.onQuit()
            } catch (_: Exception) {
            }
            finishActivities()
            stopSelf()
        }, 350L)
    }

    private fun finishActivities() {
        val manager = getSystemService(ActivityManager::class.java) ?: return
        for (task in manager.appTasks) {
            try {
                task.finishAndRemoveTask()
            } catch (error: Exception) {
                Log.w(TAG, "finish task", error)
            }
        }
    }

    private fun stopGuide(speakGoodbye: Boolean = true) {
        if (!running) return
        running = false
        cameraReady = false
        stopCollectWork()
        unregisterLight()
        releaseCamera()
        releaseDetector()
        cancelVibration()
        handler.post { listener?.onRunningChanged(false) }
        if (speakGoodbye) {
            val goodbye = speechContext().getString(R.string.status_stopped)
            postStatus(uiContext().getString(R.string.status_stopped))
            speaker.speak(goodbye, flush = true) { speechBusy.set(false) }
        }
        if (settings.voiceEnabled) {
            stopping = false
            refreshForeground()
            ensureEar()
            return
        }
        stopping = true
        releaseWakeLock()
        stopEar()
        inForeground = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        scheduleStop()
    }

    private fun scheduleStop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!stopping) return@Runnable
            stopSelf()
        }
        stopRunnable = runnable
        handler.postDelayed(runnable, if (speaker.speaking.get()) 1_600L else 400L)
    }

    private fun cancelDelayedStop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
    }

    private fun releaseCamera() {
        cameraBound = false
        cameraToken += 1
        torchWanted = false
        exposureBoost = false
        try {
            boundCamera?.cameraControl?.enableTorch(false)
        } catch (error: Exception) {
            Log.w(TAG, "torch off", error)
        }
        try {
            cameraProvider?.unbindAll()
        } catch (error: Exception) {
            Log.w(TAG, "unbind", error)
        }
        analysis = null
        imageCapture = null
        boundCamera = null
        flashUnit = false
        appliedTorch = null
        appliedExposureBoost = null
        lastScene = null
        scoreThreshold = WarningLogic.SCORE_THRESHOLD
        synchronized(lowLight) { lowLight.reset() }
    }

    private fun releaseDetector() {
        try {
            detector?.close()
        } catch (error: Exception) {
            Log.w(TAG, "detector close", error)
        }
        detector = null
        releaseDepth()
    }

    private fun releaseDepth() {
        depthGeneration.incrementAndGet()
        val closing = depthEstimator
        depthEstimator = null
        latestDepth = emptyList()
        latestAheadMedian = Float.NaN
        synchronized(depthTracker) { depthTracker.reset() }
        if (closing != null) {
            depthExecutor.execute {
                try {
                    closing.close()
                } catch (error: Exception) {
                    Log.w(TAG, "depth close", error)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObstacleGuide::detect")
        lock.setReferenceCounted(false)
        lock.acquire(6 * 60 * 60 * 1000L)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun vibrateUrgent() {
        if (!settings.vibrationEnabled) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 280, 120, 280, 120, 280)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    @Suppress("DEPRECATION")
    private fun cancelVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()
    }

    private fun postStatus(text: String) {
        lastStatus = text
        handler.post {
            listener?.onStatus(text)
            if (quitting || !inForeground) return@post
            if (!stopping || text.isNotBlank()) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            uiContext().getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.description = uiContext().getString(R.string.notif_listening)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val ui = uiContext()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this,
            1,
            Intent(this, GuideService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val voiceOff = PendingIntent.getService(
            this,
            2,
            Intent(this, GuideService::class.java).setAction(ACTION_VOICE_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ui.getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        builder.addAction(0, ui.getString(R.string.notif_stop), quit)
        if (!running && settings.voiceEnabled) {
            builder.addAction(0, ui.getString(R.string.voice_off_action), voiceOff)
        }
        return builder.build()
    }

    private fun uiContext(): Context = Localized.wrap(this, settings.languageTag)

    private fun speechContext(): Context = Localized.wrap(this, effectiveLanguage)

    private fun englishContext(): Context = Localized.wrap(this, "en")

    private fun missingVoiceRes(tag: String): Int = when (tag) {
        "hi" -> R.string.voice_missing_hi
        "ta" -> R.string.voice_missing_ta
        "bn" -> R.string.voice_missing_bn
        "sw" -> R.string.voice_missing_sw
        "es" -> R.string.voice_missing_es
        "kn" -> R.string.voice_missing_kn
        "te" -> R.string.voice_missing_te
        else -> R.string.status_tts_error
    }

    private fun ensureCollectTicker() {
        if (collectTicking) return
        collectTicking = true
        handler.post(collectTick)
    }

    private fun stopCollectTicker() {
        collectTicking = false
        handler.removeCallbacks(collectTick)
    }

    private fun stopCollectWork() {
        stopCollectTicker()
        pendingSnapshot = null
        captureBusy.set(false)
        collectHot = false
        CollectLocation.stop(this)
    }

    private fun restartCameraForCollect() {
        if (!running || stopping || !cameraBound) return
        releaseCamera()
        handler.postDelayed({
            if (running && !stopping && !cameraBound) bindCamera()
        }, 400)
    }

    private fun currentSnapshot(event: CollectEvent, fullResolution: Boolean): CollectSnapshot {
        val scene = lastScene ?: SceneLight(Float.NaN)
        val gpsOn = settings.collectGps
        return CollectSnapshot(
            event = event,
            requestedAtMs = System.currentTimeMillis(),
            detections = latestBoxes,
            depth = CollectCapture.summarizeDepth(latestDepthField),
            lighting = CollectCapture.lightingOf(scene),
            gpsEnabled = gpsOn,
            gps = CollectLocation.readingIfAllowed(gpsOn),
            fullResolution = fullResolution,
            spokenWarning = if (event == CollectEvent.WRONG) lastSpokenWarning else null,
        )
    }

    private fun noteHeat(hot: Boolean) {
        if (!settings.collectEnabled || !running || hot == collectHot) return
        collectHot = hot
        val phrase = speechContext().getString(
            if (hot) R.string.collect_paused_hot else R.string.collect_resumed,
        )
        postStatus(phrase)
        speakTracked(phrase, flush = false)
    }

    private fun maybeCapture() {
        if (!running || stopping || !settings.collectEnabled) return
        val hot = try {
            CollectStore.isHot(this)
        } catch (error: Exception) {
            Log.w(TAG, "Thermal read failed", error)
            false
        }
        noteHeat(hot)
        val event = collectScheduler.poll(
            SystemClock.elapsedRealtime(),
            settings.collectIntervalMs,
            hot,
            captureBusy.get() || pendingSnapshot != null,
        ) ?: return
        val still = imageCapture
        val snapshot = currentSnapshot(event, fullResolution = still != null)
        if (still == null) {
            pendingSnapshot = snapshot
            return
        }
        if (!captureBusy.compareAndSet(false, true)) {
            collectScheduler.requeue(event)
            return
        }
        val jpg: File
        val json: File
        try {
            val files = CollectStore.allocate(this, event, snapshot.requestedAtMs)
            jpg = files.first
            json = files.second
        } catch (error: Exception) {
            captureBusy.set(false)
            collectScheduler.requeue(event)
            Log.w(TAG, "Collect folder failed", error)
            return
        }
        try {
            still.takePicture(
                ImageCapture.OutputFileOptions.Builder(jpg).build(),
                collectExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val (width, height) = CollectStore.imageSize(jpg)
                            CollectStore.writeSidecar(json, jpg, snapshot, width, height)
                            collectScheduler.onCaptured(SystemClock.elapsedRealtime())
                            publishCollectUsage()
                        } catch (error: Exception) {
                            Log.w(TAG, "Collect sidecar failed", error)
                            jpg.delete()
                            json.delete()
                            collectScheduler.onCaptured(SystemClock.elapsedRealtime())
                        } finally {
                            captureBusy.set(false)
                            handler.post { maybeCapture() }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "Collect capture failed", exception)
                        jpg.delete()
                        json.delete()
                        collectScheduler.onCaptured(SystemClock.elapsedRealtime())
                        captureBusy.set(false)
                        handler.post { maybeCapture() }
                    }
                },
            )
        } catch (error: Exception) {
            Log.w(TAG, "Collect capture failed", error)
            jpg.delete()
            json.delete()
            captureBusy.set(false)
            collectScheduler.onCaptured(SystemClock.elapsedRealtime())
        }
    }

    private fun saveFallbackFrame(bitmap: Bitmap) {
        val snapshot = pendingSnapshot ?: return
        if (imageCapture != null) {
            pendingSnapshot = null
            collectScheduler.requeue(snapshot.event)
            handler.post { maybeCapture() }
            return
        }
        if (!::collectEncoder.isInitialized || !collectEncoder.hasRoom()) {
            pendingSnapshot = null
            collectScheduler.requeue(snapshot.event)
            return
        }
        if (!captureBusy.compareAndSet(false, true)) return
        pendingSnapshot = null
        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (error: Exception) {
            Log.w(TAG, "Collect copy failed", error)
            null
        }
        if (copy == null) {
            captureBusy.set(false)
            collectScheduler.requeue(snapshot.event)
            return
        }
        val saved = snapshot.copy(fullResolution = false)
        val accepted = collectEncoder.offer(
            copy,
            saved,
            onSaved = {
                collectScheduler.onCaptured(SystemClock.elapsedRealtime())
                publishCollectUsage()
                captureBusy.set(false)
                handler.post { maybeCapture() }
            },
            onFailed = {
                collectScheduler.onCaptured(SystemClock.elapsedRealtime())
                captureBusy.set(false)
                handler.post { maybeCapture() }
            },
        )
        if (!accepted) {
            captureBusy.set(false)
            collectScheduler.requeue(snapshot.event)
            StabilityLog.append(this, "COLLECT queue full, frame dropped")
        }
    }

    private fun publishCollectUsage() {
        val usage = try {
            CollectStore.usage(applicationContext)
        } catch (error: Exception) {
            Log.w(TAG, "Collect usage failed", error)
            return
        }
        handler.post { listener?.onCollectChanged(usage.photos, usage.bytes) }
    }

    override fun onDestroy() {
        stopping = true
        running = false
        commandCapturing = false
        cancelDelayedStop()
        stopEar()
        releaseCamera()
        releaseWakeLock()
        cancelVibration()
        releaseDetector()
        if (::speaker.isInitialized) speaker.shutdown()
        stopCollectWork()
        if (::collectEncoder.isInitialized) collectEncoder.shutdown()
        cameraExecutor.shutdown()
        depthExecutor.shutdown()
        collectExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.visualaid.obstacleguide.STOP"
        const val ACTION_QUIT = "org.visualaid.obstacleguide.QUIT"
        const val ACTION_SPOT = "org.visualaid.obstacleguide.SPOT"
        const val ACTION_VOICE_OFF = "org.visualaid.obstacleguide.VOICE_OFF"
        private const val CHANNEL_ID = "obstacle_guide"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ObstacleGuide"

        @Volatile
        var running: Boolean = false
            private set
    }
}
