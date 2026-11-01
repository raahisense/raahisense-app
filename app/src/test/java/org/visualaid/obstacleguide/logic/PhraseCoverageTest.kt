package org.visualaid.obstacleguide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every locale must contain the same fixed phrases. Warnings are patterns
 * with a single object placeholder, not sentences built in code.
 */
class PhraseCoverageTest {

    @Test
    fun templates_coverEveryDistanceAndSide() {
        val pairs = PhraseIds.templates.map { it.closeness to it.position }.toSet()
        for (closeness in Closeness.entries) {
            for (position in HorizontalPosition.entries) {
                assertTrue("$closeness $position", (closeness to position) in pairs)
            }
        }
        assertEquals(9, PhraseIds.templates.size)
        assertEquals(36, PhraseIds.objects.size)
        assertFalse("airplane" in PhraseIds.objects)
        assertFalse("???" in PhraseIds.objects)
        assertTrue("person" in PhraseIds.objects)
        assertTrue("cat" in PhraseIds.objects)
        assertTrue("chair" in PhraseIds.objects)
        assertTrue("potted plant" in PhraseIds.objects)
        assertTrue("door" in PhraseIds.objects)
        assertTrue("wall" in PhraseIds.objects)
        assertTrue("shoe" in PhraseIds.objects)
        assertTrue("bag" in PhraseIds.objects)
        assertTrue("toy" in PhraseIds.objects)
        assertTrue("bin" in PhraseIds.objects)
        assertTrue("appliance" in PhraseIds.objects)
        assertTrue("cabinet" in PhraseIds.objects)
        assertTrue("pothole" in PhraseIds.objects)
        assertTrue("auto_rickshaw" in PhraseIds.objects)
    }

    @Test
    fun everyLocaleHasTheSamePhrasePieces() {
        val root = resRoot()
        val locales = listOf(
            "values",
            "values-hi",
            "values-ta",
            "values-bn",
            "values-sw",
            "values-es",
            "values-kn",
            "values-te",
        )
        for (locale in locales) {
            val file = File(root, "$locale/strings.xml")
            assertTrue("${file.path} missing", file.isFile)
            val text = file.readText()
            val entries = stringEntries(text)
            for (name in PhraseIds.requiredInEveryLocale) {
                assertTrue("$locale missing $name", name in entries)
            }
            for (template in PhraseIds.templates) {
                val value = entries.getValue(template.resName)
                assertEquals("$locale ${template.resName}", 1, Regex("%1\\\$s").findAll(value).count())
            }
            for (name in PhraseIds.objects.values) {
                assertFalse("$locale $name", entries.getValue(name).contains("%"))
            }
            for (name in PhraseIds.depthPhrases) {
                val value = entries.getValue(name)
                assertFalse("$locale $name", value.contains("%"))
                assertTrue("$locale $name", value.length > 8)
            }
            if (locale != "values") {
                assertTrue("$locale draft comment", text.contains("DRAFT") && text.contains("native-speaker review"))
                assertTrue("$locale draft banner", entries.getValue("draft_banner").isNotBlank())
                assertTrue("$locale voice message", entries.getValue("voice_missing_screen").length > 20)
            }
            val englishOnly = listOf(
                "cmd_stop",
                "cmd_collect_start",
                "cmd_collect_stop",
                "cmd_missed",
                "cmd_wrong",
                "cmd_send_log",
            )
            val english = stringEntries(File(root, "values/strings.xml").readText())
            for (name in englishOnly) {
                assertEquals("$locale $name stays English", english.getValue(name), entries.getValue(name))
            }
            val help = entries.getValue("help_commands")
            assertTrue("$locale help names stop", help.contains("stop"))
            assertTrue("$locale help names missed", help.contains("missed"))
            assertTrue("$locale help names wrong", help.contains("wrong"))
            assertTrue("$locale help names collecting", help.contains("start collecting"))
        }
    }

    @Test
    fun englishFileHasACompleteSentenceForEachMissingVoice() {
        val entries = stringEntries(File(resRoot(), "values/strings.xml").readText())
        for (name in PhraseIds.englishVoiceMissing) {
            val value = entries[name]
            assertTrue(name, value != null && value.length > 40 && !value.contains("%"))
        }
        val picker = listOf("lang_hi", "lang_es", "lang_kn", "lang_te", "lang_ta", "lang_bn", "lang_sw")
        for (name in picker) {
            assertTrue(entries.getValue(name).contains("needs native-speaker review"))
        }
        assertEquals("Unknown object ahead, very close", entries.getValue("depth_unknown_very_close_ahead"))
        assertEquals("Unknown object on the left, very close", entries.getValue("depth_unknown_very_close_left"))
        assertEquals("Wall ahead, very close", entries.getValue("depth_wall_very_close"))
        assertEquals("Wall ahead, near", entries.getValue("depth_wall_near"))
        assertEquals("Something approaching on your left", entries.getValue("depth_approaching_left"))
        assertEquals("Something approaching ahead", entries.getValue("depth_approaching_ahead"))
        assertEquals("Something approaching on your right", entries.getValue("depth_approaching_right"))
        assertEquals("It's dark, turning on the light.", entries.getValue("light_turning_on"))
        assertEquals("Too dark to see clearly, please use caution.", entries.getValue("light_too_dark"))
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not find res directory from ${File(".").absolutePath}")
    }

    private fun stringEntries(xml: String): Map<String, String> {
        val regex = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(xml).associate { match ->
            val value = match.groupValues[2].trim()
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
            match.groupValues[1] to value
        }
    }
}
