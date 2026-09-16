package dev.nicotv.app

import dev.nicotv.core.CommentOrigin
import dev.nicotv.core.RecordedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RecordedPlaybackTest {
    @Test fun parsesBroadcastDateAndTime() {
        val ms = requireNotNull(SettingsValidator.recordedStart("2020-11-27", "08:00"))
        val zoned = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        assertEquals(2020, zoned.year)
        assertEquals(27, zoned.dayOfMonth)
        assertEquals(8, zoned.hour)
        assertEquals("2020-11-27" to "08:00", SettingsValidator.recordedFields(ms))
        assertNull(SettingsValidator.recordedStart("2020/11/27", "08:00"))
        assertNull(SettingsValidator.recordedStart("1995-01-01", "08:00"))
    }

    @Test fun parsesPlaybackOffsets() {
        assertEquals(750_000L, SettingsValidator.offsetMs("12:30"))
        assertEquals(750_000L, SettingsValidator.offsetMs("750"))
        assertEquals(3_690_000L, SettingsValidator.offsetMs("1:01:30"))
        assertNull(SettingsValidator.offsetMs("12:x"))
        assertNull(SettingsValidator.offsetMs("13:00:00"))
        assertEquals("0:12:30", SettingsValidator.offsetText(750_000L))
    }

    @Test fun recordedBackendUsesArchiveOrigin() {
        assertEquals(CommentOrigin.NX_KAKOLOG, Backend.KAKOLOG.origin)
        val settings = AppSettings(backend = Backend.KAKOLOG, recordedStartMs = 1_606_431_600_000L, recordedOffsetMs = 30_000L)
        assertEquals(RecordedPlan(1_606_431_600_000L, 30_000L).anchorMs, settings.recordedPlan().anchorMs)
        assertEquals(1_606_431_630_000L, settings.recordedPlan().anchorMs)
    }
}
