package dev.nicotv.detection

import dev.nicotv.core.PreferenceContract
import org.junit.Assert.*
import org.junit.Test

class StationEvidenceReaderTest {
    private val tv = ForegroundIdentity("test.tv", 1)
    private val station = "test.tv:id/station"
    private val live = "test.tv:id/live"
    private fun profile(active: Boolean = true, mode: String = PreferenceContract.MODE_ACCESSIBILITY,
                        packages: String = "test.tv", ids: String = station, liveIds: String = live,
                        aliases: String = "{}") = DetectionProfile.parse(active, mode, packages, ids, liveIds, aliases)
    private class Node(override val packageName: String? = "test.tv", override val resourceId: String? = null,
                       override val visible: Boolean = true, override val collection: Boolean = false,
                       private val label: String? = null, private val desc: String? = null,
                       val children: List<Node> = emptyList(), val forbidText: Boolean = false) : EvidenceNode {
        var reads = 0
        var closed = false
        override val childCount get() = children.size
        override fun text(): CharSequence? { check(!forbidText); reads++; return label }
        override fun description(): CharSequence? { check(!forbidText); reads++; return desc }
        override fun child(index: Int) = children[index]
        override fun close() { closed = true }
    }
    private fun root(vararg labels: String, marker: Boolean = true): Node = Node(children =
        labels.map { Node(resourceId = station, label = it) } +
            if (marker) listOf(Node(resourceId = live, forbidText = true)) else emptyList())
    private fun read(node: Node, config: DetectionProfile = profile()) = StationEvidenceReader.read(node, tv, config)

    @Test fun `exact NFKC whitespace label with visible live marker is a candidate`() {
        assertEquals("jk6", read(root("ＴＢＳ")).stationId)
        assertEquals("jk1", read(root("　NHK\u00a0 G  ")).stationId)
    }
    @Test fun `titles substrings remote numbers and recordings do not map`() {
        for (text in listOf("TBSの番組", "4", "日テレ ニュース", "NHK総合 録画", "録画 TBS", "tbs")) {
            assertNull(text, read(root(text)).stationId)
        }
    }
    @Test fun `EPG multiple candidates and even focused single collection item are unknown`() {
        assertNull(read(root("NHK総合", "TBS")).stationId)
        val epg = Node(collection = true, children = listOf(root("TBS")))
        assertNull(read(epg).stationId)
        assertNull(read(root("NHK総合", "不明な局")).stationId)
    }
    @Test fun `calibration insufficient invalid or overlapping IDs is disabled`() {
        for (config in listOf(profile(ids = ""), profile(liveIds = ""), profile(ids = "station"),
            profile(ids = "other.tv:id/station"), profile(liveIds = station), profile(packages = ""),
            profile(packages = "test.tv.*"), profile(aliases = "not json"))) {
            assertFalse(config.enabled)
            assertNull(read(root("TBS"), config).stationId)
        }
    }
    @Test fun `exact custom local labels require known jk and cannot conflict`() {
        assertEquals("jk4", read(root("地元テレビ"), profile(aliases = "{\"地元テレビ\":\"jk4\"}")).stationId)
        assertFalse(profile(aliases = "{\"ＴＢＳ\":\"jk4\"}").enabled)
        assertFalse(profile(aliases = "{\"地域局\":\"jk999\"}").enabled)
        assertFalse(profile(aliases = "{\"4\":\"jk4\"}").enabled)
        assertFalse(profile(aliases = "{\"地域局\":4}").enabled)
    }
    @Test fun `offlist contents and uncalibrated arbitrary titles are never read`() {
        val off = Node(packageName = "launcher", forbidText = true, children = listOf(root("TBS")))
        assertNull(read(off).stationId); assertEquals(0, off.reads)
        val title = Node(resourceId = "test.tv:id/title", label = "TBS", forbidText = true)
        val good = root("NHK総合")
        assertEquals("jk1", read(Node(children = listOf(good, title))).stationId)
        assertEquals(0, title.reads)
        assertTrue(title.closed)
    }
    @Test fun `invisible markers labels and absent marker never establish live station`() {
        assertNull(read(root("TBS", marker = false)).stationId)
        assertNull(read(Node(children = listOf(Node(resourceId = station, label = "TBS"),
            Node(resourceId = live, visible = false, forbidText = true)))).stationId)
        assertNull(read(Node(children = listOf(Node(resourceId = station, label = "TBS", visible = false, forbidText = true),
            Node(resourceId = live, forbidText = true)))).stationId)
    }
    @Test fun `bounds never silently accept first station of a truncated tree`() {
        assertNull(read(root("TBS".repeat(100))).stationId)
        val wide = Node(children = listOf(root("TBS")) + List(256) { Node(forbidText = true) })
        assertNull(read(wide).stationId)
        var deep = root("TBS")
        repeat(18) { deep = Node(children = listOf(deep)) }
        assertNull(read(deep).stationId)
    }
    @Test fun `session active plus mode gates are mandatory`() {
        for (config in listOf(profile(active = false), profile(mode = PreferenceContract.MODE_MANUAL),
            profile(mode = PreferenceContract.MODE_BRAVIA))) {
            val node = Node(forbidText = true)
            assertFalse(config.enabled); assertNull(read(node, config).stationId); assertEquals(0, node.reads)
        }
    }
    @Test fun `comma newline exact packages do not imply substring permission`() {
        val config = profile(packages = " test.tv,\nsecond.tv\r\n")
        assertEquals(setOf("test.tv", "second.tv"), config.packages)
        assertNull(read(Node(packageName = "test.tv.fake", forbidText = true), config).stationId)
    }
    @Test fun `conflicting calibrated text and description is unknown`() {
        val r = Node(children = listOf(Node(resourceId = station, label = "TBS", desc = "日テレ"), Node(resourceId = live)))
        assertNull(read(r).stationId)
    }
    @Test fun `nested JSON or oversized settings fail closed`() {
        assertNull(boundedJsonObject("[".repeat(30) + "]".repeat(30), 1000))
        assertFalse(profile(aliases = "x".repeat(17000)).enabled)
    }
}
