package com.zidex.edms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesDottedVersionsNumerically() {
        assertTrue(Updater.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updater.isNewer("1.1", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.1", "1.0.1"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.1"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun picksTheAssetBuiltForThisSignature() {
        val assets = listOf("app-release.apk", "EDMS-Android-1.0.4-zeidex.apk")
        assertEquals("app-release.apk", Updater.pickApkName(assets, Updater.CERT_EDMS))
        assertEquals("EDMS-Android-1.0.4-zeidex.apk", Updater.pickApkName(assets, Updater.CERT_ZEIDEX))
    }

    @Test
    fun refusesTheWrongLineageAndUnknownSigners() {
        assertNull(Updater.pickApkName(listOf("app-release.apk"), Updater.CERT_ZEIDEX))
        assertNull(Updater.pickApkName(listOf("app-release.apk"), "00"))
    }

    @Test
    fun prefersAnExplicitLineageTag() {
        assertEquals(
            "EDMS-Android-1.0.4-edms.apk",
            Updater.pickApkName(listOf("app-release.apk", "EDMS-Android-1.0.4-edms.apk"), Updater.CERT_EDMS),
        )
    }
}
