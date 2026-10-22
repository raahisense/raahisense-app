package org.visualaid.obstacleguide.logic

/**
 * Decides when the flashlight should be on, when to say it is dark, and when
 * the detector should accept a slightly lower score.
 *
 * Brightness comes from the frame (mean luma), the ambient light sensor when
 * the phone has one, and exposure time or ISO when the camera provides them.
 * A short dip does not flip the light. Turning the light off needs a brighter
 * reading than turning it on, so the light does not flicker at the boundary.
 *
 * This class does not build sentences. [phraseResName] is a string resource name.
 */
enum class TorchMode {
    AUTO,
    ON,
    OFF,
    ;

    fun persisted(): String = name.lowercase()

    companion object {
        fun fromPersisted(raw: String?): TorchMode = when (raw?.trim()?.lowercase()) {
            "on", "always" -> ON
            "off" -> OFF
            else -> AUTO
        }
    }
}

data class SceneLight(
    /** Mean luma of the frame, 0 to 1. NaN when this frame was not measured. */
    val meanLuma: Float,
    /** Ambient light sensor, lux. Null when the phone has no sensor or no reading yet. */
    val lux: Float? = null,
    val iso: Int? = null,
    /** Sensor exposure time in nanoseconds, when the camera metadata includes it. */
    val exposureTimeNs: Long? = null,
) {
    val measured: Boolean get() = meanLuma.isFinite()
}

data class LightDecision(
    val torchOn: Boolean,
    /** "light_turning_on", "light_too_dark", or null when nothing new should be said. */
    val phraseResName: String?,
    /** True when detection should use the lower score cutoff and exposure should rise. */
    val lowLight: Boolean,
)

object LowLight {
    const val PHRASE_TURNING_ON = "light_turning_on"
    const val PHRASE_TOO_DARK = "light_too_dark"

    const val SCORE_NORMAL = WarningLogic.SCORE_THRESHOLD
    const val SCORE_LOW = 0.40f

    /** Darkness must last this long before Auto turns the flashlight on. */
    const val DARK_HOLD_MS = 2_500L

    /** Brightness must last this long before Auto turns the flashlight off. */
    const val BRIGHT_HOLD_MS = 3_000L

    /** After the flashlight is on, wait this long before saying the scene is still too dark. */
    const val TORCH_SETTLE_MS = 2_000L

    const val CAUTION_INTERVAL_MS = 60_000L

    const val DARK_LUMA = 0.16f
    const val AMBIGUOUS_LUMA = 0.28f
    const val BRIGHT_LUMA = 0.38f
    const val CLEAR_BRIGHT_LUMA = 0.55f

    const val DARK_LUX = 8f
    const val BRIGHT_LUX = 40f
    const val HIGH_ISO = 800
    const val LONG_EXPOSURE_NS = 40_000_000L

    fun scoreThreshold(lowLight: Boolean): Float = if (lowLight) SCORE_LOW else SCORE_NORMAL

    fun isDark(scene: SceneLight): Boolean {
        if (!scene.measured) return false
        if (scene.meanLuma >= CLEAR_BRIGHT_LUMA) return false
        if (scene.meanLuma < DARK_LUMA) return true
        val lux = scene.lux
        if (lux != null && lux.isFinite() && lux < DARK_LUX) return true
        if (scene.meanLuma < AMBIGUOUS_LUMA && exposureLooksDark(scene)) return true
        return false
    }

    /**
     * Brighter than [isDark], and the sensor must agree when a lux reading exists.
     * High ISO or a long exposure keeps a mid reading from counting as bright,
     * unless the frame itself is clearly bright.
     */
    fun isBright(scene: SceneLight): Boolean {
        if (!scene.measured) return false
        val lux = scene.lux
        if (lux != null && lux.isFinite() && lux < BRIGHT_LUX) return false
        if (scene.meanLuma >= CLEAR_BRIGHT_LUMA) return true
        if (scene.meanLuma < BRIGHT_LUMA) return false
        return !exposureLooksDark(scene)
    }

    private fun exposureLooksDark(scene: SceneLight): Boolean {
        val iso = scene.iso
        if (iso != null && iso >= HIGH_ISO) return true
        val exposure = scene.exposureTimeNs
        return exposure != null && exposure >= LONG_EXPOSURE_NS
    }
}

class LowLightController {
    private var darkSinceMs: Long? = null
    private var brightSinceMs: Long? = null
    private var torchOn = false
    private var torchOnSinceMs: Long? = null
    private var announcedTurningOn = false
    private var lastCautionAtMs: Long? = null

    fun reset() {
        darkSinceMs = null
        brightSinceMs = null
        torchOn = false
        torchOnSinceMs = null
        announcedTurningOn = false
        lastCautionAtMs = null
    }

    fun step(
        scene: SceneLight,
        mode: TorchMode,
        torchAvailable: Boolean,
        nowMs: Long,
    ): LightDecision {
        val dark = LowLight.isDark(scene)
        val bright = LowLight.isBright(scene)
        if (dark) {
            if (darkSinceMs == null) darkSinceMs = nowMs
            brightSinceMs = null
        } else if (bright) {
            if (brightSinceMs == null) brightSinceMs = nowMs
            darkSinceMs = null
        } else {
            darkSinceMs = null
            brightSinceMs = null
        }

        var phrase: String? = null
        when (mode) {
            TorchMode.ON -> {
                if (torchAvailable) {
                    if (!torchOn) torchOnSinceMs = nowMs
                    torchOn = true
                } else {
                    torchOn = false
                    torchOnSinceMs = null
                }
                announcedTurningOn = false
            }
            TorchMode.OFF -> {
                torchOn = false
                torchOnSinceMs = null
                announcedTurningOn = false
            }
            TorchMode.AUTO -> {
                if (!torchAvailable) {
                    torchOn = false
                    torchOnSinceMs = null
                    announcedTurningOn = false
                } else {
                    val darkFor = darkSinceMs?.let { nowMs - it } ?: 0L
                    val brightFor = brightSinceMs?.let { nowMs - it } ?: 0L
                    if (!torchOn && darkFor >= LowLight.DARK_HOLD_MS) {
                        torchOn = true
                        torchOnSinceMs = nowMs
                        if (!announcedTurningOn) {
                            phrase = LowLight.PHRASE_TURNING_ON
                            announcedTurningOn = true
                        }
                    } else if (torchOn && brightFor >= LowLight.BRIGHT_HOLD_MS) {
                        torchOn = false
                        torchOnSinceMs = null
                        announcedTurningOn = false
                    }
                }
            }
        }

        val lowLight = dark || (scene.measured && torchOn && !bright)
        if (phrase == null && shouldCaution(mode, torchAvailable, dark, nowMs)) {
            phrase = LowLight.PHRASE_TOO_DARK
            lastCautionAtMs = nowMs
        }
        return LightDecision(
            torchOn = torchOn && torchAvailable,
            phraseResName = phrase,
            lowLight = lowLight,
        )
    }

    /**
     * Caution waits until darkness has held, so one dark frame does not speak.
     * With a flashlight, it also waits for the torch to settle. Without a torch,
     * or when the setting is off, it does not wait for that extra settle.
     * It then stays quiet for [LowLight.CAUTION_INTERVAL_MS].
     */
    private fun shouldCaution(
        mode: TorchMode,
        torchAvailable: Boolean,
        dark: Boolean,
        nowMs: Long,
    ): Boolean {
        if (!dark) return false
        val previous = lastCautionAtMs
        if (previous != null && nowMs - previous < LowLight.CAUTION_INTERVAL_MS) return false
        val darkFor = darkSinceMs?.let { nowMs - it } ?: 0L
        if (darkFor < LowLight.DARK_HOLD_MS) return false
        if (mode == TorchMode.OFF || !torchAvailable) return true
        if (!torchOn) return false
        val onFor = torchOnSinceMs?.let { nowMs - it } ?: 0L
        return onFor >= LowLight.TORCH_SETTLE_MS
    }
}
