package org.visualaid.obstacleguide.voice

import android.content.Context
import org.visualaid.obstacleguide.logic.CommandLexicon
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The wake-word model is stored in the app and unpacked on the phone.
 * It is the small English Vosk model, Apache License 2.0, from Alpha Cephei.
 */
object VoskModels {
    const val ASSET_ZIP = "vosk-model-small-en-us-0.15.zip"
    const val WORDS_ASSET = "vosk-small-en-words.txt"
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

    fun modelDirectory(context: Context): File {
        val root = File(context.applicationContext.filesDir, "vosk")
        val model = File(root, MODEL_DIR_NAME)
        val ready = File(model, "am/final.mdl")
        if (ready.isFile) return model
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        context.applicationContext.assets.open(ASSET_ZIP).use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.contains("..")) {
                        val out = File(root, name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { output -> zip.copyTo(output) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (!ready.isFile) {
            error("Vosk model did not unpack")
        }
        return model
    }
}

object VoskVocab {
    @Volatile
    private var words: Set<String>? = null

    fun words(context: Context): Set<String> {
        words?.let { return it }
        val loaded = context.applicationContext.assets.open(VoskModels.WORDS_ASSET)
            .bufferedReader()
            .useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        words = loaded
        return loaded
    }

    fun allows(context: Context, phrase: String): Boolean {
        val normalized = CommandLexicon.normalize(phrase)
        if (normalized.isEmpty()) return false
        val vocab = words(context)
        return normalized.split(' ').all { it in vocab }
    }
}
