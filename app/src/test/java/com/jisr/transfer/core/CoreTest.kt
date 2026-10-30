package com.jisr.transfer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread

class MemSink : Sink {
    val files = ConcurrentHashMap<String, ByteArray>()
    override fun create(name: String, size: Long): SinkFile = object : SinkFile {
        private val bo = ByteArrayOutputStream()
        override val out: OutputStream = bo
        override fun commit(): String {
            files[name] = bo.toByteArray()
            return name
        }
        override fun discard() {}
    }
}

/** قرص «ممتلئ»: كل كتابة تفشل. */
class FailSink : Sink {
    override fun create(name: String, size: Long): SinkFile = object : SinkFile {
        override val out: OutputStream = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        override fun commit(): String = name
        override fun discard() {}
    }
}

class CoreTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Crypto.hkdf(ikm, salt, info, 42)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun ecdhAgreesOnBothSides() {
        val a = Ephemeral()
        val b = Ephemeral()
        assertArrayEquals(a.agree(b.publicBytes), b.agree(a.publicBytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPointNotOnCurve() {
        val a = Ephemeral()
        val bad = Ephemeral().publicBytes.also { it[64] = (it[64].toInt() xor 1).toByte() }
        a.agree(bad)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedFrameIsRejected() {
        val key = Crypto.random(32)
        val a = FrameCipher(key)
        val b = FrameCipher(key)
        val plain = "hello".toByteArray()
        val ct = ByteArray(plain.size + 16)
        val n = a.seal(plain, 0, plain.size, ct, 0)
        ct[2] = (ct[2].toInt() xor 1).toByte()
        b.open(ct, 0, n, ByteArray(64), 0)
    }

    @Test
    fun qrPayloadRoundTrip() {
        val psk = Crypto.random(32)
        val p = QrPayload.decode(QrPayload("AndroidShare_1a", "pa|ss", 3, 4567, psk).encode())
        assertEquals("AndroidShare_1a", p.ssid)
        assertEquals("pa|ss", p.pass)
        assertEquals(3, p.sec)
        assertEquals(4567, p.port)
        assertArrayEquals(psk, p.psk)
    }

    @Test
    fun sanitizeNameBlocksTraversalAndIllegalChars() {
        assertEquals("a_b_.txt", sanitizeName("../a:b?.txt"))
        assertEquals("x.txt", sanitizeName("..\\..\\x.txt"))
        assertEquals("file", sanitizeName("..."))
    }

    private fun pair(pskS: ByteArray, pskR: ByteArray): Pair<SecureChannel, SecureChannel> {
        val srv = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sender = AtomicReference<SecureChannel>()
        val t = thread(isDaemon = true) {
            try {
                val s = srv.accept()
                sender.set(Handshake.asSender(s.getInputStream(), s.getOutputStream(), pskS))
            } catch (ignored: Throwable) {
            }
        }
        val c = Socket(InetAddress.getLoopbackAddress(), srv.localPort)
        try {
            val recv = Handshake.asReceiver(c.getInputStream(), c.getOutputStream(), pskR)
            t.join(10_000)
            return sender.get() to recv
        } catch (e: Throwable) {
            c.close()
            srv.close()
            throw e
        }
    }

    @Test(expected = SecurityException::class)
    fun wrongPskFailsHandshake() {
        pair(Crypto.random(32), Crypto.random(32))
    }

    @Test
    fun filesTransferIntactAndVerified() {
        val rnd = Random(7)
        val datas = listOf(
            ByteArray(3_000_000).also { rnd.nextBytes(it) },
            ByteArray(0),
            ByteArray(5) { 1 },
            ByteArray(CHUNK).also { rnd.nextBytes(it) },
        )
        val items = datas.mapIndexed { i, d -> SendItem("f$i.bin", d.size.toLong()) { ByteArrayInputStream(d) } }
        val psk = Crypto.random(32)
        val (s, r) = pair(psk, psk)
        val sink = MemSink()
        val res = AtomicReference<Outcome>()
        val err = AtomicReference<Throwable>()
        val t = thread(isDaemon = true) {
            try {
                res.set(Receiver.receive(r, sink, { true }, {}))
            } catch (e: Throwable) {
                err.set(e)
            }
        }
        val outS = Sender.send(s, items) {}
        t.join(30_000)
        err.get()?.let { throw it }
        assertEquals(4, outS.ok)
        assertEquals(4, res.get().ok)
        datas.forEachIndexed { i, d -> assertArrayEquals("file $i", d, sink.files["f$i.bin"]) }
    }

    @Test
    fun receiverDeclineStopsSender() {
        val psk = Crypto.random(32)
        val (s, r) = pair(psk, psk)
        thread(isDaemon = true) {
            try {
                Receiver.receive(r, MemSink(), { false }, {})
            } catch (ignored: Throwable) {
            }
        }
        try {
            Sender.send(s, listOf(SendItem("a", 3) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })) {}
            fail("expected DeclinedException")
        } catch (e: IOException) {
            assertTrue(e is DeclinedException)
        }
    }

    /** يمنع تكرار خطأ انهيار التطبيق: فشل القرص أثناء الاستلام يجب ألا يترك استثناءً غير معالج في أي خيط. */
    @Test
    fun failingDiskDoesNotLeakUncaughtExceptions() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            val data = ByteArray(800_000).also { Random(3).nextBytes(it) }
            val psk = Crypto.random(32)
            val (s, r) = pair(psk, psk)
            val recvErr = AtomicReference<Throwable>()
            val t1 = thread(isDaemon = true) {
                try {
                    Receiver.receive(r, FailSink(), { true }, {})
                } catch (e: Throwable) {
                    recvErr.set(e)
                }
            }
            val t2 = thread(isDaemon = true) {
                try {
                    Sender.send(s, listOf(SendItem("a.bin", data.size.toLong()) { ByteArrayInputStream(data) })) {}
                } catch (ignored: Throwable) {
                }
            }
            t1.join(15_000)
            t2.join(15_000)
            Thread.sleep(300)
            assertTrue("receiver should fail with IOException", recvErr.get() is IOException)
            assertTrue("uncaught: $uncaught", uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
    }
}
