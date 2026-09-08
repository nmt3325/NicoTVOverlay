package dev.nicotv.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import dev.nicotv.core.LiveComment
import dev.nicotv.core.OverlayPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Transparent, non-interactive native Canvas renderer. No transport, service, or window ownership.
 * Public ingress is serialized and bounded, including calls made off the UI thread. Delays live
 * inside the engine; no callback ever captures a comment that could return after clearComments().
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gate = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textBounds = Rect()
    private val fontMetrics = Paint.FontMetrics()
    private val engine = DanmakuEngine(TextMeasurer { text, size ->
        paint.textSize = size
        paint.style = Paint.Style.FILL
        val advance = paint.measureText(text)
        paint.getTextBounds(text, 0, text.length, textBounds)
        paint.getFontMetrics(fontMetrics)
        val left = min(0f, textBounds.left.toFloat())
        TextMetrics(
            max(advance, textBounds.right.toFloat()) - left,
            min(fontMetrics.top, textBounds.top.toFloat()),
            max(fontMetrics.bottom, textBounds.bottom.toFloat()),
            -left,
        )
    })
    private var initialized = false
    private var accepting = false
    private var generation = 0L
    private var refreshPosted = false
    private var frameCallback: Choreographer.FrameCallback? = null
    private var wakeCallback: Runnable? = null
    internal var monotonicClock: () -> Long = SystemClock::elapsedRealtime

    // Test diagnostics do not expose/retain raw comments outside the View.
    internal val visibleCount: Int get() = synchronized(gate) { engine.visible.size }
    internal val pendingCount: Int get() = synchronized(gate) { engine.pendingCount }
    internal val animationScheduled: Boolean get() = synchronized(gate) { frameCallback != null }
    internal val wakeScheduled: Boolean get() = synchronized(gate) { wakeCallback != null }
    internal val uiRefreshScheduled: Boolean get() = synchronized(gate) { refreshPosted }

    private val refresh = Runnable {
        synchronized(gate) {
            refreshPosted = false
            tickLocked()
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        initialized = true
    }

    fun addComment(comment: LiveComment) {
        synchronized(gate) {
            if (accepting && engine.add(comment, monotonicClock())) requestRefreshLocked()
        }
    }

    fun updatePreferences(preferences: OverlayPreferences) {
        synchronized(gate) {
            engine.updatePreferences(preferences)
            generation++
            requestRefreshLocked()
        }
    }

    fun clearComments() {
        synchronized(gate) {
            generation++
            engine.clear()
            requestRefreshLocked()
        }
    }

    /** At most one ingress message, rather than Handler.post(comment) for each network message. */
    private fun requestRefreshLocked() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handler.removeCallbacks(refresh)
            refreshPosted = false
            tickLocked()
        } else if (!refreshPosted) {
            refreshPosted = true
            handler.post(refresh)
        }
    }

    private fun cancelScheduledLocked() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        wakeCallback?.let(handler::removeCallbacks)
        wakeCallback = null
    }

    /** UI thread, gate held. Only moving glyphs have a vsync loop; all other work is one-shot. */
    private fun tickLocked() {
        cancelScheduledLocked()
        if (!accepting) return
        val now = monotonicClock()
        engine.advance(now)
        invalidate() // also erases the last glyph's render node when the engine becomes empty
        if (!engine.hasWork) return
        val expected = generation
        if (engine.hasScrolling) {
            val callback = Choreographer.FrameCallback {
                synchronized(gate) {
                    if (generation == expected && accepting) {
                        frameCallback = null
                        tickLocked()
                    }
                }
            }
            frameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        } else {
            val due = engine.nextWakeAt() ?: return
            val callback = Runnable {
                synchronized(gate) {
                    if (generation == expected && accepting) {
                        wakeCallback = null
                        tickLocked()
                    }
                }
            }
            wakeCallback = callback
            // Handler uses uptime; elapsedRealtime is sampled afresh when woken after suspend.
            handler.postDelayed(callback, (due - now.coerceAtLeast(0L)).coerceAtLeast(1L))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(gate) {
            if (!accepting) return
            val now = monotonicClock()
            engine.advance(now)
            val save = canvas.save()
            canvas.clipRect(engine.safeLeft, engine.safeTop, engine.safeRight, engine.safeBottom)
            val alpha = (engine.preferences.opacity * 255f).roundToInt().coerceIn(0, 255)
            for (glyph in engine.visible) {
                val x = glyph.xAt(now) + glyph.textInset
                paint.textSize = glyph.sizePx
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = glyph.strokePx
                paint.color = Color.BLACK
                paint.alpha = alpha
                canvas.drawText(glyph.text, x, glyph.baseline, paint)
                paint.style = Paint.Style.FILL
                paint.color = glyph.color
                paint.alpha = alpha
                canvas.drawText(glyph.text, x, glyph.baseline, paint)
            }
            canvas.restoreToCount(save)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(gate) {
            generation++
            engine.clear() // never replay a pre-detach generation
            resizeEngineLocked()
        }
        refreshVisibility()
    }

    override fun onDetachedFromWindow() {
        synchronized(gate) {
            accepting = false
            generation++
            engine.clear()
            cancelScheduledLocked()
            handler.removeCallbacks(refresh)
            refreshPosted = false
        }
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (initialized) refreshVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (initialized) refreshVisibility()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (initialized) refreshVisibility(isVisible)
    }

    private fun refreshVisibility(aggregatedVisible: Boolean = true) {
        synchronized(gate) {
            val active = aggregatedVisible && isAttachedToWindow && isShown && windowVisibility == VISIBLE
            if (active != accepting) {
                generation++
                accepting = active
                engine.clear() // hidden comments are dropped, not accumulated for a later burst
                cancelScheduledLocked()
                handler.removeCallbacks(refresh)
                refreshPosted = false
                invalidate()
            }
            if (active) tickLocked()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized) return
        synchronized(gate) {
            generation++
            resizeEngineLocked()
            tickLocked()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        synchronized(gate) {
            generation++
            engine.resize(width, height, resources.displayMetrics.density, newConfig.fontScale)
            tickLocked()
        }
    }

    private fun resizeEngineLocked() {
        engine.resize(width, height, resources.displayMetrics.density, resources.configuration.fontScale)
    }
}
