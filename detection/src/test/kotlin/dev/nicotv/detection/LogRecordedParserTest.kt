package dev.nicotv.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

/** テレビ内部ログから放送絶対時刻と放送局を読む経路の検証（画面表示を使わない）。 */
class LogRecordedParserTest {
    @Test fun readsAbsoluteBroadcastInstantFromTvLog() {
        val line = "D/TunableTvView(MainView)( 7606): timeshiftGetCurrentPositionMs: " +
            "current position =Wed Sep 09 23:41:02 GMT+09:00 2026"
        assertEquals(
            OffsetDateTime.parse("2026-09-09T23:41:02+09:00").toInstant().toEpochMilli(),
            LogRecordedParser.positionWallMs(line)!!,
        )
    }

    @Test fun ignoresLinesWithoutAParsablePosition() {
        assertNull(LogRecordedParser.positionWallMs("D/TunableTvView(MainView)( 7606): onTune called"))
        assertNull(LogRecordedParser.positionWallMs("timeshiftGetCurrentPositionMs: current position =unknown"))
        assertNull(LogRecordedParser.positionWallMs("current position =Wed Sep 09 23:41:02 GMT+09:00 2026"))
    }

    @Test fun mapsIsdbServiceIdToJikkyoChannel() {
        assertEquals(
            "jk9",
            LogRecordedParser.stationId(
                "I/[DTVBG]ShDbHelper( 2191): makeStringParams() origNetId:32391 serviceId:23608",
            ),
        )
        assertEquals(
            "jk9",
            LogRecordedParser.stationId(
                "W/DB ( 2094): [DTVCTL_W] [DB] [0380] [readSvcInfo] not found network=0 svcId=0x5c38 num=26",
            ),
        )
        assertEquals("jk1", LogRecordedParser.stationId("makeStringParams() origNetId:32736 serviceId:1024"))
    }

    @Test fun ignoresNetworkIdsAndUnmappedServices() {
        assertNull(LogRecordedParser.stationId("getServiceId() getChannelId begin nwId=32391 tsId=32391"))
        assertNull(LogRecordedParser.stationId("makeStringParams() origNetId:32391 serviceId:31000"))
        assertNull(LogRecordedParser.stationId("[readSvcInfo] not found network=0 num=26"))
    }
}
