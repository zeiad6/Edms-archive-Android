package com.zidex.edms

import com.zidex.edms.data.Passwords
import com.zidex.edms.data.Passwords.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordsTest {
    @Test
    fun rfc7914Vector() {
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            Passwords.scrypt("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64).hex(),
        )
    }

    /** Hash produced by the desktop (Node crypto.scryptSync) must verify here. */
    @Test
    fun verifiesDesktopHash() {
        val desktop = "scrypt\$32768\$8\$1\$00112233445566778899aabbccddeeff\$" +
            "a6705cae2e0188508db96d1fb36615602d94b7d6b221d8b01c5a01822b9432baa69bdd95bd4380eef8d2405808316846ee87f8712bcc0cfe9858edca5476a365"
        assertTrue(Passwords.verify("12345678", desktop))
        assertFalse(Passwords.verify("12345679", desktop))
    }

    @Test
    fun roundTripAndSanitize() {
        val h = Passwords.hash("سر-123")
        assertTrue(Passwords.verify("سر-123", h))
        assertEquals("abc", Passwords.sanitize("‏ abc \n"))
    }
}
