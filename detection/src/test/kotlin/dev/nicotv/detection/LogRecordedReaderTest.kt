package dev.nicotv.detection

import dev.nicotv.core.RecordedObservation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val pmt =
        "V/CapEngineSystem(17108): [CAPV_I][Main] sendPmtDataCaptionEngineSystem() " +
            "SiPmt.iServiceId : 1048, mLastServiceId : 1072"

    private fun collect(log: String, configured: String?, minimum: Int = 1): List<RecordedObservation> {
        val observations = java.util.Collections.synchronizedList(mutableListOf<RecordedObservation>())
        val reader = LogRecordedReader({ 5_000L }, { observations.add(it) }, { FakeLogcat(log) })
        reader.start(configured)
        val deadline = System.currentTimeMillis() + 3_000L
        while (observations.size < minimum && System.currentTimeMillis() < deadline) Thread.sleep(20L)
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
    fun `keeps the playing station when an unrelated service id appears once`() {
        val noise = "I/[DTVBG]ShDbHelper( 2191): makeStringParams() origNetId:32391 serviceId:1048"
        val observations = collect("$service\n$position\n$noise\n$position\n", "jk4", 2)
        assertTrue(observations.size >= 2)
        assertEquals("jk9", observations.last().stationId)
    }

    @Test
    fun `switches station after a new service id repeats`() {
        val other = "W/DB      ( 2094): [readSvcInfo] not found network=0 svcId=0x0418 num=26"
        val observations = collect("$service\n$position\n$other\n$other\n$position\n", "jk4", 2)
        assertTrue(observations.size >= 2)
        assertEquals("jk9", observations.first().stationId)
        assertEquals("jk6", observations.last().stationId)
    }

    @Test
    fun `never drops a stream that already read the tv log`() {
        val observations = java.util.Collections.synchronizedList(mutableListOf<RecordedObservation>())
        val reader = LogRecordedReader({ 5_000L }, { observations.add(it) }, { FakeLogcat("$service\n$position\n") })
        reader.start("jk4")
        val deadline = System.currentTimeMillis() + 3_000L
        while (observations.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertFalse(reader.reacquire())
        reader.stop()
    }

    @Test
    fun `adopts the station of the playing stream from the pmt log`() {
        val observations = collect("$pmt\n$position\n", "jk9")
        assertTrue(observations.isNotEmpty())
        assertEquals("jk6", observations.first().stationId)
    }

    @Test
    fun `keeps the pmt station when playback jumps to another program`() {
        val other =
            "D/TunableTvView(MainView)(17108): timeshiftGetCurrentPositionMs: " +
                "current position =Sun Jul 26 19:16:24 GMT+09:00 2026"
        val observations = collect("$service\n$position\n$pmt\n$other\n", "jk4", 2)
        assertTrue(observations.size >= 2)
        assertEquals("jk6", observations.last().stationId)
    }

    @Test
    fun `ignores background service ids once the pmt station is known`() {
        val observations = collect("$pmt\n$position\n$service\n$service\n$position\n", "jk4", 2)
        assertTrue(observations.size >= 2)
        assertEquals("jk6", observations.last().stationId)
    }

    @Test
    fun `stopped reader reports that monitoring is off`() {
        val reader = LogRecordedReader({ 0L }, { }, { FakeLogcat("") })
        assertEquals("TVログ監視は停止中", reader.state)
    }
}
