package dev.nicotv.app

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import dev.nicotv.core.LiveComment
import dev.nicotv.core.OverlayPreferences
import dev.nicotv.overlay.DanmakuView

class OverlayWindow(private val context: Context, private val failed: () -> Unit) : CommentSink {
    private var manager: WindowManager? = null
    private var drawingContext: Context? = null
    private var view: DanmakuView? = null
    private var options = OverlayPreferences()
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var ingress: OverlayIngress? = null
    override fun preferences(value: OverlayPreferences) {
        if (options != value) clear() // settings invalidate pre-attach and delayed ingress as well
        options = value
        view?.let {
            if (!PlatformPermissions.defaultTarget(context)) { clear(); failed(); return }
            // Alpha is applied exactly once at window level, not also to every glyph.
            it.updatePreferences(value.copy(opacity = 1f, delayMs = 0L))
            try { manager?.updateViewLayout(it, parameters()) } catch (_: RuntimeException) { clear(); failed() }
        }
    }
    override fun clear() {
        ++generation
        val previousIngress = ingress
        ingress = null
        previousIngress?.close() // invalidate before detaching or any old callback can run
        val old = view
        val previousManager = manager
        view = null; manager = null; drawingContext = null
        if (old == null) return
        old.clearComments()
        try { previousManager?.removeViewImmediate(old) } catch (_: IllegalArgumentException) { /* already removed */ }
    }
    private fun sessionAllowed(): Boolean = RuntimeSession.state.value.active &&
        SettingsRepository(context).preferences.getBoolean(dev.nicotv.core.PreferenceContract.SESSION_ACTIVE, false) &&
        PlatformPermissions.overlays(context) && PlatformPermissions.defaultTarget(context) && PlatformPermissions.screenReady(context)
    override fun comment(value: LiveComment) {
        val receivedAt = SystemClock.elapsedRealtime()
        if (!sessionAllowed()) { clear(); failed(); return }
        if (value.text.length > OverlayIngress.MAX_TEXT_UTF16 || value.id.length > OverlayIngress.MAX_ID_UTF16) return
        try {
            if (drawingContext == null) {
                val display = PlatformPermissions.defaultDisplay(context) ?: throw IllegalStateException("標準画面が不明")
                val displayContext = context.createDisplayContext(display)
                drawingContext = if (Build.VERSION.SDK_INT >= 30) displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
                manager = drawingContext!!.getSystemService(WindowManager::class.java)
                check(manager?.defaultDisplay?.displayId == android.view.Display.DEFAULT_DISPLAY)
            }
            if (view == null) {
                val target = DanmakuView(drawingContext!!)
                val token = ++generation
                view = target // failed/partial addView is also removable by clear()
                target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                // The app owns receipt-relative delay, including time spent waiting for the Window.
                target.updatePreferences(options.copy(opacity = 1f, delayMs = 0L))
                manager!!.addView(target, parameters())
                ingress = OverlayIngress(
                    now = SystemClock::elapsedRealtime,
                    ready = { OverlayIngress.ready(target.isAttachedToWindow, target.width, target.height, target.isShown, target.windowVisibility == View.VISIBLE) },
                    allowed = { generation == token && view === target && sessionAllowed() },
                    schedule = { callback, wait -> handler.postDelayed(callback, wait); Unit },
                    cancel = { callback -> handler.removeCallbacks(callback) },
                    deliver = { target.addComment(it) },
                    invalid = { if (generation == token && view === target) { clear(); failed() } }
                )
            }
            ingress?.offer(value, options.delayMs, receivedAt)
        } catch (_: RuntimeException) { clear(); failed() }
    }
    private fun parameters(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val maximum = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 0.8f
        alpha = safeAlpha(options.opacity, maximum)
        setTitle("NicoTVOverlay")
    }
    companion object {
        fun safeAlpha(requested: Float, maximum: Float): Float {
            val limit = if (maximum.isFinite()) maximum.coerceIn(0f, 0.8f) else 0.8f
            return if (requested.isFinite()) requested.coerceIn(0f, limit) else 0f
        }
    }
}
