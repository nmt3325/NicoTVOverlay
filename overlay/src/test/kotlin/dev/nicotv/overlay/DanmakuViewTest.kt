package dev.nicotv.overlay

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import dev.nicotv.core.CommentPosition
import dev.nicotv.core.LiveComment
import dev.nicotv.core.OverlayPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DanmakuViewTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var view: DanmakuView

    @Before fun setup() {
        // Keep vsync on explicit simulated time, never an auto-advancing idle loop.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        root = FrameLayout(controller.get())
        controller.get().setContentView(root)
        view = DanmakuView(controller.get())
        root.addView(view, FrameLayout.LayoutParams(1920, 1080))
        root.layout(0, 0, 1920, 1080)
        view.layout(0, 0, 1920, 1080)
        // Paused vsync defers the Window's first attach/layout until its first frame.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertTrue("fixture must be attached", view.isAttachedToWindow)
        assertTrue("fixture must be shown", view.isShown)
        assertEquals(View.VISIBLE, view.windowVisibility)
    }

    @After fun teardown() {
        root.removeAllViews()
        controller.pause().stop().destroy()
    }

    private fun comment(id: String = "1", pos: CommentPosition = CommentPosition.SCROLL) =
        LiveComment(id, "日本語 ABC テレビ", Long.MAX_VALUE, position = pos)

    private fun elapse(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test fun emptyAndExpiredViewsHaveNoSelfSustainingFrameLoop() {
        assertFalse(view.animationScheduled)
        assertFalse(view.wakeScheduled)
        view.addComment(comment())
        assertEquals(1, view.visibleCount)
        assertTrue(view.animationScheduled)
        elapse(8100)
        assertEquals(0, view.visibleCount)
        assertFalse(view.animationScheduled)
        assertFalse(view.wakeScheduled)
    }

    @Test fun fixedOnlyUsesOneWakeInsteadOfAnimationFrames() {
        view.addComment(comment(pos = CommentPosition.TOP))
        assertEquals(1, view.visibleCount)
        assertFalse(view.animationScheduled)
        assertTrue(view.wakeScheduled)
        elapse(4600)
        assertEquals(0, view.visibleCount)
        assertFalse(view.animationScheduled)
        assertFalse(view.wakeScheduled)
    }

    @Test fun clearCancelsDelayAndNeverResurrectsItsComment() {
        view.updatePreferences(OverlayPreferences(delayMs = 1000))
        view.addComment(comment())
        assertEquals(1, view.pendingCount)
        assertFalse(view.animationScheduled)
        assertTrue(view.wakeScheduled)
        view.clearComments()
        assertFalse(view.wakeScheduled)
        elapse(2000)
        assertEquals(0, view.pendingCount)
        assertEquals(0, view.visibleCount)
        assertFalse(view.animationScheduled)
        view.addComment(comment()) // dedupe was cleared too
        elapse(1000)
        assertEquals(1, view.visibleCount)
    }

    @Test fun hiddenParentAndDetachedViewDropAllOldGenerations() {
        view.addComment(comment())
        root.visibility = View.GONE
        assertEquals(0, view.visibleCount)
        assertFalse(view.animationScheduled)
        view.addComment(comment("hidden"))
        assertEquals(0, view.pendingCount)
        root.visibility = View.VISIBLE
        root.removeView(view)
        view.addComment(comment("detached"))
        elapse(10_000)
        root.addView(view)
        view.layout(0, 0, 1920, 1080)
        assertEquals(0, view.visibleCount)
        assertEquals(0, view.pendingCount)
        assertFalse(view.animationScheduled)
        assertFalse(view.wakeScheduled)
    }

    @Test fun resizeAndFontConfigurationClearMeasuredGeometry() {
        view.addComment(comment())
        assertTrue(view.animationScheduled)
        view.layout(0, 0, 1280, 720)
        assertEquals(0, view.visibleCount)
        assertFalse(view.animationScheduled)
        view.addComment(comment("2"))
        val changed = Configuration(view.resources.configuration).apply { fontScale = 1.5f }
        view.dispatchConfigurationChanged(changed)
        assertEquals(0, view.visibleCount)
        assertFalse(view.animationScheduled)
    }

    @Test fun workerIngressIsBoundedAndClearWinsBeforeQueuedUiWork() {
        view.updatePreferences(OverlayPreferences(delayMs = 30_000))
        val worker = Thread {
            repeat(2000) { view.addComment(comment(it.toString())) }
        }
        worker.start()
        worker.join()
        assertEquals(500, view.pendingCount)
        assertTrue(view.uiRefreshScheduled)
        view.clearComments()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, view.pendingCount)
        assertFalse(view.uiRefreshScheduled)
        assertFalse(view.animationScheduled)
        assertFalse(view.wakeScheduled)
        elapse(35_000)
        assertEquals(0, view.visibleCount)
    }

    @Test fun transparentCanvasHasReadableFillAndBlackStrokeWithinSafeInsets() {
        view.updatePreferences(OverlayPreferences(opacity = 1f))
        view.addComment(comment(pos = CommentPosition.TOP))
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(1920 * 1080)
        bitmap.getPixels(pixels, 0, 1920, 0, 0, 1920, 1080)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(960, 540))
        assertTrue(pixels.any { it == Color.WHITE })
        assertTrue(pixels.any { it == Color.BLACK })
        for (y in 0 until 30) for (x in 0 until 1920) assertEquals(0, pixels[y * 1920 + x])
        assertFalse(view.isFocusable)
        assertFalse(view.isClickable)
        bitmap.recycle()
    }

    @Test fun zeroOpacityAndNonFinitePreferencesCannotStartBusyWork() {
        view.updatePreferences(OverlayPreferences(opacity = -1f, fontScale = Float.NaN, speed = Float.POSITIVE_INFINITY))
        view.addComment(comment())
        assertEquals(0, view.visibleCount)
        assertFalse(view.animationScheduled)
        assertFalse(view.wakeScheduled)
    }
}
