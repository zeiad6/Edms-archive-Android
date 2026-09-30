package com.zidex.edms

import com.zidex.edms.data.SavedLogin
import com.zidex.edms.data.merge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray

/**
 * The credential store's own logic, minus the Android Keystore.
 *
 * `CredentialStore` itself needs SharedPreferences and the hardware keystore, so
 * what is covered here is the part that decides what ends up stored: identity
 * matching, ordering, and the JSON round-trip. Whether the blob is actually
 * encrypted is enforced by `KeystoreAes`, not by anything testable on the JVM.
 */
class CredentialsTest {

    private fun entry(user: String, pw: String, at: Long) = SavedLogin(user, pw, at)

    @Test
    fun `saving an existing username replaces it instead of duplicating`() {
        val before = listOf(entry("admin", "old", 100), entry("mary", "m", 200))
        val after = merge(before, entry("admin", "new", 300))
        assertEquals(2, after.size)
        assertEquals("new", after.first { it.username == "admin" }.password)
    }

    @Test
    fun `username identity is case insensitive`() {
        val after = merge(listOf(entry("Admin", "old", 100)), entry("ADMIN", "new", 300))
        assertEquals(1, after.size)
        assertEquals("new", after.single().password)
    }

    @Test
    fun `re-saving moves the entry to the newest slot`() {
        val before = listOf(entry("a", "1", 300), entry("b", "2", 200), entry("c", "3", 100))
        val after = merge(before, entry("c", "4", 400))
        assertEquals(listOf("c", "a", "b"), after.map { it.username })
    }

    @Test
    fun `json round trip keeps username password and timestamp`() {
        val original = listOf(entry("mary", "s3cret", 1_700_000_000_000), entry("admin", "pw", 1_600_000_000_000))
        val arr = JSONArray()
        original.forEach { arr.put(org.json.JSONObject().put("u", it.username).put("p", it.password).put("at", it.savedAt)) }

        val decoded = com.zidex.edms.data.CredentialStore.decodeForTest(arr)

        assertEquals(original.map { it.username }, decoded.map { it.username })
        assertEquals("s3cret", decoded.first { it.username == "mary" }.password)
        assertEquals(1_600_000_000_000L, decoded.last().savedAt)
    }

    @Test
    fun `entries without a username are dropped rather than stored`() {
        val arr = JSONArray()
            .put(org.json.JSONObject().put("u", "").put("p", "x").put("at", 1))
            .put(org.json.JSONObject().put("u", "real").put("p", "y").put("at", 2))
        val decoded = com.zidex.edms.data.CredentialStore.decodeForTest(arr)
        assertEquals(1, decoded.size)
        assertEquals("real", decoded.single().username)
    }

    @Test
    fun `an empty username is never saved`() {
        // save() drops blank names before merging, so the store cannot accumulate
        // a nameless row that would shadow nothing and leak nothing useful.
        val blank = listOf(entry("", "p", 1))
        assertTrue(merge(blank, entry("", "q", 2)).size == 1)
    }
}