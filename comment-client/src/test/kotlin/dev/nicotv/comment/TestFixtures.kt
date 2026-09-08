package dev.nicotv.comment
import com.google.protobuf.Timestamp
import dev.nicotv.core.StationCatalog
import dwango.nicolive.chat.data.Atoms.Chat
import dwango.nicolive.chat.data.Message.NicoliveMessage
import dwango.nicolive.chat.service.edge.Payload.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
internal const val BASE = 1700000000000L
internal const val VIEW = "https://mpn.live.nicovideo.jp/view"
internal const val SEGMENT = "https://mpn.live.nicovideo.jp/segment"
internal const val WATCH = "wss://a.live2.nicovideo.jp/watch?opaque=synthetic"
internal val STATION = StationCatalog.find("jk4")!!
internal fun chatChunk(id: String = "one", at: Long = BASE, text: String = "synthetic", overflow: Boolean = false,
                       modifier: Chat.Modifier = Chat.Modifier.getDefaultInstance()): ChunkedMessage {
    val chat = Chat.newBuilder().setContent(text).setNo(1).setVpos(-123).setModifier(modifier)
    val data = NicoliveMessage.newBuilder().apply { if (overflow) setOverflowedChat(chat) else setChat(chat) }
    return ChunkedMessage.newBuilder().setMeta(ChunkedMessage.Meta.newBuilder().setId(id)
        .setAt(Timestamp.newBuilder().setSeconds(at / 1000).setNanos(((at % 1000) * 1000000).toInt())))
        .setMessage(data).build()
}
internal fun frames(vararg messages: com.google.protobuf.MessageLite): ByteArray = ByteArrayOutputStream().also { out -> messages.forEach { it.writeDelimitedTo(out) } }.toByteArray()
internal fun next(at: Long): ChunkedEntry = ChunkedEntry.newBuilder().setNext(ChunkedEntry.ReadyForNext.newBuilder().setAt(at)).build()
internal fun segment(uri: String = SEGMENT): ChunkedEntry = ChunkedEntry.newBuilder().setSegment(MessageSegment.newBuilder().setUri(uri)).build()
internal fun html(id: String = "lv1", status: String = "ON_AIR", watch: String = WATCH): String {
    val props = buildJsonObject {
        putJsonObject("program") { put("nicoliveProgramId", id); put("status", status) }
        putJsonObject("site") { putJsonObject("relive") { put("webSocketUrl", watch) } }
    }.toString().replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;")
    return "<html><script data-other='ok' data-props='$props' id='embedded-data'></script></html>"
}
internal fun control(type: String, data: JsonObject? = null): String = buildJsonObject { put("type", type); if (data != null) put("data", data) }.toString()
internal fun endpoint(): String = control("messageServer", buildJsonObject { put("viewUri", VIEW) })
internal class OneByteInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
}
internal class FakeSocket : JsonSocket {
    val incoming = Channel<String>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var onSend: (String) -> Unit = {}
    var closed = false
    fun push(text: String) { check(incoming.trySend(text).isSuccess) }
    override suspend fun receive(): String = incoming.receive()
    override fun send(text: String) { check(!closed); sent += text; onSend(text) }
    override fun close() { closed = true; incoming.cancel() }
}
internal data class ReadPlan(val bytes: ByteArray = byteArrayOf(), val before: Boolean = false, val after: Boolean = false)
internal class FakeWire : CommentWire {
    val reads = mutableListOf<HttpUrl>()
    val sockets = mutableListOf<Pair<HttpUrl, FakeSocket>>()
    var activeReads = 0
    var onRead: (HttpUrl) -> ReadPlan = { if (it.host == "live.nicovideo.jp") ReadPlan(html().toByteArray()) else ReadPlan(before = true) }
    var onSocket: (HttpUrl, FakeSocket) -> Unit = { _, socket -> socket.onSend = { if (it.contains("startWatching")) socket.push(endpoint()) } }
    override suspend fun <T> read(url: HttpUrl, block: suspend (InputStream) -> T): T {
        reads += url; activeReads++
        try {
            val plan = onRead(url)
            if (plan.before) awaitCancellation()
            val result = OneByteInput(plan.bytes).use { block(it) }
            if (plan.after) awaitCancellation()
            return result
        } finally { activeReads-- }
    }
    override suspend fun socket(url: HttpUrl): JsonSocket = FakeSocket().also { sockets += url to it; onSocket(url, it) }
}
