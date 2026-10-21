package org.visualaid.obstacleguide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.visualaid.obstacleguide.GuideSettings

class WarningLogicTest {

    @Test
    fun position_usesLeftAheadRightBands() {
        assertEquals(HorizontalPosition.LEFT, WarningLogic.positionOf(0f))
        assertEquals(HorizontalPosition.LEFT, WarningLogic.positionOf(0.349f))
        assertEquals(HorizontalPosition.AHEAD, WarningLogic.positionOf(0.35f))
        assertEquals(HorizontalPosition.AHEAD, WarningLogic.positionOf(0.50f))
        assertEquals(HorizontalPosition.AHEAD, WarningLogic.positionOf(0.65f))
        assertEquals(HorizontalPosition.RIGHT, WarningLogic.positionOf(0.651f))
        assertEquals(HorizontalPosition.RIGHT, WarningLogic.positionOf(1f))
    }

    @Test
    fun position_comesFromBoxCenter() {
        val left = warning(left = 0f, top = 0.2f, right = 0.30f, bottom = 0.50f)
        val ahead = warning(left = 0.20f, top = 0.2f, right = 0.80f, bottom = 0.50f)
        val right = warning(left = 0.70f, top = 0.2f, right = 1f, bottom = 0.50f)
        assertEquals(HorizontalPosition.LEFT, left.position)
        assertEquals(HorizontalPosition.AHEAD, ahead.position)
        assertEquals(HorizontalPosition.RIGHT, right.position)
    }

    @Test
    fun closeness_largeOrTallBoxIsVeryClose() {
        assertEquals(
            Closeness.VERY_CLOSE,
            WarningLogic.closenessOf(area = 0.22f, height = 0.10f, bottom = 0.50f),
        )
        assertEquals(
            Closeness.VERY_CLOSE,
            WarningLogic.closenessOf(area = 0.01f, height = 0.55f, bottom = 0.60f),
        )
    }

    @Test
    fun closeness_bottomEdgeCountsAsVeryCloseWhenTheBoxIsTallEnough() {
        assertEquals(
            Closeness.VERY_CLOSE,
            WarningLogic.closenessOf(area = 0.02f, height = 0.22f, bottom = 0.92f),
        )
        assertEquals(
            Closeness.NEAR,
            WarningLogic.closenessOf(area = 0.02f, height = 0.219f, bottom = 0.92f),
        )
    }

    @Test
    fun closeness_mediumOrLowBoxIsNear_andSmallHighBoxIsFar() {
        assertEquals(
            Closeness.NEAR,
            WarningLogic.closenessOf(area = 0.07f, height = 0.10f, bottom = 0.40f),
        )
        assertEquals(
            Closeness.NEAR,
            WarningLogic.closenessOf(area = 0.01f, height = 0.28f, bottom = 0.40f),
        )
        assertEquals(
            Closeness.NEAR,
            WarningLogic.closenessOf(area = 0.02f, height = 0.12f, bottom = 0.75f),
        )
        assertEquals(
            Closeness.FAR,
            WarningLogic.closenessOf(area = 0.06f, height = 0.27f, bottom = 0.74f),
        )
        assertEquals(
            Closeness.NEAR,
            WarningLogic.closenessOf(area = 0.219f, height = 0.10f, bottom = 0.50f),
        )
    }

    @Test
    fun closeness_fromABoxThatReachesTheFeet() {
        val atFeet = warning(left = 0.40f, top = 0.68f, right = 0.50f, bottom = 0.93f)
        assertEquals(Closeness.VERY_CLOSE, atFeet.closeness)
        assertEquals(HorizontalPosition.AHEAD, atFeet.position)
        assertTrue(atFeet.urgent)
    }

    @Test
    fun priority_closerBeatsMoreCentral() {
        val veryCloseEdge = WarningLogic.priorityOf(
            area = 0.30f,
            centerX = 0f,
            closeness = Closeness.VERY_CLOSE,
            bottom = 0.90f,
        )
        val nearCenter = WarningLogic.priorityOf(
            area = 0.10f,
            centerX = 0.50f,
            closeness = Closeness.NEAR,
            bottom = 0.80f,
        )
        val farCenter = WarningLogic.priorityOf(
            area = 0.02f,
            centerX = 0.50f,
            closeness = Closeness.FAR,
            bottom = 0.30f,
        )
        assertEquals(6.309f, veryCloseEdge, 0.0001f)
        assertEquals(5.108f, nearCenter, 0.0001f)
        assertEquals(3.023f, farCenter, 0.0001f)
        assertTrue(veryCloseEdge > nearCenter)
        assertTrue(nearCenter > farCenter)
    }

    @Test
    fun priority_moreCentralWinsInsideTheSameDistance() {
        val veryCloseCenter = WarningLogic.priorityOf(0.30f, 0.50f, Closeness.VERY_CLOSE, 0.90f)
        val veryCloseEdge = WarningLogic.priorityOf(0.30f, 0f, Closeness.VERY_CLOSE, 0.90f)
        assertEquals(7.309f, veryCloseCenter, 0.0001f)
        assertTrue(veryCloseCenter > veryCloseEdge)
    }

    @Test
    fun priority_largerAreaThenLowerBoxBreakTies() {
        val bigger = WarningLogic.priorityOf(0.20f, 0.50f, Closeness.NEAR, 0.50f)
        val smaller = WarningLogic.priorityOf(0.10f, 0.50f, Closeness.NEAR, 0.50f)
        val lowerInFrame = WarningLogic.priorityOf(0.10f, 0.50f, Closeness.NEAR, 0.90f)
        val higherInFrame = WarningLogic.priorityOf(0.10f, 0.50f, Closeness.NEAR, 0.40f)
        assertTrue(bigger > smaller)
        assertTrue(lowerInFrame > higherInFrame)
    }

    @Test
    fun rank_putsClosestFirst_andDropsWeakOrIrrelevantDetections() {
        val farAhead = box("person", left = 0.45f, top = 0.05f, right = 0.55f, bottom = 0.15f)
        val veryCloseLeft = box("chair", left = 0.00f, top = 0.20f, right = 0.20f, bottom = 0.90f)
        val lowScore = box("car", score = 0.49f, left = 0.40f, top = 0.20f, right = 0.80f, bottom = 0.90f)
        val exactScore = box("dog", score = 0.50f, left = 0.70f, top = 0.20f, right = 1f, bottom = 0.55f)
        val airplane = box("airplane", left = 0.40f, top = 0.20f, right = 0.70f, bottom = 0.90f)
        val unknown = box("???", left = 0.40f, top = 0.20f, right = 0.70f, bottom = 0.90f)
        val empty = box("  ", left = 0.40f, top = 0.20f, right = 0.70f, bottom = 0.90f)
        val flat = box("bus", left = 0.20f, top = 0.40f, right = 0.20f, bottom = 0.80f)
        val ranked = WarningLogic.rank(
            listOf(farAhead, veryCloseLeft, lowScore, exactScore, airplane, unknown, empty, flat),
        )
        assertEquals(listOf("chair", "dog", "person"), ranked.map { it.label })
        assertTrue(ranked[0].priority > ranked[1].priority)
        assertFalse(ranked[2].urgent)
        assertFalse(ranked[0].urgent)
    }

    @Test
    fun fromBox_normalizesLabelCase_andRejectsInvalidNumbers() {
        val warning = WarningLogic.fromBox(box("Person", left = 0.40f, top = 0.30f, right = 0.60f, bottom = 0.70f))
        assertEquals("person", warning!!.label)
        assertNull(WarningLogic.fromBox(box("person", score = Float.NaN, left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)))
        assertNull(WarningLogic.fromBox(box("person", left = -1f, top = 0.2f, right = -0.5f, bottom = 0.4f)))
    }

    @Test
    fun urgent_isOnlyVeryCloseAndAhead() {
        val ahead = warning(left = 0.30f, top = 0.10f, right = 0.70f, bottom = 0.95f)
        val left = warning(left = 0.00f, top = 0.10f, right = 0.20f, bottom = 0.95f)
        val farAhead = warning(left = 0.40f, top = 0.00f, right = 0.60f, bottom = 0.16f)
        assertTrue(ahead.urgent)
        assertFalse(left.urgent)
        assertEquals(Closeness.VERY_CLOSE, left.closeness)
        assertFalse(farAhead.urgent)
    }

    @Test
    fun throttle_blocksTheSameWarningForFourSeconds() {
        val throttle = WarningThrottle(windowMs = 4_000L)
        val warning = sampleWarning()
        assertFalse(throttle.isThrottled(warning, 10_000L))
        throttle.mark(warning, 10_000L)
        assertTrue(throttle.isThrottled(warning, 10_000L))
        assertTrue(throttle.isThrottled(warning, 13_999L))
        assertFalse(throttle.isThrottled(warning, 14_000L))
    }

    @Test
    fun throttle_allowsANewSideDistanceOrObjectImmediately() {
        val throttle = WarningThrottle()
        val ahead = sampleWarning()
        throttle.mark(ahead, 1_000L)
        val left = ahead.copy(position = HorizontalPosition.LEFT)
        val nearer = ahead.copy(closeness = Closeness.VERY_CLOSE, urgent = true)
        val other = ahead.copy(label = "car")
        assertFalse(throttle.isThrottled(left, 1_100L))
        assertFalse(throttle.isThrottled(nearer, 1_100L))
        assertFalse(throttle.isThrottled(other, 1_100L))
        assertEquals("person|AHEAD|NEAR", throttle.key(ahead))
        assertEquals("person|LEFT|NEAR", throttle.key(left))
    }

    @Test
    fun select_speaksTheTopWarning_thenSuppressesIt() {
        val throttle = WarningThrottle()
        val first = sampleWarning(label = "person", priority = 5f)
        val ranked = listOf(first)
        val spoken = WarningLogic.selectAnnouncement(ranked, throttle, speechBusy = false, nowMs = 5_000L)
        assertEquals(first, spoken)
        assertNull(WarningLogic.selectAnnouncement(ranked, throttle, speechBusy = false, nowMs = 6_000L))
        val afterWindow = WarningLogic.selectAnnouncement(ranked, throttle, speechBusy = false, nowMs = 9_000L)
        assertEquals(first, afterWindow)
    }

    @Test
    fun select_skipsAThrottledLeader_andSpeaksTheNextOfTheTopTwo() {
        val throttle = WarningThrottle()
        val first = sampleWarning(label = "person", priority = 5f)
        val second = sampleWarning(label = "chair", priority = 4f)
        val third = sampleWarning(label = "dog", priority = 3f)
        throttle.mark(first, 2_000L)
        val spoken = WarningLogic.selectAnnouncement(
            listOf(first, second, third),
            throttle,
            speechBusy = false,
            nowMs = 3_000L,
        )
        assertEquals("chair", spoken!!.label)
    }

    @Test
    fun select_doesNotReachPastTheTopTwo() {
        val throttle = WarningThrottle()
        val first = sampleWarning(label = "person", priority = 5f)
        val second = sampleWarning(label = "chair", priority = 4f)
        val third = sampleWarning(label = "dog", priority = 3f)
        throttle.mark(first, 2_000L)
        throttle.mark(second, 2_000L)
        val spoken = WarningLogic.selectAnnouncement(
            listOf(first, second, third),
            throttle,
            speechBusy = false,
            nowMs = 3_000L,
        )
        assertNull(spoken)
        assertFalse(throttle.isThrottled(third, 3_000L))
    }

    @Test
    fun select_holdsNonUrgentSpeech_butLetsVeryCloseAheadInterrupt() {
        val waiting = sampleWarning(label = "person", priority = 5f, urgent = false)
        val urgent = sampleWarning(
            label = "car",
            priority = 4f,
            urgent = true,
            closeness = Closeness.VERY_CLOSE,
            position = HorizontalPosition.AHEAD,
        )
        val throttle = WarningThrottle()
        assertNull(
            WarningLogic.selectAnnouncement(listOf(waiting), throttle, speechBusy = true, nowMs = 1_000L),
        )
        assertFalse(throttle.isThrottled(waiting, 1_000L))
        val spoken = WarningLogic.selectAnnouncement(
            listOf(waiting, urgent),
            throttle,
            speechBusy = true,
            nowMs = 1_000L,
        )
        assertEquals("car", spoken!!.label)
        assertTrue(spoken.urgent)
    }

    @Test
    fun select_doesNotRepeatAnUrgentWarningInsideTheWindow() {
        val throttle = WarningThrottle()
        val urgent = sampleWarning(label = "person", priority = 9f, urgent = true, closeness = Closeness.VERY_CLOSE)
        assertEquals(
            urgent,
            WarningLogic.selectAnnouncement(listOf(urgent), throttle, speechBusy = false, nowMs = 100L),
        )
        assertNull(
            WarningLogic.selectAnnouncement(listOf(urgent), throttle, speechBusy = true, nowMs = 200L),
        )
    }

    @Test
    fun batterySaver_usesTheSlowerFrameInterval() {
        assertEquals(300L, GuideSettings.frameIntervalMs(false))
        assertEquals(1_500L, GuideSettings.frameIntervalMs(true))
        assertEquals(4_000L, WarningLogic.THROTTLE_MS)
    }

    private fun box(
        label: String,
        score: Float = 0.9f,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = DetectionBox(label, score, left, top, right, bottom)

    private fun warning(
        label: String = "person",
        score: Float = 0.9f,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): ObstacleWarning {
        return WarningLogic.fromBox(box(label, score, left, top, right, bottom))!!
    }

    private fun sampleWarning(
        label: String = "person",
        priority: Float = 1f,
        urgent: Boolean = false,
        closeness: Closeness = Closeness.NEAR,
        position: HorizontalPosition = HorizontalPosition.AHEAD,
    ) = ObstacleWarning(label, position, closeness, priority, urgent)
}
