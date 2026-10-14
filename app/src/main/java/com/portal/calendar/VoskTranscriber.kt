package com.portal.calendar

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline speech-to-text with the English Vosk model bundled in the APK. Nothing leaves the
 * device. The model needs ~300 MB of RAM, so it is loaded when the mic is tapped (while the
 * user is still talking) and released as soon as the text is back.
 */
object VoskTranscriber {
    private const val ASSET_DIR = "model-en-us"
    private const val MODEL_VERSION = "small-en-us-0.15"
    private const val WAV_HEADER = 44 // VoiceInput writes a plain 44-byte header, 16 kHz mono PCM16
    private var model: Model? = null

    /** Vosk reads real files, so the bundled model is copied out of the APK once. */
    @Synchronized
    private fun unpacked(ctx: Context): File {
        val dir = File(ctx.filesDir, "vosk/$MODEL_VERSION")
        val done = File(dir, ".complete")
        if (done.exists()) return dir
        dir.deleteRecursively()
        copyAssets(ctx.assets, ASSET_DIR, dir)
        done.writeText("ok")
        return dir
    }

    private fun copyAssets(am: AssetManager, from: String, to: File) {
        val kids = am.list(from) ?: emptyArray()
        if (kids.isEmpty()) {
            to.parentFile?.mkdirs()
            am.open(from).use { i -> to.outputStream().use { o -> i.copyTo(o) } }
        } else {
            to.mkdirs()
            for (k in kids) copyAssets(am, "$from/$k", File(to, k))
        }
    }

    @Synchronized
    fun load(ctx: Context) {
        if (model == null) model = Model(unpacked(ctx).path)
    }

    @Synchronized
    fun close() {
        model?.close()
        model = null
    }

    /** Text for a WAV from [VoiceInput]; empty when nothing was understood. */
    @Synchronized
    fun transcribe(wav: ByteArray, grammar: String? = null): String {
        val m = model ?: throw IllegalStateException("the speech model isn't loaded")
        (if (grammar == null) Recognizer(m, 16000f) else Recognizer(m, 16000f, grammar)).use { r ->
            var off = WAV_HEADER
            while (off < wav.size) {
                val n = minOf(8000, wav.size - off)
                r.acceptWaveForm(wav.copyOfRange(off, off + n), n)
                off += n
            }
            return JSONObject(r.finalResult).optString("text").trim()
        }
    }
}
