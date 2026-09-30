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
        for (i in 0 until p) {
            val off = i * 128 * r
            for (k in 0 until blockInts) x[k] = le32(b, off + k * 4)
            // ROMix
            for (j in 0 until n) {
                System.arraycopy(x, 0, v, j * blockInts, blockInts)
                blockMix(x, scratch, r)
            }
            for (j in 0 until n) {
                val idx = x[(2 * r - 1) * 16] and (n - 1)
                for (k in 0 until blockInts) x[k] = x[k] xor v[idx * blockInts + k]
                blockMix(x, scratch, r)
            }
            for (k in 0 until blockInts) put32(b, off + k * 4, x[k])
        }
        return pbkdf2(pass, b, 1, dkLen)
    }

    private fun blockMix(b: IntArray, y: IntArray, r: Int) {
        val x = IntArray(16)
        System.arraycopy(b, (2 * r - 1) * 16, x, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa8(x)
            // even blocks → first half, odd blocks → second half
            val dst = (i / 2 + (i % 2) * r) * 16
            System.arraycopy(x, 0, y, dst, 16)
        }
        System.arraycopy(y, 0, b, 0, 32 * r)
    }

    private fun salsa8(b: IntArray) {
        val x = b.copyOf()
        fun rotl(a: Int, s: Int) = (a shl s) or (a ushr (32 - s))
        repeat(4) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7); x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13); x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7); x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13); x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7); x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13); x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7); x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13); x[15] = x[15] xor rotl(x[11] + x[7], 18)
            x[1] = x[1] xor rotl(x[0] + x[3], 7); x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13); x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7); x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13); x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7); x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13); x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7); x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13); x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        for (i in 0 until 16) b[i] += x[i]
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
