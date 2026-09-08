package dev.nicotv.overlay

import dev.nicotv.core.CommentPosition
import dev.nicotv.core.CommentSize
import dev.nicotv.core.LiveComment
import dev.nicotv.core.OverlayPreferences
import java.text.Normalizer
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Bounds include font fallback, overhang and descenders; no Android dependency. */
internal data class TextMetrics(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val leftInset: Float = 0f,
)

internal fun interface TextMeasurer {
    fun measure(text: String, sizePx: Float): TextMetrics
}

/** Immutable, measured once. Position is a function of elapsed time, never frame count. */
internal data class DanmakuGlyph(
    val text: String,
    val color: Int,
    val position: CommentPosition,
    val sizePx: Float,
    val strokePx: Float,
    val width: Float,
    val height: Float,
    val textInset: Float,
    val baseline: Float,
    val firstLane: Int,
    val laneSpan: Int,
    val startsAt: Long,
    val endsAt: Long,
    val startX: Float,
    val velocity: Float,
) {
    fun xAt(nowMs: Long): Float =
        startX - velocity * (nowMs.coerceAtLeast(startsAt) - startsAt).toFloat()
}

/**
 * Single-owner engine; DanmakuView serializes access. It retains only bounded, normalized
 * text, not source comments. postedAtMs is deliberately NOT mixed with the monotonic clock.
 */
internal class DanmakuEngine(private val measurer: TextMeasurer) {
    companion object {
        const val MAX_PENDING = 500
        const val MAX_VISIBLE = 120
        const val MAX_DEDUPE = 1024
        const val MAX_RAW_UTF16 = 2048
        const val MAX_TEXT_CODE_POINTS = 160
        // Match the settings UI/validator; length is Kotlin String.length (UTF-16 units).
        const val MAX_NG_WORDS = 100
        const val MAX_NG_WORD_UTF16 = 100
        const val MAX_DELAY_MS = 30_000L
        const val PENDING_TTL_MS = 5_000L // maximum lateness AFTER the requested due time
        const val DEDUPE_TTL_MS = 60_000L
        const val SCROLL_DURATION_MS = 8_000L
        const val FIXED_DURATION_MS = 4_500L
        private const val MAX_ID_UTF16 = 256

        private fun finite(value: Float, fallback: Float, low: Float, high: Float): Float =
            (if (value.isFinite()) value else fallback).coerceIn(low, high)

        fun sanitizePreferences(value: OverlayPreferences): OverlayPreferences = value.copy(
            fontScale = finite(value.fontScale, 1f, 0.6f, 2f),
            opacity = finite(value.opacity, 0.8f, 0f, 1f),
            speed = finite(value.speed, 1f, 0.5f, 3f),
            maxVisible = value.maxVisible.coerceIn(0, MAX_VISIBLE),
            delayMs = value.delayMs.coerceIn(0L, MAX_DELAY_MS),
            ngWords = normalizeNgWords(value.ngWords),
        )

        // Widen element nullability before mapping: Java callers can violate Kotlin generics.
        private fun normalizeNgWords(raw: List<String?>): List<String> =
            raw.asSequence().take(MAX_NG_WORDS).mapNotNull { normalizeNgWord(it) }.distinct().toList()

        /** A matching rule is normalized in full, never shortened or given an ellipsis. */
        private fun normalizeNgWord(raw: String?): String? {
            if (raw == null || raw.length > MAX_NG_WORD_UTF16) return null
            return normalizeLiteralText(raw)
        }

        /** Display-only shortening is separate from literal NG matching. */
        fun normalizeText(raw: String, maxCodePoints: Int = MAX_TEXT_CODE_POINTS): String? =
            normalizeLiteralText(raw)?.let { shortenForDisplay(it, maxCodePoints) }

        private fun shortenForDisplay(text: String, maxCodePoints: Int = MAX_TEXT_CODE_POINTS): String {
            val limit = maxCodePoints.coerceIn(1, MAX_TEXT_CODE_POINTS)
            if (text.codePointCount(0, text.length) <= limit) return text
            return text.substring(0, text.offsetByCodePoints(0, limit)) + '\u2026'
        }

        /** NFC, case-sensitive, single-line literal text; preserve emoji ZWJ/variation marks. */
        private fun normalizeLiteralText(raw: String): String? {
            if (raw.isEmpty() || raw.length > MAX_RAW_UTF16) return null
            val out = StringBuilder(raw.length)
            var offset = 0
            var space = false
            while (offset < raw.length) {
                var cp = raw.codePointAt(offset)
                offset += Character.charCount(cp)
                if (cp in 0xD800..0xDFFF) cp = 0xFFFD // unpaired surrogate
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                    space = out.isNotEmpty()
                    continue
                }
                if (Character.isISOControl(cp) ||
                    (Character.getType(cp) == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D)
                ) continue
                if (space) out.append(' ')
                space = false
                out.appendCodePoint(cp)
            }
            if (out.isEmpty()) return null
            return Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
        }

        private fun addTime(time: Long, delta: Long): Long =
            if (time > Long.MAX_VALUE - delta) Long.MAX_VALUE else time + delta
    }

    private data class Pending(
        val text: String,
        val color: Int,
        val position: CommentPosition,
        val size: CommentSize,
        val due: Long,
    )

    var preferences: OverlayPreferences = sanitizePreferences(OverlayPreferences())
        private set
    private val pending = ArrayDeque<Pending>()
    private val active = ArrayList<DanmakuGlyph>(MAX_VISIBLE)
    private val dedupe = LinkedHashMap<String, Long>()
    private var lastNow = 0L
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var density = 1f
    private var systemFontScale = 1f
    private var baseTextSize = 45f
    var safeLeft = 0f
        private set
    var safeRight = 0f
        private set
    var safeTop = 0f
        private set
    var safeBottom = 0f
        private set
    private var laneHeight = 0f
    private var laneCount = 0

    val visible: List<DanmakuGlyph> get() = active
    val pendingCount: Int get() = pending.size
    val dedupeCount: Int get() = dedupe.size
    val hasScrolling: Boolean get() = active.any { it.position == CommentPosition.SCROLL }
    val hasWork: Boolean get() = active.isNotEmpty() || pending.isNotEmpty()

    private fun monotonic(nowMs: Long): Long {
        lastNow = max(lastNow, nowMs.coerceAtLeast(0L))
        return lastNow
    }

    fun resize(width: Int, height: Int, density: Float = 1f, systemFontScale: Float = 1f) {
        val w = width.coerceIn(0, 16_384)
        val h = height.coerceIn(0, 16_384)
        val d = finite(density, 1f, 0.5f, 4f)
        val s = finite(systemFontScale, 1f, 0.75f, 2f)
        if (w == viewportWidth && h == viewportHeight && d == this.density && s == this.systemFontScale) return
        viewportWidth = w
        viewportHeight = h
        this.density = d
        this.systemFontScale = s
        safeLeft = w * 0.03f
        safeRight = w * 0.97f
        safeTop = h * 0.03f
        safeBottom = h * 0.97f
        baseTextSize = (max(24f * d, h / 24f) * s).coerceAtMost(256f)
        recalculateLanes()
        clear()
    }

    fun updatePreferences(value: OverlayPreferences) {
        val clean = sanitizePreferences(value)
        if (clean == preferences) return
        preferences = clean
        recalculateLanes()
        clear() // no in-flight speed/font/NG changes can invalidate the collision proof
    }

    private fun recalculateLanes() {
        val largestSize = (baseTextSize * preferences.fontScale * 1.4f).coerceAtMost(320f)
        laneHeight = ceil(largestSize * 1.45f + max(4f, largestSize * 0.12f))
        laneCount = if (laneHeight > 0) ((safeBottom - safeTop) / laneHeight).toInt().coerceIn(0, 256) else 0
    }

    fun clear() {
        pending.clear()
        active.clear()
        dedupe.clear()
    }

    /** Does not measure or touch Android objects, so the View can call this under its ingress lock. */
    fun add(comment: LiveComment, receivedAtMs: Long): Boolean {
        val now = monotonic(receivedAtMs)
        if (laneCount == 0 || safeRight <= safeLeft || safeBottom <= safeTop ||
            preferences.maxVisible == 0 || preferences.opacity == 0f ||
            (!preferences.showFixed && comment.position != CommentPosition.SCROLL) ||
            comment.id.length > MAX_ID_UTF16
        ) return false
        val normalized = normalizeLiteralText(comment.text) ?: return false
        if (preferences.ngWords.any { normalized.contains(it, ignoreCase = false) }) return false
        val text = shortenForDisplay(normalized)
        pruneDedupe(now)
        // Empty identifiers are not deduplicated: identical live reactions are valid comments.
        val key = if (comment.id.isBlank()) null else "${comment.origin.name}:${comment.id}"
        if (key != null) {
            if (dedupe.containsKey(key)) return false
            while (dedupe.size >= MAX_DEDUPE) dedupe.remove(dedupe.keys.first())
            dedupe[key] = addTime(now, DEDUPE_TTL_MS)
        }
        while (pending.size >= MAX_PENDING) pending.removeFirst() // favor fresh live comments
        pending.addLast(Pending(text, comment.color or 0xFF000000.toInt(), comment.position,
            comment.size, addTime(now, preferences.delayMs)))
        return true
    }

    fun advance(nowMs: Long) {
        val now = monotonic(nowMs)
        active.removeAll { now >= it.endsAt }
        pruneDedupe(now)
        while (pending.isNotEmpty() && pending.first.due <= now) {
            val item = pending.removeFirst()
            if (now - item.due > PENDING_TTL_MS || active.size >= preferences.maxVisible) continue
            place(item, now)?.let(active::add)
        }
    }

    private fun pruneDedupe(now: Long) {
        val iter = dedupe.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value <= now) iter.remove() else break
        }
    }

    /** No polling is needed for future comments or stationary glyphs. */
    fun nextWakeAt(): Long? {
        var result: Long? = pending.peekFirst()?.due
        for (glyph in active) {
            if (result == null || glyph.endsAt < result) result = glyph.endsAt
        }
        return result
    }

    private fun place(item: Pending, now: Long): DanmakuGlyph? {
        val factor = when (item.size) {
            CommentSize.SMALL -> 0.75f
            CommentSize.NORMAL -> 1f
            CommentSize.LARGE -> 1.4f
        }
        val size = (baseTextSize * preferences.fontScale * factor).coerceIn(12f, 320f)
        val stroke = max(1.5f, size * 0.065f)
        val pad = stroke + 2f
        val metrics = measurer.measure(item.text, size)
        if (!metrics.width.isFinite() || !metrics.ascent.isFinite() || !metrics.descent.isFinite() ||
            !metrics.leftInset.isFinite() || metrics.width <= 0f || metrics.ascent > 0f ||
            metrics.descent < 0f || metrics.leftInset < 0f
        ) return null
        val width = metrics.width + pad * 2f
        val height = metrics.descent - metrics.ascent + pad * 2f
        val safeWidth = safeRight - safeLeft
        // No bitmap per text. Still reject extreme glyph widths before geometry/scheduling.
        val maxWidth = safeWidth * if (item.position == CommentPosition.SCROLL) 3f else 1f
        if (!width.isFinite() || !height.isFinite() || width > maxWidth || height <= 0f || height > safeBottom - safeTop) return null
        val span = ceil(height / laneHeight).toInt().coerceAtLeast(1)
        if (span > laneCount) return null
        val duration = if (item.position == CommentPosition.SCROLL)
            (SCROLL_DURATION_MS / preferences.speed).toLong() else FIXED_DURATION_MS
        val ends = addTime(item.due, duration)
        if (now >= ends) return null
        val velocity = if (item.position == CommentPosition.SCROLL) (safeWidth + width) / duration else 0f
        val startX = if (item.position == CommentPosition.SCROLL) safeRight else safeLeft + (safeWidth - width) / 2f
        val lanes = if (item.position == CommentPosition.BOTTOM) (laneCount - span downTo 0) else (0..laneCount - span)
        for (lane in lanes) {
            // Use the same top-origin grid for ALL positions; bottom alignment within the last row.
            val top = safeTop + lane * laneHeight +
                if (item.position == CommentPosition.BOTTOM) (laneHeight * span - height) else 0f
            val candidate = DanmakuGlyph(item.text, item.color, item.position, size, stroke, width,
                height, pad + metrics.leftInset, top + pad - metrics.ascent,
                lane, span, item.due, ends, startX, velocity)
            if (active.none { conflicts(it, candidate, now) }) return candidate
        }
        return null // conservative drop rather than overlap or unbounded waiting
    }

    private fun conflicts(previous: DanmakuGlyph, next: DanmakuGlyph, now: Long): Boolean {
        if (previous.firstLane >= next.firstLane + next.laneSpan ||
            next.firstLane >= previous.firstLane + previous.laneSpan
        ) return false
        // Fixed and moving glyphs share the grid; mixed occupancy reserves a whole lane.
        if (previous.position != CommentPosition.SCROLL || next.position != CommentPosition.SCROLL) return true
        val gap = max(8f, next.sizePx * 0.25f)
        val gapNow = next.xAt(now) - (previous.xAt(now) + previous.width)
        if (gapNow < gap) return true
        // Distance is linear. Check BOTH endpoints until the earlier glyph exits;
        // this rejects a wide/faster follower which would otherwise catch the leader.
        val horizon = min(previous.endsAt, next.endsAt)
        val gapAtExit = next.xAt(horizon) - (previous.xAt(horizon) + previous.width)
        return gapAtExit < gap
    }
}
