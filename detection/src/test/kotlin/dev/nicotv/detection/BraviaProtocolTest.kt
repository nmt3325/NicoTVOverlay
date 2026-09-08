package dev.nicotv.detection

import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

class BraviaProtocolTest {
    private val map = mapOf("tv:dvbt?service=1" to "jk1")
    private fun body(fields: String) = "{\"id\":1,\"result\":[{$fields}]}"
    private fun decode(fields: String) = BraviaProtocol.decode(body(fields), map, 1234)
    private val playing = "\"source\":\"tv:dvbt\",\"uri\":\"tv:dvbt?service=1\""

    @Test fun `only unambiguous canonical RFC1918 IPv4 is permitted`() {
        for (host in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1")) assertTrue(host, BraviaProtocol.privateIpv4(host))
        for (host in listOf("127.0.0.1", "8.8.8.8", "0.0.0.0", "169.254.0.1", "172.15.1.1", "172.32.1.1",
            "192.169.1.1", "10.256.1.1", "10.01.0.1", "10.1", "10.0.0.1:80", "10.0.0.1/", "localhost",
            "http://192.168.1.1", "192.168.1.1@evil.example", "[::1]", "::ffff:192.168.1.1", " 10.0.0.1", "0x0a000001")) {
            assertFalse(host, BraviaProtocol.privateIpv4(host))
            assertNull(BraviaProtocol.request(host, "synthetic-psk"))
        }
    }
    @Test fun `request uses exact documented read only method and PSK header`() {
        val request = requireNotNull(BraviaProtocol.request("192.168.1.2", "synthetic-psk"))
        assertEquals("http://192.168.1.2/sony/avContent", request.url.toString())
        assertEquals("POST", request.method); assertEquals("synthetic-psk", request.header("X-Auth-PSK"))
        val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
        assertEquals("{\"method\":\"getPlayingContentInfo\",\"id\":1,\"params\":[],\"version\":\"1.0\"}", buffer.readUtf8())
        assertNull(BraviaProtocol.request("10.0.0.1", "bad\r\nheader"))
        assertNull(BraviaProtocol.request("10.0.0.1", ""))
    }
    @Test fun `explicit TV URI mapping succeeds without a title or guessed numbers`() {
        val result = decode(playing)
        assertEquals("jk1", result.stationId); assertTrue(result.watchingTv); assertEquals(1234L, result.observedAtMs)
        assertNull(BraviaProtocol.decode(body(playing), emptyMap(), 1).stationId)
        assertNull(decode("\"source\":\"tv:dvbt\",\"title\":\"NHK総合\",\"dispNum\":\"1\"").stationId)
        assertNull(decode("\"source\":\"tv:dvbt\",\"uri\":\"tv:dvbt?service=2\",\"title\":\"NHK総合\"").stationId)
    }
    @Test fun `auth API errors malformed and ambiguous results are Unknown`() {
        for (body in listOf("", "invalid", "{\"id\":1,\"error\":[403,\"forbidden\"]}",
            "{\"id\":1,\"result\":[],\"error\":null}", "{\"id\":1,\"result\":[]}",
            "{\"id\":2,\"result\":[{$playing}]}", "{\"id\":\"1\",\"result\":[{$playing}]}",
            "{\"id\":1,\"result\":[{$playing},{$playing}]}", "[]")) {
            assertNull(BraviaProtocol.decode(body, map, 1).stationId)
        }
    }
    @Test fun `HDMI nonTV and standby never preserve old channel`() {
        assertNull(decode("\"source\":\"extInput:hdmi\",\"uri\":\"tv:dvbt?service=1\"").stationId)
        assertNull(decode("$playing,\"status\":\"standby\"").stationId)
        assertNull(decode("$playing,\"isPlaying\":false").stationId)
        assertNull(decode("\"uri\":\"tv:dvbt?service=1\"").stationId)
    }
    @Test fun `map limits and exponential backoff are bounded`() {
        assertFalse(BraviaProtocol.validMap(mapOf("extInput:hdmi" to "jk1")))
        assertFalse(BraviaProtocol.validMap(mapOf("tv:a" to "jk999")))
        assertEquals(listOf(2000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map(BraviaProtocol::pollDelay))
    }
}
