package dev.nicotv.core
import kotlinx.coroutines.flow.Flow

data class Station(val id: String, val name: String, val nicoChannelId: String, val aliases: List<String>)
enum class CommentOrigin { NICONICO, NX_JIKKYO, DEMO }
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
 const val MODE_MANUAL = "manual"
 const val MODE_ACCESSIBILITY = "accessibility"
 const val MODE_BRAVIA = "bravia"
 val DEFAULT_TV_PACKAGES = listOf("com.sony.dtv.tvx", "com.sony.dtv.tvplayer", "com.android.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.tcl.tv", "jp.co.sharp.android.tv")
}
