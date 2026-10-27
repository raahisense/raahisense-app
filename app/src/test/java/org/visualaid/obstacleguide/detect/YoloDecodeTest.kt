package org.visualaid.obstacleguide.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloDecodeTest {
    @Test
    fun classList_dropsEmptyClassesAndKeepsOrder() {
        assertEquals(36, YoloDecode.CLASS_NAMES.size)
        assertEquals("person", YoloDecode.CLASS_NAMES[0])
        assertEquals("pothole", YoloDecode.CLASS_NAMES[11])
        assertEquals("pole_bollard", YoloDecode.CLASS_NAMES[15])
        assertEquals("barrier", YoloDecode.CLASS_NAMES[16])
        assertEquals("tree", YoloDecode.CLASS_NAMES[18])
        assertEquals("cat", YoloDecode.CLASS_NAMES[19])
        assertEquals("chair", YoloDecode.CLASS_NAMES[20])
        assertEquals("door", YoloDecode.CLASS_NAMES[28])
        assertEquals("wall", YoloDecode.CLASS_NAMES[29])
        assertEquals("shoe", YoloDecode.CLASS_NAMES[30])
        assertEquals("bag", YoloDecode.CLASS_NAMES[31])
        assertEquals("toy", YoloDecode.CLASS_NAMES[32])
        assertEquals("bin", YoloDecode.CLASS_NAMES[33])
        assertEquals("appliance", YoloDecode.CLASS_NAMES[34])
        assertEquals("cabinet", YoloDecode.CLASS_NAMES[35])
        assertTrue("speed_bump" !in YoloDecode.CLASS_NAMES)
        assertTrue("low_branch" !in YoloDecode.CLASS_NAMES)
        assertTrue("potted plant" in YoloDecode.CLASS_NAMES)
    }

    @Test
    fun channelsFirstBox_mapsThroughLetterbox() {
        val classes = YoloDecode.CLASS_NAMES.size
        val anchors = 2
        val output = FloatArray((4 + classes) * anchors)
        // Anchor 0: person at the network center, in input pixels.
        output[0 * anchors + 0] = 160f
        output[1 * anchors + 0] = 160f
        output[2 * anchors + 0] = 40f
        output[3 * anchors + 0] = 40f
        output[4 * anchors + 0] = 0.9f
        // Anchor 1: low score, ignored.
        output[0 * anchors + 1] = 20f
        output[1 * anchors + 1] = 20f
        output[2 * anchors + 1] = 10f
        output[3 * anchors + 1] = 10f
        output[4 * anchors + 1] = 0.2f

        val found = YoloDecode.decode(
            output = output,
            shape = intArrayOf(1, 4 + classes, anchors),
            inputSize = 320,
            frameWidth = 640,
            frameHeight = 480,
            scoreThreshold = 0.5f,
        )
        assertEquals(1, found.size)
        assertEquals("person", found[0].label)
        assertEquals(0.5f, found[0].left + (found[0].right - found[0].left) / 2f, 0.02f)
        assertEquals(0.5f, found[0].top + (found[0].bottom - found[0].top) / 2f, 0.02f)
    }

    @Test
    fun endToEndHead_readsClassIndex() {
        // 640x480 letterbox into 320: scale 0.5, padY 40. A pothole along the bottom.
        val output = floatArrayOf(
            100f, 200f, 180f, 280f, 0.8f, 11f,
        )
        val found = YoloDecode.decode(
            output = output,
            shape = intArrayOf(1, 1, 6),
            inputSize = 320,
            frameWidth = 640,
            frameHeight = 480,
            scoreThreshold = 0.5f,
        )
        assertEquals(1, found.size)
        assertEquals("pothole", found[0].label)
        assertTrue(found[0].bottom > found[0].top)
    }
}
