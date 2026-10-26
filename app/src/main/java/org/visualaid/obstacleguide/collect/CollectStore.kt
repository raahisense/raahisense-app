package org.visualaid.obstacleguide.collect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.visualaid.obstacleguide.logic.CollectCapture
import org.visualaid.obstacleguide.logic.CollectEvent
import org.visualaid.obstacleguide.logic.CollectZip
import org.visualaid.obstacleguide.logic.DepthSummary
import org.visualaid.obstacleguide.logic.GpsReading
import org.visualaid.obstacleguide.logic.LightingEstimate
import org.visualaid.obstacleguide.logic.DetectionBox
import org.visualaid.obstacleguide.logic.SpokenWarning
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

data class CollectUsage(val photos: Int, val bytes: Long)

data class CollectSnapshot(
    val event: CollectEvent,
    val requestedAtMs: Long,
    val detections: List<DetectionBox>,
    val depth: DepthSummary,
    val lighting: LightingEstimate,
    val gpsEnabled: Boolean,
    val gps: GpsReading?,
    val fullResolution: Boolean,
    val spokenWarning: SpokenWarning? = null,
)

/**
 * Personal training photos live in app-specific storage:
 * Android/data/.../files/RaahiSense/collect/yyyy-MM-dd/.
 *
 * TODO: Blur faces before any third-party sharing. [share] only opens the
 * system share sheet. This class never uploads.
 */
object CollectStore {
    private const val TAG = "CollectStore"
    private val sequence = AtomicInteger(0)

    fun root(context: Context): File {
        val base = context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir
        return File(base, CollectCapture.FOLDER).apply { mkdirs() }
    }

    fun usage(context: Context): CollectUsage = usageOf(root(context))

    fun usageOf(root: File): CollectUsage {
        var photos = 0
        var bytes = 0L
        if (!root.isDirectory) return CollectUsage(0, 0)
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            bytes += file.length()
            if (file.extension.equals("jpg", ignoreCase = true)) photos += 1
        }
        return CollectUsage(photos, bytes)
    }

    fun isHot(context: Context): Boolean {
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.applicationContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else {
            null
        }
        val battery = if (thermal == null) {
            val sticky = ContextCompat.registerReceiver(
                context.applicationContext,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }
        } else {
            null
        }
        return CollectCapture.isHot(thermal, battery)
    }

    fun allocate(context: Context, event: CollectEvent, epochMs: Long): Pair<File, File> {
        val zone = TimeZone.getDefault()
        val dir = File(root(context), CollectCapture.sessionDate(epochMs, zone)).apply { mkdirs() }
        while (true) {
            val seq = sequence.incrementAndGet()
            val stem = CollectCapture.fileStem(epochMs, zone, event, seq)
            val jpg = File(dir, "$stem.jpg")
            val json = File(dir, "$stem.json")
            if (!jpg.exists() && !json.exists()) return jpg to json
        }
    }

    fun writeBitmap(context: Context, bitmap: Bitmap, snapshot: CollectSnapshot) {
        val (jpg, json) = allocate(context, snapshot.event, snapshot.requestedAtMs)
        FileOutputStream(jpg).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, CollectCapture.JPEG_QUALITY, stream)) {
                jpg.delete()
                return
            }
        }
        writeSidecar(json, jpg, snapshot, bitmap.width, bitmap.height)
    }

    fun writeSidecar(json: File, jpg: File, snapshot: CollectSnapshot, width: Int?, height: Int?) {
        val zone = TimeZone.getDefault()
        json.writeText(
            CollectCapture.sidecarJson(
                timestampIso = CollectCapture.isoTimestamp(snapshot.requestedAtMs, zone),
                event = snapshot.event,
                detections = snapshot.detections,
                depth = snapshot.depth,
                lighting = snapshot.lighting,
                gpsEnabled = snapshot.gpsEnabled,
                gps = snapshot.gps,
                jpegQuality = CollectCapture.JPEG_QUALITY,
                width = width,
                height = height,
                fullResolution = snapshot.fullResolution,
                spokenWarning = snapshot.spokenWarning,
                includeSpokenWarning = snapshot.event == CollectEvent.WRONG,
            ),
        )
        if (!jpg.isFile || jpg.length() == 0L) json.delete()
    }

    fun imageSize(jpg: File): Pair<Int?, Int?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpg.absolutePath, options)
        val width = options.outWidth.takeIf { it > 0 }
        val height = options.outHeight.takeIf { it > 0 }
        return width to height
    }

    fun latestSession(context: Context): File? {
        val folder = root(context)
        return folder.listFiles()
            ?.filter { it.isDirectory && CollectCapture.isSessionDate(it.name) && hasJpeg(it) }
            ?.maxByOrNull { it.name }
    }

    /**
     * TODO: Blur faces before any third-party sharing. The zip stays on this
     * phone until the developer picks a destination in the share sheet.
     */
    fun exportLatest(context: Context): File? {
        val session = latestSession(context) ?: return null
        val files = session.listFiles()?.filter { file ->
            file.isFile && (
                file.extension.equals("jpg", ignoreCase = true) ||
                    file.extension.equals("json", ignoreCase = true)
                )
        }.orEmpty()
        if (files.none { it.extension.equals("jpg", ignoreCase = true) }) return null
        val base = context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir
        val exportDir = File(base, "RaahiSense/export").apply { mkdirs() }
        val zip = File(exportDir, "RaahiSense-collect-${session.name}.zip")
        if (zip.exists()) zip.delete()
        CollectZip.write(zip, session.parentFile ?: session, files)
        return zip.takeIf { it.isFile && it.length() > 0L }
    }

    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            clipData = android.content.ClipData.newRawUri(zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, zip.name).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    private fun hasJpeg(dir: File): Boolean {
        return dir.listFiles()?.any { it.isFile && it.extension.equals("jpg", ignoreCase = true) } == true
    }
}

/**
 * GPS is off unless collection and the GPS setting are both on.
 * Updates are slow, and they stop as soon as collection stops.
 */
object CollectLocation {
    private const val TAG = "CollectLocation"
    private var manager: LocationManager? = null
    private var listening = false

    @Volatile
    var reading: GpsReading? = null
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            reading = location.toReading()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    fun readingIfAllowed(enabled: Boolean): GpsReading? = if (enabled) reading else null

    fun setActive(context: Context, active: Boolean) {
        val app = context.applicationContext
        if (!active) {
            stop(app)
            return
        }
        if (!permitted(app)) return
        val locationManager = app.getSystemService(LocationManager::class.java) ?: return
        manager = locationManager
        if (!listening) {
            try {
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (!locationManager.isProviderEnabled(provider)) continue
                    locationManager.getLastKnownLocation(provider)?.let { reading = it.toReading() }
                    locationManager.requestLocationUpdates(provider, 5_000L, 10f, listener, Looper.getMainLooper())
                }
                listening = true
            } catch (error: SecurityException) {
                Log.w(TAG, "Location was not allowed", error)
                listening = false
            } catch (error: Exception) {
                Log.w(TAG, "Location updates did not start", error)
                listening = false
            }
        }
    }

    fun stop(context: Context) {
        val locationManager = manager ?: context.applicationContext.getSystemService(LocationManager::class.java)
        if (listening && locationManager != null) {
            try {
                locationManager.removeUpdates(listener)
            } catch (error: Exception) {
                Log.w(TAG, "Location stop failed", error)
            }
        }
        listening = false
        manager = null
    }

    private fun permitted(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun Location.toReading(): GpsReading {
        return GpsReading(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            timeIso = CollectCapture.isoTimestamp(time),
        )
    }
}
