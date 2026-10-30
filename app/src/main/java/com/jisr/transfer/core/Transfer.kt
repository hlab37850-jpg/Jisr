package com.jisr.transfer.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

object T {
    const val MANIFEST = 1
    const val FILE_START = 2
    const val DATA = 3
    const val FILE_END = 4
    const val DONE = 5
    const val FILE_OK = 10
    const val FILE_BAD = 11
    const val ABORT = 12
    const val FINISH = 13
    const val READY = 14
}

class DeclinedException(msg: String = "تم رفض الاستقبال") : IOException(msg)

class SendItem(val name: String, val size: Long, val open: () -> InputStream)
data class FileInfo(val name: String, val size: Long)
data class Progress(val done: Long, val total: Long, val fileIndex: Int, val fileCount: Int, val fileName: String)
data class Outcome(val ok: Int, val failed: List<String>, val locations: List<String>)

interface SinkFile {
    val out: OutputStream
    fun commit(): String
    fun discard()
}

interface Sink {
    fun create(name: String, size: Long): SinkFile
}

private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

fun sanitizeName(raw: String): String {
    val s = raw.substringAfterLast('/').substringAfterLast('\\')
        .filter { !it.isISOControl() }
        .replace(ILLEGAL, "_")
        .trim().trimStart('.')
    val t = if (s.length > 150) s.takeLast(150) else s
    return t.ifEmpty { "file" }
}

/**
 * المرسِل: خيط قراءة (قرص + SHA-256) → طابور → خيط تشفير وإرسال.
 * لا ينتظر المرسِل تأكيدًا لكل ملف؛ يُرسل بلا توقف حتى نهاية الجلسة.
 */
object Sender {
    private class Frame(val buf: ByteArray, val len: Int, val pooled: Boolean)

    private val END = Frame(ByteArray(0), 0, false)

    private fun control(type: Int, payload: ByteArray): Frame {
        val b = ByteArray(1 + payload.size)
        b[0] = type.toByte()
        payload.copyInto(b, 1)
        return Frame(b, b.size, false)
    }

    private fun manifest(items: List<SendItem>): ByteArray {
        val bo = ByteArrayOutputStream()
        val d = DataOutputStream(bo)
        d.writeInt(items.size)
        for (i in items) {
            val nb = i.name.take(200).toByteArray(Charsets.UTF_8)
            d.writeShort(nb.size)
            d.write(nb)
            d.writeLong(i.size)
        }
        return bo.toByteArray()
    }

    fun send(ch: SecureChannel, items: List<SendItem>, onProgress: (Progress) -> Unit): Outcome {
        require(items.isNotEmpty() && items.size <= 1000) { "عدد الملفات يجب أن يكون بين 1 و1000" }
        val total = items.sumOf { it.size }
        ch.sendControl(T.MANIFEST, manifest(items))

        // انتظار موافقة المستقبِل
        val rb = ByteArray(1024)
        ch.recv(rb)
        when (rb[0].toInt()) {
            T.READY -> {}
            T.ABORT -> throw DeclinedException("رفض المستقبِل الاستقبال أو ألغاه")
            else -> throw IOException("رد غير متوقع من المستقبِل")
        }

        val q = ArrayBlockingQueue<Frame>(6)
        val pool = ArrayBlockingQueue<ByteArray>(8)
        repeat(8) { pool.add(ByteArray(1 + CHUNK)) }
        val err = AtomicReference<Throwable?>(null)

        val producer = thread(isDaemon = true, name = "jisr-read") {
            try {
                for ((i, item) in items.withIndex()) {
                    q.put(control(T.FILE_START, ByteBuffer.allocate(4).putInt(i).array()))
                    val md = MessageDigest.getInstance("SHA-256")
                    item.open().use { ins ->
                        var remaining = item.size
                        while (remaining > 0) {
                            val buf = pool.take()
                            buf[0] = T.DATA.toByte()
                            val n = ins.read(buf, 1, minOf(CHUNK.toLong(), remaining).toInt())
                            if (n < 0) throw IOException("انتهى الملف قبل الأوان: ${item.name}")
                            md.update(buf, 1, n)
                            remaining -= n
                            q.put(Frame(buf, 1 + n, true))
                        }
                    }
                    q.put(control(T.FILE_END, md.digest()))
                }
            } catch (t: Throwable) {
                err.set(t)
            } finally {
                try {
                    q.put(END)
                } catch (ignored: InterruptedException) {
                }
            }
        }

        var done = 0L
        var idx = 0
        try {
            while (true) {
                val f = q.take()
                if (f === END) break
                ch.send(f.buf, f.len)
                if (f.pooled) {
                    done += (f.len - 1).toLong()
                    pool.offer(f.buf)
                    onProgress(Progress(done, total, idx, items.size, items[idx].name))
                } else if (f.buf[0].toInt() == T.FILE_START) {
                    idx = ByteBuffer.wrap(f.buf, 1, 4).int
                }
            }
            err.get()?.let { throw it }
            ch.sendControl(T.DONE)
        } finally {
            producer.interrupt()
        }
        if (total == 0L) onProgress(Progress(0, 0, items.size - 1, items.size, items.last().name))

        var ok = 0
        val failed = ArrayList<String>()
        while (true) {
            ch.recv(rb)
            when (rb[0].toInt()) {
                T.FILE_OK -> ok++
                T.FILE_BAD -> failed += items.getOrNull(ByteBuffer.wrap(rb, 1, 4).int)?.name ?: "?"
                T.FINISH -> break
                T.ABORT -> throw IOException("ألغى المستقبِل العملية")
                else -> throw IOException("رد غير متوقع من المستقبِل")
            }
        }
        return Outcome(ok, failed, emptyList())
    }
}

/**
 * المستقبِل: خيط شبكة (استلام + فك تشفير) → طابور → خيط كتابة (قرص + SHA-256).
 * onManifest يقرر القبول (true) أو الرفض (false)؛ أي استثناء فيه يُرسل ABORT للمرسِل.
 * خيط الكتابة لا يترك أي استثناء دون معالجة (وإلا انهار التطبيق كله).
 */
object Receiver {
    private class WFrame(val buf: ByteArray, val len: Int)

    private val END = WFrame(ByteArray(0), 0)

    private fun parseManifest(b: ByteArray, len: Int): List<FileInfo> {
        val d = DataInputStream(ByteArrayInputStream(b, 1, len - 1))
        val n = d.readInt()
        if (n !in 1..1000) throw IOException("قائمة ملفات غير صالحة")
        return List(n) {
            val l = d.readUnsignedShort()
            if (l > 1000) throw IOException("اسم ملف غير صالح")
            val nb = ByteArray(l)
            d.readFully(nb)
            val size = d.readLong()
            if (size < 0) throw IOException("حجم ملف غير صالح")
            FileInfo(sanitizeName(String(nb, Charsets.UTF_8)), size)
        }
    }

    private fun ack(type: Int, idx: Int): ByteArray =
        ByteBuffer.allocate(5).put(type.toByte()).putInt(idx).array()

    private fun quietAbort(ch: SecureChannel) {
        try {
            ch.sendControl(T.ABORT)
        } catch (ignored: Throwable) {
        }
    }

    fun receive(
        ch: SecureChannel,
        sink: Sink,
        onManifest: (List<FileInfo>) -> Boolean,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val big = ByteArray(MAX_PLAIN)
        val n0 = ch.recv(big)
        if (big[0].toInt() != T.MANIFEST) throw IOException("بروتوكول غير صالح")
        val files = parseManifest(big, n0)
        val total = files.sumOf { it.size }

        val accepted = try {
            onManifest(files)
        } catch (t: Throwable) {
            quietAbort(ch)
            throw t
        }
        if (!accepted) {
            quietAbort(ch)
            throw DeclinedException()
        }
        ch.sendControl(T.READY)

        val pool = ArrayBlockingQueue<ByteArray>(8)
        repeat(8) { pool.add(ByteArray(1 + CHUNK)) }
        val q = ArrayBlockingQueue<WFrame>(6)
        val err = AtomicReference<Throwable?>(null)
        val locations = ArrayList<String>()
        val bad = ArrayList<String>()
        val acks = ArrayList<ByteArray>()

        val writer = thread(isDaemon = true, name = "jisr-write") {
            var cur: SinkFile? = null
            var md: MessageDigest? = null
            var idx = -1
            var written = 0L
            var done = 0L
            var failed = false
            try {
                while (true) {
                    val f = q.take()
                    if (f === END) break
                    try {
                        if (!failed) {
                            when (f.buf[0].toInt()) {
                                T.FILE_START -> {
                                    val i = ByteBuffer.wrap(f.buf, 1, 4).int
                                    if (i != idx + 1 || i >= files.size) throw IOException("ترتيب ملفات غير صالح")
                                    idx = i
                                    written = 0
                                    md = MessageDigest.getInstance("SHA-256")
                                    cur = sink.create(files[i].name, files[i].size)
                                }
                                T.DATA -> {
                                    val c = cur ?: throw IOException("بيانات بلا ملف")
                                    val len = f.len - 1
                                    written += len
                                    if (written > files[idx].size) throw IOException("حجم يتجاوز المعلن")
                                    c.out.write(f.buf, 1, len)
                                    md!!.update(f.buf, 1, len)
                                    done += len
                                    onProgress(Progress(done, total, idx, files.size, files[idx].name))
                                }
                                T.FILE_END -> {
                                    val c = cur ?: throw IOException("نهاية ملف بلا بداية")
                                    if (f.len != 33) throw IOException("بصمة غير صالحة")
                                    val same = MessageDigest.isEqual(md!!.digest(), f.buf.copyOfRange(1, 33))
                                    if (same && written == files[idx].size) {
                                        locations.add(c.commit())
                                        acks.add(ack(T.FILE_OK, idx))
                                    } else {
                                        c.discard()
                                        bad.add(files[idx].name)
                                        acks.add(ack(T.FILE_BAD, idx))
                                    }
                                    cur = null
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        failed = true
                        err.compareAndSet(null, t)
                        try {
                            cur?.discard()
                        } catch (ignored: Throwable) {
                        }
                        cur = null
                    } finally {
                        pool.offer(f.buf)
                    }
                }
                if (!failed && (idx != files.size - 1 || cur != null)) {
                    err.compareAndSet(null, IOException("انقطع النقل قبل اكتمال الملفات"))
                }
            } catch (ignored: InterruptedException) {
                // الخيط الرئيسي أنهى الجلسة (خطأ/إلغاء): ننظّف بصمت دون إسقاط التطبيق
                try {
                    cur?.discard()
                } catch (ignored: Throwable) {
                }
            } catch (t: Throwable) {
                err.compareAndSet(null, t)
            }
        }

        try {
            while (true) {
                err.get()?.let { throw it }
                val buf = pool.take()
                val n = ch.recv(buf)
                when (buf[0].toInt()) {
                    T.DONE -> {
                        pool.offer(buf)
                        q.put(END)
                        break
                    }
                    T.FILE_START, T.DATA, T.FILE_END -> q.put(WFrame(buf, n))
                    else -> throw IOException("إطار غير متوقع")
                }
            }
            writer.join()
            err.get()?.let { throw it }
        } catch (t: Throwable) {
            quietAbort(ch)
            throw t
        } finally {
            writer.interrupt()
        }

        for (a in acks) ch.send(a, a.size)
        ch.sendControl(T.FINISH)
        return Outcome(locations.size, bad, locations)
    }
}

/** محتوى رمز QR: يحمل بيانات الشبكة المحلية والمفتاح المشترك المسبق (PSK). */
class QrPayload(val ssid: String, val pass: String, val sec: Int, val port: Int, val psk: ByteArray) {
    fun encode(): String {
        val e = java.util.Base64.getUrlEncoder().withoutPadding()
        return listOf(
            "JISR1",
            e.encodeToString(ssid.toByteArray()),
            e.encodeToString(pass.toByteArray()),
            sec.toString(),
            port.toString(),
            e.encodeToString(psk),
        ).joinToString("|")
    }

    companion object {
        fun decode(s: String): QrPayload {
            val p = s.split("|")
            require(p.size == 6 && p[0] == "JISR1") { "ليس رمز جسر" }
            val d = java.util.Base64.getUrlDecoder()
            val psk = d.decode(p[5])
            require(psk.size == 32) { "مفتاح غير صالح" }
            return QrPayload(String(d.decode(p[1])), String(d.decode(p[2])), p[3].toInt(), p[4].toInt(), psk)
        }
    }
}
