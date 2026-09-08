package dev.nicotv.comment

import dev.nicotv.core.*
import dwango.nicolive.chat.data.Atoms.Chat
import dwango.nicolive.chat.service.edge.Payload.ChunkedMessage
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

internal class BoundedIds(private val capacity: Int) {
    private val ids = LinkedHashMap<String, Unit>()
    @Synchronized fun add(id: String): Boolean {
        if (ids.containsKey(id)) return false
        ids[id] = Unit
        if (ids.size > capacity) ids.remove(ids.keys.first())
        return true
    }
    @Synchronized fun size() = ids.size
}

internal object CommentColors {
    private val names = listOf("white", "red", "pink", "orange", "yellow", "green", "cyan", "blue", "purple", "black",
        "white2", "red2", "pink2", "orange2", "yellow2", "green2", "cyan2", "blue2", "purple2", "black2")
    private val rgb = intArrayOf(0xffffff, 0xff0000, 0xff8080, 0xffcc00, 0xffff00, 0x00ff00, 0x00ffff, 0x0000ff, 0xc000ff, 0x000000,
        0xcccc99, 0xcc0033, 0xff33cc, 0xff6600, 0x999900, 0x00cc66, 0x00cccc, 0x3399ff, 0x6633cc, 0x666666)
    fun named(index: Int): Int = 0xff000000.toInt() or (rgb.getOrNull(index) ?: 0xffffff)
    fun mail(token: String): Int? {
        val i = names.indexOf(token)
        if (i >= 0) return named(i)
        if (Regex("#[0-9a-fA-F]{6}").matches(token)) return 0xff000000.toInt() or token.drop(1).toInt(16)
        return when (token) {
            "niconicowhite" -> named(10); "truered" -> named(11); "passionorange" -> named(13)
            "madyellow" -> named(14); "elementalgreen" -> named(15); "marineblue" -> named(16)
            "nobleviolet" -> named(18); else -> null
        }
    }
}

internal fun protoComment(program: String, chunk: ChunkedMessage): LiveComment? {
    if (!chunk.hasMeta() || !chunk.meta.hasAt() || !chunk.hasMessage()) return null
    val data = chunk.message
    val chat = when { data.hasChat() -> data.chat; data.hasOverflowedChat() -> data.overflowedChat; else -> return null }
    val at = chunk.meta.at
    if (at.nanos !in 0..999999999) return null
    val timestamp = secondsMs(at.seconds)?.plus(at.nanos / 1000000) ?: return null
    val id = chunk.meta.id.takeIf { it.isNotBlank() && it.length <= 256 }
        ?: if (chat.no > 0) "no:${chat.no}" else fingerprint("$timestamp:${chat.content}")
    val mod = chat.modifier
    val color = if (mod.hasFullColor()) {
        val c = mod.fullColor
        0xff000000.toInt() or (c.r.coerceIn(0, 255) shl 16) or (c.g.coerceIn(0, 255) shl 8) or c.b.coerceIn(0, 255)
    } else CommentColors.named(mod.namedColorValue)
    return LiveComment("$program:$id", chat.content, timestamp,
        when (mod.position) { Chat.Modifier.Pos.ue -> CommentPosition.TOP; Chat.Modifier.Pos.shita -> CommentPosition.BOTTOM; else -> CommentPosition.SCROLL },
        when (mod.size) { Chat.Modifier.Size.small -> CommentSize.SMALL; Chat.Modifier.Size.big -> CommentSize.LARGE; else -> CommentSize.NORMAL },
        color, CommentOrigin.NICONICO)
}

internal fun nxComment(thread: String, chat: JsonObject): LiveComment? {
    if (chat.str("thread") != thread || chat.long("deleted") == 1L) return null
    val text = chat.str("content") ?: return null
    val micros = chat.long("date_usec") ?: 0
    if (micros !in 0..999999) return null
    val timestamp = secondsMs(chat.long("date"))?.plus(micros / 1000) ?: return null
    val number = chat.long("no")?.takeIf { it > 0 }?.toString() ?: fingerprint("$timestamp:$text")
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
    // NX includes nicolive: mirrors and NX originals. Neither is an official-direct source.
    return LiveComment("nx:$thread:$number", text, timestamp, position, size, color, CommentOrigin.NX_JIKKYO)
}

private fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

internal class CommentGate(private val ids: BoundedIds = BoundedIds(4096)) {
    fun accept(comment: LiveComment?, cutoffMs: Long, nowMs: Long): LiveComment? {
        if (comment == null || comment.text.isBlank() || comment.text.length > 2048 || hasInvalidUnicode(comment.text)) return null
        if (comment.text.any { it.isISOControl() && it != '\n' && it != '\t' } || comment.text.count { it == '\n' } > 8) return null
        if (comment.postedAtMs < cutoffMs || comment.postedAtMs < nowMs - 20000 || comment.postedAtMs > nowMs + 5000) return null
        return comment.takeIf { ids.add(it.id) }
    }
}
