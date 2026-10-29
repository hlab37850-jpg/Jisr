package com.jisr.transfer.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest

const val CHUNK = 256 * 1024
const val MAX_PLAIN = 1 shl 20

internal fun readFully(inp: InputStream, b: ByteArray, off: Int, len: Int) {
    var p = 0
    while (p < len) {
        val r = inp.read(b, off + p, len - p)
        if (r < 0) throw EOFException("انقطع الاتصال")
        p += r
    }
}

internal fun readN(inp: InputStream, n: Int): ByteArray = ByteArray(n).also { readFully(inp, it, 0, n) }

/**
 * قناة مشفرة: كل إطار = [طول 4 بايت][AES-GCM(نوع 1 بايت + حمولة)].
 * send/recv يُستخدمان من خيط واحد لكل اتجاه.
 */
class SecureChannel(
    private val input: InputStream,
    private val output: OutputStream,
    sendKey: ByteArray,
    recvKey: ByteArray,
) {
    private val enc = FrameCipher(sendKey)
    private val dec = FrameCipher(recvKey)
    private val outBuf = ByteArray(4 + MAX_PLAIN + 16)
    private val inBuf = ByteArray(MAX_PLAIN + 16)
    private val hdr = ByteArray(4)

    /** plain[0] = نوع الإطار. */
    fun send(plain: ByteArray, len: Int) {
        require(len in 1..MAX_PLAIN)
        val n = try {
            enc.seal(plain, 0, len, outBuf, 4)
        } catch (e: GeneralSecurityException) {
            throw IOException("فشل التشفير", e)
        }
        outBuf[0] = (n ushr 24).toByte()
        outBuf[1] = (n ushr 16).toByte()
        outBuf[2] = (n ushr 8).toByte()
        outBuf[3] = n.toByte()
        output.write(outBuf, 0, 4 + n)
    }

    fun sendControl(type: Int, payload: ByteArray = ByteArray(0)) {
        val b = ByteArray(1 + payload.size)
        b[0] = type.toByte()
        payload.copyInto(b, 1)
        send(b, b.size)
    }

    /** يفك تشفير إطار في into ويعيد طول النص الصريح (into[0] = النوع). */
    fun recv(into: ByteArray): Int {
        readFully(input, hdr, 0, 4)
        val clen = ((hdr[0].toInt() and 255) shl 24) or ((hdr[1].toInt() and 255) shl 16) or
            ((hdr[2].toInt() and 255) shl 8) or (hdr[3].toInt() and 255)
        if (clen < 17 || clen > MAX_PLAIN + 16) throw IOException("إطار غير صالح")
        if (clen - 16 > into.size) throw IOException("إطار أكبر من المسموح")
        readFully(input, inBuf, 0, clen)
        return try {
            dec.open(inBuf, 0, clen, into, 0)
        } catch (e: GeneralSecurityException) {
            throw SecurityException("فشل التحقق من سلامة البيانات (إطار معدّل أو مفتاح خاطئ)")
        }
    }
}

object Handshake {
    private val MAGIC = "JISR".toByteArray()
    private const val VERSION = 1

    /** الجهاز المستقبِل (هو من يتصل). psk يأتي من رمز QR. */
    fun asReceiver(inp: InputStream, out: OutputStream, psk: ByteArray): SecureChannel {
        try {
            val eph = Ephemeral()
            val nonceR = Crypto.random(16)
            out.write(MAGIC + byteArrayOf(VERSION.toByte()) + eph.publicBytes + nonceR)
            out.flush()
            val pubS = readN(inp, 65)
            val nonceS = readN(inp, 16)
            val confS = readN(inp, 32)
            val keys = deriveKeys(eph.agree(pubS), psk, nonceR, nonceS)
            val transcript = eph.publicBytes + nonceR + pubS + nonceS
            if (!MessageDigest.isEqual(confS, Crypto.hmac(keys.confirm, "S".toByteArray(), transcript))) {
                throw SecurityException("فشل التحقق: رمز QR لا يطابق المرسِل")
            }
            out.write(Crypto.hmac(keys.confirm, "R".toByteArray(), transcript))
            out.flush()
            return SecureChannel(inp, out, keys.r2s, keys.s2r)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("مصافحة غير صالحة: ${e.message}")
        }
    }

    /** الجهاز المرسِل (يستمع). */
    fun asSender(inp: InputStream, out: OutputStream, psk: ByteArray): SecureChannel {
        try {
            val hello = readN(inp, 5 + 65 + 16)
            if (!hello.copyOfRange(0, 4).contentEquals(MAGIC) || hello[4].toInt() != VERSION) {
                throw SecurityException("بروتوكول غير معروف")
            }
            val pubR = hello.copyOfRange(5, 70)
            val nonceR = hello.copyOfRange(70, 86)
            val eph = Ephemeral()
            val nonceS = Crypto.random(16)
            val keys = deriveKeys(eph.agree(pubR), psk, nonceR, nonceS)
            val transcript = pubR + nonceR + eph.publicBytes + nonceS
            out.write(eph.publicBytes + nonceS + Crypto.hmac(keys.confirm, "S".toByteArray(), transcript))
            out.flush()
            val confR = readN(inp, 32)
            if (!MessageDigest.isEqual(confR, Crypto.hmac(keys.confirm, "R".toByteArray(), transcript))) {
                throw SecurityException("فشل التحقق: الطرف الآخر لا يملك مفتاح QR")
            }
            return SecureChannel(inp, out, keys.s2r, keys.r2s)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("مصافحة غير صالحة: ${e.message}")
        }
    }
}
