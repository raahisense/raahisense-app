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
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.visualaid.obstacleguide.detect.ObstacleDetector
import org.visualaid.obstacleguide.detect.toUprightBitmap
import org.visualaid.obstacleguide.logic.WarningLogic
import org.visualaid.obstacleguide.logic.WarningThrottle
import org.visualaid.obstacleguide.speech.GuideSpeaker
import org.visualaid.obstacleguide.speech.PhraseBook
import org.visualaid.obstacleguide.speech.VoiceStatus
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the rear camera, the detector, and speech running when the screen is
 * locked. A persistent notification shows that warnings are on.
 */
class GuideService : LifecycleService() {
    interface Listener {
        fun onStatus(text: String)
        fun onRunningChanged(running: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun service(): GuideService = this@GuideService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "obstacle-guide-camera").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val speechBusy = AtomicBoolean(false)
    private val throttle = WarningThrottle()

    private lateinit var speaker: GuideSpeaker
    private var detector: ObstacleDetector? = null
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
        if (intent?.action == ACTION_STOP) {
            stopGuide()
            return START_NOT_STICKY
        }
        cancelDelayedStop()
        stopping = false
        val notification = buildNotification(uiContext().getString(R.string.notif_listening))
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not start foreground service", error)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        acquireWakeLock()
        settings = SettingsStore.load(this)
        applySettings(settings, announceMissing = false)
        handler.post { listener?.onRunningChanged(true) }
        if (detector == null) {
            loadModelThenCamera()
        } else if (analysis == null) {
            bindCamera()
        }
        return START_NOT_STICKY
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        if (listener != null && lastStatus.isNotBlank()) {
            handler.post { listener.onStatus(lastStatus) }
        }
    }

    fun applySettings(newSettings: GuideSettings, announceMissing: Boolean = true) {
        settings = newSettings.normalized()
        when (speaker.setLanguage(settings.languageTag, settings.speechRate)) {
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
                handler.post {
                    modelLoading = false
                    if (!running || stopping) {
                        created.close()
                        return@post
                    }
                    detector = created
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
        val current = detector
        if (current == null) {
            image.close()
            return
        }
        var bitmap: android.graphics.Bitmap? = null
        try {
            bitmap = image.toUprightBitmap()
            val boxes = current.detect(bitmap)
            frameErrors = 0
            val ranked = WarningLogic.rank(boxes)
            val choice = WarningLogic.selectAnnouncement(
                ranked,
                throttle,
                speechBusy.get() || speaker.speaking.get(),
                System.currentTimeMillis(),
            )
            if (choice != null) {
                val phrase = PhraseBook.warning(speechContext(), choice)
                if (phrase.isBlank()) {
                    return
                }
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
        } catch (error: Exception) {
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
        } finally {
            bitmap?.recycle()
            image.close()
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
        if (stopping) return
        stopping = true
        running = false
        cameraReady = false
        releaseCamera()
        releaseDetector()
        releaseWakeLock()
        cancelVibration()
        handler.post { listener?.onRunningChanged(false) }
        if (speakGoodbye) {
            val goodbye = speechContext().getString(R.string.status_stopped)
            postStatus(uiContext().getString(R.string.status_stopped))
            speaker.speak(goodbye, flush = true) { speechBusy.set(false) }
        }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ui.getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, ui.getString(R.string.notif_stop), stop)
            .build()
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
        cancelDelayedStop()
        releaseCamera()
        releaseWakeLock()
        cancelVibration()
        releaseDetector()
        if (::speaker.isInitialized) speaker.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.visualaid.obstacleguide.STOP"
        private const val CHANNEL_ID = "obstacle_guide"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ObstacleGuide"

        @Volatile
        var running: Boolean = false
            private set
    }
}
