package com.jisr.transfer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.jisr.transfer.core.Crypto
import com.jisr.transfer.core.DeclinedException
import com.jisr.transfer.core.Handshake
import com.jisr.transfer.core.Progress
import com.jisr.transfer.core.QrPayload
import com.jisr.transfer.core.Receiver
import com.jisr.transfer.core.SecureChannel
import com.jisr.transfer.core.SendItem
import com.jisr.transfer.core.Sender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture

/**
 * محرك النقل على مستوى العملية (وليس الشاشة): يستمر مع إغلاق الشاشة أو الخروج من التطبيق
 * طالما خدمة المقدمة (TransferService) تُبقي العملية حية. كل خطأ يتحول إلى شاشة فشل لا إلى انهيار.
 */
object TransferHub {
    private lateinit var app: Application

    private val _ui = MutableStateFlow<Ui>(Ui.Home)
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()
    private val _crash = MutableStateFlow<String?>(null)
    val crash: StateFlow<String?> = _crash.asStateFlow()
    private val _fast = MutableStateFlow(true)
    val fast: StateFlow<Boolean> = _fast.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> fail(e) })
    private var job: Job? = null

    @Volatile private var closer: (() -> Unit)? = null

    @Volatile private var gen = 0
    private var confirm: CompletableFuture<Boolean>? = null
    private var lastFiles: List<PickedFile> = emptyList()

    fun init(a: Application) {
        app = a
        _history.value = History.load(a)
        _crash.value = CrashLog.read(a)
        _fast.value = a.getSharedPreferences("jisr", Context.MODE_PRIVATE).getBoolean("fast", true)
    }

    fun setFast(v: Boolean) {
        _fast.value = v
        app.getSharedPreferences("jisr", Context.MODE_PRIVATE).edit().putBoolean("fast", v).apply()
    }

    fun clearCrash() {
        CrashLog.clear(app)
        _crash.value = null
    }

    // ---------- اختيار الملفات ----------
    fun setFiles(uris: List<Uri>) {
        scope.launch {
            val files = uris.mapNotNull { describe(it) }
            if (files.isEmpty()) {
                _ui.value = Ui.Failed("لا يمكن إرسال هذه الملفات (حجمها غير معروف أو لا يمكن قراءتها)")
            } else {
                _ui.value = Ui.Pick(files, uris.size - files.size)
            }
        }
    }

    private fun describe(uri: Uri): PickedFile? {
        val cr = app.contentResolver
        // إبقاء إذن القراءة حيًا أثناء النقل في الخلفية
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name: String? = null
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) size = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size < 0) return null
        return PickedFile(uri, name ?: uri.lastPathSegment ?: "file", size)
    }

    // ---------- الإرسال ----------
    fun startSend() {
        val files = (_ui.value as? Ui.Pick)?.files ?: return
        runSend(files)
    }

    /** تبديل بين الوضع السريع والمتوافق أثناء انتظار المستقبِل. */
    fun switchMode() {
        val files = lastFiles
        if (files.isEmpty()) return
        setFast(!_fast.value)
        gen++
        job?.cancel()
        closer?.invoke()
        closer = null
        runSend(files)
    }

    private fun runSend(files: List<PickedFile>) {
        lastFiles = files
        val myGen = ++gen
        _ui.value = Ui.Busy("جارٍ تجهيز الاتصال…")
        job = scope.launch {
            var locks: PerfLocks? = null
            var host: WifiHost? = null
            var server: ServerSocket? = null
            var sock: Socket? = null
            val close: () -> Unit = {
                runCatching { server?.close() }
                runCatching { sock?.close() }
                runCatching { host?.stop() }
                Unit
            }
            closer = close
            try {
                locks = runCatching { PerfLocks(app) }.getOrNull()
                val fastNow = _fast.value
                var h: WifiHost = if (fastNow) P2pHost(app) else HotspotHost(app)
                host = h
                var label = if (fastNow) "الوضع السريع 5GHz" else "الوضع المتوافق"
                _ui.value = Ui.Busy(if (fastNow) "جارٍ تشغيل شبكة 5GHz المباشرة…" else "جارٍ تشغيل نقطة الاتصال…")
                val info = try {
                    h.start()
                } catch (e: Exception) {
                    if (!fastNow || !isActive) throw e
                    runCatching { h.stop() }
                    h = HotspotHost(app)
                    host = h
                    label = "الوضع المتوافق (تعذّر الوضع السريع)"
                    h.start()
                }
                val srv = ServerSocket().apply {
                    receiveBufferSize = 1 shl 20
                    reuseAddress = true
                    soTimeout = 180_000
                    bind(InetSocketAddress(0))
                }
                server = srv
                val psk = Crypto.random(32)
                val payload = QrPayload(info.ssid, info.pass, info.sec, srv.localPort, psk).encode()
                val bmp = BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 800, 800)
                _ui.value = Ui.Waiting(bmp, label)

                val (s, ch) = try {
                    acceptAuthenticated(srv, psk)
                } catch (e: SocketTimeoutException) {
                    throw IOException("انتهت مهلة الانتظار (3 دقائق) دون اتصال المستقبِل")
                }
                sock = s
                s.soTimeout = 300_000
                val items = files.map { f ->
                    SendItem(f.name, f.size) {
                        app.contentResolver.openInputStream(f.uri) ?: throw IOException("تعذّر فتح ${f.name}")
                    }
                }
                val total = files.sumOf { it.size }
                _ui.value = Ui.Busy("بانتظار موافقة المستقبِل…")
                val t0 = System.nanoTime()
                val out = Sender.send(ch, items, progressSink(true))
                val secs = (System.nanoTime() - t0) / 1e9
                val avg = if (secs > 0) (total / secs).toLong() else 0L
                val bad = if (out.failed.isEmpty()) "" else "\nفشل التحقق: ${out.failed.joinToString("، ")}"
                if (out.ok > 0) _history.value = History.add(app, HistoryItem(System.currentTimeMillis(), true, out.ok, total, avg))
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
                close()
                if (gen == myGen) closer = null
                locks?.release()
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
                return s to ch
            } catch (e: Exception) {
                runCatching { s.close() }
                if (srv.isClosed) throw e
            }
        }
    }

    // ---------- الاستقبال ----------
    fun startReceive(text: String) {
        val p = try {
            QrPayload.decode(text.trim())
        } catch (e: Exception) {
            _ui.value = Ui.Failed("هذا الرمز ليس من تطبيق جسر")
            return
        }
        val myGen = ++gen
        _ui.value = Ui.Busy("جارٍ الاتصال بشبكة المرسِل… (وافق على طلب النظام إن ظهر)")
        job = scope.launch {
            var locks: PerfLocks? = null
            var link: WifiLink? = null
            var sock: Socket? = null
            val close: () -> Unit = {
                runCatching { sock?.close() }
                runCatching { link?.close() }
                Unit
            }
            closer = close
            try {
                locks = runCatching { PerfLocks(app) }.getOrNull()
                val l = joinWifi(app, p.ssid, p.pass, p.sec)
                link = l
                val host = gatewayOf(app, l.network) ?: throw IOException("تعذّر تحديد عنوان المرسِل")
                _ui.value = Ui.Busy("جارٍ تأمين الاتصال…")
                val s = connectWithRetry(l, host, p.port)
                sock = s
                val ch = Handshake.asReceiver(s.getInputStream(), s.getOutputStream(), p.psk)
                s.soTimeout = 60_000
                var total = 0L
                val t0 = System.nanoTime()
                val out = Receiver.receive(
                    ch,
                    MediaStoreSink(app),
                    { files ->
                        total = files.sumOf { it.size }
                        checkSpace(total)
                        val fut = CompletableFuture<Boolean>()
                        confirm = fut
                        _ui.value = Ui.Confirm(files, total)
                        val ok = fut.get()
                        confirm = null
                        if (ok) _ui.value = Ui.Busy("جارٍ الاستلام…")
                        ok
                    },
                    progressSink(false),
                )
                val secs = (System.nanoTime() - t0) / 1e9
                val avg = if (secs > 0) (total / secs).toLong() else 0L
                val bad = if (out.failed.isEmpty()) "" else "\nحُذفت لفشل التحقق: ${out.failed.joinToString("، ")}"
                if (out.ok > 0) _history.value = History.add(app, HistoryItem(System.currentTimeMillis(), false, out.ok, total, avg))
                _ui.value = Ui.Done(
                    false,
                    "تم استلام ${out.ok} ملف (${fmtBytes(total)}) وتم التحقق من سلامتها$bad\nالمتوسط: ${fmtBytes(avg)}/ث خلال ${"%.1f".format(secs)} ث",
                    "Download/Jisr",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeclinedException) {
                if (isActive) _ui.value = Ui.Home
            } catch (e: Exception) {
                if (isActive) fail(e)
            } finally {
                close()
                if (gen == myGen) closer = null
                locks?.release()
            }
        }
    }

    fun accept(ok: Boolean) {
        confirm?.complete(ok)
    }

    @Suppress("DEPRECATION")
    private fun checkSpace(total: Long) {
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull() ?: return
        if (total + (64L shl 20) > free) {
            throw IOException("المساحة غير كافية: تحتاج ${fmtBytes(total)} والمتاح ${fmtBytes(free)}")
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

    private fun friendly(e: Throwable): String = when (e) {
        is SecurityException -> e.message ?: "خطأ أمني"
        is SocketTimeoutException -> "انتهت مهلة الاتصال — قرّب الجهازين وأعد المحاولة"
        is EOFException, is SocketException -> "انقطع الاتصال بين الجهازين"
        is OutOfMemoryError -> "نفدت الذاكرة"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun fail(e: Throwable) {
        _ui.value = Ui.Failed(friendly(e))
    }

    fun fail(msg: String) {
        _ui.value = Ui.Failed(msg)
    }

    fun cancel() {
        gen++
        job?.cancel()
        job = null
        confirm?.complete(false)
        confirm = null
        closer?.invoke()
        closer = null
        _ui.value = Ui.Home
    }
}
