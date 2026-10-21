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
        assertEquals(30, PhraseIds.objects.size)
        assertFalse("airplane" in PhraseIds.objects)
        assertFalse("???" in PhraseIds.objects)
        assertTrue("person" in PhraseIds.objects)
        assertTrue("potted plant" in PhraseIds.objects)
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
            if (locale != "values") {
                assertTrue("$locale draft comment", text.contains("DRAFT") && text.contains("native-speaker review"))
                assertTrue("$locale draft banner", entries.getValue("draft_banner").isNotBlank())
                assertTrue("$locale voice message", entries.getValue("voice_missing_screen").length > 20)
            }
        }
    }

    @Test
    fun englishFileHasACompleteSentenceForEachMissingVoice() {
        val entries = stringEntries(File(resRoot(), "values/strings.xml").readText())
        for (name in PhraseIds.englishVoiceMissing) {
            val value = entries[name]
            assertTrue(name, value != null && value.length > 40 && !value.contains("%"))
        }
        val picker = listOf("lang_hi", "lang_ta", "lang_bn", "lang_sw", "lang_es")
        for (name in picker) {
            assertTrue(entries.getValue(name).contains("needs native-speaker review"))
        }
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not find res directory from ${File(".").absolutePath}")
    }

    private fun stringEntries(xml: String): Map<String, String> {
        val regex = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(xml).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }
}
