package org.visualaid.obstacleguide

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import org.visualaid.obstacleguide.collect.CollectStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A short log in the collect folder. Export adds it to the zip the user shares.
 * Samples stay on a background thread. The file is capped so it cannot grow without bound.
 */
object StabilityLog {
    private const val TAG = "StabilityLog"
    private const val FILE_NAME = "diagnostics.log"
    private const val MAX_BYTES = 300_000
    private const val KEEP_BYTES = 150_000
    private const val INTERVAL_MS = 10_000L
    private val lock = Any()
    private val started = AtomicBoolean(false)
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            append(app, "CRASH thread=${thread.name}\n${error.stackTraceToString()}")
            previous?.uncaughtException(thread, error)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            ExitHistory.record(app)
        }
        if (started.compareAndSet(false, true)) {
            append(app, "START pid=${android.os.Process.myPid()}")
            val application = app
            Thread({ sampleLoop(application) }, "obstacle-guide-diag").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun append(context: Context, line: String) {
        try {
            synchronized(lock) {
                val file = logFile(context)
                if (file.length() > MAX_BYTES) {
                    val bytes = file.readBytes()
                    val start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
                    file.writeBytes(bytes.copyOfRange(start, bytes.size))
                }
                file.appendText("${stamp.format(Date())} $line\n")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not write diagnostics", error)
        }
    }

    fun logFile(context: Context): File {
        return File(CollectStore.root(context), FILE_NAME)
    }

    private fun sampleLoop(app: Application) {
        while (true) {
            try {
                Thread.sleep(INTERVAL_MS)
                append(app, sample(app))
            } catch (error: InterruptedException) {
                break
            } catch (error: Exception) {
                Log.w(TAG, "Sample failed", error)
            }
        }
    }

    private fun sample(context: Context): String {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        val thermal = thermalStatus(context)
        return "MEM pssKb=${Debug.getPss()} javaHeap=$javaUsed javaMax=${runtime.maxMemory()} " +
            "nativeHeap=${Debug.getNativeHeapAllocatedSize()} availBytes=${memory.availMem} " +
            "lowMemory=${memory.lowMemory} thermal=$thermal"
    }

    private fun thermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "unknown"
        val power = context.getSystemService(PowerManager::class.java) ?: return "unknown"
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> power.currentThermalStatus.toString()
        }
    }

}
