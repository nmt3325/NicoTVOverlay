package dev.nicotv.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
