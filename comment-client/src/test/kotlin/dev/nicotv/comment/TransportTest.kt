package dev.nicotv.comment
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
class TransportTest {
    @Test fun redirectsCannotSendRequestToAnotherHostAndSettingsAreHardened() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { destination ->
            first.start(); destination.start(); first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/secret")))
            val wire = OkHttpWire(OkHttpClient.Builder().followRedirects(true).addInterceptor { it.proceed(it.request()) }.build()) { }
            assertEquals(60000, wire.client.readTimeoutMillis); assertFalse(wire.client.followRedirects); assertTrue(wire.client.interceptors.isEmpty())
            val failure = try { wire.read(first.url("/")) { it.read() }; null } catch (e: StreamFailure) { e }
            assertNotNull(failure); assertTrue(failure!!.terminal); assertEquals(0, destination.requestCount)
        } }
    }
    @Test fun cancellationClosesAnHttpReadWithoutWaitingSixtySeconds() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); val wire = OkHttpWire(OkHttpClient()) { }
            val job = launch { wire.read(server.url("/")) { it.read() } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(3000) { job.cancelAndJoin() }; assertEquals(0, wire.client.dispatcher.runningCallsCount())
        }
    }
    @Test fun websocketReceiveAndCloseWorkWithRealOkHttp() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"type\":\"ping\"}") }
            }))
            val socket = OkHttpWire(OkHttpClient()) { }.socket(server.url("/watch"))
            assertEquals("{\"type\":\"ping\"}", withTimeout(3000) { socket.receive() }); socket.close()
        }
    }
}
