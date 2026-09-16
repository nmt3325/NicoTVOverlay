package dev.nicotv.comment

import dev.nicotv.core.ConnectionState
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.random.Random

/** Only fixed, local messages are ever exposed; upstream exceptions may contain tokens. */
internal class StreamFailure(
    val state: ConnectionState = ConnectionState.RECONNECTING,
    val safeMessage: String = "接続が切れました。再接続します",
    val waitMs: Long = 0,
    val terminal: Boolean = false,
) : IOException(safeMessage)

internal fun protocolFailure(): Nothing = throw StreamFailure(safeMessage = "実況の応答形式を確認できません。再接続します")

internal object ServiceUrls {
    private const val NX = "nx-jikkyo.tsukumijima.net"
    // NX-Jikkyo の過去ログ取得が使う実況過去ログAPI。時間範囲を指定した読み取りだけに使う。
    private const val ARCHIVE = "jikkyo.tsukumijima.net"
    private fun parse(raw: String, websocket: Boolean): HttpUrl {
        val prefix = if (websocket) "wss://" else "https://"
        if (raw.length > 8192 || !raw.startsWith(prefix) || raw.any { it <= ' ' || it == '\\' }) reject()
        val url = (if (websocket) "https://" + raw.removePrefix(prefix) else raw).toHttpUrlOrNull() ?: reject()
        if (url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) reject()
        return url
    }
    fun watchPage(channel: String): HttpUrl {
        if (!Regex("ch[0-9]{1,12}").matches(channel)) reject()
        return parse("https://live.nicovideo.jp/watch/$channel", false)
    }
    fun officialWatch(raw: String): HttpUrl = parse(raw, true).also {
        if (!it.host.endsWith(".live2.nicovideo.jp")) reject()
    }
    fun stream(raw: String): HttpUrl = parse(raw, false).also {
        // Observed NDGR service. Deliberately fail closed if the service migrates.
        if (it.host != "mpn.live.nicovideo.jp") reject()
    }
    fun nxWatch(station: String): HttpUrl {
        if (!Regex("jk[0-9]{1,8}").matches(station)) reject()
        return nxSocket("wss://$NX/api/v1/channels/$station/ws/watch")
    }
    fun nxSocket(raw: String): HttpUrl = parse(raw, true).also { if (it.host != NX) reject() }
    /** 過去ログ（録画）用。局IDと妥当な時間範囲だけを許可し、任意URLは組み立てない。 */
    fun kakolog(station: String, startSec: Long, endSec: Long): HttpUrl {
        if (!Regex("jk[0-9]{1,8}").matches(station)) reject()
        if (startSec < 1 || endSec <= startSec || endSec - startSec > 1800 || endSec > 253402300799L) reject()
        return parse("https://$ARCHIVE/api/kakolog/$station?starttime=$startSec&endtime=$endSec&format=json", false)
    }
    fun transport(url: HttpUrl) {
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null ||
            !(url.host == "live.nicovideo.jp" || url.host == "mpn.live.nicovideo.jp" ||
              url.host.endsWith(".live2.nicovideo.jp") || url.host == NX || url.host == ARCHIVE)) reject()
    }
    private fun reject(): Nothing = throw StreamFailure(ConnectionState.ERROR, "安全な実況サーバーの接続先を確認できません", terminal = true)
}

internal object FrameReader {
    const val MAX_FRAME = 1024 * 1024
    /** HTTP chunks are unrelated to protobuf boundaries. EOF is normal only between frames. */
    fun next(input: InputStream, limit: Int = MAX_FRAME): ByteArray? {
        var length = 0L
        for (i in 0..4) {
            val b = input.read()
            if (b < 0) {
                if (i == 0) return null
                throw EOFException("truncated frame prefix")
            }
            length = length or ((b and 127).toLong() shl (7 * i))
            if (length > limit || (i == 4 && b and 0xf0 != 0)) throw IOException("frame too large")
            if (b and 128 == 0) {
                if (i > 0 && b == 0) throw IOException("noncanonical frame prefix")
                val result = ByteArray(length.toInt())
                var offset = 0
                while (offset < result.size) {
                    val n = input.read(result, offset, result.size - offset)
                    if (n < 0) throw EOFException("truncated frame body")
                    if (n == 0) {
                        val one = input.read()
                        if (one < 0) throw EOFException("truncated frame body")
                        result[offset++] = one.toByte()
                    } else offset += n
                }
                return result
            }
        }
        throw IOException("invalid frame prefix")
    }
}

internal fun boundedBytes(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf, 0, minOf(buf.size, maxBytes + 1 - out.size()))
        if (n < 0) return out.toByteArray()
        if (n == 0) continue
        out.write(buf, 0, n)
        if (out.size() > maxBytes) protocolFailure()
    }
}

internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

internal fun hasInvalidUnicode(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        // U+FFFD is a valid scalar, not evidence of malformed bytes (use strictUtf8 for bytes).
        if (c.isHighSurrogate()) {
            if (i == text.length || !text[i++].isLowSurrogate()) return true
        } else if (c.isLowSurrogate()) return true
    }
    return false
}

internal fun parseJson(text: String, maxLength: Int = 65536): JsonElement {
    if (text.length > maxLength || hasInvalidUnicode(text)) protocolFailure()
    var depth = 0
    var quoted = false
    var escaped = false
    for (c in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 48) protocolFailure()
            '}', ']' -> depth--
        }
    }
    return try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) { protocolFailure() }
}
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun instantMs(value: String?): Long? = try { value?.let { Instant.parse(it).toEpochMilli() } } catch (_: Exception) { null }
internal fun secondsMs(value: Long?): Long? = value?.takeIf { it in 1..253402300799L }?.times(1000)

internal class Program(val id: String, val watchUrl: HttpUrl, val endMs: Long?)

internal object Bootstrap {
    private val script = Regex("<script\\b(?:[^\"'>]++|\"[^\"]*+\"|'[^']*+')*+>", RegexOption.IGNORE_CASE)
    /** Linear attribute scan avoids regex backtracking on a malformed multi-megabyte tag. */
    private fun attributes(tag: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 7 // after <script
        while (i < tag.length) {
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i == tag.length || tag[i] == '>') break
            val start = i
            while (i < tag.length && (tag[i].isLetterOrDigit() || tag[i] in "_-:.")) i++
            if (start == i) { i++; continue }
            val name = tag.substring(start, i).lowercase()
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i == tag.length || tag[i] != '=') continue
            i++
            while (i < tag.length && tag[i].isWhitespace()) i++
            if (i == tag.length) break
            val quote = tag[i].takeIf { it == '\'' || it == '"' }
            if (quote != null) i++
            val valueStart = i
            while (i < tag.length && if (quote != null) tag[i] != quote else !tag[i].isWhitespace() && tag[i] != '>') i++
            result[name] = decodeAttribute(tag.substring(valueStart, i))
            if (quote != null && i < tag.length) i++
        }
        return result
    }
    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|quot|apos|amp|lt|gt);")
    /** Single-pass entity decoding: &amp;quot; must remain &quot;, not become a quote. */
    internal fun decodeAttribute(value: String): String = entity.replace(value) { m ->
        when (val e = m.groupValues[1]) {
            "quot" -> "\""; "apos" -> "'"; "amp" -> "&"; "lt" -> "<"; "gt" -> ">"
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                if (code == null || code !in 1..0x10ffff || code in 0xd800..0xdfff) protocolFailure()
                String(Character.toChars(code))
            }
        }
    }
    fun parse(html: String): Program {
        if (html.length > 4 * 1024 * 1024) protocolFailure()
        var props: String? = null
        for (tag in script.findAll(html)) {
            val attrs = attributes(tag.value)
            if (attrs["id"] == "embedded-data") { props = attrs["data-props"]; break }
        }
        val root = props?.let { parseJson(it, 4 * 1024 * 1024) as? JsonObject } ?: protocolFailure()
        val program = root.obj("program") ?: protocolFailure()
        if (program.str("status") != "ON_AIR") throw StreamFailure(ConnectionState.NO_PROGRAM, "現在放送中の実況番組がありません", 30000)
        val id = program.str("nicoliveProgramId")?.takeIf { Regex("lv[0-9]{1,15}").matches(it) } ?: protocolFailure()
        val ws = root.obj("site")?.obj("relive")?.str("webSocketUrl") ?: protocolFailure()
        return Program(id, ServiceUrls.officialWatch(ws), secondsMs(program.long("endTime")))
    }
}

internal class RetryPolicy(private val random: () -> Double = { Random.nextDouble() }) {
    fun delayMs(attempt: Int, minimum: Long = 0): Long {
        val base = minOf(30000L, 2000L shl attempt.coerceIn(0, 4))
        val wait = max(base, minimum.coerceAtLeast(0))
        val jitter = (random().coerceIn(0.0, 1.0) * 1000).toLong()
        return if (wait > Long.MAX_VALUE - jitter) Long.MAX_VALUE else wait + jitter
    }
}

internal fun retryAfterMs(value: String?, nowMs: Long): Long {
    if (value == null) return 0
    value.trim().toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
    return try { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs).coerceAtLeast(0) }
    catch (_: Exception) { 0 }
}

internal fun httpFailure(code: Int, retryAfter: String?, nowMs: Long): StreamFailure {
    val wait = retryAfterMs(retryAfter, nowMs)
    return when (code) {
        401, 403 -> StreamFailure(ConnectionState.ERROR, "匿名で受信できない実況番組です", wait, true)
        404, 410 -> StreamFailure(ConnectionState.NO_PROGRAM, "実況番組を再確認しています", max(30000, wait))
        in 300..399 -> StreamFailure(ConnectionState.ERROR, "実況サーバーの転送先を安全のため拒否しました", terminal = true)
        else -> StreamFailure(safeMessage = "実況サーバーに接続できません。再接続します", waitMs = wait)
    }
}
