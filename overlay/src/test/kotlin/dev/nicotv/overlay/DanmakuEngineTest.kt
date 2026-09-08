package dev.nicotv.overlay

import dev.nicotv.core.CommentOrigin
import dev.nicotv.core.CommentPosition
import dev.nicotv.core.CommentSize
import dev.nicotv.core.LiveComment
import dev.nicotv.core.OverlayPreferences
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DanmakuEngineTest {
    private fun engine(width: Int = 1920, height: Int = 1080) = DanmakuEngine(TextMeasurer { text, px ->
        TextMetrics(text.codePointCount(0, text.length) * px * 0.7f, -px, px * 0.25f)
    }).apply { resize(width, height) }

    private fun comment(id: String = "1", text: String = "こんにちは テレビ", position: CommentPosition = CommentPosition.SCROLL,
        size: CommentSize = CommentSize.NORMAL, timestamp: Long = Long.MIN_VALUE) =
        LiveComment(id, text, timestamp, position, size)

    @Test fun scrollLifetimeUsesTimeNotFramesOrServerTimestamp() {
        val e = engine()
        assertTrue(e.add(comment(timestamp = Long.MAX_VALUE), 1000))
        e.advance(1000)
        val glyph = e.visible.single()
        assertEquals(e.safeRight, glyph.xAt(1000), 0.001f)
        assertTrue(glyph.xAt(5000) < glyph.xAt(2000))
        assertEquals(e.safeLeft - glyph.width, glyph.xAt(9000), 0.01f)
        e.advance(8999)
        assertEquals(1, e.visible.size)
        e.advance(9000)
        assertFalse(e.hasWork)
        assertNull(e.nextWakeAt())
    }

    @Test fun exactDelayAndClearRemoveAllPendingVisibleAndDedupe() {
        val e = engine()
        e.updatePreferences(OverlayPreferences(delayMs = 1000))
        e.add(comment(), 100)
        assertEquals(1100L, e.nextWakeAt())
        e.advance(1099)
        assertTrue(e.visible.isEmpty())
        e.advance(1100)
        assertEquals(1, e.visible.size)
        e.add(comment("2"), 1200)
        e.clear()
        assertEquals(0, e.pendingCount)
        assertEquals(0, e.dedupeCount)
        e.advance(100_000)
        assertFalse(e.hasWork)
        assertTrue(e.add(comment(), 100_001))
    }

    @Test fun lateQueueHasTtlAndNeverRestartsAFullLifetime() {
        val e = engine()
        e.updatePreferences(OverlayPreferences(delayMs = 30_000))
        e.add(comment(), 0)
        e.advance(35_001)
        assertFalse(e.hasWork)
        e.add(comment("2"), 40_000)
        e.advance(71_000)
        assertEquals(70_000L, e.visible.single().startsAt)
        assertEquals(78_000L, e.visible.single().endsAt)
        e.advance(78_000)
        assertFalse(e.hasWork)
    }

    @Test fun pressureDropsOldestAndBoundsAllRetainedCollections() {
        val e = engine()
        e.updatePreferences(OverlayPreferences(delayMs = 30_000, maxVisible = Int.MAX_VALUE))
        repeat(2500) { assertTrue(e.add(comment(it.toString(), "v$it"), 0)) }
        assertEquals(DanmakuEngine.MAX_PENDING, e.pendingCount)
        assertEquals(DanmakuEngine.MAX_DEDUPE, e.dedupeCount)
        e.advance(30_000)
        assertTrue(e.visible.isNotEmpty())
        assertEquals("v2000", e.visible.first().text)
        assertTrue(e.visible.size <= DanmakuEngine.MAX_VISIBLE)
        assertEquals(0, e.pendingCount)
        e.advance(100_000)
        assertEquals(0, e.dedupeCount)
    }

    @Test fun visibleLimitIsHardAndZeroDisables() {
        val e = engine()
        e.updatePreferences(OverlayPreferences(maxVisible = 2))
        repeat(40) { e.add(comment(it.toString()), 0) }
        e.advance(0)
        assertEquals(2, e.visible.size)
        e.updatePreferences(OverlayPreferences(maxVisible = 0))
        assertFalse(e.add(comment(), 1))
        assertFalse(e.hasWork)
    }

    @Test fun normalizedUnicodeIsSingleLineAndLiteralNotHtml() {
        assertEquals("日本 語 <b>😀</b>", DanmakuEngine.normalizeText("  日本\n\t語\u0000 \u202E<b>😀</b>  "))
        assertEquals("é 👩‍💻", DanmakuEngine.normalizeText("e\u0301 👩‍💻"))
        assertEquals("�", DanmakuEngine.normalizeText("\uD800"))
        assertNull(DanmakuEngine.normalizeText("\n\u0000\u202E"))
        val text = DanmakuEngine.normalizeText("😀".repeat(200))!!
        assertEquals(161, text.codePointCount(0, text.length))
        assertTrue(text.endsWith("…"))
        assertFalse(text.contains('\uFFFD'))
        assertNull(DanmakuEngine.normalizeText("x".repeat(2049)))
    }

    @Test fun oversizedTextAndUnrenderableMetricsAreDropped() {
        val e = engine(320, 100)
        assertFalse(e.add(comment(text = "x".repeat(2049)), 0))
        assertTrue(e.add(comment(text = "日".repeat(160)), 0))
        e.advance(0)
        assertFalse(e.hasWork)
        val broken = DanmakuEngine(TextMeasurer { _, _ -> TextMetrics(Float.NaN, -1f, 1f) })
        broken.resize(1920, 1080)
        broken.add(comment(), 0)
        broken.advance(0)
        assertFalse(broken.hasWork)
    }

    @Test fun finiteClampsAndBoundedNgAreApplied() {
        val p = DanmakuEngine.sanitizePreferences(OverlayPreferences(Float.NaN, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Int.MAX_VALUE, Long.MAX_VALUE, List(100) { "word$it" }))
        assertEquals(1f, p.fontScale, 0f)
        assertEquals(0.8f, p.opacity, 0f)
        assertEquals(1f, p.speed, 0f)
        assertEquals(120, p.maxVisible)
        assertEquals(30_000L, p.delayMs)
        assertEquals(64, p.ngWords.size)
        val low = DanmakuEngine.sanitizePreferences(OverlayPreferences(-1f, -1f, -1f, -9, Long.MIN_VALUE))
        assertEquals(0.75f, low.fontScale, 0f)
        assertEquals(0f, low.opacity, 0f)
        assertEquals(0.5f, low.speed, 0f)
        assertEquals(0, low.maxVisible)
        assertEquals(0L, low.delayMs)
    }

    @Test fun ngUsesCaseSensitiveNfcLiteralSubstringNotRegex() {
        val e = engine()
        e.updatePreferences(OverlayPreferences(ngWords = listOf("AbC", "é", ".*")))
        assertFalse(e.add(comment(text = "some AbC text"), 0))
        assertTrue(e.add(comment(text = "some abc text"), 0))
        assertFalse(e.add(comment("2", "e\u0301"), 0))
        assertTrue(e.add(comment("3", "anything"), 0))
        assertFalse(e.add(comment("4", ".*"), 0))
    }

    @Test fun settingsResizeAndSystemScaleClearGeometryAndFutureQueue() {
        val e = engine()
        e.add(comment(), 0)
        e.advance(0)
        e.resize(1280, 720)
        assertFalse(e.hasWork)
        assertEquals(0, e.dedupeCount)
        e.updatePreferences(OverlayPreferences(delayMs = 1000))
        e.add(comment(), 0)
        e.resize(1280, 720, systemFontScale = 1.5f)
        assertFalse(e.hasWork)
        e.add(comment(), 0)
        e.updatePreferences(OverlayPreferences(fontScale = 2f))
        assertFalse(e.hasWork)
        e.resize(-1, Int.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY)
        assertFalse(e.add(comment(), 0))
    }

    @Test fun topAndBottomAndScrollingNeverCompeteForTheSameOccupiedLane() {
        val e = engine()
        e.add(comment("top", position = CommentPosition.TOP), 0)
        e.add(comment("bottom", position = CommentPosition.BOTTOM), 0)
        e.add(comment("scroll"), 0)
        e.advance(0)
        assertEquals(3, e.visible.size)
        val (top, bottom, scroll) = e.visible
        assertTrue(top.firstLane < bottom.firstLane)
        assertNotEquals(top.firstLane, scroll.firstLane)
        assertNotEquals(bottom.firstLane, scroll.firstLane)
        assertEquals(top.xAt(0), top.xAt(1000), 0f)
        assertTrue(top.baseline > e.safeTop)
        assertTrue(bottom.baseline < e.safeBottom)
        e.advance(DanmakuEngine.FIXED_DURATION_MS)
        assertTrue(e.visible.all { it.position == CommentPosition.SCROLL })
        e.updatePreferences(OverlayPreferences(showFixed = false))
        assertFalse(e.add(comment(position = CommentPosition.TOP), 5000))
        assertFalse(e.add(comment(position = CommentPosition.BOTTOM), 5000))
    }

    @Test fun fasterWiderFollowerCannotOvertakeLeader() {
        // 100px height yields exactly one lane with this deterministic font/density.
        val e = engine(1000, 100)
        e.add(comment("leader", "a"), 0)
        e.advance(0)
        assertEquals(1, e.visible.size)
        e.add(comment("fast", "b".repeat(30)), 1000)
        e.advance(1000)
        assertEquals(1, e.visible.size) // entry gap exists, but catch-up before exit is unsafe
        e.add(comment("slow", "a"), 2000)
        e.advance(2000)
        assertEquals(2, e.visible.size) // equal speeds and sufficient entry gap are safe
        val a = e.visible[0]
        val b = e.visible[1]
        for (time in 2000L..7999L step 17) {
            assertTrue(b.xAt(time) > a.xAt(time) + a.width)
        }
    }

    @Test fun mixedSizesColorsAndDenseRandomTrafficNeverOverlap() {
        val e = engine()
        val random = Random(429)
        for (time in 0L..25_000L step 100) {
            repeat(3) { index ->
                val pos = CommentPosition.entries[random.nextInt(3)]
                val size = CommentSize.entries[random.nextInt(3)]
                e.add(comment("$time-$index", "日".repeat(random.nextInt(1, 35)), pos, size)
                    .copy(color = 0x00FF0000), time)
            }
            e.advance(time)
            val items = e.visible
            for (i in items.indices) for (j in i + 1 until items.size) {
                val a = items[i]
                val b = items[j]
                val overlapY = a.firstLane < b.firstLane + b.laneSpan && b.firstLane < a.firstLane + a.laneSpan
                if (overlapY) {
                    assertEquals(CommentPosition.SCROLL, a.position)
                    assertEquals(CommentPosition.SCROLL, b.position)
                    assertTrue("time=$time", a.xAt(time) + a.width <= b.xAt(time) || b.xAt(time) + b.width <= a.xAt(time))
                }
            }
            assertTrue(items.all { it.color == 0xFFFF0000.toInt() })
            assertTrue(items.size <= 60)
        }
    }

    @Test fun dedupeExpiresSeparatesOriginsAndClearReleasesIt() {
        val e = engine()
        assertTrue(e.add(comment(), 0))
        assertFalse(e.add(comment(), 1))
        assertTrue(e.add(comment().copy(origin = CommentOrigin.NX_JIKKYO), 1))
        assertFalse(e.add(comment("i".repeat(257)), 1))
        e.advance(60_000)
        assertTrue(e.add(comment(), 60_000))
        e.clear()
        assertTrue(e.add(comment(), 60_001))
    }

    @Test fun backwardAndExtremeTimesCannotCrashOrCreateImmortalWork() {
        val e = engine()
        e.add(comment(), 100)
        e.advance(-100)
        assertEquals(1, e.visible.size)
        e.advance(Long.MAX_VALUE)
        assertFalse(e.hasWork)
        e.add(comment("2", timestamp = Long.MIN_VALUE), Long.MAX_VALUE)
        e.advance(Long.MAX_VALUE)
        assertFalse(e.hasWork)
        val fresh = engine()
        fresh.add(comment(timestamp = -99), -123)
        fresh.advance(0)
        assertEquals(0L, fresh.visible.single().startsAt)
    }
}
