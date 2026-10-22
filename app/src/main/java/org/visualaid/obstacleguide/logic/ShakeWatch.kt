package org.visualaid.obstacleguide.logic

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A hard shake is two strong opposite jerks close together.
 * Ordinary walking stays under the threshold, so a pocket does not keep firing.
 */
class ShakeWatch(
    private val thresholdMps2: Float = 28f,
    private val releaseMps2: Float = 14f,
    private val windowMs: Long = 700L,
    private val cooldownMs: Long = 3_000L,
) {
    private var lastPeakAt = 0L
    private var lastSign = 0
    private var needRelease = false
    private var cooldownUntil = 0L

    fun onSample(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (timeMs < cooldownUntil) return false
        val magnitude = sqrt(x * x + y * y + z * z)
        if (needRelease) {
            if (magnitude < releaseMps2) needRelease = false
            return false
        }
        if (magnitude < thresholdMps2) return false
        val sign = dominantSign(x, y, z)
        val opposite = lastSign != 0 && sign != lastSign && timeMs - lastPeakAt <= windowMs
        lastPeakAt = timeMs
        lastSign = sign
        needRelease = true
        if (!opposite) return false
        cooldownUntil = timeMs + cooldownMs
        lastSign = 0
        return true
    }

    private fun dominantSign(x: Float, y: Float, z: Float): Int {
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return when {
            ax >= ay && ax >= az -> if (x >= 0f) 1 else -1
            ay >= az -> if (y >= 0f) 1 else -1
            else -> if (z >= 0f) 1 else -1
        }
    }
}
