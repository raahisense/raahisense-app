package org.visualaid.obstacleguide.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ShakeWatchTest {

    @Test
    fun walkingLikeMotion_doesNotFire() {
        val watch = ShakeWatch()
        var fired = false
        for (step in 0 until 40) {
            val t = step * 50L
            val wobble = 3.0 * sin(step / 3.0)
            fired = fired || watch.onSample(t, wobble.toFloat(), 0.4f, (9.8 + wobble).toFloat())
        }
        assertFalse(fired)
    }

    @Test
    fun twoOppositeJerks_fireOnce() {
        val watch = ShakeWatch()
        assertFalse(watch.onSample(0L, 32f, 0f, 0f))
        assertFalse(watch.onSample(80L, 0f, 0f, 0f))
        assertTrue(watch.onSample(180L, -32f, 0f, 0f))
    }

    @Test
    fun oneJerk_doesNotFire() {
        val watch = ShakeWatch()
        assertFalse(watch.onSample(0L, 32f, 0f, 0f))
        assertFalse(watch.onSample(100L, 0f, 0f, 0f))
        assertFalse(watch.onSample(400L, 2f, 0f, 9f))
    }

    @Test
    fun cooldown_blocksTheNextShake() {
        val watch = ShakeWatch(cooldownMs = 3_000L)
        assertFalse(watch.onSample(0L, 32f, 0f, 0f))
        assertFalse(watch.onSample(50L, 0f, 0f, 0f))
        assertTrue(watch.onSample(120L, -32f, 0f, 0f))
        assertFalse(watch.onSample(400L, 0f, 0f, 0f))
        assertFalse(watch.onSample(500L, 32f, 0f, 0f))
        assertFalse(watch.onSample(560L, 0f, 0f, 0f))
        assertFalse(watch.onSample(640L, -32f, 0f, 0f))
    }
}
