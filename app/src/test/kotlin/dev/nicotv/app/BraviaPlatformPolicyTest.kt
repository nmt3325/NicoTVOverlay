package dev.nicotv.app

import dev.nicotv.core.PreferenceContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BraviaPlatformPolicyTest {
    @Test fun localHostRequiresAnExactConfirmedPrivateIpv4() {
        val own = setOf("192.168.1.20", "10.0.0.2", "203.0.113.1")
        assertTrue(PlatformPermissions.localHostMatches("192.168.1.20", own))
        assertTrue(PlatformPermissions.localHostMatches("10.0.0.2", own))
        for (host in listOf("192.168.1.21", "203.0.113.1", "127.0.0.1", "192.168.001.20", "http://192.168.1.20", "tv.local")) assertFalse(host, PlatformPermissions.localHostMatches(host, own))
        assertFalse(PlatformPermissions.localHostMatches("192.168.1.20", emptySet()))
    }
    @Test fun braviaStartRequiresAccessibilityCalibrationLocalHostAndDefaultDisplay() {
        val mode = PreferenceContract.MODE_BRAVIA
        assertNull(PlatformPermissions.startBlock(true, true, true, mode, true, true, true))
        assertNotNull(PlatformPermissions.startBlock(true, true, false, mode, true, true, true))
        assertNotNull(PlatformPermissions.startBlock(true, true, true, mode, true, false, true))
        assertNotNull(PlatformPermissions.startBlock(true, true, true, mode, true, true, false))
        assertNotNull(PlatformPermissions.startBlock(true, true, true, mode, false, true, true))
        assertNotNull(PlatformPermissions.startBlock(false, true, true, mode, true, true, true))
        assertNotNull(PlatformPermissions.startBlock(true, false, true, mode, true, true, true))
    }
    @Test fun braviaVisibilityProfileNeedsLiveMarkerButNotStationOsd() {
        val profile = AppSettings(mode = PreferenceContract.MODE_BRAVIA, tvPackages = "com.example.tv", liveIds = "com.example.tv:id/live")
        assertFalse(profile.calibrated); assertTrue(profile.braviaVisibilityCalibrated)
        assertFalse(profile.copy(liveIds = "").braviaVisibilityCalibrated)
        assertFalse(profile.copy(tvPackages = "").braviaVisibilityCalibrated)
    }
    @Test fun secondaryOrUnknownDisplayAlsoBlocksManualStart() {
        assertNotNull(PlatformPermissions.startBlock(true, true, false, PreferenceContract.MODE_MANUAL, defaultDisplay = false))
        assertNull(PlatformPermissions.startBlock(true, true, false, PreferenceContract.MODE_MANUAL, defaultDisplay = true))
    }
}
