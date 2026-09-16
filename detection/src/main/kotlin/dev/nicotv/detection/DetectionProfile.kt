package dev.nicotv.detection

import dev.nicotv.core.PreferenceContract
import dev.nicotv.core.AquosProfile
import dev.nicotv.core.StationCatalog
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object DetectionLimits {
    const val MAX_NODES = 256
    const val MAX_DEPTH = 16
    const val MAX_LABEL_CHARS = 256
    const val MAX_TEXT_CHARS = 4096
    const val MIN_SCAN_MS = 250L
    const val HEARTBEAT_MS = 1000L
    const val GUARD_TTL_MS = 2500L
    const val DEBOUNCE_MS = 750L
    const val EVIDENCE_TTL_MS = 30_000L
    const val RESTORE_TTL_MS = 90_000L
    const val RESUME_GRACE_MS = 2_500L
}

/** NFKC followed by whitespace folding, NOT substring/case/number guessing. */
internal fun normalizeLabel(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("[\\p{Z}\\s]+"), " ").trim()

/** Prevent deeply nested/oversized settings and device JSON from exhausting the parser. */
internal fun boundedJsonObject(text: String, maxChars: Int): JsonObject? {
    if (text.length > maxChars) return null
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > 16) return null }
            '}', ']' -> { depth--; if (depth < 0) return null }
        }
    }
    if (depth != 0 || quoted) return null
    return try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
}

internal data class DetectionProfile(
    val sessionActive: Boolean,
    val mode: String,
    val packages: Set<String>,
    val stationIds: Set<String>,
    val liveIds: Set<String>,
    val aliases: Map<String, String>,
    val valid: Boolean,
    /** 録画再生画面で日時・局・位置を表示するビューID。空なら自動取得は無効。 */
    val recordedIds: Set<String> = emptySet(),
) {
    val enabled: Boolean get() = sessionActive && mode == PreferenceContract.MODE_ACCESSIBILITY &&
        valid && packages.isNotEmpty() && stationIds.isNotEmpty() && liveIds.isNotEmpty()
    val guardEnabled: Boolean get() = sessionActive && mode == PreferenceContract.MODE_BRAVIA &&
        valid && packages.isNotEmpty() && liveIds.isNotEmpty()
    /** 録画の放送日時・放送局の自動取得。局ラベル校正（OSD）とは独立に成立する。 */
    val recordedEnabled: Boolean get() = sessionActive && mode == PreferenceContract.MODE_ACCESSIBILITY &&
        valid && packages.isNotEmpty() && recordedIds.isNotEmpty()
    val collecting: Boolean get() = enabled || guardEnabled || recordedEnabled
    // Continuity is opt-in and limited to the exact, physically verified AQUOS profile.
    val transientOsd: Boolean get() = enabled && packages == setOf(AquosProfile.PACKAGE) &&
        stationIds == setOf(AquosProfile.STATION) && liveIds == setOf(AquosProfile.LIVE)

    companion object {
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val idPattern = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+):id/[A-Za-z_][A-Za-z0-9_]*")
        private fun entries(value: String): Set<String> =
            value.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty).toSet()

        fun parse(active: Boolean, mode: String, packages: String, stationIds: String,
                  liveIds: String, customAliases: String, recordedIds: String = ""): DetectionProfile {
            if (mode == PreferenceContract.MODE_BRAVIA) {
                // Foreground guard only: station OSD/aliases are deliberately not parsed or required.
                val bounded = packages.length <= 16_384 && liveIds.length <= 16_384
                val pkgs = if (bounded) entries(packages) else emptySet()
                val markers = if (bounded) entries(liveIds) else emptySet()
                val valid = bounded && pkgs.size <= 32 && markers.size <= 16 &&
                    pkgs.all { packagePattern.matches(it) } && markers.all {
                        idPattern.matchEntire(it)?.groupValues?.get(1) in pkgs
                    }
                return DetectionProfile(active, mode, pkgs, emptySet(), markers, emptyMap(), valid)
            }
            var valid = listOf(packages, stationIds, liveIds, customAliases, recordedIds).all { it.length <= 16_384 }
            // Do not partially apply malformed/oversized settings.
            val pkgs = if (valid) entries(packages) else emptySet()
            val labels = if (valid) entries(stationIds) else emptySet()
            val live = if (valid) entries(liveIds) else emptySet()
            val recorded = if (valid) entries(recordedIds) else emptySet()
            valid = valid && pkgs.size <= 32 && labels.size <= 16 && live.size <= 16 &&
                pkgs.all { packagePattern.matches(it) } &&
                (labels + live).all { id ->
                    idPattern.matchEntire(id)?.groupValues?.get(1) in pkgs
                } && labels.intersect(live).isEmpty() && recorded.size <= 16 &&
                recorded.all { id -> idPattern.matchEntire(id)?.groupValues?.get(1) in pkgs }
            val aliases = mutableMapOf<String, String>()
            for (station in StationCatalog.stations) {
                for (alias in station.aliases + station.name) aliases[normalizeLabel(alias)] = station.id
            }
            val custom = boundedJsonObject(customAliases.ifBlank { "{}" }, 16_384)
            if (custom == null || custom.size > 128) valid = false
            else for ((label, value) in custom) {
                val key = normalizeLabel(label)
                val primitive = value as? JsonPrimitive
                val id = primitive?.takeIf { it.isString }?.content
                if (key.isEmpty() || key.length > DetectionLimits.MAX_LABEL_CHARS ||
                    key.all { it.isDigit() || it.isWhitespace() } || StationCatalog.find(id) == null ||
                    (aliases[key] != null && aliases[key] != id)) {
                    valid = false
                } else aliases[key] = requireNotNull(id)
            }
            return DetectionProfile(active, mode, pkgs, labels, live, aliases.toMap(), valid, recorded)
        }
    }
}
