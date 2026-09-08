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
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: DanmakuView? = null
    private var options = OverlayPreferences()
    override fun preferences(value: OverlayPreferences) {
        options = value
        view?.let {
            // Alpha is applied exactly once at window level, not also to every glyph.
            it.updatePreferences(value.copy(opacity = 1f))
            try { manager.updateViewLayout(it, parameters()) } catch (_: RuntimeException) { clear(); failed() }
        }
    }
    override fun clear() {
        val old = view ?: return
        view = null
        old.clearComments()
        try { manager.removeViewImmediate(old) } catch (_: IllegalArgumentException) { /* already removed */ }
    }
    override fun comment(value: LiveComment) {
        if (!RuntimeSession.state.value.active ||
            !SettingsRepository(context).preferences.getBoolean(dev.nicotv.core.PreferenceContract.SESSION_ACTIVE, false) ||
            !PlatformPermissions.overlays(context)) { clear(); failed(); return }
        try {
            val target = view ?: DanmakuView(context).also {
                it.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                it.updatePreferences(options.copy(opacity = 1f))
                manager.addView(it, parameters()); view = it
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
