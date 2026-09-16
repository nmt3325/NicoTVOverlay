package dev.nicotv.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.KeyguardManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import java.net.NetworkInterface
import java.net.Inet4Address
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import dev.nicotv.core.PreferenceContract
import dev.nicotv.detection.AccessibilityLink

object PlatformPermissions {
    /** ユーザー補助が無効な時の停止理由。一覧の瞬断と区別できるよう定数で持つ。 */
    const val ACCESSIBILITY_BLOCK = "端末設定でNicoTVOverlayのユーザー補助を有効にしてください"
    fun overlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    /**
     * 有効一覧に自分が出ているか、または自分のサービスが実際に接続中か。
     * 一覧は setServiceInfo の直後などに自分を一時的に外すため、一覧だけで判定すると
     * 録画再生中のセッションが瞬断で止まる。接続の目印は OS がバインドした自分のサービスだけが更新する。
     */
    fun accessibility(context: Context): Boolean =
        accessibilityListed(context) || AccessibilityLink.live(SystemClock.elapsedRealtime())
    fun accessibilityListed(context: Context): Boolean = context.getSystemService(AccessibilityManager::class.java)
        ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == "dev.nicotv.detection.NicoTvAccessibilityService"
        } == true
    fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    fun ownIpv4Addresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }.filter(SettingsValidator::isPrivateIpv4).toSet()
    } catch (_: Exception) { emptySet() }
    fun localHostMatches(host: String, ownAddresses: Set<String>): Boolean =
        SettingsValidator.isPrivateIpv4(host) && host in ownAddresses
    fun defaultDisplay(context: Context): Display? = try {
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.takeIf { it.isValid && it.state == Display.STATE_ON }
    } catch (_: RuntimeException) { null }
    fun defaultTarget(context: Context): Boolean = try {
        val display = if (context is Activity) {
            if (Build.VERSION.SDK_INT >= 30) context.display else context.windowManager.defaultDisplay
        } else context.getSystemService(WindowManager::class.java)?.defaultDisplay
        display != null && display.isValid && display.displayId == Display.DEFAULT_DISPLAY && defaultDisplay(context) != null
    } catch (_: RuntimeException) { false }
    fun screenReady(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    } catch (_: RuntimeException) { false }
    fun block(context: Context, config: AppSettings, visible: Boolean = true): String? = try {
        startBlock(visible, overlays(context), accessibility(context), config.mode,
            defaultDisplay = defaultTarget(context) && screenReady(context),
            braviaCalibrated = config.braviaVisibilityCalibrated,
            localHost = config.mode != PreferenceContract.MODE_BRAVIA || localHostMatches(config.braviaHost, ownIpv4Addresses()))
            ?: if (SettingsValidator.validate(config).isNotEmpty()) "設定に不正な値があります" else if (config.backend == Backend.KAKOLOG && config.recordedAuto && !config.recordedCalibrated) "録画画面の校正（RECORDED_RESOURCE_IDS）を登録してください" else if (config.backend == Backend.KAKOLOG && !config.recordedAuto && config.recordedStartMs <= 0L) "録画番組の放送日時（放送日・開始時刻）を設定してください" else if (config.mode == PreferenceContract.MODE_BRAVIA &&
                (!EncryptedPskStore(context).contains() || SettingsValidator.stationMap(config.braviaMapJson, true).isEmpty())) "PSKと局URI対応表を登録してください" else null
    } catch (_: Exception) { "表示先・権限・ネットワークを確認できない端末では開始できません" }
    fun startBlock(visible: Boolean, overlays: Boolean, accessibility: Boolean, mode: String,
        defaultDisplay: Boolean = true, braviaCalibrated: Boolean = false, localHost: Boolean = false): String? = when {
        !visible -> "画面を開いた状態で開始してください"
        !defaultDisplay -> "有効な標準画面（default display）と画面ON・ロック解除を確認できません"
        !overlays -> "「他のアプリの上に表示」の許可が必要です"
        mode in setOf(PreferenceContract.MODE_ACCESSIBILITY, PreferenceContract.MODE_BRAVIA) && !accessibility -> ACCESSIBILITY_BLOCK
        mode == PreferenceContract.MODE_BRAVIA && !braviaCalibrated -> "BRAVIAにはTV_PACKAGESとLIVE_RESOURCE_IDSの校正が必要です（局OSDは不要）"
        mode == PreferenceContract.MODE_BRAVIA && !localHost -> "BRAVIAホストはこのテレビ自身の私有IPv4に一致する必要があります。未確認・別端末は非対応です"
        else -> null // POST_NOTIFICATIONS denial is not an OS FGS-start prohibition.
    }
}

object ServiceCommands {
    fun start(activity: Activity, visible: Boolean): Boolean {
        if (PlatformPermissions.block(activity, SettingsRepository(activity).read(), visible) != null) return false
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
        if (PlatformPermissions.block(context, SettingsRepository(context).read()) != null) { stop(context); return }
        try { context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_RECONFIGURE)) }
        catch (_: IllegalStateException) { stop(context) }
        catch (_: SecurityException) { stop(context) }
    }
}
