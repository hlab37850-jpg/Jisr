package com.jisr.transfer

import android.graphics.Bitmap
import android.net.Uri
import com.jisr.transfer.core.FileInfo
import com.jisr.transfer.core.Progress
import java.util.Locale

class PickedFile(val uri: Uri, val name: String, val size: Long)

sealed interface Ui {
    data object Home : Ui
    class Pick(val files: List<PickedFile>, val skipped: Int) : Ui
    class Waiting(val qr: Bitmap, val label: String) : Ui
    class Busy(val msg: String) : Ui
    class Confirm(val files: List<FileInfo>, val total: Long) : Ui
    class Running(val sending: Boolean, val p: Progress, val bps: Long) : Ui
    class Done(val sending: Boolean, val text: String, val where: String?) : Ui
    class Failed(val msg: String) : Ui
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, u[i])
}

fun fmtDuration(sec: Long): String = if (sec < 60) "$sec ث" else "${sec / 60} د ${sec % 60} ث"

fun etaText(p: Progress, bps: Long): String =
    if (bps > 0 && p.total > p.done) "المتبقي ≈ ${fmtDuration((p.total - p.done) / bps)}" else ""
