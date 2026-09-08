package dev.nicotv.comment

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal suspend fun readNxRoom(
    wire: CommentWire,
    room: JsonObject,
    gate: CommentGate,
    cutoff: Long,
    options: CommentOptions,
    emit: EmitEvent,
): Unit = coroutineScope {
    val url = ServiceUrls.nxSocket(room.obj("messageServer")?.str("uri") ?: protocolFailure())
    val thread = room.str("threadId")?.takeIf { Regex("[0-9]{1,20}").matches(it) } ?: protocolFailure()
    val key = room.str("yourPostKey")?.takeIf { it.length <= 8192 } ?: protocolFailure()
    val socket = withTimeout(options.handshakeMs) { wire.socket(url) }
    val handshake = launch { delay(options.handshakeMs); throw StreamFailure() }
    try {
        val request = buildJsonArray {
            for (p in listOf("rs:0", "ps:0")) add(buildJsonObject { putJsonObject("ping") { put("content", p) } })
            add(buildJsonObject { putJsonObject("thread") {
                put("thread", thread); put("version", "20061206"); put("threadkey", key)
                put("user_id", ""); put("res_from", 0)
            } })
            for (p in listOf("pf:0", "rf:0")) add(buildJsonObject { putJsonObject("ping") { put("content", p) } })
        }
        socket.send(request.toString())
        var live = false
        while (currentCoroutineContext().isActive) {
            // No chat is not a failure. WS protocol pings detect a dead transport;
            // the watch session's heartbeat and schedule govern the quiet comment socket.
            val root = parseJson(socket.receive())
            val messages = if (root is JsonArray) root else listOf(root)
            if (messages.size > 1024) protocolFailure()
            for (raw in messages) {
                val message = raw as? JsonObject ?: continue
                message.obj("thread")?.let { info ->
                    if (info.str("thread") != thread || info.long("resultcode") != 0L) {
                        throw StreamFailure(ConnectionState.NO_PROGRAM, "NX-Jikkyoの実況スレッドを再確認しています", 30000)
                    }
                    handshake.cancel()
                    if (!live) {
                        emit(StreamEvent.State(ConnectionState.LIVE, "NX-Jikkyo経由で受信中（公式ミラー・NX独自コメント）", CommentOrigin.NX_JIKKYO))
                        live = true
                    }
                }
                if (live) message.obj("chat")?.let { chat ->
                    gate.accept(nxComment(thread, chat), cutoff, options.wallMs())?.let { emit(StreamEvent.Comment(it)) }
                }
            }
            yield()
        }
    } finally { handshake.cancel(); socket.close() }
}
