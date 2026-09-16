package dev.nicotv.comment

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.security.MessageDigest

// 1リクエストで取る放送時間幅。1日分のスレッド全体を一度に読むことはしない。
private const val CHUNK_MS = 120_000L
private const val MAX_LATE_MS = 2_000L
private const val MAX_BYTES = 4 * 1024 * 1024
private const val MAX_CHATS = 20_000
private const val MIN_WINDOW_MS = 60_000L
private const val MAX_WINDOW_MS = 12 * 60 * 60 * 1000L

internal class PastComment(val broadcastMs: Long, val comment: LiveComment)

/**
 * 過去ログ専用の受け入れ判定。放送時刻は現在時刻と無関係なので鮮度判定は行わず、
 * 本文の安全性と重複だけを見る。生放送の CommentGate は変更しない。
 */
internal class PastLogGate(private val ids: BoundedIds = BoundedIds(4096)) {
    fun accept(comment: LiveComment?): LiveComment? {
        if (comment == null || comment.text.isBlank() || comment.text.length > 2048 || hasInvalidUnicode(comment.text)) return null
        if (comment.text.any { it.isISOControl() && it != '\n' && it != '\t' } || comment.text.count { it == '\n' } > 8) return null
        return comment.takeIf { ids.add(it.id) }
    }
}

/** 過去ログの chat を放送時刻付きで写す。postedAtMs は表示直前に実時刻へ差し替える。 */
internal fun kakologComment(station: String, chat: JsonObject): PastComment? {
    if (chat.long("deleted") == 1L || chat.long("abone") == 1L) return null
    val text = chat.str("content") ?: return null
    val micros = chat.long("date_usec") ?: 0
    if (micros !in 0..999999) return null
    val broadcast = secondsMs(chat.long("date"))?.plus(micros / 1000) ?: return null
    val thread = chat.str("thread")?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all(Char::isDigit) } ?: "log"
    val number = chat.long("no")?.takeIf { it > 0 }?.toString() ?: archiveFingerprint("$broadcast:$text")
    var position = CommentPosition.SCROLL
    var size = CommentSize.NORMAL
    var color = CommentColors.named(0)
    val mail = chat.str("mail") ?: ""
    if (mail.length > 1024) return null
    for (token in mail.split(Regex("\\s+"))) when (token) {
        "ue" -> position = CommentPosition.TOP; "shita" -> position = CommentPosition.BOTTOM; "naka" -> position = CommentPosition.SCROLL
        "small" -> size = CommentSize.SMALL; "big" -> size = CommentSize.LARGE; "medium" -> size = CommentSize.NORMAL
        else -> CommentColors.mail(token)?.let { color = it }
    }
    // 過去ログは公式直結でもNXの生放送でもない、別の取得元として常に表示する。
    return PastComment(broadcast, LiveComment("kakolog:$station:$thread:$number", text, broadcast, position, size, color, CommentOrigin.NX_KAKOLOG))
}

private fun archiveFingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

/**
 * 録画番組向けバックエンド。NX-Jikkyoの過去ログ取得で放送当時のコメントを読み、
 * 「再生開始からの経過時間」に合わせて配信する。投稿・映像取得・選局操作はしない。
 */
class KakologCommentSource internal constructor(
    private val plan: RecordedPlan,
    private val wire: CommentWire,
    private val options: CommentOptions,
) : CommentSource {
    constructor(plan: RecordedPlan, client: OkHttpClient = OkHttpClient()) : this(plan, OkHttpWire(client), CommentOptions())

    override fun stream(station: Station): Flow<StreamEvent> = flow {
        coroutineScope {
            val output = EventMailbox(options.wallMs)
            // 再生の基準は収集開始時の単調時計。再試行でも基準はずらさない。
            val startedMono = options.monoMs()
            val producer = launch(options.dispatcher) {
                try {
                    val gate = PastLogGate()
                    var failures = 0
                    while (currentCoroutineContext().isActive) {
                        val since = options.monoMs()
                        val epoch = output.begin(StreamEvent.State(ConnectionState.RESOLVING, "録画番組の過去ログを準備中", CommentOrigin.NX_KAKOLOG)) ?: break
                        currentCoroutineContext().ensureActive()
                        val failure = try {
                            val deliver: EmitEvent = {
                                currentCoroutineContext().ensureActive()
                                output.offer(epoch, it)
                            }
                            deliver(StreamEvent.State(ConnectionState.CONNECTING, "過去ログを取得中（生放送ではありません）", CommentOrigin.NX_KAKOLOG))
                            replay(station, gate, startedMono, deliver)
                            StreamFailure(ConnectionState.NO_PROGRAM, "録画番組の過去ログを再生し終えました", terminal = true)
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            e as? StreamFailure ?: (e.cause as? StreamFailure) ?: StreamFailure()
                        }
                        output.offer(epoch, StreamEvent.State(failure.state, failure.safeMessage, CommentOrigin.NX_KAKOLOG))
                        if (failure.terminal) break
                        if (options.monoMs() - since >= 60000) failures = 0
                        delay(options.retry.delayMs(failures, failure.waitMs))
                        failures = (failures + 1).coerceAtMost(16)
                    }
                } finally { output.finish() }
            }
            try {
                while (true) emit(output.next() ?: break)
            } finally {
                producer.cancel()
                output.close()
            }
        }
    }

    /** 放送時刻の窓を順に取得し、経過時間に対応する時刻まで待って配信する。 */
    private suspend fun replay(station: Station, gate: PastLogGate, startedMono: Long, deliver: EmitEvent) = coroutineScope {
        val anchor = plan.anchorMs
        val endMs = anchor + plan.windowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        // 再試行時は先頭に戻らず、いま再生されている位置から続ける。
        var windowStart = anchor + (options.monoMs() - startedMono).coerceAtLeast(0)
        var prefetch: Deferred<List<PastComment>>? = null
        var announced = false
        try {
            while (windowStart < endMs) {
                val windowEnd = minOf(windowStart + CHUNK_MS, endMs)
                val chunk = prefetch?.await() ?: fetch(station.id, windowStart, windowEnd)
                prefetch = null
                if (!announced) {
                    deliver(StreamEvent.State(ConnectionState.LIVE, "録画番組の過去ログを再生中", CommentOrigin.NX_KAKOLOG))
                    announced = true
                }
                if (windowEnd < endMs) {
                    val nextEnd = minOf(windowEnd + CHUNK_MS, endMs)
                    prefetch = async { fetch(station.id, windowEnd, nextEnd) }
                }
                for (item in chunk) {
                    val wait = startedMono + (item.broadcastMs - anchor) - options.monoMs()
                    if (wait > 0) delay(wait) else if (wait < -MAX_LATE_MS) continue
                    val accepted = gate.accept(item.comment.copy(postedAtMs = options.wallMs())) ?: continue
                    deliver(StreamEvent.Comment(accepted))
                }
                // コメントのない区間も等速で進める。
                val tail = startedMono + (windowEnd - anchor) - options.monoMs()
                if (tail > 0) delay(tail)
                windowStart = windowEnd
            }
        } finally { prefetch?.cancel() }
    }

    private suspend fun fetch(stationId: String, fromMs: Long, toMs: Long): List<PastComment> {
        val url = ServiceUrls.kakolog(stationId, fromMs / 1000, (toMs + 999) / 1000)
        val text = withTimeout(options.handshakeMs) { wire.read(url) { strictUtf8(boundedBytes(it, MAX_BYTES)) } }
        val root = parseJson(text, MAX_BYTES) as? JsonObject ?: protocolFailure()
        if (root.str("error") != null) throw StreamFailure(ConnectionState.NO_PROGRAM, "この日時の過去ログを取得できません", 30_000)
        val packet = root["packet"] as? JsonArray ?: protocolFailure()
        if (packet.size > MAX_CHATS) protocolFailure()
        return packet.mapNotNull { entry -> (entry as? JsonObject)?.obj("chat")?.let { kakologComment(stationId, it) } }
            .filter { it.broadcastMs >= fromMs && it.broadcastMs < toMs }
            .sortedBy { it.broadcastMs }
    }
}
