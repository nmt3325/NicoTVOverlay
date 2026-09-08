package dev.nicotv.detection

import android.os.SystemClock
import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.StationCatalog
import dev.nicotv.core.StationObservation
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Experimental, explicit same-LAN polling. The owning session must cancel this cold flow on Stop. */
class BraviaStationDetector(client: OkHttpClient = OkHttpClient(), channelMap: Map<String, String> = emptyMap()) {
    private val transport = BraviaTransport(restrictedBraviaClient(client), SystemClock::elapsedRealtime)
    private val channelMap = channelMap.toMap()

    fun observations(host: String, psk: String): Flow<StationObservation> = flow {
        val request = BraviaProtocol.request(host, psk)
        if (request == null) {
            emit(braviaUnknown(SystemClock.elapsedRealtime(), "数値のプライベートIPv4と有効なPSKが必要です"))
            return@flow
        }
        if (!BraviaProtocol.validMap(channelMap)) {
            emit(braviaUnknown(SystemClock.elapsedRealtime(), "テレビURIと実況局の明示的な対応付けが必要です"))
            return@flow
        }
        emit(braviaUnknown(SystemClock.elapsedRealtime(), "BRAVIAを確認中（実験的）"))
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = transport.fetch(request, channelMap)
            currentCoroutineContext().ensureActive() // Never emit a late result after cancellation.
            emit(result)
            failures = if (result.stationId != null) 0 else (failures + 1).coerceAtMost(5)
            delay(BraviaProtocol.pollDelay(failures)) // One request in flight; delay AFTER it completes.
        }
    }
}

internal fun braviaUnknown(now: Long, reason: String) =
    StationObservation(null, DetectionOrigin.BRAVIA, now, false, reason)

/** Private transport settings do not alter the caller's comment client. No inherited PSK loggers/proxies. */
internal fun restrictedBraviaClient(client: OkHttpClient): OkHttpClient = client.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    eventListener(EventListener.NONE)
    authenticator(Authenticator.NONE)
    proxyAuthenticator(Authenticator.NONE)
    cookieJar(CookieJar.NO_COOKIES)
    cache(null)
    proxy(Proxy.NO_PROXY)
    // Do not inherit a custom resolver/socket factory that could route a private URL outside the LAN.
    dns(object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            if (!BraviaProtocol.privateIpv4(hostname)) throw java.net.UnknownHostException("Private IPv4 required")
            val bytes = hostname.split('.').map { it.toInt().toByte() }.toByteArray()
            return listOf(java.net.InetAddress.getByAddress(bytes)) // No DNS lookup.
        }
    })
    socketFactory(javax.net.SocketFactory.getDefault())
    connectionPool(okhttp3.ConnectionPool())
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
    connectTimeout(1500, TimeUnit.MILLISECONDS)
    readTimeout(1500, TimeUnit.MILLISECONDS)
    writeTimeout(1500, TimeUnit.MILLISECONDS)
    callTimeout(2500, TimeUnit.MILLISECONDS)
}.build()

internal object BraviaProtocol {
    const val MAX_BODY_BYTES = 65_536L
    private const val REQUEST_JSON = "{\"method\":\"getPlayingContentInfo\",\"id\":1,\"params\":[],\"version\":\"1.0\"}"
    fun privateIpv4(host: String): Boolean {
        if (!Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}").matches(host)) return false
        val parts = host.split('.').map { it.toInt() }
        if (parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }
    fun request(host: String, psk: String): Request? {
        if (!privateIpv4(host) || psk.isBlank() || psk.length > 256 || psk.any { it.code !in 32..126 }) return null
        return Request.Builder().url("http://$host/sony/avContent")
            .header("X-Auth-PSK", psk).header("Accept", "application/json")
            .header("Accept-Encoding", "identity").header("Connection", "close") // No idle PSK connection survives polling.
            .post(REQUEST_JSON.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }
    fun validMap(map: Map<String, String>): Boolean = map.isNotEmpty() && map.size <= 256 &&
        map.all { (uri, station) -> uri.startsWith("tv:") && uri.length in 4..2048 &&
            uri.none { it.isWhitespace() || it.code < 32 } && StationCatalog.find(station) != null }
    fun pollDelay(failures: Int): Long = if (failures <= 1) 2000 else
        (2000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(30_000)

    fun decode(body: String, map: Map<String, String>, now: Long): StationObservation {
        fun unknown(reason: String) = braviaUnknown(now, reason)
        if (!validMap(map)) return unknown("局の対応付けが未設定です")
        val json = boundedJsonObject(body, MAX_BODY_BYTES.toInt()) ?: return unknown("BRAVIA応答の形式が不明です")
        val id = json["id"] as? JsonPrimitive
        if (id == null || id.isString || id.intOrNull != 1 || "error" in json) return unknown("BRAVIAの認証・APIエラーです")
        val result = json["result"] as? JsonArray
        if (result?.size != 1) return unknown("再生内容を一意に確認できません")
        val content = result[0] as? JsonObject ?: return unknown("再生内容の形式が不明です")
        fun string(key: String) = (content[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // These OPTIONAL fields are conservative rejection hints, not a claimed universal Sony schema.
        for (container in listOf(json, content)) {
            for (field in listOf("status", "state", "powerStatus")) {
                if (field !in container) continue
                val state = (container[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (state !in setOf("active", "playing", "on")) return unknown("ライブ再生を確認できません")
            }
            if ("isPlaying" in container && container["isPlaying"] != JsonPrimitive(true)) {
                return unknown("ライブ再生ではありません")
            }
        }
        val source = string("source") ?: return unknown("放送ソースを確認できません")
        val uri = string("uri") ?: return unknown("テレビURIを確認できません")
        if (!source.startsWith("tv:") || !uri.startsWith("tv:")) return unknown("放送以外の入力です")
        val station = map[uri] ?: return unknown("対応付けされていないテレビURIです")
        // Title, display number and unverified consumer-model fields NEVER determine a station.
        return StationObservation(station, DetectionOrigin.BRAVIA, now, true, "明示URI対応で確認（BRAVIA実験的）")
    }
}

/** Internal seam accepts a test-local request; the public adapter constructs only validated private IPv4 URLs. */
internal class BraviaTransport(private val calls: Call.Factory, private val now: () -> Long) {
    suspend fun fetch(request: Request, map: Map<String, String>): StationObservation = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(braviaUnknown(now(), "BRAVIA通信失敗・タイムアウトです")) { _, _, _ -> }
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        when {
                            it.code == 401 || it.code == 403 -> braviaUnknown(now(), "BRAVIA認証エラーです")
                            !it.isSuccessful -> braviaUnknown(now(), "BRAVIA HTTPエラーです")
                            it.body == null -> braviaUnknown(now(), "BRAVIA応答が空です")
                            else -> {
                                val body = requireNotNull(it.body)
                                val source = body.source()
                                if (body.contentLength() > BraviaProtocol.MAX_BODY_BYTES ||
                                    source.request(BraviaProtocol.MAX_BODY_BYTES + 1)) {
                                    braviaUnknown(now(), "BRAVIA応答のサイズ上限を超えました")
                                } else BraviaProtocol.decode(source.buffer.readUtf8(), map, now())
                            }
                        }
                    }
                } catch (_: IOException) { braviaUnknown(now(), "BRAVIA通信失敗・タイムアウトです") }
                catch (_: RuntimeException) { braviaUnknown(now(), "BRAVIA応答を処理できません") }
                if (continuation.isActive) continuation.resume(result) { _, _, _ -> }
            }
        })
    }
}
