package org.visualaid.obstacleguide.collect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JPEG encoding stays off the camera thread and off the main thread.
 * The queue is small so a burst cannot pin several full-frame bitmaps.
 * A rejected frame is recycled immediately.
 */
class CollectEncoder(context: Context) {
    private val appContext = context.applicationContext
    private val queue = ArrayBlockingQueue<Job>(CAPACITY)
    private val alive = AtomicBoolean(true)
    private val thread = Thread(::loop, "obstacle-guide-collect-jpeg").apply {
        priority = Thread.NORM_PRIORITY - 1
        start()
    }

    fun hasRoom(): Boolean = alive.get() && queue.remainingCapacity() > 0

    /**
     * Takes ownership of [bitmap]. Returns false when the queue is full.
     * The bitmap is recycled here in that case.
     */
    fun offer(bitmap: Bitmap, snapshot: CollectSnapshot, onSaved: () -> Unit, onFailed: () -> Unit): Boolean {
        val job = Job(bitmap, snapshot, onSaved, onFailed)
        if (!alive.get() || !queue.offer(job)) {
            recycle(bitmap)
            return false
        }
        return true
    }

    fun shutdown() {
        alive.set(false)
        thread.interrupt()
        thread.join(1_000L)
        while (true) {
            val job = queue.poll() ?: break
            recycle(job.bitmap)
            try {
                job.onFailed()
            } catch (error: Exception) {
                Log.w(TAG, "Collect drop failed", error)
            }
        }
    }

    private fun loop() {
        while (alive.get() || queue.isNotEmpty()) {
            val job = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                if (!alive.get() && queue.isEmpty()) break
                continue
            } ?: continue
            try {
                CollectStore.writeBitmap(appContext, job.bitmap, job.snapshot)
                job.onSaved()
            } catch (error: Exception) {
                Log.w(TAG, "Collect save failed", error)
                try {
                    job.onFailed()
                } catch (callback: Exception) {
                    Log.w(TAG, "Collect failure callback failed", callback)
                }
            } finally {
                recycle(job.bitmap)
            }
        }
    }

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private class Job(
        val bitmap: Bitmap,
        val snapshot: CollectSnapshot,
        val onSaved: () -> Unit,
        val onFailed: () -> Unit,
    )

    companion object {
        private const val CAPACITY = 2
        private const val TAG = "CollectEncoder"
    }
}
