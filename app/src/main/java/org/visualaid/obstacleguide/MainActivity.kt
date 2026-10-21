package org.visualaid.obstacleguide

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.visualaid.obstacleguide.databinding.ActivityMainBinding
import org.visualaid.obstacleguide.speech.GuideSpeaker

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var announcer: GuideSpeaker? = null
    private var guide: GuideService? = null
    private var bound = false
    private var settings = GuideSettings()
    private var updating = false

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as GuideService.LocalBinder
            guide = local.service()
            bound = true
            guide?.setListener(serviceListener)
            guide?.lastStatus?.takeIf { it.isNotBlank() }?.let { showStatus(it) }
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
        ensureAnnouncer()
        bindControls()
        showStatus(initialStatus())
        render()
    }

    override fun onStart() {
        super.onStart()
        if (GuideService.running && !bound) {
            bindService(Intent(this, GuideService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        updateBatteryHint()
        render()
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
        binding.toggle.setOnClickListener { onToggle() }
        binding.batterySaver.setOnClickListener {
            settings = settings.copy(batterySaver = !settings.batterySaver)
            persist()
            speakUi(getString(if (settings.batterySaver) R.string.battery_on else R.string.battery_off))
            render()
        }
        binding.vibration.setOnClickListener {
            settings = settings.copy(vibrationEnabled = !settings.vibrationEnabled)
            persist()
            speakUi(getString(if (settings.vibrationEnabled) R.string.vibration_on else R.string.vibration_off))
            render()
        }
        binding.batteryOpt.setOnClickListener { openBatterySettings() }
        binding.languages.setOnCheckedChangeListener { _, checkedId ->
            if (updating) return@setOnCheckedChangeListener
            val tag = languageTagFor(checkedId) ?: return@setOnCheckedChangeListener
            if (tag == settings.languageTag) return@setOnCheckedChangeListener
            settings = settings.copy(languageTag = tag)
            persist()
            guide?.applySettings(settings)
            recreate()
        }
        binding.rates.setOnCheckedChangeListener { _, checkedId ->
            if (updating) return@setOnCheckedChangeListener
            val rate = rateFor(checkedId) ?: return@setOnCheckedChangeListener
            if (rate == settings.speechRate) return@setOnCheckedChangeListener
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
        val draft = getString(R.string.draft_banner)
        binding.draftBanner.visibility = if (draft.isBlank()) View.GONE else View.VISIBLE
        binding.draftBanner.text = draft
        updateBatteryHint()
    }

    private fun showStatus(text: String) {
        binding.status.text = text
    }

    private fun speakUi(text: String) {
        if (GuideService.running) {
            guide?.speakStatus(text)
        } else {
            ensureAnnouncer()
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

    private fun rateFor(checkedId: Int): Float? = when (checkedId) {
        R.id.rateSlow -> GuideSettings.RATE_SLOW
        R.id.rateNormal -> GuideSettings.RATE_NORMAL
        R.id.rateFast -> GuideSettings.RATE_FAST
        else -> null
    }

}
