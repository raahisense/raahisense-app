package org.visualaid.obstacleguide

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.visualaid.obstacleguide.detect.ArcoreDepthCamera
import org.visualaid.obstacleguide.detect.ObstacleDetector
import org.visualaid.obstacleguide.detect.TfliteDepth
import org.visualaid.obstacleguide.detect.toUprightBitmap
import org.visualaid.obstacleguide.logic.CommandLexicon
import org.visualaid.obstacleguide.logic.DepthCalibration
import org.visualaid.obstacleguide.logic.DepthField
import org.visualaid.obstacleguide.logic.DepthTracker
import org.visualaid.obstacleguide.logic.DepthWarnings
import org.visualaid.obstacleguide.logic.DetectionBox
import org.visualaid.obstacleguide.logic.ObstacleWarning
import org.visualaid.obstacleguide.logic.VoiceAction
import org.visualaid.obstacleguide.logic.VoiceCommand
import org.visualaid.obstacleguide.logic.ShakeWatch
import org.visualaid.obstacleguide.logic.WarningLogic
import org.visualaid.obstacleguide.logic.WarningThrottle
import org.visualaid.obstacleguide.speech.GuideSpeaker
import org.visualaid.obstacleguide.speech.PhraseBook
import org.visualaid.obstacleguide.speech.VoiceStatus
import org.visualaid.obstacleguide.voice.VoskEar
import org.visualaid.obstacleguide.voice.VoskVocab
import java.util.concurrent.Executors
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
    private var stopRunnable: Runnable? = null
    private var lastFrameAt = 0L
    private var frameErrors = 0
    private var toldFrameError = false
    private var ear: VoskEar? = null
    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private val shakeWatch = ShakeWatch()
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

    fun aheadMedian(): Float = latestAheadMedian

    @Volatile
    var lastStatus: String = ""
        private set

    override fun onCreate() {
        super.onCreate()
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
        speaker.start()
        ensureChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        settings = SettingsStore.load(this)
        if (intent == null) {
            if (settings.voiceEnabled) startSpotting()
            return stickyResult()
        }
        when (intent.action) {
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
        cancelDelayedStop()
        stopping = false
        settings = SettingsStore.load(this)
        if (!promoteForeground(guiding = true)) return
        running = true
        acquireWakeLock()
        applySettings(settings, announceMissing = false)
        handler.post { listener?.onRunningChanged(true) }
        if (settings.voiceEnabled) ensureEar()
        if (detector == null) {
            loadModelThenCamera()
        } else if (!cameraBound) {
            bindCamera()
        }
    }

    private fun startSpotting() {
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

        override fun vocab(): Set<String> = VoskVocab.words(this@GuideService)
    }

    private fun performVoice(action: VoiceAction) {
        val speech = speechContext()
        when (action) {
            VoiceAction.IGNORE, VoiceAction.WAKE_ONLY -> Unit
            VoiceAction.UNKNOWN -> speakTracked(speech.getString(R.string.confirm_unknown), flush = true)
            VoiceAction.START -> {
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
            VoiceAction.STOP -> {
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
        val speech = speechContext()
        fun phrases(name: Int) = CommandLexicon.pipes(speech.getString(name))
        return CommandLexicon.ENGLISH.withExtra(
            mapOf(
                VoiceCommand.START to phrases(R.string.cmd_start),
                VoiceCommand.STOP to phrases(R.string.cmd_stop),
                VoiceCommand.WHAT_IS_AHEAD to phrases(R.string.cmd_ahead),
                VoiceCommand.REPEAT to phrases(R.string.cmd_repeat),
                VoiceCommand.LOUDER to phrases(R.string.cmd_louder),
                VoiceCommand.QUIETER to phrases(R.string.cmd_quieter),
                VoiceCommand.FASTER to phrases(R.string.cmd_faster),
                VoiceCommand.SLOWER to phrases(R.string.cmd_slower),
                VoiceCommand.CHANGE_LANGUAGE to phrases(R.string.cmd_language),
                VoiceCommand.HELP to phrases(R.string.cmd_help),
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
        val token = ++cameraToken
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
                    if (running && !stopping && token == cameraToken) bindCameraX()
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
                    considerFrame(shot.bitmap, shot.depth, allowMonocular = false)
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
                if (running && !stopping && token == cameraToken) bindCameraX()
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

    private fun bindCameraX() {
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
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        imageAnalysis,
                    )
                    onCameraReady()
                } catch (error: Exception) {
                    Log.e(TAG, "Camera bind failed", error)
                    failAndStop(speechContext().getString(R.string.status_camera_error))
                }
            },
            ContextCompat.getMainExecutor(this),
        )
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
        var bitmap: android.graphics.Bitmap? = null
        try {
            bitmap = image.toUprightBitmap()
            considerFrame(bitmap, depthField = null, allowMonocular = true)
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
    ) {
        val current = detector ?: return
        val boxes = current.detect(bitmap)
        frameErrors = 0
        if (depthField != null) {
            publishDepth(depthField, boxes, settings.depthCalibration())
        } else if (allowMonocular) {
            scheduleDepth(bitmap, boxes)
        }
        val ranked = rankFrame(boxes)
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
        speechBusy.set(true)
        if (choice.urgent && settings.vibrationEnabled) {
            handler.post { vibrateUrgent() }
        }
        handler.post {
            postStatus(phrase)
            speaker.speak(phrase, flush = true) { speechBusy.set(false) }
        }
        Log.i(TAG, "warn ${choice.label} ${choice.position} ${choice.closeness}")
    }

    private fun rankFrame(boxes: List<DetectionBox>): List<ObstacleWarning> {
        return (WarningLogic.rank(boxes) + latestDepth).sortedWith(
            compareByDescending<ObstacleWarning> { it.priority }.thenBy { it.label },
        )
    }

    private fun scheduleDepth(bitmap: android.graphics.Bitmap, boxes: List<DetectionBox>) {
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
                publishDepth(estimator.estimate(copy), detections, calibration)
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
    ) {
        val warnings = synchronized(depthTracker) {
            depthTracker.update(field, boxes, calibration)
        }
        latestAheadMedian = DepthWarnings.aheadMedian(field)
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
        stopGuide(speakGoodbye = false)
    }

    private fun stopGuide(speakGoodbye: Boolean = true) {
        if (!running) return
        running = false
        cameraReady = false
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
        try {
            cameraProvider?.unbindAll()
        } catch (error: Exception) {
            Log.w(TAG, "unbind", error)
        }
        analysis = null
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
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, GuideService::class.java).setAction(ACTION_STOP),
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
        if (running) {
            builder.addAction(0, ui.getString(R.string.notif_stop), stop)
        } else if (settings.voiceEnabled) {
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
        else -> R.string.status_tts_error
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
        cameraExecutor.shutdown()
        depthExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.visualaid.obstacleguide.STOP"
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
