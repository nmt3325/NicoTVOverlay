package dev.nicotv.core
import kotlinx.coroutines.flow.Flow

data class Station(val id: String, val name: String, val nicoChannelId: String, val aliases: List<String>)
enum class CommentOrigin { NICONICO, NX_JIKKYO, NX_KAKOLOG, DEMO }
enum class CommentPosition { SCROLL, TOP, BOTTOM }
enum class CommentSize { SMALL, NORMAL, LARGE }
data class LiveComment(val id: String, val text: String, val postedAtMs: Long, val position: CommentPosition = CommentPosition.SCROLL, val size: CommentSize = CommentSize.NORMAL, val color: Int = 0xFFFFFFFF.toInt(), val origin: CommentOrigin = CommentOrigin.NICONICO)
enum class ConnectionState { IDLE, RESOLVING, CONNECTING, LIVE, RECONNECTING, NO_PROGRAM, ERROR }
sealed interface StreamEvent {
 data class State(val state: ConnectionState, val message: String, val origin: CommentOrigin) : StreamEvent
 data class Comment(val comment: LiveComment) : StreamEvent
}
interface CommentSource { fun stream(station: Station): Flow<StreamEvent> }
data class OverlayPreferences(val fontScale: Float = 1f, val opacity: Float = 0.8f, val speed: Float = 1f, val maxVisible: Int = 60, val delayMs: Long = 0L, val ngWords: List<String> = emptyList(), val showFixed: Boolean = true)
enum class DetectionOrigin { ACCESSIBILITY, BRAVIA, MANUAL }
/**
 * 録画再生の時間対応。過去ログは放送時刻順に並ぶため、再生開始時点の放送時刻（anchorMs）と
 * 取り扱う最大の長さだけを保持する。映像・チャンネル操作には一切関与しない。
 */
data class RecordedPlan(val programStartMs: Long, val offsetMs: Long = 0L, val windowMs: Long = 6 * 60 * 60 * 1000L) {
    val anchorMs: Long get() = programStartMs + offsetMs
}
/**
 * 録画再生画面から読み取った観測。programStartMs は放送開始の実時刻（epoch ms）、
 * positionMs は再生位置、observedAtMs は端末の単調時計。番組名などの本文は含めない。
 */
data class RecordedObservation(
    val stationId: String?, val programStartMs: Long, val positionMs: Long,
    val origin: DetectionOrigin, val observedAtMs: Long, val detail: String,
)
data class StationObservation(val stationId: String?, val origin: DetectionOrigin, val observedAtMs: Long, val watchingTv: Boolean, val detail: String)
object PreferenceContract {
 const val SESSION_ACTIVE = "session_active"
 const val OSD_RESOURCE_IDS = "osd_resource_ids"
 const val LIVE_RESOURCE_IDS = "live_resource_ids"
 const val BRAVIA_CHANNEL_MAP = "bravia_channel_map"
 const val STORE = "nicotv"
 const val DETECTION_MODE = "detection_mode"
 const val TV_PACKAGES = "tv_packages"
 const val CUSTOM_ALIASES = "custom_aliases"
 const val RECORDED_RESOURCE_IDS = "recorded_resource_ids"
 const val RECORDED_START = "recorded_start"
 const val RECORDED_OFFSET = "recorded_offset_ms"
 const val RECORDED_ADJUST = "recorded_adjust_ms"
 const val RECORDED_AUTO = "recorded_auto"
 const val MODE_MANUAL = "manual"
 const val MODE_ACCESSIBILITY = "accessibility"
 const val MODE_BRAVIA = "bravia"
 val DEFAULT_TV_PACKAGES = listOf("com.sony.dtv.tvx", "com.sony.dtv.tvplayer", "com.android.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.tcl.tv", "jp.co.sharp.android.tv")
}
