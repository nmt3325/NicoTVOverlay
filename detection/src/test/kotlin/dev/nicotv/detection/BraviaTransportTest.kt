package dev.nicotv.detection

import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BraviaTransportTest {
    private lateinit var server: MockWebServer
    private val map = mapOf("tv:test" to "jk6")
    private val good = "{\"id\":1,\"result\":[{\"source\":\"tv:test\",\"uri\":\"tv:test\"}]}"
    // Internal test-only transport seam allows this test's loopback MockWebServer resolver.
    private val client = restrictedBraviaClient(OkHttpClient()).newBuilder().dns(okhttp3.Dns.SYSTEM).build()
    @Before fun before() { server = MockWebServer(); server.start() }
    @After fun after() { server.shutdown() }
    // Test-local seam; the PUBLIC adapter never accepts a loopback host or arbitrary port.
    private fun request(): Request = requireNotNull(BraviaProtocol.request("192.168.1.2", "synthetic-psk"))
        .newBuilder().url(server.url("/sony/avContent")).build()
    private fun fetch() = runBlocking { withTimeout(5000) { BraviaTransport(client) { 42L }.fetch(request(), map) } }

    @Test fun `successful response carries explicit URI match and correct request`() {
        server.enqueue(MockResponse().setBody(good))
        assertEquals("jk6", fetch().stationId)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/sony/avContent", request.path)
        assertEquals("synthetic-psk", request.getHeader("X-Auth-PSK"))
        assertTrue(request.body.readUtf8().contains("getPlayingContentInfo"))
    }
    @Test fun `HTTP auth and API error response clear station without body in detail`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-error-fixture"))
        val auth = fetch(); assertNull(auth.stationId); assertFalse(auth.detail.contains("sensitive"))
        server.enqueue(MockResponse().setBody("{\"id\":1,\"error\":[7,\"fixture\"]}"))
        assertNull(fetch().stationId)
    }
    @Test fun `redirect is not followed and credential never reaches redirect endpoint`() {
        val target = MockWebServer(); target.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/leak")))
            assertNull(fetch().stationId)
            assertEquals(1, server.requestCount)
            assertEquals(0, target.requestCount)
        } finally { target.shutdown() }
    }
    @Test fun `oversized and slow response fail boundedly`() {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(70_000), 8192))
        assertNull(fetch().stationId)
        server.enqueue(MockResponse().setBody(good).setBodyDelay(3, TimeUnit.SECONDS))
        assertNull(fetch().stationId)
    }
    @Test fun `cancellation cancels in flight socket and returns promptly`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = AtomicReference<Call>()
        val factory = Call.Factory { req -> client.newCall(req).also(call::set) }
        val job = launch(Dispatchers.IO) { BraviaTransport(factory) { 42L }.fetch(request(), map) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(call.get().isCanceled())
    }
    @Test fun `private adapter strips inherited logging proxy redirect retry and cache behaviors`() {
        val base = OkHttpClient.Builder().addInterceptor { error("must not be called") }.build()
        val restricted = restrictedBraviaClient(base)
        assertTrue(restricted.interceptors.isEmpty()); assertTrue(restricted.networkInterceptors.isEmpty())
        assertFalse(restricted.followRedirects); assertFalse(restricted.followSslRedirects)
        assertFalse(restricted.retryOnConnectionFailure); assertEquals(Proxy.NO_PROXY, restricted.proxy)
        assertNull(restricted.cache); assertEquals(2500, restricted.callTimeoutMillis)
        assertEquals(1, base.interceptors.size); assertTrue(base.followRedirects)
    }
}
