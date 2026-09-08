package dev.nicotv.detection

import dev.nicotv.core.PreferenceContract
import dev.nicotv.core.StationObservation
import org.junit.Assert.*
import org.junit.Test

class DetectionPolicyTest {
    private var now = 0L
    private val emitted = mutableListOf<StationObservation>()
    private val policy = DetectionPolicy({ now }, emitted::add)
    private val tv = ForegroundIdentity("test.tv", 1)
    private fun evidence(station: String? = "jk1", foreground: ForegroundIdentity? = tv) =
        StationEvidence(station, foreground, "synthetic fixture")
    private fun started(): DetectionPolicy.Pending {
        policy.authorize(true)
        return requireNotNull(policy.evidence(evidence()))
    }
    private fun confirmed() {
        val p = started(); now = 750; policy.confirm(p.generation, evidence())
        assertEquals("jk1", policy.observation.stationId)
    }

    @Test fun `no selection before explicit authorization or reread`() {
        assertNull(policy.evidence(evidence()))
        val p = started()
        now = 749; policy.confirm(p.generation, evidence())
        assertNull(policy.observation.stationId)
        now = 750; policy.confirm(p.generation, evidence())
        assertEquals("jk1", policy.observation.stationId)
        assertEquals(750L, policy.observation.observedAtMs)
    }
    @Test fun `rapid A B C immediately clears A and never publishes B`() {
        confirmed()
        now = 1000; val b = requireNotNull(policy.evidence(evidence("jk2")))
        assertNull(policy.observation.stationId)
        now = 1200; val c = requireNotNull(policy.evidence(evidence("jk4")))
        now = 1750; policy.confirm(b.generation, evidence("jk2"))
        assertEquals(c, policy.pending)
        assertNull(policy.observation.stationId)
        now = 1950; policy.confirm(c.generation, evidence("jk4"))
        assertEquals("jk4", policy.observation.stationId)
        assertFalse(emitted.any { it.stationId == "jk2" })
    }
    @Test fun `old generation cannot clear a newer confirmed station`() {
        val a = started()
        now = 20; val c = requireNotNull(policy.evidence(evidence("jk4")))
        now = 770; policy.confirm(c.generation, evidence("jk4"))
        policy.confirm(a.generation, evidence(null, null))
        assertEquals("jk4", policy.observation.stationId)
    }
    @Test fun `confirmation rereads and changes candidate instead of trusting earlier label`() {
        val a = started()
        now = 750; val b = requireNotNull(policy.confirm(a.generation, evidence("jk2")))
        assertNull(policy.observation.stationId)
        assertEquals(1500L, b.dueAt)
    }
    @Test fun `foreground heartbeat does not extend 30 second evidence TTL`() {
        confirmed()
        for (time in 1000L..30_000L step 1000) { now = time; policy.heartbeat(tv, true) }
        assertEquals(750L, policy.observation.observedAtMs)
        now = 30_750; policy.heartbeat(tv, true)
        assertNull(policy.observation.stationId)
    }
    @Test fun `fresh actual evidence alone renews the timestamp`() {
        confirmed()
        now = 1000; policy.evidence(evidence())
        assertEquals(1000L, policy.observation.observedAtMs)
        now = 30_999; policy.heartbeat(tv, true)
        assertEquals("jk1", policy.observation.stationId)
        now = 31_000; policy.heartbeat(tv, true)
        assertNull(policy.observation.stationId)
    }
    @Test fun `home root null screen off and lock clear selection`() {
        for ((root, usable) in listOf(null to true, ForegroundIdentity("launcher", 2) to true, tv to false)) {
            now = 0; confirmed(); now = 1000
            policy.heartbeat(root, usable)
            assertNull(policy.observation.stationId)
            assertNull(policy.pending)
        }
    }
    @Test fun `session stop or mode change cancels pending confirmation`() {
        val p = started()
        policy.authorize(false)
        now = 1000; policy.confirm(p.generation, evidence())
        assertNull(policy.observation.stationId)
        assertNull(policy.evidence(evidence()))
        policy.authorize(true)
        policy.confirm(p.generation, evidence())
        assertNull(policy.observation.stationId)
    }
    @Test fun `duplicate candidate does not restart debounce and changed window does`() {
        val p = started(); now = 200
        assertEquals(p, policy.evidence(evidence()))
        val changed = requireNotNull(policy.evidence(evidence(foreground = tv.copy(windowId = 2))))
        assertNotEquals(p.generation, changed.generation)
        assertEquals(950L, changed.dueAt)
    }
    @Test fun `late timer cannot establish expired evidence`() {
        val p = started(); now = 30_000
        policy.confirm(p.generation, evidence())
        assertNull(policy.observation.stationId)
    }
}
