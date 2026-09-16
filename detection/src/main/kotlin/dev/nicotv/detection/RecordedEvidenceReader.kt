package dev.nicotv.detection

import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.RecordedObservation
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** 録画再生画面の観測。番組名やあらすじなどの本文は読まず、日時・局・位置だけを保持する。 */
internal data class RecordedEvidence(
    val stationId: String?,
    val programStartMs: Long,
    val positionMs: Long,
    val reason: String,
)

/**
 * 校正済みIDのテキストから「放送日時」「放送局」「再生位置」だけを取り出す純粋な解析。
 * 部分一致や番号からの局推測はせず、局は別名表の完全一致だけで決める。
 */
internal object RecordedTextParser {
    const val MAX_POSITION_MS = 12 * 60 * 60 * 1000L
    // 「2026/9/13 21:00」「9/13(土) 21:00〜21:54」「9月13日 21:00」など、日付と開始時刻のみ。
    private val datePart = Regex("(?:(20[0-9]{2})\\s*[/年.-]\\s*)?([0-9]{1,2})\\s*[/月.-]\\s*([0-9]{1,2})")
    // 実機（AQUOS）の画面表示は「9/2(水) 午後11:30～午前0:00」形式。午前/午後は12時間表記として扱う。
    private val timePart = Regex("(午前|午後|AM|PM|am|pm)?\\s*([0-9]{1,2}):([0-9]{2})")
    private const val TIME_WINDOW_CHARS = 24
    // 文字列全体が時間表記のノードだけを位置候補にする。
    private val clock = Regex("(?:([0-9]{1,2}):)?([0-9]{1,2}):([0-9]{2})")

    /** 年の表記がない画面表示では、いまを越えない直近の年として解釈する。 */
    fun broadcastStartMs(text: String, nowWallMs: Long, zone: ZoneId): Long? {
        val date = datePart.find(text) ?: return null
        val year = date.groupValues[1].toIntOrNull()
        val month = date.groupValues[2].toIntOrNull() ?: return null
        val day = date.groupValues[3].toIntOrNull() ?: return null
        // 日付の直後に現れる最初の時刻だけを開始時刻として使う（終了時刻は読まない）。
        val time = timePart.find(text.substring(date.range.last + 1).take(TIME_WINDOW_CHARS)) ?: return null
        val marker = time.groupValues[1]
        val rawHour = time.groupValues[2].toIntOrNull() ?: return null
        val minute = time.groupValues[3].toIntOrNull() ?: return null
        val am = marker == "午前" || marker.equals("AM", ignoreCase = true)
        val pm = marker == "午後" || marker.equals("PM", ignoreCase = true)
        if ((am || pm) && rawHour !in 0..12) return null
        val hour = when {
            am -> if (rawHour == 12) 0 else rawHour
            pm -> if (rawHour == 12 || rawHour == 0) 12 else rawHour + 12
            else -> rawHour
        }
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val today = try { java.time.Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate() } catch (_: DateTimeException) { return null }
        val years = if (year != null) listOf(year) else listOf(today.year, today.year - 1)
        for (candidate in years) {
            val at = try {
                LocalDate.of(candidate, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeException) { continue }
            // 未来の日時は録画済み番組ではない（時計ずれの猶予として1日だけ許す）。
            if (at <= nowWallMs + 86_400_000L) return at
        }
        return null
    }

    fun clockMs(text: String): Long? {
        val match = clock.matchEntire(text.trim()) ?: return null
        val a = match.groupValues[1].toLongOrNull()
        val b = match.groupValues[2].toLongOrNull() ?: return null
        val c = match.groupValues[3].toLongOrNull() ?: return null
        if (b > 59 || c > 59) return if (a == null && c <= 59 && b <= 99) null else null
        val seconds = if (a != null) a * 3600 + b * 60 + c else b * 60 + c
        return (seconds * 1000).takeIf { it in 0..MAX_POSITION_MS }
    }

    /**
     * 経過時間の決め方: 最大値は総時間表示とみなし、それ以外の最大を経過時間として扱う。
     * 画面表示の組み合わせに依存するため、ずれは同期ボタンで補正できるようにしている。
     */
    fun positionMs(values: List<Long>): Long? {
        val sorted = values.filter { it in 0..MAX_POSITION_MS }.distinct().sorted()
        return when {
            sorted.isEmpty() -> null
            sorted.size == 1 -> sorted[0]
            else -> sorted[sorted.size - 2]
        }
    }
}

/** 校正済みIDのテキストだけを読む。一覧・別パッケージ・上限超過は証拠にしない。 */
internal object RecordedEvidenceReader {
    fun read(
        root: EvidenceNode,
        foreground: ForegroundIdentity,
        profile: DetectionProfile,
        nowWallMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): RecordedEvidence {
        fun unknown(reason: String) = RecordedEvidence(null, 0L, 0L, reason)
        if (!profile.recordedEnabled) return unknown("録画画面の校正が必要です")
        if (foreground.packageName !in profile.packages || root.packageName != foreground.packageName) {
            return unknown("テレビアプリが前面にありません")
        }
        var nodes = 0
        var characters = 0
        var rejected: String? = null
        val stations = mutableSetOf<String>()
        val starts = mutableSetOf<Long>()
        val clocks = mutableListOf<Long>()
        fun visit(node: EvidenceNode, depth: Int) {
            if (rejected != null) return
            if (++nodes > DetectionLimits.MAX_NODES) { rejected = "nodes"; return }
            if (depth > 32) { rejected = "depth"; return }
            if (node.packageName != foreground.packageName) { rejected = "package"; return }
            if (!node.visible) return
            // 一覧で選択中の番組は再生中の番組ではない。
            if (node.collection) { rejected = "list"; return }
            if (node.resourceId in profile.recordedIds) {
                for (raw in listOf(node.text(), node.description())) {
                    if (raw == null || raw.isBlank()) continue
                    characters += raw.length
                    if (raw.length > DetectionLimits.MAX_LABEL_CHARS || characters > DetectionLimits.MAX_TEXT_CHARS) {
                        rejected = "text"; return
                    }
                    val value = normalizeLabel(raw.toString())
                    if (value.isEmpty()) continue
                    profile.aliases[value]?.let { stations += it }
                    RecordedTextParser.clockMs(value)?.let { clocks += it }
                    RecordedTextParser.broadcastStartMs(value, nowWallMs, zone)?.let { starts += it }
                }
            }
            val count = node.childCount
            if (count < 0 || count > DetectionLimits.MAX_NODES - nodes || (depth >= 32 && count > 0)) { rejected = "children"; return }
            for (index in 0 until count) {
                val child = node.child(index)
                if (child == null) { rejected = "missing"; return }
                child.use { visit(it, depth + 1) }
                if (rejected != null) return
            }
        }
        return try {
            visit(root, 0)
            val failure = rejected
            when {
                failure == "list" -> unknown("一覧表示では再生中の録画を確認できません")
                failure != null -> unknown("録画画面を読み取れません ($failure nodes=$nodes)")
                stations.size > 1 -> unknown("録画の放送局を一意に確認できません（別名表に登録すると認識します）")
                starts.size != 1 -> unknown("録画の放送日時を一意に確認できません")
                // 実機の録画再生の画面表示には放送局が出ないため、設定で選んだ放送局を使う。
                stations.isEmpty() && profile.recordedStationId == null ->
                    unknown("録画の放送局が画面に出ないため、設定で放送局を選んでください")
                else -> {
                    val station = stations.firstOrNull() ?: requireNotNull(profile.recordedStationId)
                    val position = RecordedTextParser.positionMs(clocks)
                    val reason = when {
                        stations.isEmpty() && position == null -> "放送日時を自動取得（放送局は設定・再生位置は先頭）"
                        stations.isEmpty() -> "放送日時と再生位置を自動取得（放送局は設定）"
                        position == null -> "放送日時と放送局を自動取得（再生位置は先頭）"
                        else -> "録画の放送日時と放送局を確認"
                    }
                    RecordedEvidence(station, starts.single(), position ?: 0L, reason)
                }
            }
        } catch (_: RuntimeException) { unknown("録画画面を読み取れません") }
    }
}

internal fun recordedObservation(evidence: RecordedEvidence, monotonicMs: Long) = RecordedObservation(
    evidence.stationId, evidence.programStartMs, evidence.positionMs,
    DetectionOrigin.ACCESSIBILITY, monotonicMs, evidence.reason,
)
