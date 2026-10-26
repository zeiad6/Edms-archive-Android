package com.zidex.edms.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Password hashing, byte-for-byte compatible with the desktop
 * (src/lib/password.ts): `scrypt$N$r$p$saltHex$hashHex`, N=2^15, r=8, p=1,
 * 16-byte salt, 64-byte key. A database moved between desktop and phone
 * (backup/restore) therefore keeps every user's password.
 *
 * Android ships no scrypt, so RFC 7914 is implemented here (PBKDF2-HMAC-SHA256
 * + Salsa20/8 BlockMix + ROMix). Verified against the RFC test vectors.
 */
object Passwords {
    const val DEFAULT_PASSWORD = "12345678"
    private const val N = 32768
    private const val R = 8
    private const val P = 1
    private const val KEY_LEN = 64

    /** Same trimming as the desktop's sanitizePastedPassword (RTL marks, zero-width, spaces). */
    fun sanitize(raw: String): String {
        // Spelled as escapes: the characters are invisible in source, and lint
        // (rightly) flags invisible bidi marks as a spoofing risk. What they do
        // here is get *stripped* from pasted passwords — LRM/RLM, the embedding
        // controls U+202A..U+202E, and the isolates U+2066..U+2069 — mirroring
        // the desktop's sanitizePastedPassword.
        val MARKS = "[\\s\\u200E-\\u200F\\u202A-\\u202E\\u2066-\\u2069]"
        return raw.replace(Regex("^$MARKS+|$MARKS+$"), "")
    }

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = scrypt(password.toByteArray(), salt, N, R, P, KEY_LEN)
        return "scrypt\$$N\$$R\$$P\$${salt.hex()}\$${key.hex()}"
    }

    fun verify(password: String, stored: String?): Boolean {
        val parts = stored?.split('$') ?: return false
        if (parts.size != 6 || parts[0] != "scrypt") return false
        val n = parts[1].toIntOrNull() ?: return false
        val r = parts[2].toIntOrNull() ?: return false
        val p = parts[3].toIntOrNull() ?: return false
        if (n < 2 || n and (n - 1) != 0 || r < 1 || p < 1 || n.toLong() * r > 1 shl 22) return false
        val salt = parts[4].unhex() ?: return false
        val expected = parts[5].unhex() ?: return false
        if (salt.isEmpty() || expected.isEmpty()) return false
        val actual = scrypt(password.toByteArray(), salt, n, r, p, expected.size)
        return MessageDigest.isEqual(actual, expected)
    }

    // ───────────── RFC 7914 ─────────────

    fun scrypt(pass: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        val blockInts = 32 * r
        val b = pbkdf2(pass, salt, 1, p * 128 * r)
        val x = IntArray(blockInts)
        val v = IntArray(blockInts * n)
        val scratch = IntArray(blockInts)
        val blockMixX = IntArray(16)
        for (i in 0 until p) {
            val off = i * 128 * r
            for (k in 0 until blockInts) x[k] = le32(b, off + k * 4)
            // ROMix
            for (j in 0 until n) {
                System.arraycopy(x, 0, v, j * blockInts, blockInts)
                blockMix(x, scratch, blockMixX, r)
            }
            for (j in 0 until n) {
                val idx = x[(2 * r - 1) * 16] and (n - 1)
                for (k in 0 until blockInts) x[k] = x[k] xor v[idx * blockInts + k]
                blockMix(x, scratch, blockMixX, r)
            }
            for (k in 0 until blockInts) put32(b, off + k * 4, x[k])
        }
        return pbkdf2(pass, b, 1, dkLen)
    }

    private fun blockMix(b: IntArray, y: IntArray, x: IntArray, r: Int) {
        System.arraycopy(b, (2 * r - 1) * 16, x, 0, 16)
        for (i in 0 until 2 * r) {
            val bOff = i * 16
            for (k in 0 until 16) x[k] = x[k] xor b[bOff + k]
            salsa8(x)
            // even blocks → first half, odd blocks → second half
            val dst = (i / 2 + (i % 2) * r) * 16
            System.arraycopy(x, 0, y, dst, 16)
        }
        System.arraycopy(y, 0, b, 0, 32 * r)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun rotl(a: Int, s: Int) = (a shl s) or (a ushr (32 - s))

    private fun salsa8(b: IntArray) {
        var x0 = b[0]; var x1 = b[1]; var x2 = b[2]; var x3 = b[3]
        var x4 = b[4]; var x5 = b[5]; var x6 = b[6]; var x7 = b[7]
        var x8 = b[8]; var x9 = b[9]; var x10 = b[10]; var x11 = b[11]
        var x12 = b[12]; var x13 = b[13]; var x14 = b[14]; var x15 = b[15]

        repeat(4) {
            x4 = x4 xor rotl(x0 + x12, 7); x8 = x8 xor rotl(x4 + x0, 9)
            x12 = x12 xor rotl(x8 + x4, 13); x0 = x0 xor rotl(x12 + x8, 18)
            x9 = x9 xor rotl(x5 + x1, 7); x13 = x13 xor rotl(x9 + x5, 9)
            x1 = x1 xor rotl(x13 + x9, 13); x5 = x5 xor rotl(x1 + x13, 18)
            x14 = x14 xor rotl(x10 + x6, 7); x2 = x2 xor rotl(x14 + x10, 9)
            x6 = x6 xor rotl(x2 + x14, 13); x10 = x10 xor rotl(x6 + x2, 18)
            x3 = x3 xor rotl(x15 + x11, 7); x7 = x7 xor rotl(x3 + x15, 9)
            x11 = x11 xor rotl(x7 + x3, 13); x15 = x15 xor rotl(x11 + x7, 18)
            x1 = x1 xor rotl(x0 + x3, 7); x2 = x2 xor rotl(x1 + x0, 9)
            x3 = x3 xor rotl(x2 + x1, 13); x0 = x0 xor rotl(x3 + x2, 18)
            x6 = x6 xor rotl(x5 + x4, 7); x7 = x7 xor rotl(x6 + x5, 9)
            x4 = x4 xor rotl(x7 + x6, 13); x5 = x5 xor rotl(x4 + x7, 18)
            x11 = x11 xor rotl(x10 + x9, 7); x8 = x8 xor rotl(x11 + x10, 9)
            x9 = x9 xor rotl(x8 + x11, 13); x10 = x10 xor rotl(x9 + x8, 18)
            x12 = x12 xor rotl(x15 + x14, 7); x13 = x13 xor rotl(x12 + x15, 9)
            x14 = x14 xor rotl(x13 + x12, 13); x15 = x15 xor rotl(x14 + x13, 18)
        }

        b[0] += x0; b[1] += x1; b[2] += x2; b[3] += x3
        b[4] += x4; b[5] += x5; b[6] += x6; b[7] += x7
        b[8] += x8; b[9] += x9; b[10] += x10; b[11] += x11
        b[12] += x12; b[13] += x13; b[14] += x14; b[15] += x15
    }

    /** PBKDF2-HMAC-SHA256 on raw bytes (the platform factory only takes char[] passwords). */
    private fun pbkdf2(pass: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(pass.isNotEmpty()) { "empty password" } // SecretKeySpec rejects empty keys
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pass, "HmacSHA256")) }
        val out = ByteArray(dkLen)
        var block = 1
        var pos = 0
        while (pos < dkLen) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(t.size, dkLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray? =
        if (length % 2 != 0 || !all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) null
        else ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
