package com.zidex.edms.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM under a key that lives in the Android Keystore and never leaves it.
 * Same scheme the desktop pairing token uses (see `LinkStore`), extracted here
 * so both stores share one key-derivation path instead of two copies.
 */
object KeystoreAes {
    private const val IV = 12
    private const val TAG = 128

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    /** `base64(iv ‖ ciphertext ‖ tag)`. */
    fun encrypt(alias: String, plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(alias)) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** null when the payload is corrupt or was written by a replaced key. */
    fun decrypt(alias: String, stored: String): String? = runCatching {
        val b = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(TAG, b, 0, IV))
        }
        String(c.doFinal(b, IV, b.size - IV))
    }.getOrNull()
}

/** One saved login: [username] identifies it, [password] is the secret. */
data class SavedLogin(val username: String, val password: String, val savedAt: Long)

/**
 * Sign-in credentials the user chose to keep on the phone, encrypted with
 * [KeystoreAes].
 *
 * This is deliberately opt-in (the login screen's «حفظ بيانات الدخول» switch)
 * and deliberately separate from the archive's own user table: the passwords
 * here are the ones the user typed on this device, never rows from the
 * database, so this store cannot become a back door around the scrypt hashes
 * in `users.password_hash`. Removing an entry, or the whole store, is one call
 * and leaves nothing recoverable.
 */
class CredentialStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val blob get() = prefs.getString("blob", null)

    /** Saved logins, newest first. A corrupt or undecryptable blob reads as empty. */
    fun all(): List<SavedLogin> {
        val json = blob?.let { KeystoreAes.decrypt(ALIAS, it) } ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            decode(arr)
        }.getOrDefault(emptyList())
    }

    /** Insert or replace by username, then bump it to the newest slot. */
    fun save(username: String, password: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        write(merge(all(), SavedLogin(name, password, now())))
    }

    fun remove(username: String) = write(all().filterNot { it.username.equals(username, ignoreCase = true) })

    fun clear() = prefs.edit().remove("blob").apply()

    /** The login to pre-fill on next launch, or null when nothing is saved. */
    fun last(): SavedLogin? = all().firstOrNull()

    private fun decode(arr: JSONArray): List<SavedLogin> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val user = o.optString("u")
            if (user.isBlank()) null else SavedLogin(user, o.optString("p"), o.optLong("at"))
        }.sortedByDescending { it.savedAt }

    private fun write(list: List<SavedLogin>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("u", it.username).put("p", it.password).put("at", it.savedAt))
        }
        prefs.edit().putString("blob", KeystoreAes.encrypt(ALIAS, arr.toString())).apply()
    }

    companion object {
        /** Keystore alias, not key material — safe to be visible. */
        const val ALIAS = "edms-credentials"

        fun now() = System.currentTimeMillis()

        /**
         * Test seam: [decode] itself is pure, but reaching it needs a
         * `CredentialStore`, and that needs a Context. Unit tests live on the
         * JVM where SharedPreferences and the hardware keystore do not exist.
         */
        @JvmStatic
        fun decodeForTest(arr: JSONArray): List<SavedLogin> =
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val user = o.optString("u")
                if (user.isBlank()) null else SavedLogin(user, o.optString("p"), o.optLong("at"))
            }.sortedByDescending { it.savedAt }
    }
}

/**
 * Identity is the username, case-insensitively: signing in as `Admin` must
 * replace the stored `admin` row rather than shadowing it, and the surviving
 * entry moves to the newest slot so it is the one pre-filled next time.
 */
internal fun merge(existing: List<SavedLogin>, entry: SavedLogin): List<SavedLogin> =
    (existing.filterNot { it.username.equals(entry.username, ignoreCase = true) } + entry)
        .sortedByDescending { it.savedAt }