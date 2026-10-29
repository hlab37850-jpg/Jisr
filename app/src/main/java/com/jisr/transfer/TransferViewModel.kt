package com.jisr.transfer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.jisr.transfer.core.Crypto
import com.jisr.transfer.core.Handshake
import com.jisr.transfer.core.Progress
import com.jisr.transfer.core.QrPayload
import com.jisr.transfer.core.Receiver
import com.jisr.transfer.core.SecureChannel
import com.jisr.transfer.core.SendItem
import com.jisr.transfer.core.Sender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class TransferViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow<Ui>(Ui.Home)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var job: Job? = null
    private var closer: (() -> Unit)? = null
    private val ctx get() = getApplication<Application>()

    // ---------- اختيار الملفات ----------
    fun setFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { describe(it) }
            if (files.isEmpty()) {
                _ui.value = Ui.Failed("لا يمكن إرسال هذه الملفات (حجمها غير معروف)")
            } else {
                _ui.value = Ui.Pick(files, uris.size - files.size)
            }
        }
    }

    private fun describe(uri: Uri): PickedFile? {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) size = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size < 0) return null
        return PickedFile(uri, name ?: uri.lastPathSegment ?: "file", size)
    }

    // ---------- الإرسال ----------
    fun startSend() {
        val files = (ui.value as? Ui.Pick)?.files ?: return
        job = viewModelScope.launch(Dispatchers.IO) {
            val hs = HotspotHost(ctx)
            val locks = PerfLocks(ctx)
            var server: ServerSocket? = null
            var sock: Socket? = null
            closer = {
                runCatching { server?.close() }
                runCatching { sock?.close() }
                hs.stop()
            }
            try {
                _ui.value = Ui.Busy("جارٍ تشغيل نقطة الاتصال المحلية…")
                val info = hs.start()
                val srv = ServerSocket().apply {
                    receiveBufferSize = 1 shl 20
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
                server = srv
                val psk = Crypto.random(32)
                val payload = QrPayload(info.ssid, info.pass, info.sec, srv.localPort, psk).encode()
                val bmp = BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 800, 800)
                _ui.value = Ui.Waiting(bmp)

                val (s, ch) = acceptAuthenticated(srv, psk)
                sock = s
                val items = files.map { f ->
                    SendItem(f.name, f.size) {
                        ctx.contentResolver.openInputStream(f.uri) ?: throw IOException("تعذّر فتح ${f.name}")
                    }
                }
                val total = files.sumOf { it.size }
                val t0 = System.nanoTime()
                val out = Sender.send(ch, items, progressSink(true))
                val secs = (System.nanoTime() - t0) / 1e9
                val avg = if (secs > 0) (total / secs).toLong() else 0L
                val bad = if (out.failed.isEmpty()) "" else "\nفشل التحقق: ${out.failed.joinToString("، ")}"
                _ui.value = Ui.Done(
                    true,
                    "تم إرسال ${out.ok} من ${files.size} ملف (${fmtBytes(total)})$bad\nالمتوسط: ${fmtBytes(avg)}/ث خلال ${"%.1f".format(secs)} ث",
                    null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) fail(e)
            } finally {
                closer?.invoke()
                closer = null
                locks.release()
            }
        }
    }

    private fun acceptAuthenticated(srv: ServerSocket, psk: ByteArray): Pair<Socket, SecureChannel> {
        while (true) {
            val s = srv.accept()
            try {
                tune(s)
                s.soTimeout = 15_000
                val ch = Handshake.asSender(s.getInputStream(), s.getOutputStream(), psk)
                s.soTimeout = 60_000
                return s to ch
            } catch (e: Exception) {
                runCatching { s.close() }
                if (srv.isClosed) throw e
            }
        }
    }

    // ---------- الاستقبال ----------
    fun onScanned(text: String) {
        val p = try {
            QrPayload.decode(text.trim())
        } catch (e: Exception) {
            _ui.value = Ui.Failed("هذا الرمز ليس من تطبيق جسر")
            return
        }
        job = viewModelScope.launch(Dispatchers.IO) {
            val locks = PerfLocks(ctx)
            var link: WifiLink? = null
            var sock: Socket? = null
            closer = {
                runCatching { sock?.close() }
                link?.close()
            }
            try {
                _ui.value = Ui.Busy("جارٍ الاتصال بشبكة المرسِل… (وافق على طلب النظام إن ظهر)")
                val l = joinWifi(ctx, p.ssid, p.pass, p.sec)
                link = l
                val host = gatewayOf(ctx, l.network) ?: throw IOException("تعذّر تحديد عنوان المرسِل")
                _ui.value = Ui.Busy("جارٍ تأمين الاتصال…")
                val s = connectWithRetry(l, host, p.port)
                sock = s
                val ch = Handshake.asReceiver(s.getInputStream(), s.getOutputStream(), p.psk)
                s.soTimeout = 60_000
                var total = 0L
                val t0 = System.nanoTime()
                val out = Receiver.receive(
                    ch,
                    MediaStoreSink(ctx),
                    { files ->
                        total = files.sumOf { it.size }
                        _ui.value = Ui.Busy("استلام ${files.size} ملف (${fmtBytes(total)})…")
                    },
                    progressSink(false),
                )
                val secs = (System.nanoTime() - t0) / 1e9
                val avg = if (secs > 0) (total / secs).toLong() else 0L
                val bad = if (out.failed.isEmpty()) "" else "\nحُذفت لفشل التحقق: ${out.failed.joinToString("، ")}"
                _ui.value = Ui.Done(
                    false,
                    "تم استلام ${out.ok} ملف (${fmtBytes(total)}) وتم التحقق من سلامتها$bad\nالمتوسط: ${fmtBytes(avg)}/ث خلال ${"%.1f".format(secs)} ث",
                    "Download/Jisr",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) fail(e)
            } finally {
                closer?.invoke()
                closer = null
                locks.release()
            }
        }
    }

    private suspend fun connectWithRetry(link: WifiLink, host: String, port: Int): Socket {
        var last: Exception? = null
        repeat(10) {
            var s: Socket? = null
            try {
                s = link.network.socketFactory.createSocket()
                tune(s)
                s.connect(InetSocketAddress(host, port), 4000)
                return s
            } catch (e: Exception) {
                last = e
                runCatching { s?.close() }
                delay(700)
            }
        }
        throw IOException("تعذّر الاتصال بالمرسِل", last)
    }

    // ---------- أدوات ----------
    private fun tune(s: Socket) {
        s.tcpNoDelay = true
        s.keepAlive = true
        s.sendBufferSize = 1 shl 20
        s.receiveBufferSize = 1 shl 20
    }

    private fun progressSink(sending: Boolean): (Progress) -> Unit {
        var tSpeed = System.nanoTime()
        var bSpeed = 0L
        var bps = 0L
        var lastUi = 0L
        return { p ->
            val now = System.nanoTime()
            if (now - tSpeed >= 500_000_000L) {
                bps = (p.done - bSpeed) * 1_000_000_000L / (now - tSpeed)
                tSpeed = now
                bSpeed = p.done
            }
            if (now - lastUi > 120_000_000L || p.done >= p.total) {
                lastUi = now
                _ui.value = Ui.Running(sending, p, bps)
            }
        }
    }

    private fun fail(e: Throwable) {
        _ui.value = Ui.Failed(e.message ?: e.javaClass.simpleName)
    }

    fun fail(msg: String) {
        _ui.value = Ui.Failed(msg)
    }

    fun cancel() {
        job?.cancel()
        job = null
        closer?.invoke()
        closer = null
        _ui.value = Ui.Home
    }

    override fun onCleared() {
        job?.cancel()
        closer?.invoke()
    }
}
