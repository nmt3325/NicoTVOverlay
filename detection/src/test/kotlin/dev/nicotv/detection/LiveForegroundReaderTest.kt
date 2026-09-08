package dev.nicotv.detection

import dev.nicotv.core.PreferenceContract
import org.junit.Assert.*
import org.junit.Test

class LiveForegroundReaderTest {
    private fun profile(packages: String = "test.tv", live: String = "test.tv:id/live", active: Boolean = true,
                        mode: String = PreferenceContract.MODE_BRAVIA) =
        DetectionProfile.parse(active, mode, packages, "ignored invalid OSD", live, "ignored invalid JSON")
    private class Node(override val packageName: String? = "test.tv", override val resourceId: String? = null,
                       override val visible: Boolean = true, override val collection: Boolean = false,
                       private val children: List<Node> = emptyList()) : EvidenceNode {
        override val childCount get() = children.size
        var closed = false
        override fun child(index: Int) = children[index]
        override fun text(): CharSequence? = throw AssertionError("Guard must not read ANY text")
        override fun description(): CharSequence? = throw AssertionError("Guard must not read ANY description")
        override fun close() { closed = true }
    }
    private fun marker() = Node(resourceId = "test.tv:id/live")
    @Test fun `guard accepts visible calibrated marker without parsing OSD aliases or any text`() {
        val config = profile(); assertTrue(config.guardEnabled); assertFalse(config.enabled)
        assertTrue(config.stationIds.isEmpty()); assertTrue(config.aliases.isEmpty())
        val marker = marker()
        assertTrue(LiveForegroundReader.read(Node(children = listOf(Node(resourceId = "test.tv:id/station"), marker)), "test.tv", config))
        assertTrue(marker.closed)
    }
    @Test fun `explicit package live calibration active mode are all required`() {
        for (p in listOf(profile(packages = ""), profile(packages = "test.tv.*"), profile(live = ""),
            profile(live = "live"), profile(live = "foreign.tv:id/live"), profile(active = false),
            profile(mode = PreferenceContract.MODE_MANUAL), profile(packages = "x".repeat(17000)))) {
            assertFalse(LiveForegroundReader.read(marker(), "test.tv", p))
        }
    }
    @Test fun `Home and foreign subtrees never reveal text or establish guard`() {
        assertFalse(LiveForegroundReader.read(Node(packageName = "test.tv.fake", children = listOf(marker())), "test.tv", profile()))
        assertFalse(LiveForegroundReader.read(Node(children = listOf(marker(), Node(packageName = "foreign.tv"))), "test.tv", profile()))
    }
    @Test fun `guide recording absent invisible and blank marker IDs fail closed`() {
        for (root in listOf(Node(collection = true, children = listOf(marker())),
            Node(resourceId = "test.tv:id/recording"), Node(resourceId = ""), Node(),
            Node(resourceId = "test.tv:id/live", visible = false))) {
            assertFalse(LiveForegroundReader.read(root, "test.tv", profile()))
        }
    }
    @Test fun `tree limits cannot accept a partial marker match`() {
        assertFalse(LiveForegroundReader.read(Node(children = listOf(marker()) + List(256) { Node() }), "test.tv", profile()))
        var deep = marker(); repeat(18) { deep = Node(children = listOf(deep)) }
        assertFalse(LiveForegroundReader.read(deep, "test.tv", profile()))
    }
}
