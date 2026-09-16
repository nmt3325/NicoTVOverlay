package dev.nicotv.detection

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityLinkTest {
    @Before fun setUp() = AccessibilityLink.reset()

    @After fun tearDown() = AccessibilityLink.reset()

    @Test
    fun `unbound service is not live`() {
        assertFalse(AccessibilityLink.live(1_000L))
    }

    @Test
    fun `connected service is live`() {
        AccessibilityLink.markConnected()
        assertTrue(AccessibilityLink.connected)
        assertTrue(AccessibilityLink.live(1_000L))
    }

    @Test
    fun `short disconnect stays live until the grace ends`() {
        AccessibilityLink.markConnected()
        AccessibilityLink.markDisconnected(1_000L)
        assertFalse(AccessibilityLink.connected)
        assertTrue(AccessibilityLink.live(1_000L))
        assertTrue(AccessibilityLink.live(1_000L + AccessibilityLink.GRACE_MS - 1))
        assertFalse(AccessibilityLink.live(1_000L + AccessibilityLink.GRACE_MS))
    }
}
