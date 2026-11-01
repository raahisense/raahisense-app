package org.visualaid.obstacleguide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightTest {
    private val dark = SceneLight(meanLuma = 0.08f, lux = 2f, iso = 1600, exposureTimeNs = 50_000_000L)
    private val bright = SceneLight(meanLuma = 0.62f, lux = 200f, iso = 100, exposureTimeNs = 8_000_000L)
    private val mid = SceneLight(meanLuma = 0.30f, lux = 20f, iso = 200, exposureTimeNs = 10_000_000L)

    @Test
    fun darkForAFewSecondsTurnsTheTorchOnOnce() {
        val controller = LowLightController()
        val first = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 0L)
        assertFalse(first.torchOn)
        assertNull(first.phraseResName)

        val early = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_499L)
        assertFalse(early.torchOn)
        assertNull(early.phraseResName)

        val on = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_500L)
        assertTrue(on.torchOn)
        assertEquals(LowLight.PHRASE_TURNING_ON, on.phraseResName)
        assertTrue(on.lowLight)

        val again = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_800L)
        assertTrue(again.torchOn)
        assertNull(again.phraseResName)
    }

    @Test
    fun oneDarkDipDoesNotTurnTheTorchOn() {
        val controller = LowLightController()
        controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 0L)
        controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_000L)
        controller.step(bright, TorchMode.AUTO, torchAvailable = true, nowMs = 2_100L)
        val restarted = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_200L)
        assertFalse(restarted.torchOn)
        val stillWaiting = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 4_600L)
        assertFalse(stillWaiting.torchOn)
        assertNull(stillWaiting.phraseResName)
    }

    @Test
    fun brightMustHoldBeforeTheTorchTurnsOff() {
        val controller = LowLightController()
        controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 0L)
        assertTrue(controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_500L).torchOn)

        val soon = controller.step(bright, TorchMode.AUTO, torchAvailable = true, nowMs = 3_000L)
        assertTrue(soon.torchOn)
        val notYet = controller.step(bright, TorchMode.AUTO, torchAvailable = true, nowMs = 5_999L)
        assertTrue(notYet.torchOn)
        val off = controller.step(bright, TorchMode.AUTO, torchAvailable = true, nowMs = 6_000L)
        assertFalse(off.torchOn)
        assertNull(off.phraseResName)
    }

    @Test
    fun midBandHoldsThePreviousTorchState() {
        val controller = LowLightController()
        controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 0L)
        controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_500L)
        val heldOn = controller.step(mid, TorchMode.AUTO, torchAvailable = true, nowMs = 8_000L)
        assertTrue(heldOn.torchOn)
        assertNull(heldOn.phraseResName)

        val fresh = LowLightController()
        val heldOff = fresh.step(mid, TorchMode.AUTO, torchAvailable = true, nowMs = 5_000L)
        assertFalse(heldOff.torchOn)
        assertNull(heldOff.phraseResName)
    }

    @Test
    fun cautionWaitsForTheTorchToSettleThenRepeatsAtSixtySeconds() {
        val controller = LowLightController()
        controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 0L)
        val turningOn = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 2_500L)
        assertEquals(LowLight.PHRASE_TURNING_ON, turningOn.phraseResName)

        val settling = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 4_499L)
        assertNull(settling.phraseResName)

        val caution = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 4_500L)
        assertEquals(LowLight.PHRASE_TOO_DARK, caution.phraseResName)
        assertTrue(caution.torchOn)

        val quiet = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 64_499L)
        assertNull(quiet.phraseResName)
        val repeat = controller.step(dark, TorchMode.AUTO, torchAvailable = true, nowMs = 64_500L)
        assertEquals(LowLight.PHRASE_TOO_DARK, repeat.phraseResName)
    }

    @Test
    fun unavailableTorchCautionsWithoutWaitingForALightThatWillNotTurnOn() {
        val controller = LowLightController()
        val first = controller.step(dark, TorchMode.AUTO, torchAvailable = false, nowMs = 0L)
        assertFalse(first.torchOn)
        assertNull(first.phraseResName)

        val caution = controller.step(dark, TorchMode.AUTO, torchAvailable = false, nowMs = 2_500L)
        assertFalse(caution.torchOn)
        assertEquals(LowLight.PHRASE_TOO_DARK, caution.phraseResName)
        assertTrue(caution.lowLight)

        val quiet = controller.step(dark, TorchMode.AUTO, torchAvailable = false, nowMs = 3_000L)
        assertNull(quiet.phraseResName)
    }

    @Test
    fun offNeverEnablesTheTorchButStillCautions() {
        val controller = LowLightController()
        val first = controller.step(dark, TorchMode.OFF, torchAvailable = true, nowMs = 0L)
        assertFalse(first.torchOn)
        assertNull(first.phraseResName)
        val caution = controller.step(dark, TorchMode.OFF, torchAvailable = true, nowMs = 2_500L)
        assertFalse(caution.torchOn)
        assertEquals(LowLight.PHRASE_TOO_DARK, caution.phraseResName)
    }

    @Test
    fun alwaysOnEnablesTheTorchWithoutTheTurningOnSentence() {
        val controller = LowLightController()
        val on = controller.step(dark, TorchMode.ON, torchAvailable = true, nowMs = 0L)
        assertTrue(on.torchOn)
        assertNull(on.phraseResName)
        val still = controller.step(bright, TorchMode.ON, torchAvailable = true, nowMs = 10_000L)
        assertTrue(still.torchOn)
        assertNull(still.phraseResName)
        assertFalse(still.lowLight)
    }

    @Test
    fun lowLightLowersTheScoreCutoffAndKeepsDepthDebounce() {
        assertEquals(0.50f, LowLight.scoreThreshold(lowLight = false))
        assertEquals(0.40f, LowLight.scoreThreshold(lowLight = true))
        assertEquals(3, DepthWarnings.STATIC_DEBOUNCE)
        assertEquals(4, DepthWarnings.APPROACH_FRAMES)
        assertEquals(0.82f, DepthWarnings.APPROACH_SHRINK, 0.0001f)
        assertEquals(3_000L, WarningLogic.THROTTLE_MS)

        val box = DetectionBox("chair", 0.42f, 0f, 0.40f, 0.34f, 0.95f)
        assertNull(WarningLogic.fromBox(box, WarningLogic.SCORE_THRESHOLD))
        val kept = WarningLogic.fromBox(box, LowLight.SCORE_LOW)
        assertEquals("chair", kept?.label)
        assertFalse(DepthWarnings.explains(box, HorizontalPosition.LEFT, WarningLogic.SCORE_THRESHOLD))
        assertTrue(DepthWarnings.explains(box, HorizontalPosition.LEFT, LowLight.SCORE_LOW))
    }

    @Test
    fun exposureAndLuxCanMarkADimFrameDark() {
        val dim = SceneLight(meanLuma = 0.22f, lux = null, iso = 100, exposureTimeNs = 10_000_000L)
        assertFalse(LowLight.isDark(dim))
        assertTrue(LowLight.isDark(dim.copy(iso = 800)))
        assertTrue(LowLight.isDark(dim.copy(iso = 100, exposureTimeNs = 40_000_000L)))
        assertTrue(LowLight.isDark(SceneLight(meanLuma = 0.40f, lux = 3f)))
        assertFalse(LowLight.isBright(SceneLight(meanLuma = 0.40f, lux = 3f)))
        assertTrue(LowLight.isBright(SceneLight(meanLuma = 0.70f, lux = 80f)))
    }
}
