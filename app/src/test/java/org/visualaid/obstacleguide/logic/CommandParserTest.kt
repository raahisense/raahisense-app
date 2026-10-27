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
        assertEquals(VoiceCommand.STOP, CommandParser.parse("stop app"))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("exit"))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("close"))
        assertEquals(VoiceCommand.PAUSE, CommandParser.parse("pause"))
        assertEquals(VoiceCommand.RESUME, CommandParser.parse("resume"))
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
        assertNull(CommandParser.parse("RaahiSense stopped"))
        assertNull(CommandParser.parse("closed"))
    }

    @Test
    fun warningSentence_containsStopButSpeechGateDropsIt() {
        assertEquals(VoiceCommand.STOP, CommandParser.parse("very close stop sign ahead"))
        assertFalse(RecognitionGate.accepts(speechActive = true, nowMs = 1_000, quietUntilMs = 0))
        assertFalse(RecognitionGate.accepts(speechActive = false, nowMs = 1_000, quietUntilMs = 1_400))
        assertTrue(RecognitionGate.accepts(speechActive = false, nowMs = 1_400, quietUntilMs = 1_400))
        assertEquals(1_400L, RecognitionGate.quietUntil(1_000))
        assertEquals(RecognitionGate.TAIL_MS, 400L)
        assertEquals(3_500L, RecognitionGate.correctionUntil(1_000))
        assertEquals(RecognitionGate.CORRECTION_WINDOW_MS, 2_500L)
        assertFalse(RecognitionGate.acceptsCorrection(speechActive = true, nowMs = 1_000, correctionUntilMs = 3_500))
        assertFalse(RecognitionGate.accepts(speechActive = false, nowMs = 1_200, quietUntilMs = 1_400))
        assertTrue(RecognitionGate.acceptsCorrection(speechActive = false, nowMs = 1_200, correctionUntilMs = 3_500))
        assertFalse(RecognitionGate.acceptsCorrection(speechActive = false, nowMs = 3_501, correctionUntilMs = 3_500))
    }

    @Test
    fun wrong_acceptsFalseAlarmAndWrongWarning_andBareOnlyAfterAWarning() {
        assertEquals(VoiceCommand.WRONG, CommandParser.parse("wrong"))
        assertEquals(VoiceCommand.WRONG, CommandParser.parse("false alarm"))
        assertEquals(VoiceCommand.WRONG, CommandParser.parse("wrong warning"))
        assertEquals(VoiceCommand.WRONG, CommandParser.parse("that was a wrong warning"))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("stop"))
        assertEquals(VoiceCommand.START, CommandParser.parse("start"))
        assertEquals(
            VoiceAction.IGNORE,
            VoiceInterpreter.interpret("wrong", requireWake = true),
        )
        assertEquals(
            VoiceAction.WRONG,
            VoiceInterpreter.interpret("wrong", requireWake = true, allowBareCorrection = true),
        )
        assertEquals(
            VoiceAction.WRONG,
            VoiceInterpreter.interpret("false alarm", requireWake = true, allowBareCorrection = true),
        )
        assertEquals(
            VoiceAction.MISSED,
            VoiceInterpreter.interpret("missed", requireWake = true, allowBareCorrection = true),
        )
        assertEquals(
            VoiceAction.IGNORE,
            VoiceInterpreter.interpret("stop", requireWake = true, allowBareCorrection = true),
        )
        assertEquals(
            VoiceAction.WRONG,
            VoiceInterpreter.interpret("hey guide wrong", requireWake = true),
        )
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
        assertEquals(VoiceCommand.PAUSE, CommandParser.parse("pause", lexicon))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("stop app", lexicon))
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
            VoiceAction.PAUSE,
            VoiceInterpreter.interpret("ok guide pause", wakePhrase = "ok guide", requireWake = true),
        )
        assertEquals(
            VoiceAction.STOP,
            VoiceInterpreter.interpret("hey guide exit", requireWake = true),
        )
        assertEquals(
            VoiceAction.RESUME,
            VoiceInterpreter.interpret("resume", requireWake = false),
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

    @Test
    fun everydayCommands_canBeHindiOrSpanish_andStopStaysEnglish() {
        val hindi = CommandLexicon.ENGLISH.withExtra(
            mapOf(
                VoiceCommand.START to listOf("शुरू", "शुरू करो"),
                VoiceCommand.PAUSE to listOf("विराम"),
                VoiceCommand.HELP to listOf("मदद"),
            ),
        )
        assertEquals(VoiceCommand.START, CommandParser.parse("शुरू करो", hindi))
        assertEquals(VoiceCommand.PAUSE, CommandParser.parse("विराम", hindi))
        assertEquals(VoiceCommand.HELP, CommandParser.parse("मदद", hindi))
        assertEquals(VoiceCommand.STOP, CommandParser.parse("stop", hindi))
        assertNull(CommandParser.parse("रुको", hindi))
        assertEquals(VoiceCommand.WRONG, CommandParser.parse("wrong", hindi))
        assertEquals(VoiceCommand.WRONG, CommandParser.parse("false alarm", hindi))
        assertEquals(
            VoiceAction.START,
            VoiceInterpreter.interpret(
                "हे गाइड शुरू",
                hindi,
                wakePhrase = "hey guide",
                requireWake = true,
                extraWakePhrases = SpokenEar.nativeWakes("hi"),
            ),
        )
        assertEquals(
            VoiceAction.IGNORE,
            VoiceInterpreter.interpret("शुरू", hindi, requireWake = true, extraWakePhrases = listOf("हे गाइड")),
        )
        val spanish = CommandLexicon.ENGLISH.withExtra(
            mapOf(VoiceCommand.START to listOf("empezar"), VoiceCommand.HELP to listOf("ayuda")),
        )
        assertEquals(VoiceCommand.START, CommandParser.parse("empezar", spanish))
        assertNull(CommandParser.parse("parar", spanish))
        assertEquals(
            VoiceAction.HELP,
            VoiceInterpreter.interpret(
                "oye guía ayuda",
                spanish,
                requireWake = true,
                extraWakePhrases = SpokenEar.nativeWakes("es"),
            ),
        )
        assertEquals(VoiceAction.STOP, EarChoice.prefer(VoiceAction.START, VoiceAction.STOP))
        assertEquals(VoiceAction.WRONG, EarChoice.prefer(VoiceAction.HELP, VoiceAction.WRONG))
        assertEquals(VoiceAction.START, EarChoice.prefer(VoiceAction.START, VoiceAction.UNKNOWN))
        assertEquals(VoiceAction.PAUSE, EarChoice.prefer(null, VoiceAction.PAUSE))
        assertTrue(SpokenEar.hasCommandModel("hi"))
        assertTrue(SpokenEar.hasCommandModel("es"))
        assertFalse(SpokenEar.hasCommandModel("kn"))
        assertFalse(SpokenEar.hasCommandModel("te"))
        assertFalse(SpokenEar.hasCommandModel("ta"))
        assertEquals(listOf("हे गाइड"), SpokenEar.nativeWakes("hi"))
        assertEquals(listOf("oye guía"), SpokenEar.nativeWakes("es"))
    }
}
