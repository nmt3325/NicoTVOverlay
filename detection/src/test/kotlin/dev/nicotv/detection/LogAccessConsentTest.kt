package dev.nicotv.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogAccessConsentTest {
    @Test
    fun `only the system ui confirmation counts as the log access dialog`() {
        assertTrue(LogAccessConsent.dialogPackage("com.android.systemui"))
        assertFalse(LogAccessConsent.dialogPackage("jp.co.sharp.av.android.aquostvapp"))
        assertFalse(LogAccessConsent.dialogPackage(null))
    }

    @Test
    fun `the allow button is matched by id or by a known label`() {
        assertTrue(LogAccessConsent.isAllow("com.android.systemui:id/log_access_dialog_allow_button", null))
        assertTrue(LogAccessConsent.isAllow(null, " 1回限りのアクセスを許可 "))
    }

    @Test
    fun `no other button is ever pressed`() {
        assertFalse(LogAccessConsent.isAllow("com.android.systemui:id/log_access_dialog_deny_button", "許可しない"))
        assertFalse(LogAccessConsent.isAllow(null, "許可"))
        assertFalse(LogAccessConsent.isAllow(null, null))
    }
}
