package org.visualaid.obstacleguide.voice

import android.content.Context
import org.visualaid.obstacleguide.logic.CommandLexicon
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * One offline Vosk model. Hindi and Spanish are the small Apache-2.0 models.
 * Telugu's small model is not bundled: its published error rate is about 88 percent.
 * Tamil and Kannada have no Vosk model.
 */
data class EarModel(
    val tag: String,
    val assetZip: String,
    val wordsAsset: String,
    val directoryName: String,
) {
    companion object {
        val ENGLISH = EarModel(
            tag = "en",
            assetZip = "vosk-model-small-en-us-0.15.zip",
            wordsAsset = "vosk-small-en-words.txt",
            directoryName = "vosk-model-small-en-us-0.15",
        )
        val HINDI = EarModel(
            tag = "hi",
            assetZip = "vosk-model-small-hi-0.22.zip",
            wordsAsset = "vosk-small-hi-words.txt",
            directoryName = "vosk-model-small-hi-0.22",
        )
        val SPANISH = EarModel(
            tag = "es",
            assetZip = "vosk-model-small-es-0.42.zip",
            wordsAsset = "vosk-small-es-words.txt",
            directoryName = "vosk-model-small-es-0.42",
        )

        fun forLanguage(tag: String): EarModel? = when (tag) {
            "hi" -> HINDI
            "es" -> SPANISH
            else -> null
        }
    }
}

/**
 * Wake-word models are stored in the app and unpacked on the phone.
 * Unpacking one language leaves the other models in place.
 */
object VoskModels {
    fun modelDirectory(context: Context, spec: EarModel = EarModel.ENGLISH): File {
        val root = File(context.applicationContext.filesDir, "vosk")
        val model = File(root, spec.directoryName)
        val ready = File(model, "am/final.mdl")
        if (ready.isFile) return model
        if (model.exists()) model.deleteRecursively()
        root.mkdirs()
        context.applicationContext.assets.open(spec.assetZip).use { raw ->
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
            error("Vosk model did not unpack: ${spec.directoryName}")
        }
        return model
    }
}

object VoskVocab {
    private val cache = ConcurrentHashMap<String, Set<String>>()

    fun words(context: Context, asset: String = EarModel.ENGLISH.wordsAsset): Set<String> {
        cache[asset]?.let { return it }
        val loaded = context.applicationContext.assets.open(asset)
            .bufferedReader()
            .useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        cache[asset] = loaded
        return loaded
    }

    /** True when every word is in the English list, or in the selected language model. */
    fun allows(context: Context, phrase: String, languageTag: String = "en"): Boolean {
        if (phraseIn(words(context, EarModel.ENGLISH.wordsAsset), phrase)) return true
        val extra = EarModel.forLanguage(languageTag) ?: return false
        return phraseIn(words(context, extra.wordsAsset), phrase)
    }

    private fun phraseIn(vocab: Set<String>, phrase: String): Boolean {
        val normalized = CommandLexicon.normalize(phrase)
        if (normalized.isEmpty()) return false
        return normalized.split(' ').all { it in vocab }
    }
}
