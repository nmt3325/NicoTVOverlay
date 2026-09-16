package dev.nicotv.app

import android.content.Context
import android.content.SharedPreferences
import dev.nicotv.core.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class Backend(val label: String, val origin: CommentOrigin) {
    OFFICIAL("ニコニコ公式", CommentOrigin.NICONICO),
    NX("NX-Jikkyo（別サービス）", CommentOrigin.NX_JIKKYO),
    KAKOLOG("録画の過去ログ", CommentOrigin.NX_KAKOLOG)
}

data class AppSettings(
    val mode: String = PreferenceContract.MODE_MANUAL,
    val stationId: String = "jk4",
    val backend: Backend = Backend.OFFICIAL,
    val overlay: OverlayPreferences = OverlayPreferences(),
    val tvPackages: String = PreferenceContract.DEFAULT_TV_PACKAGES.joinToString(","),
    val osdIds: String = "",
    val liveIds: String = "",
    val aliasesJson: String = "{}",
    val braviaHost: String = "",
    val braviaMapJson: String = "{}",
    /** 録画再生画面で放送日時・放送局・再生位置を表示するビューID。 */
    val recordedIds: String = "",
    /** true で録画再生画面から放送日時を自動取得、false で手入力。 */
    val recordedAuto: Boolean = true,
    val recordedStartMs: Long = 0L,
    val recordedOffsetMs: Long = 0L,
    /** 自動取得した放送時刻への±補正。 */
    val recordedAdjustMs: Long = 0L
) {
    val calibrated: Boolean get() = SettingsValidator.entries(tvPackages).isNotEmpty() &&
        SettingsValidator.entries(osdIds).isNotEmpty() && SettingsValidator.entries(liveIds).isNotEmpty()
    val braviaVisibilityCalibrated: Boolean get() = SettingsValidator.entries(tvPackages).isNotEmpty() && SettingsValidator.entries(liveIds).isNotEmpty()
    val recordedCalibrated: Boolean get() = SettingsValidator.entries(tvPackages).isNotEmpty() &&
        SettingsValidator.entries(recordedIds).isNotEmpty()
    /** 手入力時の時間対応。自動取得時は観測から SessionController が作る。 */
    fun recordedPlan(): RecordedPlan = RecordedPlan(recordedStartMs, recordedOffsetMs)
    val modeLabel: String get() = when (mode) {
        PreferenceContract.MODE_ACCESSIBILITY -> "自動OSD"
        PreferenceContract.MODE_BRAVIA -> "BRAVIA・実験"
        else -> "手動固定"
    }
}

object SettingsValidator {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val resourcePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+:id/[A-Za-z0-9_]+")
    fun entries(value: String): List<String> = value.split(',', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
    fun isPrivateIpv4(host: String): Boolean {
        val pieces = host.split('.')
        if (pieces.size != 4) return false
        val n = pieces.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 } || pieces.zip(n).any { (s, i) -> s != i.toString() }) return false
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
    fun stationMap(raw: String, uriKeys: Boolean = false): Map<String, String> {
        require(raw.length <= 16_384) { "JSONは16KB以下にしてください" }
        val obj = try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException("対応表はJSONオブジェクトで入力してください") }
        require(obj.length() <= 100) { "対応表は100件以下にしてください" }
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(key.isNotBlank() && key.length <= 256 && !key.any { it.isISOControl() }) { "対応表のキーが不正です" }
                require(!uriKeys || key.startsWith("tv:")) { "BRAVIA対応表のキーはtv:で始まるURIです" }
                require(uriKeys || !key.all { it.isDigit() || it.isWhitespace() }) { "番号だけの局別名は使用できません" }
                val id = obj.get(key)
                require(id is String && StationCatalog.find(id) != null) { "対応表の値は既知のjk IDにしてください" }
                put(key, id as String)
            }
        }
    }
    const val RECORDED_MIN_MS = 1_257_000_000_000L // 過去ログの提供が始まる2009年11月ごろ
    const val RECORDED_MAX_MS = 4_102_444_800_000L
    const val RECORDED_MAX_OFFSET_MS = 43_200_000L
    const val RECORDED_MAX_ADJUST_MS = 600_000L
    /** 端末のタイムゾーンで放送日時を解釈する。曖昧な入力は保存しない。 */
    fun recordedStart(date: String, time: String): Long? = try {
        val raw = time.trim()
        LocalDate.parse(date.trim()).atTime(LocalTime.parse(if (raw.length == 5) raw + ":00" else raw))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().takeIf { it in RECORDED_MIN_MS..RECORDED_MAX_MS }
    } catch (_: Exception) { null }
    fun recordedFields(ms: Long): Pair<String, String> = if (ms < RECORDED_MIN_MS) "" to "" else try {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .let { it.toLocalDate().toString() to "%02d:%02d".format(it.hour, it.minute) }
    } catch (_: Exception) { "" to "" }
    /** 秒数・mm:ss・hh:mm:ss のいずれかだけを受け取る。 */
    fun offsetMs(text: String): Long? {
        val parts = text.trim().ifEmpty { "0" }.split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() || it.length > 6 || !it.all(Char::isDigit) }) return null
        val values = parts.map { it.toLongOrNull() ?: return null }
        val seconds = when (values.size) {
            1 -> values[0]
            2 -> values[0] * 60 + values[1]
            else -> values[0] * 3600 + values[1] * 60 + values[2]
        }
        return (seconds * 1000).takeIf { it in 0L..RECORDED_MAX_OFFSET_MS }
    }
    fun offsetText(ms: Long): String {
        val total = (ms / 1000).coerceIn(0, RECORDED_MAX_OFFSET_MS / 1000)
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }
    fun validate(s: AppSettings): List<String> = buildList {
        if (s.mode !in setOf(PreferenceContract.MODE_MANUAL, PreferenceContract.MODE_ACCESSIBILITY, PreferenceContract.MODE_BRAVIA)) add("検出モードが不正です")
        if (StationCatalog.find(s.stationId) == null) add("手動局を選んでください")
        if (!s.overlay.fontScale.isFinite() || s.overlay.fontScale !in 0.6f..2f) add("文字サイズは60〜200%です")
        if (!s.overlay.opacity.isFinite() || s.overlay.opacity !in 0.1f..0.8f) add("透明度は10〜80%です")
        if (!s.overlay.speed.isFinite() || s.overlay.speed !in 0.5f..2f) add("速度は50〜200%です")
        if (s.overlay.delayMs !in 0L..30_000L) add("遅延は0〜30秒です")
        if (s.overlay.maxVisible !in 1..100) add("表示数は1〜100件です")
        if (s.overlay.ngWords.size > 100 || s.overlay.ngWords.any { it.length > 100 }) add("NGワードは各100文字・100件以下です")
        if (s.tvPackages.length > 4096 || entries(s.tvPackages).size > 32 || entries(s.tvPackages).any { !packagePattern.matches(it) }) add("TV_PACKAGESは完全なパッケージ名を32件以下で入力してください")
        for ((name, value) in listOf("OSD_RESOURCE_IDS" to s.osdIds, "LIVE_RESOURCE_IDS" to s.liveIds)) {
            if (value.length > 8192 || entries(value).size > 64 || entries(value).any { !resourcePattern.matches(it) }) add("$name は package:id/name 形式で64件以下です")
        }
        for ((raw, uri) in listOf(s.aliasesJson to false, s.braviaMapJson to true)) {
            try { stationMap(raw, uri) } catch (e: IllegalArgumentException) { add(e.message ?: "JSONが不正です") }
        }
        if (s.braviaHost.isNotEmpty() && !isPrivateIpv4(s.braviaHost)) add("BRAVIAは私有IPv4のみです（10.* / 172.16〜31.* / 192.168.*）")
        if (s.recordedIds.length > 8192 || entries(s.recordedIds).size > 16 || entries(s.recordedIds).any { !resourcePattern.matches(it) }) add("RECORDED_RESOURCE_IDS は package:id/name 形式で16件以下です")
        if (s.recordedStartMs != 0L && s.recordedStartMs !in RECORDED_MIN_MS..RECORDED_MAX_MS) add("録画番組の放送日時は2009年11月以降にしてください")
        if (s.recordedOffsetMs !in 0L..RECORDED_MAX_OFFSET_MS) add("録画の再生位置は0〜12時間です")
        if (s.recordedAdjustMs !in -RECORDED_MAX_ADJUST_MS..RECORDED_MAX_ADJUST_MS) add("同期の補正は±10分までです")
        if (s.backend == Backend.KAKOLOG && s.recordedAuto && s.mode != PreferenceContract.MODE_ACCESSIBILITY) add("録画の自動取得は自動検出（ユーザー補助）モードで使います")
        if (s.backend == Backend.KAKOLOG && !s.recordedAuto && s.mode != PreferenceContract.MODE_MANUAL) add("録画の手入力は手動の局指定で使います")
    }
}

class SettingsRepository(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences(PreferenceContract.STORE, Context.MODE_PRIVATE)
    fun read(): AppSettings {
        val p = preferences
        return try {
            AppSettings(
                mode = p.getString(PreferenceContract.DETECTION_MODE, PreferenceContract.MODE_MANUAL)!!,
                stationId = p.getString("manual_station", "jk4")!!,
                backend = Backend.entries.firstOrNull { it.name == p.getString("backend", null) } ?: Backend.OFFICIAL,
                overlay = OverlayPreferences(p.getFloat("font", 1f), p.getFloat("opacity", 0.8f), p.getFloat("speed", 1f),
                    p.getInt("max_visible", 60), p.getLong("delay_ms", 0), p.getString("ng_words", "")!!.lines().map(String::trim).filter(String::isNotEmpty), p.getBoolean("show_fixed", true)),
                tvPackages = p.getString(PreferenceContract.TV_PACKAGES, PreferenceContract.DEFAULT_TV_PACKAGES.joinToString(","))!!,
                osdIds = p.getString(PreferenceContract.OSD_RESOURCE_IDS, "")!!,
                liveIds = p.getString(PreferenceContract.LIVE_RESOURCE_IDS, "")!!,
                aliasesJson = p.getString(PreferenceContract.CUSTOM_ALIASES, "{}")!!,
                braviaHost = p.getString("bravia_host", "")!!,
                braviaMapJson = p.getString(PreferenceContract.BRAVIA_CHANNEL_MAP, "{}")!!,
                recordedIds = p.getString(PreferenceContract.RECORDED_RESOURCE_IDS, "")!!,
                recordedAuto = p.getBoolean(PreferenceContract.RECORDED_AUTO, true),
                recordedStartMs = p.getLong(PreferenceContract.RECORDED_START, 0L),
                recordedOffsetMs = p.getLong(PreferenceContract.RECORDED_OFFSET, 0L),
                recordedAdjustMs = p.getLong(PreferenceContract.RECORDED_ADJUST, 0L)
            )
        } catch (_: ClassCastException) { AppSettings() }
    }
    fun save(s: AppSettings) {
        require(SettingsValidator.validate(s).isEmpty()) { "設定を確認してください" }
        check(preferences.edit()
            .putString(PreferenceContract.DETECTION_MODE, s.mode).putString("manual_station", s.stationId)
            .putString("backend", s.backend.name).putFloat("font", s.overlay.fontScale).putFloat("opacity", s.overlay.opacity)
            .putFloat("speed", s.overlay.speed).putInt("max_visible", s.overlay.maxVisible).putLong("delay_ms", s.overlay.delayMs)
            .putString("ng_words", s.overlay.ngWords.joinToString("\n")).putBoolean("show_fixed", s.overlay.showFixed)
            .putString(PreferenceContract.TV_PACKAGES, s.tvPackages).putString(PreferenceContract.OSD_RESOURCE_IDS, s.osdIds)
            .putString(PreferenceContract.LIVE_RESOURCE_IDS, s.liveIds).putString(PreferenceContract.CUSTOM_ALIASES, s.aliasesJson)
            .putString("bravia_host", s.braviaHost).putString(PreferenceContract.BRAVIA_CHANNEL_MAP, s.braviaMapJson)
            .putString(PreferenceContract.RECORDED_RESOURCE_IDS, s.recordedIds).putBoolean(PreferenceContract.RECORDED_AUTO, s.recordedAuto)
            .putLong(PreferenceContract.RECORDED_START, s.recordedStartMs).putLong(PreferenceContract.RECORDED_OFFSET, s.recordedOffsetMs)
            .putLong(PreferenceContract.RECORDED_ADJUST, s.recordedAdjustMs).commit())
    }
    fun setSessionActive(active: Boolean) { check(preferences.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, active).commit()) }
}
