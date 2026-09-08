package dev.nicotv.detection

/** BRAVIA's independent live-screen guard. No text()/description() call exists on this path. */
internal object LiveForegroundReader {
    fun read(root: EvidenceNode, packageName: String, profile: DetectionProfile): Boolean {
        if (!profile.guardEnabled || packageName !in profile.packages) return false
        var nodes = 0
        var marker = false
        var invalid = false
        fun visit(node: EvidenceNode, depth: Int) {
            if (invalid) return
            if (++nodes > DetectionLimits.MAX_NODES || depth > DetectionLimits.MAX_DEPTH ||
                node.packageName != packageName) { invalid = true; return }
            if (!node.visible) return
            if (node.collection) { invalid = true; return } // Guide/list/recording-list is not live evidence.
            if (node.resourceId in profile.liveIds) marker = true
            val count = node.childCount
            if (count < 0 || count > DetectionLimits.MAX_NODES - nodes ||
                (depth >= DetectionLimits.MAX_DEPTH && count > 0)) { invalid = true; return }
            for (index in 0 until count) {
                val child = node.child(index)
                if (child == null) { invalid = true; return }
                child.use { visit(it, depth + 1) }
                if (invalid) return
            }
        }
        return try { visit(root, 0); marker && !invalid } catch (_: RuntimeException) { false }
    }
}
