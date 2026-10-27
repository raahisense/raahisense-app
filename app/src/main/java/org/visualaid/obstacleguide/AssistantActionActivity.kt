package org.visualaid.obstacleguide

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Exported entry for launcher shortcuts and Assistant App Actions.
 *
 * The intent filter accepts only raahisense://start, guidance, stop, close,
 * quit, and exit. This activity never forwards the incoming intent. It opens
 * [MainActivity] with one of those links, or it does nothing.
 *
 * Stop is handled by [MainActivity], which calls [GuideService.ACTION_QUIT].
 * That is the same full shutdown as the Stop RaahiSense button: speak
 * "RaahiSense stopped", then stop detection, the camera, speech, the
 * foreground service, and the notification, and close the app.
 */
class AssistantActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (commandOf(intent)) {
            AssistantCommand.START -> forward(AssistantLinks.START_URI)
            AssistantCommand.STOP -> forward(stopUri(intent))
            null -> Unit
        }
        finish()
    }

    private fun forward(uri: String) {
        val open = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uri)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(open)
    }

    companion object {
        fun commandOf(intent: Intent?): AssistantCommand? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val data = intent.data ?: return null
            return AssistantLinks.command(data.scheme, data.host, data.userInfo, data.path)
        }

        private fun stopUri(intent: Intent?): String {
            val host = intent?.data?.host?.trim()?.lowercase()
            return if (host == "close") AssistantLinks.CLOSE_URI else AssistantLinks.STOP_URI
        }
    }
}
