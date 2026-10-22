package org.visualaid.obstacleguide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    @Test
    fun englishCommands_matchTheDefaultPhrases() {
        assertEquals(VoiceCommand.START, CommandParser.parse("start"))
        assertEquals(VoiceCommand.START, CommandParser.parse("Please start now"))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("stop"))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("pause"))
        assertEquals(VoiceCommand.WHAT_IS_AHEAD, CommandParser.parse("what's in front of me"))
        assertEquals(VoiceCommand.WHAT_IS_AHEAD, CommandParser.parse("what is ahead"))
        assertEquals(VoiceCommand.REPEAT, CommandParser.parse("say again"))
        assertEquals(VoiceCommand.LOUDER, CommandParser.parse("volume up"))
        assertEquals(VoiceCommand.QUIETER, CommandParser.parse("quieter"))
        assertEquals(VoiceCommand.FASTER, CommandParser.parse("speak faster"))
        assertEquals(VoiceCommand.SLOWER, CommandParser.parse("slower"))
        assertEquals(VoiceCommand.CHANGE_LANGUAGE, CommandParser.parse("change language"))
        assertEquals(VoiceCommand.HELP, CommandParser.parse("help"))
    }

    @Test
    fun unknownEmptyAndWordPieces_doNotMatch() {
        assertNull(CommandParser.parse(""))
        assertNull(CommandParser.parse("   "))
        assertNull(CommandParser.parse("look at the sky"))
        assertNull(CommandParser.parse("[unk]"))
        assertNull(CommandParser.parse("restart"))
        assertNull(CommandParser.parse("stopping by the shop"))
    }

    @Test
    fun longestPhraseWins_andEarlierTieBreaks() {
        val lexicon = CommandLexicon.ENGLISH.withExtra(
            mapOf(
                VoiceCommand.HELP to listOf("change"),
                VoiceCommand.CHANGE_LANGUAGE to listOf("change language"),
            ),
        )
        assertEquals(VoiceCommand.CHANGE_LANGUAGE, CommandParser.parse("please change language", lexicon))
        assertEquals(
            VoiceCommand.START,
            CommandParser.parse("help me start"),
        )
    }

    @Test
    fun localePhrases_areAddedWithoutRemovingEnglish() {
        val lexicon = CommandLexicon.ENGLISH.withExtra(
            mapOf(VoiceCommand.START to CommandLexicon.pipes("शुरू|शुरू करो")),
        )
        assertEquals(VoiceCommand.START, CommandParser.parse("शुरू करो", lexicon))
        assertEquals(VoiceCommand.START, CommandParser.parse("START", lexicon))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("pause", lexicon))
    }

    @Test
    fun wakeWord_isRequiredOnlyWhileSpotting() {
        assertEquals(
            VoiceAction.WAKE_ONLY,
            VoiceInterpreter.interpret("Hey Guide", requireWake = true),
        )
        assertEquals(
            VoiceAction.START,
            VoiceInterpreter.interpret("hey guide start", requireWake = true),
        )
        assertEquals(
            VoiceAction.WHAT_IS_AHEAD,
            VoiceInterpreter.interpret("hey guide what's in front of me", requireWake = true),
        )
        assertEquals(
            VoiceAction.IGNORE,
            VoiceInterpreter.interpret("start", requireWake = true),
        )
        assertEquals(
            VoiceAction.START,
            VoiceInterpreter.interpret("start", requireWake = false),
        )
        assertEquals(
            VoiceAction.UNKNOWN,
            VoiceInterpreter.interpret("banana", requireWake = false),
        )
        assertEquals(
            VoiceAction.WAKE_ONLY,
            VoiceInterpreter.interpret("hey guide banana", requireWake = true),
        )
    }

    @Test
    fun customWakePhrase_replacesTheDefault() {
        assertEquals(
            VoiceAction.IGNORE,
            VoiceInterpreter.interpret("hey guide start", wakePhrase = "ok guide", requireWake = true),
        )
        assertEquals(
            VoiceAction.STOP,
            VoiceInterpreter.interpret("ok guide pause", wakePhrase = "ok guide", requireWake = true),
        )
    }

    @Test
    fun grammar_keepsOnlyPhrasesTheModelCanSay() {
        val vocab = setOf("hey", "guide", "start", "stop", "pause")
        val wake = CommandGrammar.wakePhrases(
            "Hey Guide",
            listOf("start", "शुरू करो", "stop"),
            vocab,
        )
        assertEquals(listOf("hey guide", "hey guide start", "hey guide stop"), wake)
        val json = CommandGrammar.json(wake)
        assertTrue(json.contains("\"hey guide\""))
        assertTrue(json.contains("\"[unk]\""))
        assertFalse(json.contains("शुरू"))
        assertTrue(CommandGrammar.inVocabulary("pause", vocab))
        assertFalse(CommandGrammar.inVocabulary("शुरू करो", vocab))
    }
}
