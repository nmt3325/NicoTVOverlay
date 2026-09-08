package dev.nicotv.comment

import dev.nicotv.core.ConnectionState
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

internal suspend fun watchSession(
    wire: CommentWire,
    url: HttpUrl,
    initialEndMs: Long?,
    options: CommentOptions,
    endpointType: String,
    endpoint: suspend CoroutineScope.(JsonObject) -> Unit,
): Unit = coroutineScope {
    val socket = withTimeout(options.handshakeMs) { wire.socket(url) }
    var seatJob: Job? = null
    var endJob: Job? = null
    var endpointJob: Job? = null
    var lastEndpoint: String? = null
    val handshake = launch {
        delay(options.handshakeMs)
        throw StreamFailure(safeMessage = "実況サーバーから接続情報が届きません。再接続します")
    }
    fun schedule(endMs: Long?) {
        if (endMs == null) return
        endJob?.cancel()
        endJob = launch {
            delay((endMs - options.wallMs()).coerceAtLeast(0))
            throw StreamFailure(ConnectionState.NO_PROGRAM, "実況番組の切り替わりを確認中", 3000)
        }
    }
    try {
        socket.send("{\"type\":\"startWatching\",\"data\":{\"reconnect\":false}}")
        schedule(initialEndMs)
        while (currentCoroutineContext().isActive) {
            val message = parseJson(withTimeout(options.watchIdleMs) { socket.receive() }) as? JsonObject ?: protocolFailure()
            val data = message.obj("data")
            when (message.str("type")) {
                "ping" -> socket.send("{\"type\":\"pong\"}")
                "seat" -> {
                    val seconds = data?.long("keepIntervalSec")?.takeIf { it in 1..3600 } ?: protocolFailure()
                    seatJob?.cancelAndJoin()
                    seatJob = launch {
                        while (isActive) { delay(seconds * 1000); socket.send("{\"type\":\"keepSeat\"}") }
                    }
                }
                "schedule" -> schedule(instantMs(data?.str("end")))
                endpointType -> {
                    val info = data ?: protocolFailure()
                    val key = if (endpointType == "messageServer") info.str("viewUri") else
                        (info.str("threadId") ?: "") + ":" + (info.obj("messageServer")?.str("uri") ?: "")
                    if (key == null) protocolFailure()
                    handshake.cancel()
                    if (key != lastEndpoint) {
                        endpointJob?.cancelAndJoin()
                        lastEndpoint = key
                        endpointJob = launch { endpoint(info) }
                    }
                }
                "reconnect" -> {
                    val seconds = data?.long("waitTimeSec")?.coerceIn(0, Long.MAX_VALUE / 1000) ?: 0
                    // Re-resolve watch/ch rather than inventing a query parameter for audienceToken.
                    throw StreamFailure(waitMs = maxOf(3000, seconds * 1000))
                }
                "disconnect" -> throw controlFailure(data?.str("reason"))
                "error" -> throw controlFailure(data?.str("code"))
                // serverTime/statistics/akashic/unknown messages are deliberately not comments.
            }
        }
    } finally {
        handshake.cancel(); seatJob?.cancel(); endJob?.cancel(); endpointJob?.cancel()
        socket.close()
    }
}

internal fun controlFailure(code: String?): StreamFailure = when (code) {
    "END_PROGRAM", "NOT_ON_AIR", "BROADCAST_NOT_FOUND", "NO_THREAD_AVAILABLE", "NO_ROOM_AVAILABLE" ->
        StreamFailure(ConnectionState.NO_PROGRAM, "現在の実況番組を再確認しています", 10000)
    "NO_PERMISSION", "USER_BANNED", "TIMESHIFT_PERMISSION_EXPIRED", "TAKEOVER" ->
        StreamFailure(ConnectionState.ERROR, "この実況番組を匿名で受信できません", terminal = true)
    "TOO_MANY_WATCHINGS", "TOO_MANY_CONNECTIONS", "CROWDED", "TEMPORARILY_CROWDED", "MAINTENANCE_IN" ->
        StreamFailure(safeMessage = "実況サーバーが混雑またはメンテナンス中です", waitMs = 60000)
    else -> StreamFailure()
}
