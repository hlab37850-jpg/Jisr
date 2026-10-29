package com.jisr.transfer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
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

    private fun pair(pskS: ByteArray, pskR: ByteArray): Pair<SecureChannel, SecureChannel> {
        val srv = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sender = AtomicReference<SecureChannel>()
        val t = thread(isDaemon = true) {
            try {
                val s = srv.accept()
                sender.set(Handshake.asSender(s.getInputStream(), s.getOutputStream(), pskS))
            } catch (_: Throwable) {
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
                res.set(Receiver.receive(r, sink, {}, {}))
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
}
