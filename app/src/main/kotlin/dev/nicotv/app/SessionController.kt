package dev.nicotv.app

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SessionUiState(
    val active: Boolean = false, val stationId: String? = null,
    val mode: String = PreferenceContract.MODE_MANUAL, val backend: Backend = Backend.OFFICIAL,
    val connection: ConnectionState = ConnectionState.IDLE, val message: String = "停止中",
    val generation: Long = 0, val receivedComments: Long = 0
)
interface CommentSink {
    fun preferences(value: OverlayPreferences)
    fun clear()
    fun comment(value: LiveComment)
}

/** All mutation is serialized on the service's main scope; UI/platform work is injected. */
class SessionController(
    private val scope: CoroutineScope, private val now: () -> Long,
    private val source: (Backend) -> CommentSource, private val sink: CommentSink,
    private val publish: (SessionUiState) -> Unit = {}
) {
    var state = SessionUiState(); private set
    private var settings = AppSettings()
    private var streamJob: Job? = null
    private var epochStarted = 0L
    private var lastObservation: StationObservation? = null
    private var lastTimestamp = Long.MIN_VALUE
    fun start(value: AppSettings) {
        require(SettingsValidator.validate(value).isEmpty())
        state = state.copy(active = true)
        configure(value)
    }
    fun configure(value: AppSettings) {
        if (!state.active) return
        settings = value
        epochStarted = now()
        lastTimestamp = Long.MIN_VALUE
        lastObservation = null
        resetStream()
        sink.preferences(value.overlay)
        state = state.copy(mode = value.mode, backend = value.backend, stationId = null, connection = ConnectionState.IDLE)
        val manual = value.mode == PreferenceContract.MODE_MANUAL
        if (manual) select(StationCatalog.find(value.stationId)?.id, "手動で局を固定")
        else updateMessage(if (value.mode == PreferenceContract.MODE_ACCESSIBILITY && !value.calibrated) "校正が必要です。詳細設定でOSD・ライブ表示IDを登録してください" else "局を待機中（未検出時は非表示）")
    }
    fun observation(value: StationObservation) {
        if (!state.active || settings.mode == PreferenceContract.MODE_MANUAL) return
        val origin = if (settings.mode == PreferenceContract.MODE_ACCESSIBILITY) DetectionOrigin.ACCESSIBILITY else DetectionOrigin.BRAVIA
        if (value.origin != origin || value.observedAtMs < epochStarted || value.observedAtMs < lastTimestamp) return
        val time = now()
        if (value.observedAtMs > time) { unknown("局情報の時刻を確認できません"); return }
        lastTimestamp = value.observedAtMs
        lastObservation = value
        if ((settings.mode == PreferenceContract.MODE_ACCESSIBILITY && !settings.calibrated) ||
            !value.watchingTv || StationCatalog.find(value.stationId) == null || time - value.observedAtMs >= EVIDENCE_TTL_MS) {
            unknown(if (settings.mode == PreferenceContract.MODE_ACCESSIBILITY && !settings.calibrated) "校正が必要です（番号・局文字だけでは判定しません）" else "局未検出・対象外・期限切れ：コメントを停止")
            return
        }
        select(value.stationId, "局を確認")
    }
    fun tick() {
        if (!state.active || settings.mode == PreferenceContract.MODE_MANUAL) return
        val evidence = lastObservation ?: return
        if (now() - evidence.observedAtMs >= EVIDENCE_TTL_MS) unknown("局情報が30秒更新されていないため停止")
    }
    private fun select(id: String?, message: String) {
        if (id == null) { unknown("局を選択してください"); return }
        if (state.stationId == id) return
        resetStream()
        state = state.copy(stationId = id, connection = ConnectionState.RESOLVING, message = message)
        publish(state)
        val generation = state.generation
        val station = StationCatalog.find(id) ?: return
        val backend = settings.backend
        streamJob = scope.launch {
            try {
                source(backend).stream(station).collect { accept(generation, it) }
                if (isCurrent(generation) && state.connection !in setOf(ConnectionState.ERROR, ConnectionState.NO_PROGRAM)) {
                    accept(generation, StreamEvent.State(ConnectionState.ERROR, "", backend.origin))
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { accept(generation, StreamEvent.State(ConnectionState.ERROR, "", backend.origin)) }
        }
    }
    internal fun accept(generation: Long, event: StreamEvent) {
        if (!isCurrent(generation)) return
        when (event) {
            is StreamEvent.Comment -> if (event.comment.origin == settings.backend.origin && state.connection == ConnectionState.LIVE) {
                sink.comment(event.comment)
                state = state.copy(receivedComments = state.receivedComments + 1)
                publish(state)
            }
            is StreamEvent.State -> {
                if (event.origin != settings.backend.origin) return
                if (event.state != ConnectionState.LIVE) sink.clear()
                // External messages can contain URLs/tokens. Only local status text is shown.
                state = state.copy(connection = event.state, message = when (event.state) {
                    ConnectionState.LIVE -> "受信中"
                    ConnectionState.RESOLVING -> "実況番組を確認中"
                    ConnectionState.CONNECTING -> "接続中"
                    ConnectionState.RECONNECTING -> "再接続中"
                    ConnectionState.NO_PROGRAM -> "放送中の実況番組がありません"
                    ConnectionState.ERROR -> "接続エラー：停止・再開始で再試行できます（自動切替なし）"
                    ConnectionState.IDLE -> "待機中"
                })
                publish(state)
            }
        }
    }
    private fun isCurrent(generation: Long) = state.active && state.stationId != null && state.generation == generation
    private fun unknown(message: String) {
        if (state.stationId != null || streamJob != null) resetStream()
        state = state.copy(stationId = null, connection = ConnectionState.IDLE, message = message)
        publish(state)
    }
    private fun updateMessage(message: String) { state = state.copy(message = message); publish(state) }
    private fun resetStream() {
        state = state.copy(generation = state.generation + 1, receivedComments = 0)
        streamJob?.cancel(); streamJob = null
        sink.clear()
    }
    fun stop(message: String = "停止中") {
        state = state.copy(active = false)
        resetStream(); lastObservation = null
        state = state.copy(stationId = null, connection = ConnectionState.IDLE, message = message)
        publish(state)
    }
    companion object { const val EVIDENCE_TTL_MS = 30_000L }
}
