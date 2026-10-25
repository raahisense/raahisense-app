package org.visualaid.obstacleguide.logic

import java.util.Locale

/**
 * Hands-free commands. Matching is whole words, longest phrase wins.
 * English phrases are always available. A locale may add more.
 * This file does not use Android, so the tests can run on a normal JVM.
 */
enum class VoiceCommand {
    START,
    STOP,
    PAUSE,
    RESUME,
    WHAT_IS_AHEAD,
    REPEAT,
    LOUDER,
    QUIETER,
    FASTER,
    SLOWER,
    CHANGE_LANGUAGE,
    HELP,
    START_COLLECTING,
    STOP_COLLECTING,
    MISSED,
}

enum class VoiceAction {
    IGNORE,
    WAKE_ONLY,
    UNKNOWN,
    START,
    STOP,
    PAUSE,
    RESUME,
    WHAT_IS_AHEAD,
    REPEAT,
    LOUDER,
    QUIETER,
    FASTER,
    SLOWER,
    CHANGE_LANGUAGE,
    HELP,
    START_COLLECTING,
    STOP_COLLECTING,
    MISSED,
}

data class CommandLexicon(
    val phrases: Map<VoiceCommand, List<String>>,
) {
    fun withExtra(extra: Map<VoiceCommand, List<String>>): CommandLexicon {
        val merged = VoiceCommand.entries.associateWith { command ->
            val combined = extra[command].orEmpty() + phrases[command].orEmpty()
            combined.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
        }
        return CommandLexicon(merged)
    }

    fun allPhrases(): List<String> = phrases.values.flatten().distinct()

    companion object {
        const val DEFAULT_WAKE = "hey guide"

        val ENGLISH = CommandLexicon(
            mapOf(
                VoiceCommand.START to listOf("start", "start guide", "begin"),
                VoiceCommand.STOP to listOf("stop", "stop app", "exit", "close", "stop guide"),
                VoiceCommand.PAUSE to listOf("pause"),
                VoiceCommand.RESUME to listOf("resume"),
                VoiceCommand.WHAT_IS_AHEAD to listOf(
                    "what is in front of me",
                    "what is ahead",
                    "what is in front",
                ),
                VoiceCommand.REPEAT to listOf("repeat", "say again", "repeat that"),
                VoiceCommand.LOUDER to listOf("louder", "speak louder", "volume up"),
                VoiceCommand.QUIETER to listOf("quieter", "speak quieter", "softer", "volume down"),
                VoiceCommand.FASTER to listOf("faster", "speak faster"),
                VoiceCommand.SLOWER to listOf("slower", "speak slower"),
                VoiceCommand.CHANGE_LANGUAGE to listOf("change language", "next language"),
                VoiceCommand.HELP to listOf("help", "list commands", "what can i say"),
                VoiceCommand.START_COLLECTING to listOf("start collecting"),
                VoiceCommand.STOP_COLLECTING to listOf("stop collecting"),
                VoiceCommand.MISSED to listOf("missed"),
            ),
        )

        fun pipes(raw: String): List<String> {
            return raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun normalize(raw: String): String {
            val lowered = raw.lowercase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace("'", "")
            val cleaned = buildString {
                for (ch in lowered) {
                    if (ch.isLetterOrDigit() || ch.isWhitespace()) append(ch) else append(' ')
                }
            }
            return cleaned.trim()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\bwhats\\b"), "what is")
        }
    }
}

object CommandGrammar {
    fun json(phrases: Iterable<String>): String {
        val body = phrases.map { CommandLexicon.normalize(it) }
            .filter { it.isNotEmpty() && it != "unk" }
            .distinct()
            .plus("[unk]")
            .joinToString(",") { "\"${escape(it)}\"" }
        return "[$body]"
    }

    fun inVocabulary(phrase: String, vocab: Set<String>): Boolean {
        val normalized = CommandLexicon.normalize(phrase)
        if (normalized.isEmpty() || normalized == "unk") return false
        return normalized.split(' ').all { it in vocab }
    }

    fun wakePhrases(wakePhrase: String, commands: List<String>, vocab: Set<String>): List<String> {
        val wake = CommandLexicon.normalize(wakePhrase)
        if (!inVocabulary(wake, vocab)) return emptyList()
        val out = linkedSetOf(wake)
        for (command in commands) {
            val normalized = CommandLexicon.normalize(command)
            if (!inVocabulary(normalized, vocab)) continue
            out.add("$wake $normalized")
        }
        return out.toList()
    }

    fun commandPhrases(commands: List<String>, vocab: Set<String>): List<String> {
        return commands.map { CommandLexicon.normalize(it) }
            .filter { inVocabulary(it, vocab) }
            .distinct()
    }

    private fun escape(phrase: String): String {
        return phrase.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

object CommandParser {
    fun parse(transcript: String, lexicon: CommandLexicon = CommandLexicon.ENGLISH): VoiceCommand? {
        val text = CommandLexicon.normalize(transcript)
        if (text.isEmpty() || text == "unk") return null
        val padded = " $text "
        var best: VoiceCommand? = null
        var bestLength = -1
        var bestIndex = Int.MAX_VALUE
        for (command in VoiceCommand.entries) {
            for (phrase in lexicon.phrases[command].orEmpty()) {
                val normalized = CommandLexicon.normalize(phrase)
                if (normalized.isEmpty()) continue
                val index = padded.indexOf(" $normalized ")
                if (index < 0) continue
                val length = normalized.length
                if (length > bestLength || (length == bestLength && index < bestIndex)) {
                    best = command
                    bestLength = length
                    bestIndex = index
                }
            }
        }
        return best
    }
}

object VoiceInterpreter {
    fun interpret(
        transcript: String,
        lexicon: CommandLexicon = CommandLexicon.ENGLISH,
        wakePhrase: String = CommandLexicon.DEFAULT_WAKE,
        requireWake: Boolean,
    ): VoiceAction {
        val text = CommandLexicon.normalize(transcript)
        if (text.isEmpty() || text == "unk") {
            return if (requireWake) VoiceAction.IGNORE else VoiceAction.UNKNOWN
        }
        val wake = CommandLexicon.normalize(wakePhrase)
        val woke = wake.isNotEmpty() && containsPhrase(text, wake)
        val remainder = if (woke) removeFirst(text, wake) else text
        if (requireWake && !woke) return VoiceAction.IGNORE
        if (remainder.isEmpty()) return VoiceAction.WAKE_ONLY
        val command = CommandParser.parse(remainder, lexicon)
            ?: return if (requireWake) VoiceAction.WAKE_ONLY else VoiceAction.UNKNOWN
        return command.toAction()
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        return " $text ".contains(" $phrase ")
    }

    private fun removeFirst(text: String, phrase: String): String {
        val padded = " $text "
        val target = " $phrase "
        val index = padded.indexOf(target)
        if (index < 0) return text
        return (padded.substring(0, index) + " " + padded.substring(index + target.length))
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}

/**
 * The microphone hears the speaker. A warning such as "stop sign" must not
 * be treated as a command, so recognition is dropped while speech is playing
 * and for a short tail after it ends.
 */
object RecognitionGate {
    const val TAIL_MS = 400L

    fun accepts(speechActive: Boolean, nowMs: Long, quietUntilMs: Long): Boolean {
        if (speechActive) return false
        return nowMs >= quietUntilMs
    }

    fun quietUntil(speechEndedAtMs: Long): Long = speechEndedAtMs + TAIL_MS
}

private fun VoiceCommand.toAction(): VoiceAction = when (this) {
    VoiceCommand.START -> VoiceAction.START
    VoiceCommand.STOP -> VoiceAction.STOP
    VoiceCommand.PAUSE -> VoiceAction.PAUSE
    VoiceCommand.RESUME -> VoiceAction.RESUME
    VoiceCommand.WHAT_IS_AHEAD -> VoiceAction.WHAT_IS_AHEAD
    VoiceCommand.REPEAT -> VoiceAction.REPEAT
    VoiceCommand.LOUDER -> VoiceAction.LOUDER
    VoiceCommand.QUIETER -> VoiceAction.QUIETER
    VoiceCommand.FASTER -> VoiceAction.FASTER
    VoiceCommand.SLOWER -> VoiceAction.SLOWER
    VoiceCommand.CHANGE_LANGUAGE -> VoiceAction.CHANGE_LANGUAGE
    VoiceCommand.HELP -> VoiceAction.HELP
    VoiceCommand.START_COLLECTING -> VoiceAction.START_COLLECTING
    VoiceCommand.STOP_COLLECTING -> VoiceAction.STOP_COLLECTING
    VoiceCommand.MISSED -> VoiceAction.MISSED
}
