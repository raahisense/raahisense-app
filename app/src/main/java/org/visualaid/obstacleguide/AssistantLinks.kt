package org.visualaid.obstacleguide

/**
 * Deep links for Google Assistant and the launcher shortcuts.
 *
 * Only the host selects an action. Query strings, fragments, and extras are
 * ignored, so a link cannot ask the app to do anything other than start
 * guidance or run the full stop.
 */
object AssistantLinks {
    const val SCHEME = "raahisense"
    const val START_URI = "raahisense://start"
    const val STOP_URI = "raahisense://stop"
    const val CLOSE_URI = "raahisense://close"

    private val startHosts = setOf("start", "guidance")
    private val stopHosts = setOf("stop", "close", "quit", "exit")

    fun command(scheme: String?, host: String?, userInfo: String?, path: String?): AssistantCommand? {
        if (!scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!userInfo.isNullOrEmpty()) return null
        val name = host?.trim()?.lowercase() ?: return null
        if (name.isEmpty() || '.' in name || '/' in name || '@' in name) return null
        val extra = path?.trim().orEmpty()
        if (extra.isNotEmpty() && extra != "/") return null
        return when {
            name in startHosts -> AssistantCommand.START
            name in stopHosts -> AssistantCommand.STOP
            else -> null
        }
    }
}

enum class AssistantCommand {
    START,
    STOP,
}
