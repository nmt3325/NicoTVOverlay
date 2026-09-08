package dev.nicotv.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import dev.nicotv.core.PreferenceContract

object PlatformPermissions {
    fun overlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    fun accessibility(context: Context): Boolean = context.getSystemService(AccessibilityManager::class.java)
        ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == "dev.nicotv.detection.NicoTvAccessibilityService"
        } == true
    fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    fun startBlock(visible: Boolean, overlays: Boolean, accessibility: Boolean, mode: String): String? = when {
        !visible -> "画面を開いた状態で開始してください"
        !overlays -> "「他のアプリの上に表示」の許可が必要です"
        mode == PreferenceContract.MODE_ACCESSIBILITY && !accessibility -> "端末設定でNicoTVOverlayのユーザー補助を有効にしてください"
        else -> null // POST_NOTIFICATIONS denial is not an OS FGS-start prohibition.
    }
}

object ServiceCommands {
    fun start(activity: Activity, visible: Boolean): Boolean {
        val ticket = RuntimeSession.authorize(visible) ?: return false
        return try {
            ContextCompat.startForegroundService(activity, Intent(activity, OverlayService::class.java)
                .setAction(OverlayService.ACTION_START).putExtra(OverlayService.EXTRA_TICKET, ticket))
            true
        } catch (_: IllegalStateException) { RuntimeSession.invalidate(); false }
        catch (_: SecurityException) { RuntimeSession.invalidate(); false }
    }
    fun stop(context: Context) {
        RuntimeSession.invalidate()
        SettingsRepository(context).setSessionActive(false)
        // Synchronously invalidate the controller/window before the asynchronous platform stop.
        // This is a stable Runnable registered by the live Service, never a recreated SAM listener.
        RuntimeSession.stopRegisteredService()
        val stopIntent = Intent(context, OverlayService::class.java)
        context.stopService(stopIntent)
        RuntimeSession.publish(RuntimeSession.state.value.copy(active = false, stationId = null, message = "停止中"))
    }
    fun reload(context: Context) {
        if (!RuntimeSession.state.value.active) return
        try { context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_RECONFIGURE)) }
        catch (_: IllegalStateException) { stop(context) }
        catch (_: SecurityException) { stop(context) }
    }
}
