package com.jisr.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.jisr.transfer.core.Sink
import com.jisr.transfer.core.SinkFile
import java.io.IOException
import java.io.OutputStream
import java.net.URLConnection

/** يحفظ في Download/Jisr عبر MediaStore (لا حاجة لصلاحيات تخزين). الملف يبقى «معلّقًا» حتى يتحقق SHA-256. */
class MediaStoreSink(private val ctx: Context) : Sink {
    override fun create(name: String, size: Long): SinkFile {
        val cr = ctx.contentResolver
        val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Jisr")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("تعذّر إنشاء الملف في التنزيلات")
        val stream = cr.openOutputStream(uri, "w") ?: throw IOException("تعذّر فتح الملف للكتابة")
        return object : SinkFile {
            override val out: OutputStream = stream

            override fun commit(): String {
                stream.close()
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(uri, done, null, null)
                return "Download/Jisr/$name"
            }

            override fun discard() {
                runCatching { stream.close() }
                runCatching { cr.delete(uri, null, null) }
            }
        }
    }
}
