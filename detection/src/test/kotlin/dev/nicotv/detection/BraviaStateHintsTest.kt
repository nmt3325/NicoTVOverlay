package dev.nicotv.detection

import org.junit.Assert.assertNull
import org.junit.Test

class BraviaStateHintsTest {
    private val map = mapOf("tv:test" to "jk1")
    @Test fun `standby in envelope cannot be masked by active content status`() {
        val body = """{"id":1,"status":"standby","result":[{"source":"tv:test","uri":"tv:test","status":"active"}]}"""
        assertNull(BraviaProtocol.decode(body, map, 1).stationId)
    }
    @Test fun `explicit stopped envelope and malformed playing flags are Unknown`() {
        for (flag in listOf("false", "null", "\"false\"", "0")) {
            val body = """{"id":1,"isPlaying":$flag,"result":[{"source":"tv:test","uri":"tv:test"}]}"""
            assertNull(BraviaProtocol.decode(body, map, 1).stationId)
        }
    }
}
