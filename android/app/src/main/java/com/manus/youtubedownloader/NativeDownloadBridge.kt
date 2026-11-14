package com.manus.youtubedownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Narrow bridge for saving completed web downloads; input arrives in bounded Base64 chunks. */
internal class NativeDownloadBridge(private val context: Context) {
    private data class PendingSave(val file: File, val stream: FileOutputStream, val name: String, val mime: String, var bytes: Long = 0)
    private val pending = ConcurrentHashMap<String, PendingSave>()

    @JavascriptInterface
    fun beginSave(filename: String, mimeType: String): String {
        val cleanName = filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(180).ifBlank { "download.bin" }
        val mime = mimeType.take(120).takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) }
            ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "download-$id.tmp")
        return try {
            pending[id] = PendingSave(file, FileOutputStream(file), cleanName, mime)
            id
        } catch (_: Exception) {
            file.delete()
            ""
        }
    }

    @JavascriptInterface
    fun appendSaveChunk(id: String, encoded: String): Boolean {
        val item = pending[id] ?: return false
        if (encoded.length > MAX_ENCODED_CHUNK) return false
        return synchronized(item) {
            try {
                val data = Base64.decode(encoded, Base64.DEFAULT)
                if (item.bytes + data.size > MAX_FILE_BYTES) return@synchronized false
                item.stream.write(data)
                item.bytes += data.size
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    @JavascriptInterface
    fun finishSave(id: String): String {
        val item = pending.remove(id) ?: return ""
        var uri: Uri? = null
        return try {
            synchronized(item) { item.stream.flush(); item.stream.close() }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IllegalStateException("Android 10 ou superior é necessário")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                put(MediaStore.Downloads.MIME_TYPE, item.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/YouTube Downloader")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não foi possível criar o arquivo em Downloads")
            context.contentResolver.openOutputStream(uri!!, "w")?.use { output -> item.file.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("Não foi possível gravar o arquivo em Downloads")
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri!!, ready, null, null)
            item.file.delete()
            item.name
        } catch (_: Exception) {
            uri?.let { context.contentResolver.delete(it, null, null) }
            item.file.delete()
            ""
        }
    }

    @JavascriptInterface
    fun abortSave(id: String) {
        pending.remove(id)?.let { item ->
            synchronized(item) { try { item.stream.close() } catch (_: Exception) {} }
            item.file.delete()
        }
    }

    fun close() {
        pending.keys.toList().forEach(::abortSave)
    }

    companion object {
        private const val MAX_ENCODED_CHUNK = 96 * 1024
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
    }
}
