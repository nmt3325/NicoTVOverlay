package dev.nicotv.comment

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

internal class CommentOptions(
    val wallMs: () -> Long = System::currentTimeMillis,
    val monoMs: () -> Long = { System.nanoTime() / 1000000 },
    val retry: RetryPolicy = RetryPolicy(),
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val handshakeMs: Long = 20000,
    val watchIdleMs: Long = 90000,
    val interViewDelayMs: Long = 250,
)
internal typealias EmitEvent = suspend (StreamEvent) -> Unit

/** Anonymous official-direct receive only; each collection owns an independent session. */
class NicoLiveCommentSource internal constructor(
    private val wire: CommentWire,
    private val options: CommentOptions,
) : CommentSource {
    constructor(client: OkHttpClient = OkHttpClient()) : this(OkHttpWire(client), CommentOptions())
    override fun stream(station: Station): Flow<StreamEvent> = sourceFlow(CommentOrigin.NICONICO, options) { gate, cutoff, emit ->
        val page = ServiceUrls.watchPage(station.nicoChannelId)
        val program = withTimeout(options.handshakeMs) {
            wire.read(page) { Bootstrap.parse(strictUtf8(boundedBytes(it, 4 * 1024 * 1024))) }
        }
        emit(StreamEvent.State(ConnectionState.CONNECTING, "ニコニコ実況（公式から直接）に接続中", CommentOrigin.NICONICO))
        watchSession(wire, program.watchUrl, program.endMs, options, "messageServer") { data ->
            val view = ServiceUrls.stream(data.str("viewUri") ?: protocolFailure())
            readNdgr(wire, view, program.id, gate, cutoff, options, emit)
        }
    }
}

/** Explicit alternative backend. NX mirrors and original comments are both labeled NX_JIKKYO. */
class NxJikkyoCommentSource internal constructor(
    private val wire: CommentWire,
    private val options: CommentOptions,
) : CommentSource {
    constructor(client: OkHttpClient = OkHttpClient()) : this(OkHttpWire(client), CommentOptions())
    override fun stream(station: Station): Flow<StreamEvent> = sourceFlow(CommentOrigin.NX_JIKKYO, options) { gate, cutoff, emit ->
        val watch = ServiceUrls.nxWatch(station.id)
        emit(StreamEvent.State(ConnectionState.CONNECTING, "NX-Jikkyo経由に接続中（公式直結ではありません）", CommentOrigin.NX_JIKKYO))
        watchSession(wire, watch, null, options, "room") { room -> readNxRoom(wire, room, gate, cutoff, options, emit) }
    }
}

private fun sourceFlow(
    origin: CommentOrigin,
    options: CommentOptions,
    attempt: suspend (CommentGate, Long, EmitEvent) -> Unit,
): Flow<StreamEvent> = channelFlow {
    val gate = CommentGate()
    var failures = 0
    while (currentCoroutineContext().isActive) {
        val since = options.monoMs()
        val cutoff = options.wallMs() - 1000
        send(StreamEvent.State(ConnectionState.RESOLVING, "現在の実況番組を確認中", origin))
        val failure = try {
            attempt(gate, cutoff) { send(it) }
            StreamFailure()
        } catch (e: Exception) {
            // Do not turn collection cancellation into a reconnect (including IO cancellation races).
            currentCoroutineContext().ensureActive()
            e as? StreamFailure ?: (e.cause as? StreamFailure) ?: StreamFailure()
        }
        send(StreamEvent.State(failure.state, failure.safeMessage, origin))
        if (failure.terminal) break
        if (options.monoMs() - since >= 60000) failures = 0
        delay(options.retry.delayMs(failures, failure.waitMs))
        failures = (failures + 1).coerceAtMost(16)
    }
}.buffer(256, BufferOverflow.DROP_OLDEST).flowOn(options.dispatcher)
