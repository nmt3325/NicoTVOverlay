package dev.nicotv.detection

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BraviaSafetyTest {
    @Test fun `custom DNS cannot route a private endpoint to a public address`() {
        var inheritedLookups = 0
        val hostile = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                inheritedLookups++
                return listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
            }
        }).build()
        val safe = restrictedBraviaClient(hostile)
        assertEquals("192.168.1.2", safe.dns.lookup("192.168.1.2").single().hostAddress)
        assertEquals(0, inheritedLookups)
        for (host in listOf("example.com", "8.8.8.8", "127.0.0.1")) {
            try { safe.dns.lookup(host); fail("resolver accepted non-private host") }
            catch (_: UnknownHostException) { /* expected, no lookup attempted */ }
        }
        assertEquals(0, inheritedLookups)
    }
    @Test fun `invalid endpoint flow completes with explicit Unknown without a request`() = runBlocking {
        val detector = BraviaStationDetector(channelMap = mapOf("tv:test" to "jk1"))
        for (host in listOf("example.com", "8.8.8.8", "127.0.0.1", "192.168.1.2:8080")) {
            val results = detector.observations(host, "synthetic-psk").toList()
            assertEquals(1, results.size); assertNull(results.single().stationId)
            assertFalse(results.single().watchingTv)
        }
    }
    @Test fun `missing explicit map completes Unknown before contacting a valid private host`() = runBlocking {
        val results = BraviaStationDetector().observations("192.168.1.2", "synthetic-psk").toList()
        assertEquals(1, results.size); assertNull(results.single().stationId)
    }
}
