package dev.nicotv.detection

import dev.nicotv.core.RecordedObservation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRecordedReaderTest {
    private class FakeLogcat(text: String) : Process() {
        private val input = ByteArrayInputStream(text.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }

    private val position =
        "D/TunableTvView(MainView)(17108): timeshiftGetCurrentPositionMs: " +
            "current position =Wed Sep 09 23:41:02 GMT+09:00 2026"
    private val service = "W/DB      ( 2094): [readSvcInfo] not found network=0 svcId=0x5c38 num=26"

    private fun collect(log: String, configured: String?): List<RecordedObservation> {
        val observations = java.util.Collections.synchronizedList(mutableListOf<RecordedObservation>())
        val reader = LogRecordedReader({ 5_000L }, { observations.add(it) }, { FakeLogcat(log) })
        reader.start(configured)
        val deadline = System.currentTimeMillis() + 3_000L
        while (observations.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        reader.stop()
        return observations.toList()
    }

    @Test
    fun `reads broadcast time and station from the tv log`() {
        val observations = collect("$service\n$position\n", "jk4")
        assertTrue(observations.isNotEmpty())
        val first = observations.first()
        assertEquals("jk9", first.stationId)
        assertEquals(LogRecordedParser.positionWallMs(position), first.programStartMs)
        assertEquals(0L, first.positionMs)
    }

    @Test
    fun `falls back to the configured station when the log has no service id`() {
        val observations = collect("$position\n", "jk4")
        assertTrue(observations.isNotEmpty())
        assertEquals("jk4", observations.first().stationId)
    }

    @Test
    fun `stopped reader reports that monitoring is off`() {
        val reader = LogRecordedReader({ 0L }, { }, { FakeLogcat("") })
        assertEquals("TVログ監視は停止中", reader.state)
    }
}
