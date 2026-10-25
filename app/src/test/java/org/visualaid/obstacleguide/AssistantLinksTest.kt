package org.visualaid.obstacleguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantLinksTest {

    @Test
    fun startAndStopHosts() {
        assertEquals(AssistantCommand.START, AssistantLinks.command("raahisense", "start", null, null))
        assertEquals(AssistantCommand.START, AssistantLinks.command("RAAHISENSE", "Guidance", null, "/"))
        assertEquals(AssistantCommand.STOP, AssistantLinks.command("raahisense", "stop", null, ""))
        assertEquals(AssistantCommand.STOP, AssistantLinks.command("raahisense", "close", null, null))
        assertEquals(AssistantCommand.STOP, AssistantLinks.command("raahisense", "quit", null, null))
        assertEquals(AssistantCommand.STOP, AssistantLinks.command("raahisense", "EXIT", null, null))
    }

    @Test
    fun rejectsAnythingExceptStartOrFullStop() {
        assertNull(AssistantLinks.command("https", "stop", null, null))
        assertNull(AssistantLinks.command("raahisense", "stop", "user", null))
        assertNull(AssistantLinks.command("raahisense", "stop.evil.com", null, null))
        assertNull(AssistantLinks.command("raahisense", "start", null, "/secret"))
        assertNull(AssistantLinks.command("raahisense", "pause", null, null))
        assertNull(AssistantLinks.command("raahisense", "", null, null))
        assertNull(AssistantLinks.command(null, "stop", null, null))
        assertNull(AssistantLinks.command("raahisense", "start guidance", null, null))
    }

    @Test
    fun shortcutsXmlDeclaresLauncherLabelsAndFullStopLink() {
        val xml = readProjectFile("src/main/res/xml/shortcuts.xml", "app/src/main/res/xml/shortcuts.xml")
        assertTrue(xml.contains("actions.intent.OPEN_APP_FEATURE"))
        assertTrue(xml.contains("custom.actions.intent.START_GUIDANCE"))
        assertTrue(xml.contains("custom.actions.intent.STOP_RAAHISENSE"))
        assertTrue(xml.contains("custom.actions.intent.CLOSE_RAAHISENSE"))
        assertTrue(xml.contains("android:shortcutId=\"start_guidance\""))
        assertTrue(xml.contains("android:shortcutId=\"stop_raahisense\""))
        assertTrue(xml.contains("@string/shortcut_start"))
        assertTrue(xml.contains("@string/shortcut_stop"))
        assertTrue(xml.contains("raahisense://start"))
        assertTrue(xml.contains("raahisense://stop"))
        assertTrue(xml.contains("raahisense://close"))
        assertTrue(xml.contains("AssistantActionActivity"))

        val manifest = readProjectFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".AssistantActionActivity\""))
        assertTrue(manifest.contains("android:scheme=\"raahisense\""))
        assertTrue(manifest.contains("android:host=\"stop\""))
        assertTrue(manifest.contains("android:host=\"close\""))
        assertTrue(manifest.contains("@xml/shortcuts"))

        val english = readProjectFile("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
        assertTrue(english.contains(">Start guidance<"))
        assertTrue(english.contains(">Stop RaahiSense<"))
    }

    private fun readProjectFile(vararg candidates: String): String {
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("Missing ${candidates.toList()} from ${File(".").absolutePath}")
        return file.readText()
    }
}
