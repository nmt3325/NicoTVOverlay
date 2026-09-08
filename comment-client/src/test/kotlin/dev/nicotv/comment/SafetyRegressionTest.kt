package dev.nicotv.comment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class SafetyRegressionTest {
    @Test(timeout = 3000) fun longMalformedHtmlAttributesAreBounded() {
        assertEquals("lv1", Bootstrap.parse("<script " + "x".repeat(100000) + "></script>" + html()).id)
        assertThrows(StreamFailure::class.java) { Bootstrap.parse("x".repeat(4 * 1024 * 1024 + 1)) }
    }
    @Test fun viewCursorContinuesWhileSegmentHeadersAreBlocked() = runBlocking {
        MockWebServer().use { server ->
            val nextView = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/watch/") -> MockResponse().setBody(html())
                    request.path == "/view?at=now" -> MockResponse().setBody(Buffer().write(frames(segment(), next(1))))
                    request.path == "/view?at=1" -> { nextView.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                    else -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            server.start()
            val real = OkHttpWire(OkHttpClient()) { }
            val control = FakeSocket().apply { onSend = { if (it.contains("startWatching")) push(endpoint()) } }
            val remapped = object : CommentWire {
                override suspend fun <T> read(url: HttpUrl, block: suspend (InputStream) -> T): T = real.read(server.url(url.encodedPath).newBuilder().encodedQuery(url.encodedQuery).build(), block)
                override suspend fun socket(url: HttpUrl): JsonSocket = control
            }
            val source = NicoLiveCommentSource(remapped, CommentOptions(wallMs = { BASE }, interViewDelayMs = 1))
            val job = launch { source.stream(STATION).collect() }
            try {
                val reached = withContext(Dispatchers.IO) { nextView.await(5, TimeUnit.SECONDS) }
                assertTrue("The view must not wait for the current segment to produce a frame", reached)
            } finally { withTimeout(3000) { job.cancelAndJoin() } }
            assertTrue(control.closed); assertEquals(0, real.client.dispatcher.runningCallsCount())
        }
    }
    @Test fun oversizedWebsocketMessageIsRejectedBeforeJsonAndDoesNotHang() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("x".repeat(65537)) }
            }))
            val socket = OkHttpWire(OkHttpClient()) { }.socket(server.url("/"))
            val error = runCatching { withTimeout(3000) { socket.receive() } }.exceptionOrNull()
            socket.close(); assertTrue(error is StreamFailure)
        }
    }
    @Test fun failedWebsocketUpgradePreservesRetryAfter() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            val error = runCatching { OkHttpWire(OkHttpClient()) { }.socket(server.url("/")) }.exceptionOrNull()
            assertTrue(error is StreamFailure); assertEquals(120000L, (error as StreamFailure).waitMs)
        }
    }
}
