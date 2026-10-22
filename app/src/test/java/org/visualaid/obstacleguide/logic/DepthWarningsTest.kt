package org.visualaid.obstacleguide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Depth warnings are decided from raw depth, where a larger value is farther.
 * Phrases stay in the locale files. These tests only check the decision.
 */
class DepthWarningsTest {

    private val saved = DepthCalibration(openRaw = 3f, wallRaw = 1f)

    @Test
    fun unknownObjectOnTheLeftAfterThreeCloseFrames() {
        val tracker = DepthTracker()
        val closeOnLeft = scene { x -> if (x <= 6) 1f else 3f }
        assertTrue(tracker.update(closeOnLeft, emptyList(), saved).isEmpty())
        assertTrue(tracker.update(closeOnLeft, emptyList(), saved).isEmpty())
        val warnings = tracker.update(closeOnLeft, emptyList(), saved)
        assertEquals(1, warnings.size)
        val warning = warnings.single()
        assertEquals(DepthWarnings.LABEL_UNKNOWN, warning.label)
        assertEquals(HorizontalPosition.LEFT, warning.position)
        assertEquals(Closeness.VERY_CLOSE, warning.closeness)
        assertFalse(warning.urgent)
        assertEquals("depth_unknown_very_close_left", DepthWarnings.phraseName(warning))
        assertFalse(warning.label in PhraseIds.objects)
    }

    @Test
    fun unknownObjectAheadUsesDirectionAndDistance() {
        val tracker = DepthTracker()
        val closeAhead = scene { x -> if (x in 7..12) 1f else 3f }
        repeat(2) { tracker.update(closeAhead, emptyList(), saved) }
        val warning = tracker.update(closeAhead, emptyList(), saved).single()
        assertEquals(DepthWarnings.LABEL_UNKNOWN, warning.label)
        assertEquals(HorizontalPosition.AHEAD, warning.position)
        assertEquals(Closeness.VERY_CLOSE, warning.closeness)
        assertTrue(warning.urgent)
        assertEquals("depth_unknown_very_close_ahead", DepthWarnings.phraseName(warning))
    }

    @Test
    fun nearUnknownIsNotUrgent() {
        val tracker = DepthTracker()
        val nearLeft = scene { x -> if (x <= 6) 1.8f else 3f }
        repeat(2) { tracker.update(nearLeft, emptyList(), saved) }
        val warning = tracker.update(nearLeft, emptyList(), saved).single()
        assertEquals(Closeness.NEAR, warning.closeness)
        assertEquals(HorizontalPosition.LEFT, warning.position)
        assertFalse(warning.urgent)
        assertEquals("depth_unknown_near_left", DepthWarnings.phraseName(warning))
    }

    @Test
    fun detectorBoxSuppressesTheUnknownObject() {
        val tracker = DepthTracker()
        val closeOnLeft = scene { x -> if (x <= 6) 1f else 3f }
        val chair = DetectionBox("chair", 0.9f, 0f, 0.40f, 0.34f, 0.95f)
        repeat(3) {
            assertTrue(tracker.update(closeOnLeft, listOf(chair), saved).isEmpty())
        }
    }

    @Test
    fun lowScoreBoxDoesNotExplainTheRegion() {
        val tracker = DepthTracker()
        val closeOnLeft = scene { x -> if (x <= 6) 1f else 3f }
        val faint = DetectionBox("chair", 0.2f, 0f, 0.40f, 0.34f, 0.95f)
        repeat(2) { tracker.update(closeOnLeft, listOf(faint), saved) }
        val warning = tracker.update(closeOnLeft, listOf(faint), saved).single()
        assertEquals(DepthWarnings.LABEL_UNKNOWN, warning.label)
    }

    @Test
    fun wallOnlyWhenCloseFlatAndWide() {
        val tracker = DepthTracker()
        val wall = scene { 1f }
        assertTrue(tracker.update(wall, emptyList(), saved).isEmpty())
        assertTrue(tracker.update(wall, emptyList(), saved).isEmpty())
        val warning = tracker.update(wall, emptyList(), saved).single()
        assertEquals(DepthWarnings.LABEL_WALL, warning.label)
        assertEquals(HorizontalPosition.AHEAD, warning.position)
        assertEquals(Closeness.VERY_CLOSE, warning.closeness)
        assertTrue(warning.urgent)
        assertEquals("depth_wall_very_close", DepthWarnings.phraseName(warning))
        assertTrue(warning.priority > DepthWarnings.PRIORITY_UNKNOWN_VERY_CLOSE_AHEAD)
    }

    @Test
    fun nearFlatWallUsesTheNearPhrase() {
        val tracker = DepthTracker()
        val wall = scene { 1.8f }
        repeat(2) { tracker.update(wall, emptyList(), saved) }
        val warning = tracker.update(wall, emptyList(), saved).single()
        assertEquals(DepthWarnings.LABEL_WALL, warning.label)
        assertEquals(Closeness.NEAR, warning.closeness)
        assertFalse(warning.urgent)
        assertEquals("depth_wall_near", DepthWarnings.phraseName(warning))
    }

    @Test
    fun wideButUnevenCloseRegionStaysUnknown() {
        val tracker = DepthTracker()
        val bumpy = scene { x -> if (x % 2 == 0) 0.6f else 2f }
        repeat(2) { tracker.update(bumpy, emptyList(), saved) }
        val warnings = tracker.update(bumpy, emptyList(), saved)
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.none { it.label == DepthWarnings.LABEL_WALL })
        assertTrue(warnings.any { it.label == DepthWarnings.LABEL_UNKNOWN && it.position == HorizontalPosition.AHEAD })
    }

    @Test
    fun closeCenterThatDoesNotSpanTheFrameIsNotAWall() {
        val tracker = DepthTracker()
        val center = scene { x -> if (x in 7..12) 1f else 3f }
        repeat(2) { tracker.update(center, emptyList(), saved) }
        val warning = tracker.update(center, emptyList(), saved).single()
        assertEquals(DepthWarnings.LABEL_UNKNOWN, warning.label)
    }

    @Test
    fun carFillingTheFrameSuppressesWallAndUnknown() {
        val tracker = DepthTracker()
        val wall = scene { 1f }
        val car = DetectionBox("car", 0.95f, 0f, 0.2f, 1f, 1f)
        repeat(3) {
            assertTrue(tracker.update(wall, listOf(car), saved).isEmpty())
        }
    }

    @Test
    fun oneFrameSpikeAndAShakeDoNotSpeak() {
        val tracker = DepthTracker()
        val close = scene { x -> if (x <= 6) 1f else 3f }
        val far = scene { 3f }
        assertTrue(tracker.update(close, emptyList(), saved).isEmpty())
        assertTrue(tracker.update(far, emptyList(), saved).isEmpty())
        assertTrue(tracker.update(close, emptyList(), saved).isEmpty())
        assertTrue(tracker.update(far, emptyList(), saved).isEmpty())
        assertTrue(tracker.update(close, emptyList(), saved).isEmpty())
    }

    @Test
    fun approachingOnTheLeftOutranksTheStaticWarning() {
        val tracker = DepthTracker()
        val series = floatArrayOf(2.0f, 1.7f, 1.45f, 1.2f)
        val early = mutableListOf<List<ObstacleWarning>>()
        for (index in 0..2) {
            early += tracker.update(scene { x -> if (x <= 6) series[index] else 3f }, emptyList(), saved)
        }
        assertTrue(early.flatten().none { it.label == DepthWarnings.LABEL_APPROACHING })
        val warnings = tracker.update(scene { x -> if (x <= 6) series[3] else 3f }, emptyList(), saved)
        val approaching = warnings.first()
        assertEquals(DepthWarnings.LABEL_APPROACHING, approaching.label)
        assertEquals(HorizontalPosition.LEFT, approaching.position)
        assertTrue(approaching.urgent)
        assertEquals(DepthWarnings.APPROACH_PRIORITY, approaching.priority, 0.001f)
        assertEquals("depth_approaching_left", DepthWarnings.phraseName(approaching))
        val staticWarning = warnings.first { it.label == DepthWarnings.LABEL_UNKNOWN }
        assertTrue(approaching.priority > staticWarning.priority)
        val loudestObject = WarningLogic.priorityOf(1f, 0.5f, Closeness.VERY_CLOSE, 1f)
        assertTrue(approaching.priority > loudestObject)
    }

    @Test
    fun approachingAheadAndRightUseTheirOwnSides() {
        val ahead = approach(column = { x -> x in 7..12 })
        assertEquals(HorizontalPosition.AHEAD, ahead.position)
        assertEquals("depth_approaching_ahead", DepthWarnings.phraseName(ahead))

        val right = approach(column = { x -> x >= 13 })
        assertEquals(HorizontalPosition.RIGHT, right.position)
        assertEquals("depth_approaching_right", DepthWarnings.phraseName(right))
    }

    @Test
    fun bothSidesApproachingSpeaksAhead() {
        val warning = approach(column = { x -> x <= 6 || x >= 13 })
        assertEquals(HorizontalPosition.AHEAD, warning.position)
        assertEquals(DepthWarnings.LABEL_APPROACHING, warning.label)
    }

    @Test
    fun cameraShakeDoesNotCountAsApproaching() {
        val tracker = DepthTracker()
        val wobble = floatArrayOf(1.5f, 1.2f, 1.6f, 1.1f)
        val warnings = wobble.flatMap { value ->
            tracker.update(scene { x -> if (x <= 6) value else 3f }, emptyList(), saved)
        }
        assertTrue(warnings.none { it.label == DepthWarnings.LABEL_APPROACHING })
    }

    @Test
    fun walkingAnOpenPathDoesNotWarn() {
        val tracker = DepthTracker()
        val walking = floatArrayOf(2.9f, 2.6f, 2.3f, 2.15f)
        for (value in walking) {
            assertTrue(tracker.update(scene { value }, emptyList(), saved).isEmpty())
        }
    }

    @Test
    fun uncalibratedFlatFrameDoesNotWarn() {
        val tracker = DepthTracker()
        val flat = scene { 1.5f }
        repeat(5) {
            assertTrue(tracker.update(flat, emptyList(), null).isEmpty())
        }
    }

    @Test
    fun uncalibratedCloseObjectStillSpeaksAfterDebounce() {
        val tracker = DepthTracker()
        val closeOnLeft = scene { x -> if (x <= 6) 0.4f else 1.5f }
        repeat(2) { assertTrue(tracker.update(closeOnLeft, emptyList(), null).isEmpty()) }
        val warning = tracker.update(closeOnLeft, emptyList(), null).single()
        assertEquals(DepthWarnings.LABEL_UNKNOWN, warning.label)
        assertEquals(HorizontalPosition.LEFT, warning.position)
        assertEquals(Closeness.VERY_CLOSE, warning.closeness)
    }

    @Test
    fun rotateClockwiseTurnsTheLeftEdgeDown() {
        val field = DepthField(2, 2, floatArrayOf(1f, 2f, 3f, 4f))
        val turned = DepthFields.rotate(field, 90)
        assertEquals(2, turned.width)
        assertEquals(2, turned.height)
        assertEquals(3f, turned.raw[0], 0.001f)
        assertEquals(1f, turned.raw[1], 0.001f)
        assertEquals(4f, turned.raw[2], 0.001f)
        assertEquals(2f, turned.raw[3], 0.001f)
        val full = DepthFields.rotate(field, 360)
        assertEquals(1f, full.raw[0], 0.001f)
        assertEquals(4f, full.raw[3], 0.001f)
    }

    @Test
    fun aheadMedianReadsTheWalkingCorridor() {
        val field = scene { 1.25f }
        assertEquals(1.25f, DepthWarnings.aheadMedian(field), 0.001f)
        assertFalse(DepthCalibration(1f, 1f).usable())
        assertFalse(DepthCalibration(Float.NaN, 0.4f).usable())
        assertTrue(saved.usable())
    }

    private fun approach(column: (Int) -> Boolean): ObstacleWarning {
        val tracker = DepthTracker()
        val series = floatArrayOf(2.0f, 1.7f, 1.45f, 1.2f)
        var last = emptyList<ObstacleWarning>()
        for (value in series) {
            last = tracker.update(scene { x -> if (column(x)) value else 3f }, emptyList(), saved)
        }
        return last.first { it.label == DepthWarnings.LABEL_APPROACHING }
    }

    /** 20 by 10 frame. Rows inside the corridor share one depth per column. */
    private fun scene(columnDepth: (Int) -> Float): DepthField {
        val width = 20
        val height = 10
        val raw = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                raw[y * width + x] = columnDepth(x)
            }
        }
        return DepthField(width, height, raw)
    }
}
