package dev.nicotv.detection

/** Metadata is inspected within ONE active root; text access is restricted to calibrated IDs. */
internal interface EvidenceNode : AutoCloseable {
    val packageName: String?
    val resourceId: String?
    val visible: Boolean
    val childCount: Int
    val collection: Boolean
    val bounds: EvidenceBounds? get() = null
    fun text(): CharSequence?
    fun description(): CharSequence?
    fun child(index: Int): EvidenceNode?
}

internal data class EvidenceBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun covers(screen: EvidenceBounds): Boolean {
        val w = screen.right.toLong() - screen.left
        val h = screen.bottom.toLong() - screen.top
        return w > 0 && h > 0 && right > left && bottom > top &&
            left <= screen.left + w / 20 && top <= screen.top + h / 20 &&
            right >= screen.right - w / 20 && bottom >= screen.bottom - h / 20 &&
            left >= screen.left - w / 20 && top >= screen.top - h / 20 &&
            right <= screen.right + w / 20 && bottom <= screen.bottom + h / 20
    }
}

internal data class ForegroundIdentity(val packageName: String, val windowId: Int)
internal data class StationEvidence(
    val stationId: String?,
    val foreground: ForegroundIdentity?,
    val reason: String,
    val retainStation: Boolean = false,
)

internal object StationEvidenceReader {
    /** Caller closes the root. Every acquired child is closed, including early rejection. */
    fun read(root: EvidenceNode, foreground: ForegroundIdentity, profile: DetectionProfile): StationEvidence {
        fun unknown(reason: String) = StationEvidence(null, foreground, reason)
        if (!profile.enabled) return unknown("自動検出の開始・校正が必要です")
        if (foreground.packageName !in profile.packages || root.packageName != foreground.packageName) {
            return unknown("テレビアプリが前面にありません")
        }
        val screen = if (profile.transientOsd) root.bounds else null
        val maxDepth = if (profile.transientOsd) 32 else DetectionLimits.MAX_DEPTH
        var nodes = 0
        var characters = 0
        var live = false
        var markerBounds: EvidenceBounds? = null
        var invalid = false
        var labels = 0
        val candidates = mutableSetOf<String>()
        fun visit(node: EvidenceNode, depth: Int) {
            if (invalid) return
            if (++nodes > DetectionLimits.MAX_NODES || depth > maxDepth) {
                invalid = true; return
            }
            // Never access foreign package text, descriptions or descendants.
            if (node.packageName != foreground.packageName) { invalid = true; return }
            if (!node.visible) return
            // A list/grid item is not tuned-channel evidence even when focused/selected.
            if (node.collection) { invalid = true; return }
            val id = node.resourceId
            if (id in profile.liveIds) {
                markerBounds = node.bounds
                // AQUOS retains this ID in its EPG as a 252x140 thumbnail. ID alone is not live evidence.
                if (!profile.transientOsd || (screen != null && node.bounds?.covers(screen) == true)) live = true
            } // Marker TEXT is deliberately never read.
            if (id in profile.stationIds) {
                var hasLabel = false
                for (raw in listOf(node.text(), node.description())) {
                    if (raw == null || raw.isBlank()) continue
                    characters += raw.length
                    if (raw.length > DetectionLimits.MAX_LABEL_CHARS || characters > DetectionLimits.MAX_TEXT_CHARS) {
                        invalid = true; return
                    }
                    val normalized = normalizeLabel(raw.toString())
                    if (normalized.isEmpty()) continue
                    hasLabel = true
                    val station = profile.aliases[normalized]
                    if (station == null) { invalid = true; return }
                    candidates += station
                }
                if (hasLabel) labels++
            }
            val count = node.childCount
            if (count < 0 || count > DetectionLimits.MAX_NODES - nodes ||
                (depth >= maxDepth && count > 0)) { invalid = true; return }
            for (index in 0 until count) {
                val child = node.child(index)
                if (child == null) { invalid = true; return }
                child.use { visit(it, depth + 1) }
                if (invalid) return
            }
        }
        return try {
            visit(root, 0)
            when {
                invalid -> unknown("局情報が曖昧・一覧表示・読み取り上限超過です")
                !live -> unknown("校正済みのライブ表示を確認できません" +
                    if (profile.transientOsd) " (root=$screen marker=$markerBounds nodes=$nodes)" else "")
                labels == 0 && profile.transientOsd -> StationEvidence(null, foreground,
                    "AQUOSの全画面ライブを再確認・OSDは非表示", retainStation = true)
                labels == 0 || candidates.size != 1 -> unknown("局を一意に確認できません")
                else -> StationEvidence(candidates.single(), foreground, "校正済みライブ局ラベル")
            }
        } catch (_: RuntimeException) {
            unknown("画面情報を読み取れません")
        }
    }
}
