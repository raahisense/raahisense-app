package org.visualaid.obstacleguide

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Loaded only on API 30 and newer. Older phones do not have [ApplicationExitInfo].
 */
internal object ExitHistory {
    private const val PREFS = "stability_log"
    private const val KEY_EXIT = "last_exit_timestamp"

    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val reasons = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var newest = prefs.getLong(KEY_EXIT, 0L)
            val seen = newest
            for (reason in reasons) {
                if (reason.timestamp <= seen) continue
                StabilityLog.append(context, line(reason))
                if (reason.timestamp > newest) newest = reason.timestamp
            }
            if (newest > seen) prefs.edit().putLong(KEY_EXIT, newest).apply()
        } catch (error: Exception) {
            Log.w("StabilityLog", "Exit reasons were not read", error)
        }
    }

    private fun line(reason: ApplicationExitInfo): String {
        val name = when (reason.reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "UNKNOWN"
        }
        return "EXIT reason=$name code=${reason.reason} status=${reason.status} " +
            "importance=${reason.importance} pssKb=${reason.pss} rssKb=${reason.rss} " +
            "description=${reason.description.orEmpty()} timestamp=${reason.timestamp}"
    }
}
