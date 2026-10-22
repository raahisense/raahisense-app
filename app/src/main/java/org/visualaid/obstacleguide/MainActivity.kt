package org.visualaid.obstacleguide

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.visualaid.obstacleguide.databinding.ActivityMainBinding
import org.visualaid.obstacleguide.logic.DepthCalibration
import org.visualaid.obstacleguide.logic.TorchMode
import org.visualaid.obstacleguide.speech.GuideSpeaker
import org.visualaid.obstacleguide.voice.VoskVocab

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var announcer: GuideSpeaker? = null
    private var guide: GuideService? = null
    private var bound = false
    private var settings = GuideSettings()
    private var updating = false
    private var captureWhenBound = false
    private var askingMic = false
    private var ignoreClicksUntil = 0L
    private var lastDownAt = 0L
    private var settingsSnapshot: GuideSettings? = null
    private var volumeHold = false
    private var calibrateStep = 0
    private var pendingOpenRaw = 0f
    private val tapHandler = Handler(Looper.getMainLooper())
    private lateinit var gestures: GestureDetector

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        SettingsStore.setAskedCamera(this)
        if (result[Manifest.permission.CAMERA] == true) {
            showStatus(getString(R.string.status_ready))
            render()
            startGuide()
        } else {
            val message = getString(
                if (permanentlyDenied()) R.string.status_camera_blocked else R.string.status_camera_denied,
            )
            showStatus(message)
            speakUi(message)
            render()
        }
    }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        askingMic = false
        SettingsStore.setAskedMic(this)
        val granted = result[Manifest.permission.RECORD_AUDIO] == true
        if (granted) {
            ensureSpottingService()
            if (captureWhenBound && guide != null) {
                captureWhenBound = false
                guide?.requestCommandCapture()
            }
        } else {
            val message = getString(
                if (micPermanentlyDenied()) R.string.status_mic_blocked else R.string.status_mic_denied,
            )
            showStatus(message)
            speakUi(message)
        }
        render()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as GuideService.LocalBinder
            guide = local.service()
            bound = true
            guide?.setListener(serviceListener)
            guide?.lastStatus?.takeIf { it.isNotBlank() }?.let { showStatus(it) }
            if (captureWhenBound) {
                captureWhenBound = false
                guide?.requestCommandCapture()
            }
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            guide = null
            bound = false
            render()
        }
    }

    private val serviceListener = object : GuideService.Listener {
        override fun onStatus(text: String) {
            showStatus(text)
        }

        override fun onRunningChanged(running: Boolean) {
            if (!running) ensureAnnouncer()
            render()
        }

        override fun onVoiceSettingsChanged(languageChanged: Boolean) {
            settings = SettingsStore.load(this@MainActivity)
            if (languageChanged) {
                recreate()
            } else {
                render()
            }
        }

        override fun onNeedCameraPermission() {
            if (!cameraGranted()) requestCamera()
        }

        override fun onNeedMicPermission() {
            if (!micGranted() && !askingMic) requestMic()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val tag = SettingsStore.load(newBase).languageTag
        super.attachBaseContext(Localized.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore.load(this)
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (touchExploration()) return false
                ignoreClicksUntil = SystemClock.elapsedRealtime() + 500L
                settingsSnapshot?.let {
                    settings = it
                    SettingsStore.save(this@MainActivity, settings)
                }
                render()
                if (!settings.voiceEnabled) {
                    speakUi(getString(R.string.status_voice_off))
                } else {
                    ensureMicThenCapture()
                }
                return true
            }
        })
        ensureAnnouncer()
        bindControls()
        showStatus(initialStatus())
        render()
        if (settings.voiceEnabled && !micGranted() && !SettingsStore.askedMic(this)) {
            requestMic()
        }
    }

    override fun onStart() {
        super.onStart()
        if (settings.voiceEnabled && micGranted()) {
            ensureSpottingService()
        }
        if ((GuideService.running || settings.voiceEnabled) && !bound) {
            bindService(Intent(this, GuideService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        updateBatteryHint()
        render()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.eventTime - lastDownAt > 450L) {
            settingsSnapshot = settings
        }
        lastDownAt = ev.eventTime
        if (!touchExploration()) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode)) {
            if (event.repeatCount == 0) event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode)) {
            volumeHold = true
            if (settings.voiceEnabled) ensureMicThenCapture() else speakUi(getString(R.string.status_voice_off))
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode)) {
            if (volumeHold) {
                volumeHold = false
            } else {
                val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                }
                getSystemService(AudioManager::class.java).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        if (bound) {
            guide?.setListener(null)
            unbindService(connection)
            bound = false
            guide = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        releaseAnnouncer()
        super.onDestroy()
    }

    private fun ensureAnnouncer() {
        if (announcer != null) return
        announcer = GuideSpeaker(applicationContext).also { it.start() }
    }

    private fun releaseAnnouncer() {
        announcer?.shutdown()
        announcer = null
    }

    private fun bindControls() {
        binding.toggle.setOnClickListener { afterTap { onToggle() } }
        binding.batterySaver.setOnClickListener {
            afterTap {
                settings = settings.copy(batterySaver = !settings.batterySaver)
                persist()
                speakUi(getString(if (settings.batterySaver) R.string.battery_on else R.string.battery_off))
                render()
            }
        }
        binding.vibration.setOnClickListener {
            afterTap {
                settings = settings.copy(vibrationEnabled = !settings.vibrationEnabled)
                persist()
                speakUi(getString(if (settings.vibrationEnabled) R.string.vibration_on else R.string.vibration_off))
                render()
            }
        }
        binding.voiceCommands.setOnClickListener { afterTap { onVoiceToggle() } }
        binding.about.setOnClickListener { afterTap { startActivity(Intent(this, AboutActivity::class.java)) } }
        binding.calibrate.setOnClickListener { afterTap { onCalibrate() } }
        binding.batteryOpt.setOnClickListener { afterTap { openBatterySettings() } }
        binding.wakePhrase.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitWakePhrase()
        }
        binding.wakePhrase.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitWakePhrase()
                binding.wakePhrase.clearFocus()
                true
            } else {
                false
            }
        }
        binding.languages.setOnCheckedChangeListener { _, checkedId ->
            if (updating) return@setOnCheckedChangeListener
            val tag = languageTagFor(checkedId) ?: return@setOnCheckedChangeListener
            afterTap {
                if (tag == settings.languageTag) return@afterTap
                settings = settings.copy(languageTag = tag)
                persist()
                guide?.applySettings(settings)
                recreate()
            }
        }
        binding.rates.setOnCheckedChangeListener { _, checkedId ->
            if (updating) return@setOnCheckedChangeListener
            val rate = rateFor(checkedId) ?: return@setOnCheckedChangeListener
            afterTap {
                if (rate == settings.speechRate) return@afterTap
                settings = settings.copy(speechRate = rate)
                persist()
                val spoken = when (rate) {
                    GuideSettings.RATE_SLOW -> getString(R.string.rate_slow)
                    GuideSettings.RATE_FAST -> getString(R.string.rate_fast)
                    else -> getString(R.string.rate_normal)
                }
                speakUi(spoken)
            }
        }
        binding.lights.setOnCheckedChangeListener { _, checkedId ->
            if (updating) return@setOnCheckedChangeListener
            val mode = lightFor(checkedId) ?: return@setOnCheckedChangeListener
            afterTap {
                if (mode == settings.torchMode) return@afterTap
                settings = settings.copy(torchMode = mode)
                persist()
                val spoken = when (mode) {
                    TorchMode.ON -> getString(R.string.light_on)
                    TorchMode.OFF -> getString(R.string.light_off)
                    TorchMode.AUTO -> getString(R.string.light_auto)
                }
                speakUi(spoken)
            }
        }
    }

    private fun onCalibrate() {
        if (!GuideService.running || guide == null) {
            calibrateStep = 0
            speakUi(getString(R.string.calibrate_need_start))
            showStatus(getString(R.string.calibrate_need_start))
            return
        }
        when (calibrateStep) {
            0 -> {
                calibrateStep = 1
                speakUi(getString(R.string.calibrate_open))
                showStatus(getString(R.string.calibrate_open))
            }
            1 -> {
                val open = guide?.aheadMedian() ?: Float.NaN
                if (!open.isFinite() || open == 0f) {
                    speakUi(getString(R.string.calibrate_failed))
                    showStatus(getString(R.string.calibrate_failed))
                    return
                }
                pendingOpenRaw = open
                calibrateStep = 2
                speakUi(getString(R.string.calibrate_wall))
                showStatus(getString(R.string.calibrate_wall))
            }
            else -> {
                val wall = guide?.aheadMedian() ?: Float.NaN
                if (!DepthCalibration(pendingOpenRaw, wall).usable()) {
                    calibrateStep = 1
                    val message = if (wall.isFinite() && wall != 0f) {
                        getString(R.string.calibrate_same)
                    } else {
                        getString(R.string.calibrate_failed)
                    }
                    speakUi(message)
                    showStatus(message)
                    return
                }
                settings = settings.copy(depthOpenRaw = pendingOpenRaw, depthWallRaw = wall)
                persist()
                calibrateStep = 0
                speakUi(getString(R.string.calibrate_done))
                showStatus(getString(R.string.calibrate_done))
            }
        }
    }

    private fun onToggle() {
        when {
            !cameraGranted() && permanentlyDenied() -> {
                speakUi(getString(R.string.status_camera_blocked))
                openAppSettings()
            }
            !cameraGranted() -> requestCamera()
            GuideService.running -> stopGuide()
            else -> startGuide()
        }
    }

    private fun requestCamera() {
        speakUi(getString(R.string.prompt_allow_camera))
        showStatus(getString(R.string.prompt_allow_camera))
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startGuide() {
        releaseAnnouncer()
        val intent = Intent(this, GuideService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        render()
    }

    private fun stopGuide() {
        if (!GuideService.running && guide == null) return
        startService(Intent(this, GuideService::class.java).setAction(GuideService.ACTION_STOP))
        render()
    }

    private fun persist() {
        SettingsStore.save(this, settings)
        guide?.applySettings(settings)
    }

    private fun render() {
        val runningNow = GuideService.running
        val camera = cameraGranted()
        val blocked = permanentlyDenied()
        updating = true
        checkLanguage(settings.languageTag)
        checkRate(settings.speechRate)
        checkLight(settings.torchMode)
        updating = false

        val label: String
        val description: String
        val runningStyle: Boolean
        when {
            !camera && blocked -> {
                label = getString(R.string.action_open_settings)
                description = getString(R.string.cd_open_settings)
                runningStyle = false
            }
            !camera -> {
                label = getString(R.string.action_allow_camera)
                description = getString(R.string.cd_allow_camera)
                runningStyle = false
            }
            runningNow -> {
                label = getString(R.string.action_stop)
                description = getString(R.string.cd_stop)
                runningStyle = true
            }
            else -> {
                label = getString(R.string.action_start)
                description = getString(R.string.cd_start)
                runningStyle = false
            }
        }
        binding.toggle.text = label
        binding.toggle.contentDescription = description
        binding.toggle.setBackgroundResource(if (runningStyle) R.drawable.btn_running else R.drawable.btn_idle)
        binding.toggle.setTextColor(getColor(if (runningStyle) R.color.black else R.color.yellow))

        val saverOn = settings.batterySaver
        binding.batterySaver.text = getString(if (saverOn) R.string.battery_on else R.string.battery_off)
        binding.batterySaver.contentDescription = getString(
            if (saverOn) R.string.cd_battery_turn_off else R.string.cd_battery_turn_on,
        )
        val vibeOn = settings.vibrationEnabled
        binding.vibration.text = getString(if (vibeOn) R.string.vibration_on else R.string.vibration_off)
        binding.vibration.contentDescription = getString(
            if (vibeOn) R.string.cd_vibration_turn_off else R.string.cd_vibration_turn_on,
        )
        val voiceOn = settings.voiceEnabled
        binding.voiceCommands.text = getString(if (voiceOn) R.string.wake_on else R.string.wake_off)
        binding.voiceCommands.contentDescription = getString(
            if (voiceOn) R.string.cd_wake_turn_off else R.string.cd_wake_turn_on,
        )
        if (!binding.wakePhrase.isFocused && binding.wakePhrase.text.toString() != settings.wakePhrase) {
            binding.wakePhrase.setText(settings.wakePhrase)
        }
        binding.voiceEarNote.text = getString(R.string.voice_ear_note)
        val draft = getString(R.string.draft_banner)
        binding.draftBanner.visibility = if (draft.isBlank()) View.GONE else View.VISIBLE
        binding.draftBanner.text = draft
        updateBatteryHint()
    }

    private fun showStatus(text: String) {
        binding.status.text = text
    }

    private fun speakUi(text: String) {
        if (guide != null) {
            guide?.speakStatus(text)
        } else {
            ensureAnnouncer()
            announcer?.setLanguage(settings.languageTag, settings.speechRate, settings.speechVolume)
            announcer?.speak(text, flush = true)
        }
    }

    private fun initialStatus(): String = when {
        GuideService.running -> getString(R.string.status_listening)
        !cameraGranted() && permanentlyDenied() -> getString(R.string.status_camera_blocked)
        !cameraGranted() -> getString(R.string.status_camera_needed)
        else -> getString(R.string.status_ready)
    }

    private fun cameraGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun permanentlyDenied(): Boolean {
        return SettingsStore.askedCamera(this) &&
            !cameraGranted() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }

    private fun updateBatteryHint() {
        val power = getSystemService(PowerManager::class.java)
        val ignoring = power.isIgnoringBatteryOptimizations(packageName)
        binding.batteryHint.text = getString(
            if (ignoring) R.string.battery_hint_unrestricted else R.string.battery_hint_restricted,
        )
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                val message = getString(R.string.status_battery_settings_unavailable)
                showStatus(message)
                speakUi(message)
            }
        }
    }

    private fun checkLanguage(tag: String) {
        val id = when (tag) {
            "hi" -> R.id.langHi
            "ta" -> R.id.langTa
            "bn" -> R.id.langBn
            "sw" -> R.id.langSw
            "es" -> R.id.langEs
            else -> R.id.langEn
        }
        if (binding.languages.checkedRadioButtonId != id) {
            binding.languages.check(id)
        }
    }

    private fun checkLight(mode: TorchMode) {
        val id = when (mode) {
            TorchMode.ON -> R.id.lightOn
            TorchMode.OFF -> R.id.lightOff
            TorchMode.AUTO -> R.id.lightAuto
        }
        if (binding.lights.checkedRadioButtonId != id) {
            binding.lights.check(id)
        }
    }

    private fun checkRate(rate: Float) {
        val id = when (rate) {
            GuideSettings.RATE_SLOW -> R.id.rateSlow
            GuideSettings.RATE_FAST -> R.id.rateFast
            else -> R.id.rateNormal
        }
        if (binding.rates.checkedRadioButtonId != id) {
            binding.rates.check(id)
        }
    }

    private fun languageTagFor(checkedId: Int): String? = when (checkedId) {
        R.id.langEn -> "en"
        R.id.langHi -> "hi"
        R.id.langTa -> "ta"
        R.id.langBn -> "bn"
        R.id.langSw -> "sw"
        R.id.langEs -> "es"
        else -> null
    }

    private fun lightFor(checkedId: Int): TorchMode? = when (checkedId) {
        R.id.lightAuto -> TorchMode.AUTO
        R.id.lightOn -> TorchMode.ON
        R.id.lightOff -> TorchMode.OFF
        else -> null
    }

    private fun rateFor(checkedId: Int): Float? = when (checkedId) {
        R.id.rateSlow -> GuideSettings.RATE_SLOW
        R.id.rateNormal -> GuideSettings.RATE_NORMAL
        R.id.rateFast -> GuideSettings.RATE_FAST
        else -> null
    }

    private fun afterTap(block: () -> Unit) {
        val delay = if (settings.voiceEnabled && !touchExploration()) 320L else 0L
        tapHandler.postDelayed({
            if (SystemClock.elapsedRealtime() < ignoreClicksUntil) {
                render()
                return@postDelayed
            }
            block()
        }, delay)
    }

    private fun onVoiceToggle() {
        if (settings.voiceEnabled) {
            settings = settings.copy(voiceEnabled = false)
            persist()
            if (GuideService.running || bound) {
                startService(Intent(this, GuideService::class.java).setAction(GuideService.ACTION_VOICE_OFF))
            }
            speakUi(getString(R.string.wake_off))
        } else {
            settings = settings.copy(voiceEnabled = true)
            persist()
            requestMic()
        }
        render()
    }

    private fun commitWakePhrase() {
        val proposed = binding.wakePhrase.text?.toString().orEmpty()
        if (proposed.trim() == settings.wakePhrase) return
        if (!VoskVocab.allows(this, proposed)) {
            binding.wakePhrase.setText(settings.wakePhrase)
            speakUi(getString(R.string.wake_phrase_rejected))
            return
        }
        settings = settings.copy(wakePhrase = proposed).normalized()
        persist()
        guide?.reloadEar()
        speakUi(getString(R.string.confirm_wake_phrase, settings.wakePhrase))
    }

    private fun ensureSpottingService() {
        if (!settings.voiceEnabled || !micGranted()) return
        val intent = Intent(this, GuideService::class.java).setAction(GuideService.ACTION_SPOT)
        ContextCompat.startForegroundService(this, intent)
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun ensureMicThenCapture() {
        if (!micGranted()) {
            captureWhenBound = true
            requestMic()
            return
        }
        captureWhenBound = guide == null
        ensureSpottingService()
        if (guide != null) {
            captureWhenBound = false
            guide?.requestCommandCapture()
        }
    }

    private fun requestMic() {
        if (askingMic) return
        if (micPermanentlyDenied()) {
            speakUi(getString(R.string.status_mic_blocked))
            showStatus(getString(R.string.status_mic_blocked))
            return
        }
        askingMic = true
        speakUi(getString(R.string.prompt_allow_microphone))
        showStatus(getString(R.string.prompt_allow_microphone))
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        micLauncher.launch(permissions.toTypedArray())
    }

    private fun micGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun micPermanentlyDenied(): Boolean {
        return SettingsStore.askedMic(this) &&
            !micGranted() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    }

    private fun touchExploration(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager?.isTouchExplorationEnabled == true
    }

    private fun isVolumeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
    }

}
