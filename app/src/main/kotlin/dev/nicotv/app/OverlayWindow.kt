package dev.nicotv.app

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
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
    override fun preferences(value: OverlayPreferences) {
        options = value
        view?.let {
            if (!PlatformPermissions.defaultTarget(context)) { clear(); failed(); return }
            // Alpha is applied exactly once at window level, not also to every glyph.
            it.updatePreferences(value.copy(opacity = 1f))
            try { manager?.updateViewLayout(it, parameters()) } catch (_: RuntimeException) { clear(); failed() }
        }
    }
    override fun clear() {
        val old = view
        val previousManager = manager
        view = null; manager = null; drawingContext = null
        if (old == null) return
        old.clearComments()
        try { previousManager?.removeViewImmediate(old) } catch (_: IllegalArgumentException) { /* already removed */ }
    }
    override fun comment(value: LiveComment) {
        if (!RuntimeSession.state.value.active ||
            !SettingsRepository(context).preferences.getBoolean(dev.nicotv.core.PreferenceContract.SESSION_ACTIVE, false) ||
            !PlatformPermissions.overlays(context) || !PlatformPermissions.defaultTarget(context) || !PlatformPermissions.screenReady(context)) { clear(); failed(); return }
        try {
            if (drawingContext == null) {
                val display = PlatformPermissions.defaultDisplay(context) ?: throw IllegalStateException("標準画面が不明")
                val displayContext = context.createDisplayContext(display)
                drawingContext = if (Build.VERSION.SDK_INT >= 30) displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
                manager = drawingContext!!.getSystemService(WindowManager::class.java)
                check(manager?.defaultDisplay?.displayId == android.view.Display.DEFAULT_DISPLAY)
            }
            val target = view ?: DanmakuView(drawingContext!!).also {
                it.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                it.updatePreferences(options.copy(opacity = 1f))
                manager!!.addView(it, parameters()); view = it
            }
            target.addComment(value)
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
