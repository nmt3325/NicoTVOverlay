package dev.nicotv.comment

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import dev.nicotv.core.*
import dwango.nicolive.chat.service.edge.Payload.ChunkedEntry
import dwango.nicolive.chat.service.edge.Payload.ChunkedMessage
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal fun decodeEntry(frame: ByteArray): ChunkedEntry = ChunkedEntry.parseFrom(limitedProto(frame))
internal fun decodeMessage(frame: ByteArray): ChunkedMessage = ChunkedMessage.parseFrom(limitedProto(frame))
private fun limitedProto(frame: ByteArray): CodedInputStream = CodedInputStream.newInstance(frame).apply {
    setRecursionLimit(32); setSizeLimit(FrameReader.MAX_FRAME)
}

internal suspend fun readNdgr(
    wire: CommentWire,
    view: HttpUrl,
    program: String,
    gate: CommentGate,
    cutoff: Long,
    options: CommentOptions,
    emit: EmitEvent,
): Unit = coroutineScope {
    val segments = BoundedIds(128)
    val cursors = BoundedIds(128)
    val inFlight = AtomicInteger(0)
    var cursor = "now"
    var live = false
    while (currentCoroutineContext().isActive) {
        val since = options.monoMs()
        var next: String? = null
        wire.read(view.newBuilder().setQueryParameter("at", cursor).build()) { input ->
            if (!live) {
                emit(StreamEvent.State(ConnectionState.LIVE, "ニコニコ実況（公式から直接）を受信中", CommentOrigin.NICONICO))
                live = true
            }
            var invalid = 0
            var entries = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = FrameReader.next(input) ?: break
                if (++entries > 16384) protocolFailure()
                val entry = try { decodeEntry(frame) } catch (_: InvalidProtocolBufferException) {
                    if (++invalid > 8) protocolFailure()
                    continue
                }
                invalid = 0
                when {
                    entry.hasNext() -> {
                        val at = entry.next.at
                        if (at <= 0) protocolFailure()
                        next = at.toString()
                    }
                    entry.hasSegment() -> {
                        val segment = entry.segment
                        val url = ServiceUrls.stream(segment.uri)
                        if (segment.hasUntil() && (secondsMs(segment.until.seconds) ?: 0) < cutoff) continue
                        if (!segments.add(url.toString())) continue
                        if (inFlight.incrementAndGet() > 4) {
                            inFlight.decrementAndGet()
                            throw StreamFailure(safeMessage = "実況の受信が遅れたため最新位置に接続し直します")
                        }
                        // Schedule independently: an undispatched child could block this view's
                        // IO thread waiting for a quiet segment's HTTP headers/first frame.
                        launch {
                            try {
                                wire.read(url) { stream ->
                                    var bad = 0
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val payload = FrameReader.next(stream) ?: break
                                        val chunk = try { decodeMessage(payload) } catch (_: InvalidProtocolBufferException) {
                                            if (++bad > 8) protocolFailure()
                                            continue
                                        }
                                        bad = 0
                                        gate.accept(protoComment(program, chunk), cutoff, options.wallMs())?.let { emit(StreamEvent.Comment(it)) }
                                        yield()
                                    }
                                }
                            } finally { inFlight.decrementAndGet() }
                        }
                    }
                    // previous/backward are archive links. Never fetch them for the live overlay.
                }
                yield()
            }
        }
        val following = next ?: throw IOException("view cursor missing")
        if (following == cursor || !cursors.add(following)) throw IOException("view cursor stalled")
        cursor = following
        // A next-only at=now response followed by normal EOF is expected, not END_PROGRAM.
        delay((options.interViewDelayMs - (options.monoMs() - since)).coerceAtLeast(0))
    }
}
