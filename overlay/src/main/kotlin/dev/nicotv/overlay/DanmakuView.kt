package dev.nicotv.overlay
import android.content.Context
import android.util.AttributeSet
import android.view.View
import dev.nicotv.core.*
// Compile-only worktree contract placeholder; replace before final integration.
class DanmakuView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
 fun addComment(comment: LiveComment) {}
 fun updatePreferences(preferences: OverlayPreferences) {}
 fun clearComments() {}
}
