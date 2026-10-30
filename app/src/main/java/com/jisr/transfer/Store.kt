package com.jisr.transfer

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** يحفظ آخر عطل في ملف ليظهر في الشاشة الرئيسية ويمكن نسخه وإرساله للمطوّر. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText(
                    "الوقت: $time\nالجهاز: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})\n" +
                        "الخيط: ${t.name}\n${e.stackTraceToString()}",
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String? = runCatching { file(c).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(c: Context) {
        runCatching { file(c).delete() }
    }
}

class HistoryItem(val time: Long, val sending: Boolean, val count: Int, val bytes: Long, val avgBps: Long)

object History {
    private fun file(c: Context) = File(c.filesDir, "history.tsv")

    fun load(c: Context): List<HistoryItem> = runCatching {
        file(c).readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size == 5) HistoryItem(p[0].toLong(), p[1] == "S", p[2].toInt(), p[3].toLong(), p[4].toLong()) else null
        }
    }.getOrDefault(emptyList())

    fun add(c: Context, item: HistoryItem): List<HistoryItem> {
        val all = (listOf(item) + load(c)).take(20)
        runCatching {
            file(c).writeText(all.joinToString("\n") { x -> "${x.time}\t${if (x.sending) "S" else "R"}\t${x.count}\t${x.bytes}\t${x.avgBps}" })
        }
        return all
    }
}
