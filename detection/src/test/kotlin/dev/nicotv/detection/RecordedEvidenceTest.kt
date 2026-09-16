package dev.nicotv.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.nicotv.core.PreferenceContract
import java.time.ZoneId

class RecordedEvidenceTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = 1_764_000_000_000L

    @Test fun readsBroadcastStartFromRecordedScreenText() {
        assertEquals(1_606_431_600_000L, RecordedTextParser.broadcastStartMs("2020/11/27(金) 08:00〜08:15", now, zone))
        val inferred = requireNotNull(RecordedTextParser.broadcastStartMs("11月27日(金) 8:00〜8:15", now, zone))
        // 年の表記がない画面では、未来にならない直近の年として解釈する。
        assertTrue(inferred <= now + 86_400_000L)
        assertNull(RecordedTextParser.broadcastStartMs("録画一覧", now, zone))
    }

    @Test fun picksElapsedTimeFromClockCandidates() {
        assertEquals(750_000L, RecordedTextParser.clockMs("12:30"))
        assertEquals(3_690_000L, RecordedTextParser.clockMs("1:01:30"))
        assertNull(RecordedTextParser.clockMs("12:30〜13:00"))
        // 最大値は総時間表示とみなし、次に大きい値を経過時間とする。
        assertEquals(2_400_000L, RecordedTextParser.positionMs(listOf(3_240_000L, 2_400_000L, 840_000L)))
        assertEquals(0L, RecordedTextParser.positionMs(listOf(0L, 3_240_000L)))
        assertEquals(750_000L, RecordedTextParser.positionMs(listOf(750_000L)))
        assertNull(RecordedTextParser.positionMs(emptyList()))
    }

    @Test fun readsJapaneseTwelveHourBroadcastTime() {
        val expected = java.time.LocalDate.of(2025, 9, 2).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        // 実機の画面表示。終了時刻（午前0:00）ではなく開始時刻を読む。
        assertEquals(expected, RecordedTextParser.broadcastStartMs("9/2(水) 午後11:30～午前0:00", now, zone))
        val midnight = java.time.LocalDate.of(2025, 9, 3).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
        assertEquals(midnight, RecordedTextParser.broadcastStartMs("9/3(木) 午前0:10～午前1:00", now, zone))
        val noon = java.time.LocalDate.of(2025, 9, 3).atTime(12, 5).atZone(zone).toInstant().toEpochMilli()
        assertEquals(noon, RecordedTextParser.broadcastStartMs("9/3(木) 午後12:05～午後12:35", now, zone))
    }

    @Test fun usesConfiguredStationWhenRecordedScreenHidesIt() {
        fun profile(station: String) = DetectionProfile.parse(true, PreferenceContract.MODE_ACCESSIBILITY,
            "jp.co.sharp.av.android.aquostvapp", "", "", "{}",
            "jp.co.sharp.av.android.aquostvapp:id/channel_call_recording_program_time_text", station)
        assertEquals("jk9", profile("jk9").recordedStationId)
        assertTrue(profile("jk9").recordedEnabled)
        assertNull(profile("jk999").recordedStationId)
        assertNull(profile("").recordedStationId)
    }
}
