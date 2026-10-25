package org.visualaid.obstacleguide.logic

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Decides when a personal training photo is saved.
 *
 * Warnings are not part of this decision. The guide keeps speaking while
 * collection is on. Photos wait when the phone is hot, and a warning or a
 * spoken "missed" saves a short burst immediately.
 *
 * GPS stays off unless the caller passes a reading. Nothing here uploads.
 *
 * TODO: Blur faces before any third-party sharing. This collection is for the
 * developer walking with the phone. Export only builds a zip for the share
 * sheet. Do not send a session to anyone else until faces are blurred.
 */
enum class CollectEvent(val wire: String) {
    PERIODIC("periodic"),
    WARNING("warning"),
    MISSED("missed"),
}

data class GpsReading(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timeIso: String,
)

data class DepthSummary(
    val aheadMedian: Float?,
    val corridorMin: Float?,
    val corridorMax: Float?,
    val finiteSamples: Int,
)

data class LightingEstimate(
    val meanLuma: Float?,
    val lux: Float?,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val estimate: String,
)

object CollectCapture {
    const val DEFAULT_INTERVAL_MS = 1_500L
    const val MIN_INTERVAL_MS = 500L
    const val MAX_INTERVAL_MS = 10_000L
    const val BURST_FRAMES = 3
    const val JPEG_QUALITY = 90
    const val POLL_MS = 200L

    /** Matches PowerManager.THERMAL_STATUS_SEVERE. At this point the phone is hot. */
    const val HOT_THERMAL_STATUS = 3

    /** Battery intent temperature is tenths of a degree Celsius. 42.0 C. */
    const val HOT_BATTERY_TENTHS_C = 420

    const val FOLDER = "RaahiSense/collect"

    const val SHARE_NOTE =
        "TODO: blur faces before any third-party sharing. Personal collection. Nothing is uploaded automatically."

    fun clampInterval(ms: Long): Long {
        if (ms < MIN_INTERVAL_MS) return MIN_INTERVAL_MS
        if (ms > MAX_INTERVAL_MS) return MAX_INTERVAL_MS
        return ms
    }

    fun nextIntervalMs(current: Long): Long = when (clampInterval(current)) {
        1_000L -> DEFAULT_INTERVAL_MS
        DEFAULT_INTERVAL_MS -> 3_000L
        3_000L -> 1_000L
        else -> DEFAULT_INTERVAL_MS
    }

    fun intervalLabel(ms: Long): String {
        val clamped = clampInterval(ms)
        return if (clamped % 1_000L == 0L) {
            (clamped / 1_000L).toString()
        } else {
            String.format(Locale.US, "%.1f", clamped / 1_000.0)
        }
    }

    fun isHot(thermalStatus: Int?, batteryTenthsC: Int?): Boolean {
        if (thermalStatus != null) return thermalStatus >= HOT_THERMAL_STATUS
        if (batteryTenthsC != null) return batteryTenthsC >= HOT_BATTERY_TENTHS_C
        return false
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        if (safe < 1024L) return "$safe B"
        val kb = safe / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun sessionDate(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = calendar(epochMs, zone)
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    fun isoTimestamp(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = calendar(epochMs, zone)
        val offsetMinutes = zone.getOffset(epochMs) / 60_000
        val sign = if (offsetMinutes >= 0) "+" else "-"
        val absolute = kotlin.math.abs(offsetMinutes)
        return String.format(
            Locale.US,
            "%04d-%02d-%02dT%02d:%02d:%02d.%03d%s%02d:%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
            sign,
            absolute / 60,
            absolute % 60,
        )
    }

    fun fileStem(epochMs: Long, zone: TimeZone, event: CollectEvent, sequence: Int): String {
        val calendar = calendar(epochMs, zone)
        val stamp = String.format(
            Locale.US,
            "%04d%02d%02d-%02d%02d%02d%03d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
        )
        return "$stamp-${event.wire}-${sequence.coerceAtLeast(0).toString().padStart(4, '0')}"
    }

    fun isSessionDate(name: String): Boolean = Regex("\\d{4}-\\d{2}-\\d{2}").matches(name)

    fun lightingOf(scene: SceneLight): LightingEstimate {
        val luma = scene.meanLuma.takeIf { it.isFinite() }
        val lux = scene.lux?.takeIf { it.isFinite() }
        val estimate = when {
            lux != null && lux < LowLight.DARK_LUX -> "dark"
            lux != null && lux < LowLight.BRIGHT_LUX -> "dim"
            lux != null -> "bright"
            luma == null -> "unknown"
            luma < LowLight.DARK_LUMA -> "dark"
            luma < LowLight.AMBIGUOUS_LUMA -> "dim"
            luma < LowLight.BRIGHT_LUMA -> "mixed"
            else -> "bright"
        }
        return LightingEstimate(
            meanLuma = luma,
            lux = scene.lux?.takeIf { it.isFinite() },
            iso = scene.iso,
            exposureTimeNs = scene.exposureTimeNs,
            estimate = estimate,
        )
    }

    fun summarizeDepth(field: DepthField?): DepthSummary {
        if (field == null || field.width <= 0 || field.height <= 0 || field.raw.size != field.width * field.height) {
            return DepthSummary(null, null, null, 0)
        }
        val ahead = DepthWarnings.aheadMedian(field).takeIf { it.isFinite() }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var count = 0
        val yStart = (field.height * DepthWarnings.CORRIDOR_TOP).toInt().coerceIn(0, field.height - 1)
        val yEnd = (field.height * DepthWarnings.CORRIDOR_BOTTOM).toInt().coerceIn(yStart, field.height - 1)
        var y = yStart
        while (y <= yEnd) {
            val row = y * field.width
            var x = 0
            while (x < field.width) {
                val value = field.raw[row + x]
                if (value.isFinite()) {
                    count += 1
                    if (value < min) min = value
                    if (value > max) max = value
                }
                x += 1
            }
            y += 1
        }
        return DepthSummary(
            aheadMedian = ahead,
            corridorMin = if (count == 0) null else min,
            corridorMax = if (count == 0) null else max,
            finiteSamples = count,
        )
    }

    fun sidecarJson(
        timestampIso: String,
        event: CollectEvent,
        detections: List<DetectionBox>,
        depth: DepthSummary,
        lighting: LightingEstimate,
        gpsEnabled: Boolean,
        gps: GpsReading?,
        jpegQuality: Int = JPEG_QUALITY,
        width: Int?,
        height: Int?,
        fullResolution: Boolean,
    ): String {
        val savedGps = if (gpsEnabled) gps else null
        return buildString {
            append("{\n")
            append("  \"timestamp\": ${jsonString(timestampIso)},\n")
            append("  \"event\": ${jsonString(event.wire)},\n")
            append("  \"jpegQuality\": $jpegQuality,\n")
            append("  \"fullResolution\": $fullResolution,\n")
            append("  \"image\": { \"width\": ${jsonInt(width)}, \"height\": ${jsonInt(height)} },\n")
            append("  \"detections\": [\n")
            detections.forEachIndexed { index, box ->
                append("    { ")
                append("\"label\": ${jsonString(box.label)}, ")
                append("\"confidence\": ${jsonFloat(box.score)}, ")
                append("\"box\": { ")
                append("\"left\": ${jsonFloat(box.left)}, ")
                append("\"top\": ${jsonFloat(box.top)}, ")
                append("\"right\": ${jsonFloat(box.right)}, ")
                append("\"bottom\": ${jsonFloat(box.bottom)} ")
                append("} }")
                if (index != detections.lastIndex) append(",")
                append("\n")
            }
            append("  ],\n")
            append("  \"depth\": {\n")
            append("    \"aheadMedian\": ${jsonFloat(depth.aheadMedian)},\n")
            append("    \"corridorMin\": ${jsonFloat(depth.corridorMin)},\n")
            append("    \"corridorMax\": ${jsonFloat(depth.corridorMax)},\n")
            append("    \"finiteSamples\": ${depth.finiteSamples}\n")
            append("  },\n")
            append("  \"lighting\": {\n")
            append("    \"meanLuma\": ${jsonFloat(lighting.meanLuma)},\n")
            append("    \"lux\": ${jsonFloat(lighting.lux)},\n")
            append("    \"iso\": ${jsonInt(lighting.iso)},\n")
            append("    \"exposureTimeNs\": ${jsonLong(lighting.exposureTimeNs)},\n")
            append("    \"estimate\": ${jsonString(lighting.estimate)}\n")
            append("  },\n")
            append("  \"gpsEnabled\": $gpsEnabled,\n")
            if (savedGps == null) {
                append("  \"gps\": null,\n")
            } else {
                append("  \"gps\": {\n")
                append("    \"latitude\": ${jsonDouble(savedGps.latitude, 6)},\n")
                append("    \"longitude\": ${jsonDouble(savedGps.longitude, 6)},\n")
                append("    \"accuracyMeters\": ${jsonFloat(savedGps.accuracyMeters, 1)},\n")
                append("    \"time\": ${jsonString(savedGps.timeIso)}\n")
                append("  },\n")
            }
            append("  \"note\": ${jsonString(SHARE_NOTE)}\n")
            append("}\n")
        }
    }

    private fun calendar(epochMs: Long, zone: TimeZone): Calendar {
        return Calendar.getInstance(zone, Locale.US).apply { timeInMillis = epochMs }
    }

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun jsonFloat(value: Float?, digits: Int = 4): String {
        if (value == null || !value.isFinite()) return "null"
        return String.format(Locale.US, "%.${digits}f", value)
    }

    private fun jsonDouble(value: Double, digits: Int): String {
        if (!value.isFinite()) return "null"
        return String.format(Locale.US, "%.${digits}f", value)
    }

    private fun jsonInt(value: Int?): String = value?.toString() ?: "null"

    private fun jsonLong(value: Long?): String = value?.toString() ?: "null"
}

/**
 * Queue of frames to save. Periodic frames wait for the interval.
 * Warning and missed frames are a short burst and do not wait.
 * A hot phone keeps the queue but does not save until it cools.
 */
class CollectScheduler(
    private val burstFrames: Int = CollectCapture.BURST_FRAMES,
) {
    private val lock = Any()
    private val queue = ArrayDeque<CollectEvent>()
    private var enabled = false
    private var lastCaptureAtMs = Long.MIN_VALUE

    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            val was = enabled
            enabled = on
            if (!on) {
                queue.clear()
            } else if (!was) {
                lastCaptureAtMs = Long.MIN_VALUE
                queue.clear()
            }
        }
    }

    fun onWarning() = enqueue(CollectEvent.WARNING, front = false)

    fun onMissed() = enqueue(CollectEvent.MISSED, front = true)

    fun requeue(event: CollectEvent) {
        synchronized(lock) {
            if (!enabled) return
            queue.addFirst(event)
            trim()
        }
    }

    fun poll(nowMs: Long, intervalMs: Long, hot: Boolean, busy: Boolean): CollectEvent? {
        synchronized(lock) {
            if (!enabled || hot || busy) return null
            if (queue.isNotEmpty()) return queue.removeFirst()
            val interval = CollectCapture.clampInterval(intervalMs)
            val neverSaved = lastCaptureAtMs == Long.MIN_VALUE
            if (neverSaved || nowMs - lastCaptureAtMs >= interval) return CollectEvent.PERIODIC
            return null
        }
    }

    fun onCaptured(nowMs: Long) {
        synchronized(lock) {
            lastCaptureAtMs = nowMs
        }
    }

    private fun enqueue(event: CollectEvent, front: Boolean) {
        synchronized(lock) {
            if (!enabled) return
            repeat(burstFrames) {
                if (front) queue.addFirst(event) else queue.addLast(event)
            }
            trim()
        }
    }

    private fun trim() {
        while (queue.size > burstFrames * 3) {
            queue.removeFirst()
        }
    }
}

object CollectZip {
    /**
     * TODO: Blur faces before any third-party sharing. The zip is only for the
     * share sheet. The app does not upload it.
     */
    fun write(destination: File, root: File, files: List<File>) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            for (file in files) {
                if (!file.isFile) continue
                val name = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                if (name.isBlank() || name.startsWith("..")) continue
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                FileInputStream(file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
