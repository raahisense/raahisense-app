package org.visualaid.obstacleguide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipFile

class CollectCaptureTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun interval_isAboutOneAndAHalfSeconds_andCanChange() {
        assertEquals(1_500L, CollectCapture.DEFAULT_INTERVAL_MS)
        assertEquals(90, CollectCapture.JPEG_QUALITY)
        assertEquals(1_500L, CollectCapture.clampInterval(1_500L))
        assertEquals(500L, CollectCapture.clampInterval(1L))
        assertEquals(10_000L, CollectCapture.clampInterval(99_000L))
        assertEquals(1_500L, CollectCapture.nextIntervalMs(1_000L))
        assertEquals(3_000L, CollectCapture.nextIntervalMs(1_500L))
        assertEquals(1_000L, CollectCapture.nextIntervalMs(3_000L))
        assertEquals("1.5", CollectCapture.intervalLabel(1_500L))
        assertEquals("3", CollectCapture.intervalLabel(3_000L))
    }

    @Test
    fun hotPhone_pausesSaving() {
        assertFalse(CollectCapture.isHot(thermalStatus = 0, batteryTenthsC = null))
        assertFalse(CollectCapture.isHot(thermalStatus = 2, batteryTenthsC = null))
        assertTrue(CollectCapture.isHot(thermalStatus = CollectCapture.HOT_THERMAL_STATUS, batteryTenthsC = null))
        assertTrue(CollectCapture.isHot(thermalStatus = null, batteryTenthsC = 420))
        assertFalse(CollectCapture.isHot(thermalStatus = null, batteryTenthsC = 360))
        assertFalse(CollectCapture.isHot(thermalStatus = null, batteryTenthsC = null))
    }

    @Test
    fun periodicWaits_warningAndMissedBurstImmediately_andHeatHoldsTheQueue() {
        val scheduler = CollectScheduler()
        assertNull(scheduler.poll(nowMs = 10_000, intervalMs = 1_500, hot = false, busy = false))
        scheduler.setEnabled(true)
        assertEquals(
            CollectEvent.PERIODIC,
            scheduler.poll(nowMs = 10_000, intervalMs = 1_500, hot = false, busy = false),
        )
        scheduler.onCaptured(10_000)
        assertNull(scheduler.poll(nowMs = 11_000, intervalMs = 1_500, hot = false, busy = false))
        scheduler.onWarning()
        assertEquals(
            CollectEvent.WARNING,
            scheduler.poll(nowMs = 11_100, intervalMs = 1_500, hot = false, busy = false),
        )
        assertNull(scheduler.poll(nowMs = 11_100, intervalMs = 1_500, hot = false, busy = true))
        assertNull(scheduler.poll(nowMs = 11_200, intervalMs = 1_500, hot = true, busy = false))
        val cooled = scheduler.poll(nowMs = 11_300, intervalMs = 1_500, hot = false, busy = false)
        assertEquals(CollectEvent.WARNING, cooled)
        scheduler.onMissed()
        assertEquals(
            CollectEvent.MISSED,
            scheduler.poll(nowMs = 11_400, intervalMs = 1_500, hot = false, busy = false),
        )
        scheduler.setEnabled(false)
        assertNull(scheduler.poll(nowMs = 20_000, intervalMs = 1_500, hot = false, busy = false))
    }

    @Test
    fun pathsUseTheCollectDateFolder() {
        val epoch = 1_761_422_400_000L
        assertEquals("2025-10-25", CollectCapture.sessionDate(epoch, utc))
        assertTrue(CollectCapture.isSessionDate("2026-10-25"))
        assertFalse(CollectCapture.isSessionDate("collect"))
        assertEquals(
            "20251025-200000000-missed-0003",
            CollectCapture.fileStem(epoch, utc, CollectEvent.MISSED, 3),
        )
        assertEquals("2025-10-25T20:00:00.000+00:00", CollectCapture.isoTimestamp(epoch, utc))
        assertEquals("RaahiSense/collect", CollectCapture.FOLDER)
    }

    @Test
    fun sidecar_recordsDetectionsDepthLightAndLeavesGpsOff() {
        val depth = DepthField(2, 2, floatArrayOf(1f, 3f, 2f, 4f))
        val summary = CollectCapture.summarizeDepth(depth)
        assertEquals(4, summary.finiteSamples)
        assertEquals(1f, summary.corridorMin)
        assertEquals(4f, summary.corridorMax)
        val lighting = CollectCapture.lightingOf(SceneLight(meanLuma = 0.10f, lux = 4f, iso = 800, exposureTimeNs = 50L))
        assertEquals("dark", lighting.estimate)
        val json = CollectCapture.sidecarJson(
            timestampIso = "2026-10-25T12:00:00.000+00:00",
            event = CollectEvent.WARNING,
            detections = listOf(DetectionBox("person", 0.82f, 0.1f, 0.2f, 0.4f, 0.9f)),
            depth = summary,
            lighting = lighting,
            gpsEnabled = false,
            gps = GpsReading(12.9, 77.6, 8f, "2026-10-25T12:00:00.000+00:00"),
            width = 4000,
            height = 3000,
            fullResolution = true,
        )
        assertTrue(json.contains("\"event\": \"warning\""))
        assertTrue(json.contains("\"label\": \"person\""))
        assertTrue(json.contains("\"confidence\": 0.8200"))
        assertTrue(json.contains("\"left\": 0.1000"))
        assertTrue(json.contains("\"estimate\": \"dark\""))
        assertTrue(json.contains("\"gpsEnabled\": false"))
        assertTrue(json.contains("\"gps\": null"))
        assertFalse(json.contains("77.6"))
        assertTrue(json.contains("blur faces"))
        assertTrue(json.contains("\"fullResolution\": true"))
        assertTrue(json.contains("\"jpegQuality\": 90"))
        assertFalse(json.contains("spokenWarning"))
        val withGps = CollectCapture.sidecarJson(
            timestampIso = "2026-10-25T12:00:00.000+00:00",
            event = CollectEvent.PERIODIC,
            detections = emptyList(),
            depth = DepthSummary(null, null, null, 0),
            lighting = CollectCapture.lightingOf(SceneLight(Float.NaN)),
            gpsEnabled = true,
            gps = GpsReading(12.9716, 77.5946, 12f, "2026-10-25T12:00:00.000+00:00"),
            width = null,
            height = null,
            fullResolution = false,
        )
        assertTrue(withGps.contains("\"event\": \"periodic\""))
        assertTrue(withGps.contains("\"latitude\": 12.971600"))
        assertTrue(withGps.contains("\"estimate\": \"unknown\""))
        assertTrue(withGps.contains("\"gpsEnabled\": true"))
    }

    @Test
    fun voicePhrases_doNotStealStopOrStart() {
        assertEquals(VoiceCommand.START_COLLECTING, CommandParser.parse("start collecting"))
        assertEquals(VoiceCommand.STOP_COLLECTING, CommandParser.parse("stop collecting"))
        assertEquals(VoiceCommand.MISSED, CommandParser.parse("missed"))
        assertEquals(VoiceCommand.START, CommandParser.parse("start"))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("stop"))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("stop app"))
        assertEquals(
            VoiceAction.START_COLLECTING,
            VoiceInterpreter.interpret("hey guide start collecting", requireWake = true),
        )
        assertEquals(
            VoiceAction.STOP_COLLECTING,
            VoiceInterpreter.interpret("stop collecting", requireWake = false),
        )
        assertEquals(
            VoiceAction.MISSED,
            VoiceInterpreter.interpret("missed", requireWake = false),
        )
        assertEquals(VoiceAction.STOP, VoiceInterpreter.interpret("stop", requireWake = false))
        assertEquals(VoiceCommand.WRONG, CommandParser.parse("wrong warning"))
        assertEquals(
            VoiceAction.WRONG,
            VoiceInterpreter.interpret("wrong", requireWake = true, allowBareCorrection = true),
        )
    }

    @Test
    fun wrong_burstsImmediatelyAndKeepsTheSpokenWarning() {
        val scheduler = CollectScheduler()
        scheduler.setEnabled(true)
        scheduler.onCaptured(10_000)
        scheduler.onWarning()
        scheduler.onWrong()
        assertEquals(
            CollectEvent.WRONG,
            scheduler.poll(nowMs = 10_100, intervalMs = 1_500, hot = false, busy = false),
        )
        assertNull(scheduler.poll(nowMs = 10_200, intervalMs = 1_500, hot = true, busy = false))
        assertEquals(
            CollectEvent.WRONG,
            scheduler.poll(nowMs = 10_300, intervalMs = 1_500, hot = false, busy = false),
        )
        val spokenBox = DetectionBox("person", 0.91f, 0.05f, 0.20f, 0.40f, 0.95f)
        val other = DetectionBox("person", 0.80f, 0.40f, 0.40f, 0.55f, 0.60f)
        val warning = WarningLogic.fromBox(spokenBox)
        assertEquals(HorizontalPosition.LEFT, warning?.position)
        assertEquals(Closeness.VERY_CLOSE, warning?.closeness)
        val spoken = CollectCapture.spokenWarning(
            warning!!,
            listOf(other, spokenBox),
            "2026-10-25T12:00:01.000+00:00",
        )
        assertEquals("person", spoken.label)
        assertEquals(0.91f, spoken.confidence)
        assertEquals(0.05f, spoken.left)
        assertEquals(0.95f, spoken.bottom)
        val wall = ObstacleWarning("wall", HorizontalPosition.AHEAD, Closeness.VERY_CLOSE, 1f, true)
        val depthOnly = CollectCapture.spokenWarning(wall, listOf(spokenBox), "2026-10-25T12:00:02.000+00:00")
        assertEquals("wall", depthOnly.label)
        assertNull(depthOnly.confidence)
        assertFalse(depthOnly.hasBox())
        val lighting = CollectCapture.lightingOf(SceneLight(Float.NaN))
        val json = CollectCapture.sidecarJson(
            timestampIso = "2026-10-25T12:00:03.000+00:00",
            event = CollectEvent.WRONG,
            detections = listOf(spokenBox),
            depth = DepthSummary(null, null, null, 0),
            lighting = lighting,
            gpsEnabled = false,
            gps = GpsReading(12.9716, 77.5946, 8f, "2026-10-25T12:00:03.000+00:00"),
            width = 4000,
            height = 3000,
            fullResolution = true,
            spokenWarning = spoken,
            includeSpokenWarning = true,
        )
        assertTrue(json.contains("\"event\": \"wrong\""))
        assertTrue(json.contains("\"spokenWarning\""))
        assertTrue(json.contains("\"label\": \"person\""))
        assertTrue(json.contains("\"confidence\": 0.9100"))
        assertTrue(json.contains("\"left\": 0.0500"))
        assertTrue(json.contains("\"spokenAt\": \"2026-10-25T12:00:01.000+00:00\""))
        assertTrue(json.contains("\"gps\": null"))
        assertFalse(json.contains("77.5946"))
        val missing = CollectCapture.sidecarJson(
            timestampIso = "2026-10-25T12:00:03.000+00:00",
            event = CollectEvent.WRONG,
            detections = emptyList(),
            depth = DepthSummary(null, null, null, 0),
            lighting = lighting,
            gpsEnabled = false,
            gps = null,
            width = null,
            height = null,
            fullResolution = true,
            spokenWarning = null,
            includeSpokenWarning = true,
        )
        assertTrue(missing.contains("\"spokenWarning\": null"))
        assertTrue(missing.contains("\"event\": \"wrong\""))
    }

    @Test
    fun zip_roundTripKeepsTheSessionNames() {
        val root = File.createTempFile("collect-root", "").apply {
            delete()
            mkdirs()
        }
        val session = File(root, "2026-10-25").apply { mkdirs() }
        val jpg = File(session, "frame.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val json = File(session, "frame.json").apply { writeText("{\"event\":\"missed\"}") }
        val log = File(root, "diagnostics.log").apply { writeText("MEM pssKb=1\n") }
        val zip = File(root, "session.zip")
        CollectZip.write(zip, root, listOf(jpg, json, log))
        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("2026-10-25/frame.jpg", "2026-10-25/frame.json", "diagnostics.log"), names)
            val saved = archive.getInputStream(archive.getEntry("2026-10-25/frame.json")).readBytes()
            assertEquals("{\"event\":\"missed\"}", saved.toString(Charsets.UTF_8))
        }
        root.deleteRecursively()
    }

    @Test
    fun byteLabels_stayShort() {
        assertEquals("0 B", CollectCapture.formatBytes(0))
        assertEquals("1.5 KB", CollectCapture.formatBytes(1536))
        assertEquals("1.0 MB", CollectCapture.formatBytes(1024L * 1024L))
    }
}
