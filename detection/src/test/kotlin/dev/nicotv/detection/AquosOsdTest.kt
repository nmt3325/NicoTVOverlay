package dev.nicotv.detection

import dev.nicotv.core.AquosProfile
import dev.nicotv.core.PreferenceContract
import org.junit.Assert.*
import org.junit.Test

class AquosOsdTest {
    private val tv = ForegroundIdentity(AquosProfile.PACKAGE, 17)
    private val full = EvidenceBounds(0, 0, 1920, 1080)
    private fun profile() = DetectionProfile.parse(true, PreferenceContract.MODE_ACCESSIBILITY,
        AquosProfile.PACKAGE, AquosProfile.STATION, AquosProfile.LIVE, AquosProfile.aliasesJson)
    private class Node(override val resourceId: String? = null, private val label: String? = null,
        override val bounds: EvidenceBounds? = EvidenceBounds(0, 0, 1920, 1080),
        val children: List<Node> = emptyList(), override val packageName: String? = AquosProfile.PACKAGE,
        override val visible: Boolean = true, override val collection: Boolean = false,
        private val dropChild: Boolean = false) : EvidenceNode {
        override val childCount get() = children.size
        override fun text(): CharSequence? {
            check(resourceId == AquosProfile.STATION) { "Uncalibrated text must not be read" }
            return label
        }
        override fun description(): CharSequence? {
            check(resourceId == AquosProfile.STATION)
            return null
        }
        override fun child(index: Int) = if (dropChild) null else children[index]
        override fun close() {}
    }
    private fun read(label: String? = null, marker: EvidenceBounds? = full,
        identity: ForegroundIdentity = tv): StationEvidence {
        val nodes = mutableListOf(Node(AquosProfile.LIVE, bounds = marker))
        if (label != null) nodes += Node(AquosProfile.STATION, label)
        return StationEvidenceReader.read(Node(children = nodes), identity, profile())
    }
    @Test fun exactPhysicalTvLabelsResolveWithoutRemoteNumberGuessing() {
        assertTrue(profile().transientOsd)
        assertEquals("jk1", read("ＮＨＫ総合１・東京").stationId)
        assertEquals("jk2", read("ＮＨＫＥテレ１東京").stationId)
        assertEquals("jk4", read("日テレ１").stationId)
        for (label in listOf("041", "4", "日テレ１の番組", "録画 日テレ１", "未対応局")) {
            val e = read(label); assertNull(e.stationId); assertFalse(e.retainStation)
        }
    }
    @Test fun observedEpgThumbnailCannotEstablishOrRetainAStation() {
        for (label in listOf(null, "日テレ１")) {
            val e = read(label, EvidenceBounds(0, 0, 252, 140))
            assertNull(e.stationId); assertFalse(e.retainStation)
        }
        assertFalse(read(marker = null).retainStation)
        assertFalse(read(marker = EvidenceBounds(0, 0, 1920, 540)).retainStation)
    }
    @Test fun absentOsdNeverBootstrapsAnUnknownStation() {
        var now = 0L; val policy = DetectionPolicy({ now }, {})
        policy.authorize(true)
        assertTrue(read().retainStation)
        repeat(5) { now += 1000; policy.evidence(read()); assertNull(policy.observation.stationId) }
    }
    @Test fun freshFullScreenScansKeepConfirmedStationBeyondTransientOsdAndThirtySeconds() {
        var now = 0L; val policy = DetectionPolicy({ now }, {})
        policy.authorize(true)
        val p = requireNotNull(policy.evidence(read("ＮＨＫ総合１・東京")))
        now = p.dueAt; policy.confirm(p.generation, read("ＮＨＫ総合１・東京"))
        repeat(45) {
            now += 1000; policy.evidence(read()); policy.expire()
            assertEquals("jk1", policy.observation.stationId)
            assertEquals(now, policy.observation.observedAtMs)
        }
    }
    @Test fun switchingClearsOldStationBeforeNewConfirmationAndCannotRetainPendingCandidate() {
        var now = 0L; val policy = DetectionPolicy({ now }, {})
        policy.authorize(true)
        var p = requireNotNull(policy.evidence(read("ＮＨＫ総合１・東京")))
        now = p.dueAt; policy.confirm(p.generation, read("ＮＨＫ総合１・東京"))
        now += 1000; p = requireNotNull(policy.evidence(read("日テレ１")))
        assertNull(policy.observation.stationId)
        policy.evidence(read()); assertNull(policy.observation.stationId); assertNull(policy.pending)
        now += 1000; p = requireNotNull(policy.evidence(read("日テレ１")))
        now = p.dueAt; policy.confirm(p.generation, read("日テレ１"))
        assertEquals("jk4", policy.observation.stationId)
    }
    @Test fun staleScanGapHomeUnknownStationAndTuningInvalidationNeverReplayOldStation() {
        for (reason in 0..4) {
            var now = 0L; val policy = DetectionPolicy({ now }, {})
            policy.authorize(true)
            val p = requireNotNull(policy.evidence(read("日テレ１")))
            now = p.dueAt; policy.confirm(p.generation, read("日テレ１"))
            when (reason) {
                0 -> now += 2501
                1 -> policy.heartbeat(ForegroundIdentity("launcher", 18), true)
                2 -> policy.evidence(read("未対応局"))
                3 -> policy.evidence(read(marker = EvidenceBounds(0, 0, 252, 140)))
                else -> policy.invalidate("選局キー")
            }
            policy.evidence(read()); assertNull(policy.observation.stationId)
        }
    }
    @Test fun continuityRequiresTheSameWindowAndExactProfile() {
        var now = 0L; val policy = DetectionPolicy({ now }, {})
        policy.authorize(true)
        val p = requireNotNull(policy.evidence(read("日テレ１")))
        now = p.dueAt; policy.confirm(p.generation, read("日テレ１"))
        now += 1000; policy.evidence(read(identity = tv.copy(windowId = 18)))
        assertNull(policy.observation.stationId)
        val generic = DetectionProfile.parse(true, PreferenceContract.MODE_ACCESSIBILITY,
            AquosProfile.PACKAGE, AquosProfile.STATION, AquosProfile.LIVE + "," + AquosProfile.PACKAGE + ":id/other", "{}")
        assertFalse(generic.transientOsd)
    }
    @Test fun deepAquosDecorDoesNotRejectAValidScopedLabel() {
        var node = Node(children = listOf(Node(AquosProfile.LIVE), Node(AquosProfile.STATION, "日テレ１")))
        repeat(20) { node = Node(children = listOf(node)) }
        assertEquals("jk4", StationEvidenceReader.read(node, tv, profile()).stationId)
        repeat(20) { node = Node(children = listOf(node)) }
        val tooDeep = StationEvidenceReader.read(node, tv, profile())
        assertNull(tooDeep.stationId); assertFalse(tooDeep.retainStation)
    }

    @Test fun aTreeThatMutatesMidReadIsTransientWhileAListScreenIsNot() {
        val mutating = StationEvidenceReader.read(
            Node(children = listOf(Node(AquosProfile.LIVE)), dropChild = true), tv, profile())
        assertTrue(mutating.transient); assertNull(mutating.stationId); assertFalse(mutating.retainStation)
        val list = StationEvidenceReader.read(
            Node(children = listOf(Node(AquosProfile.LIVE), Node(collection = true))), tv, profile())
        assertFalse(list.transient); assertNull(list.stationId); assertFalse(list.retainStation)
        assertFalse(read("未対応局").transient)
        assertFalse(read().transient)
    }
}
