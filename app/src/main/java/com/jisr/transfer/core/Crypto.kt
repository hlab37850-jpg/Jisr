package com.jisr.transfer.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** كل التشفير مبني على مكتبات JCA القياسية فقط (لا خوارزميات مكتوبة يدويًا ما عدا HKDF وفق RFC 5869). */
object Crypto {
    private val rnd = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) m.update(p)
        return m.doFinal()
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < len) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }
}

/** مفتاح ECDH مؤقت (P-256) لكل جلسة → سرية أمامية. */
class Ephemeral {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val params: ECParameterSpec = (pair.public as ECPublicKey).params

    /** 65 بايت: 0x04 || X || Y */
    val publicBytes: ByteArray = encode(pair.public as ECPublicKey)

    fun agree(peer: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(pair.private)
        ka.doPhase(decode(peer), true)
        return ka.generateSecret()
    }

    private fun encode(k: ECPublicKey): ByteArray {
        val out = ByteArray(65)
        out[0] = 4
        fixed32(k.w.affineX).copyInto(out, 1)
        fixed32(k.w.affineY).copyInto(out, 33)
        return out
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        val out = ByteArray(32)
        src.copyInto(out, 32 - src.size)
        return out
    }

    private fun decode(b: ByteArray): PublicKey {
        require(b.size == 65 && b[0] == 4.toByte()) { "مفتاح عام غير صالح" }
        val x = BigInteger(1, b.copyOfRange(1, 33))
        val y = BigInteger(1, b.copyOfRange(33, 65))
        val p = (params.curve.field as ECFieldFp).p
        require(x < p && y < p) { "إحداثيات خارج الحقل" }
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        require(lhs == rhs) { "النقطة ليست على المنحنى" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }
}

/** AES-256-GCM بعدّاد nonce تصاعدي (مفتاح مستقل لكل اتجاه، فلا يتكرر الـ nonce أبدًا). */
class FrameCipher(key: ByteArray) {
    private val k = SecretKeySpec(key, "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val iv = ByteArray(12)
    private var counter = 0L

    private fun nextSpec(): GCMParameterSpec {
        var c = counter++
        for (i in 11 downTo 4) {
            iv[i] = c.toByte()
            c = c ushr 8
        }
        return GCMParameterSpec(128, iv)
    }

    fun seal(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        cipher.init(Cipher.ENCRYPT_MODE, k, nextSpec())
        return cipher.doFinal(src, off, len, dst, dstOff)
    }

    fun open(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        cipher.init(Cipher.DECRYPT_MODE, k, nextSpec())
        return cipher.doFinal(src, off, len, dst, dstOff)
    }
}

class SessionKeys(val s2r: ByteArray, val r2s: ByteArray, val confirm: ByteArray)

fun deriveKeys(shared: ByteArray, psk: ByteArray, nonceR: ByteArray, nonceS: ByteArray): SessionKeys {
    val okm = Crypto.hkdf(shared + psk, nonceR + nonceS, "jisr-v1".toByteArray(), 96)
    return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), okm.copyOfRange(64, 96))
}
