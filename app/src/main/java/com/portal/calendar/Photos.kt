package com.portal.calendar

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/** Photos uploaded over Wi-Fi land in the shared Pictures folder, where the Immortal photo frame's "Local folder" source reads them. */
object Photos {
    private val EXT = setOf("jpg", "jpeg", "png", "webp")
    private const val MAX_BYTES = 15 * 1024 * 1024 // the server's body cap is 16 MB

    private fun dir() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

    /** Checks the file's own first bytes, not just its name, so only real images are written. */
    fun looksLikeImage(b: ByteArray): Boolean = b.size > 12 && (
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
        (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) ||
        (String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"))

    /** A safe file name: no folders, odd characters or hidden dots; an unused name is picked if it already exists. */
    fun safeName(raw: String, exists: (String) -> Boolean): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', "").lowercase()
        require(ext in EXT) { "only JPG, PNG or WebP photos" }
        val stem = base.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(60).ifEmpty { "photo" }
        var name = "$stem.$ext"
        var n = 1
        while (exists(name)) name = "$stem-${n++}.$ext"
        return name
    }

    fun save(ctx: Context, rawName: String, bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "that photo is over 15 MB" }
        require(looksLikeImage(bytes)) { "that file isn't a photo" }
        val d = dir().also { it.mkdirs() }
        val name = safeName(rawName) { File(d, it).exists() }
        val f = File(d, name)
        try { f.writeBytes(bytes) } catch (e: java.io.IOException) {
            throw IllegalArgumentException("can't write to the Portal's Pictures folder (storage permission needed)")
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
        return name
    }

    fun count(): Int = dir().listFiles { f -> f.extension.lowercase() in EXT }?.size ?: 0
}
