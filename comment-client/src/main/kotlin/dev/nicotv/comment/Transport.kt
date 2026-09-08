package dev.nicotv.comment

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface JsonSocket : Closeable {
    suspend fun receive(): String
    fun send(text: String)
}
internal interface CommentWire {
    suspend fun <T> read(url: HttpUrl, block: suspend (InputStream) -> T): T
    suspend fun socket(url: HttpUrl): JsonSocket
}

/** The caller's connection pool is reusable, but redirects, credentials and logging are not. */
internal class OkHttpWire(
    client: OkHttpClient,
    private val validate: (HttpUrl) -> Unit = ServiceUrls::transport,
) : CommentWire {
    internal val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .eventListener(EventListener.NONE)
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()

    override suspend fun <T> read(url: HttpUrl, block: suspend (InputStream) -> T): T = coroutineScope {
        validate(url)
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "NicoTVOverlay/0.1 (comment receiver)").build())
        // Synchronous body reads run on IO. This separate child closes the socket immediately
        // on parent cancellation, rather than waiting for the 60-second read timeout.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw httpFailure(response.code, response.header("Retry-After"), System.currentTimeMillis())
                    val body = response.body ?: protocolFailure()
                    block(body.byteStream())
                }
            }
        } finally { closer.cancelAndJoin() }
    }

    override suspend fun socket(url: HttpUrl): JsonSocket {
        validate(url)
        val pipe = SocketPipe()
        pipe.socket = client.newWebSocket(Request.Builder().url(url).build(), pipe)
        try {
            withTimeout(20000) { pipe.opened.await() }
            currentCoroutineContext().ensureActive()
            return pipe
        } catch (e: Throwable) { pipe.close(); throw e }
    }

    private class SocketPipe : WebSocketListener(), JsonSocket {
        val opened = CompletableDeferred<Unit>()
        private val closed = AtomicBoolean(false)
        private val messages = Channel<String>(64)
        @Volatile var socket: WebSocket? = null
            set(value) { field = value; if (closed.get()) value?.cancel() }
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get()) webSocket.cancel() else opened.complete(Unit)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Bound work before JSON parsing; never queue unbounded callbacks.
            if (text.length > 65536 || hasInvalidUnicode(text) || messages.trySend(text).isFailure) fail()
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail() }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { fail() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(response?.let { httpFailure(it.code, it.header("Retry-After"), System.currentTimeMillis()) } ?: StreamFailure())
        }
        private fun fail(error: StreamFailure = StreamFailure()) {
            opened.completeExceptionally(error)
            messages.close(error)
            // Preserve the failure (and Retry-After) rather than replacing it with channel cancellation.
            if (closed.compareAndSet(false, true)) socket?.cancel()
        }
        override suspend fun receive(): String = messages.receive()
        override fun send(text: String) { if (closed.get() || socket?.send(text) != true) throw StreamFailure() }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                socket?.cancel()
                messages.cancel()
                opened.cancel()
            }
        }
    }
}
