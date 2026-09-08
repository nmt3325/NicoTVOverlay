package dev.nicotv.detection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.nicotv.core.PreferenceContract

/** Explicitly started, calibrated live OSD reader. Connecting this service never starts an FGS/network. */
class NicoTvAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = DetectionPolicy(SystemClock::elapsedRealtime, ::publishObservation)
    private val expiry = Runnable { policy.expire() }
    private fun publishObservation(value: dev.nicotv.core.StationObservation) {
        StationDetectionBus.publish(value)
        handler.removeCallbacks(expiry)
        if (value.stationId != null) handler.postDelayed(expiry,
            (value.observedAtMs + DetectionLimits.EVIDENCE_TTL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    private lateinit var preferences: SharedPreferences
    private var connected = false
    private var receiverRegistered = false
    private var lastScanAt = -DetectionLimits.MIN_SCAN_MS
    private var confirmation: Runnable? = null
    private var profile = DetectionProfile.parse(false, "", "", "", "", "{}")

    private val scan = Runnable {
        if (canObserve()) {
            val pending = policy.pending
            val fresh = readEvidence()
            // Event storms cannot indefinitely postpone a due confirmation.
            scheduleConfirmation(if (pending != null && SystemClock.elapsedRealtime() >= pending.dueAt)
                policy.confirm(pending.generation, fresh) else policy.evidence(fresh))
        } else suspendObservation("自動検出は停止中、または画面が無効です")
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!connected || !profile.enabled) return
            if (!canObserve()) suspendObservation("画面が消灯・ロック中、または検出が停止中です")
            else {
                // No child/text/description access; package-filtered events miss Home departure.
                val identity = foregroundOnly()
                policy.heartbeat(identity, identity != null)
                if (policy.pending == null) cancelConfirmation()
            }
            if (connected && profile.enabled) handler.postDelayed(this, DetectionLimits.HEARTBEAT_MS)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> suspendObservation("画面が消灯しました")
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (canObserve()) requestScan() else suspendObservation("画面がロック中です")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences(PreferenceContract.STORE, Context.MODE_PRIVATE)
        preferences.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Only protected system broadcasts, never an externally callable Start path.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverRegistered = true
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        reconfigure()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null || key in configurationKeys) reconfigure()
    }

    private fun reconfigure() {
        handler.removeCallbacksAndMessages(null)
        confirmation = null
        lastScanAt = -DetectionLimits.MIN_SCAN_MS
        profile = try {
            DetectionProfile.parse(
                preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false),
                preferences.getString(PreferenceContract.DETECTION_MODE, "") ?: "",
                preferences.getString(PreferenceContract.TV_PACKAGES, PreferenceContract.DEFAULT_TV_PACKAGES.joinToString(",")) ?: "",
                preferences.getString(PreferenceContract.OSD_RESOURCE_IDS, "") ?: "",
                preferences.getString(PreferenceContract.LIVE_RESOURCE_IDS, "") ?: "",
                preferences.getString(PreferenceContract.CUSTOM_ALIASES, "{}") ?: "{}",
            )
        } catch (_: ClassCastException) { DetectionProfile.parse(false, "", "", "", "", "{}") }
        policy.authorize(connected && profile.enabled)
        if (!connected) return
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = if (profile.enabled) supportedEvents else 0
        // null/empty framework filters can mean all packages. Explicit sentinel plus code gate instead.
        info.packageNames = if (profile.enabled) profile.packages.toTypedArray() else arrayOf(DISABLED_PACKAGE)
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = DetectionLimits.MIN_SCAN_MS
        serviceInfo = info
        if (profile.enabled) {
            handler.post(heartbeat)
            requestScan()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || !profile.enabled || event == null) return
        if (!canObserve()) { suspendObservation("自動検出は停止中、または画面が無効です"); return }
        if (event.eventType and supportedEvents == 0) return
        // Event text/source is NEVER read. Delayed events only trigger a fresh active-root read.
        if (event.packageName?.toString() !in profile.packages) {
            suspendObservation("テレビアプリが前面にありません"); return
        }
        requestScan()
    }

    private fun requestScan() {
        handler.removeCallbacks(scan)
        val wait = (DetectionLimits.MIN_SCAN_MS - (SystemClock.elapsedRealtime() - lastScanAt)).coerceAtLeast(0)
        if (wait == 0L) scan.run() else handler.postDelayed(scan, wait)
    }

    private fun scheduleConfirmation(pending: DetectionPolicy.Pending?) {
        cancelConfirmation()
        if (pending == null) return
        val runnable = object : Runnable {
            override fun run() {
                if (policy.pending?.generation != pending.generation) return
                if (!canObserve()) { suspendObservation("検出が停止中、または画面が無効です"); return }
                val wait = DetectionLimits.MIN_SCAN_MS - (SystemClock.elapsedRealtime() - lastScanAt)
                if (wait > 0) { handler.postDelayed(this, wait); return }
                scheduleConfirmation(policy.confirm(pending.generation, readEvidence()))
            }
        }
        confirmation = runnable
        handler.postDelayed(runnable, (pending.dueAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun canObserve(): Boolean {
        if (!connected || !profile.enabled || !::preferences.isInitialized) return false
        return try {
            preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false) &&
                preferences.getString(PreferenceContract.DETECTION_MODE, "") == PreferenceContract.MODE_ACCESSIBILITY &&
                getSystemService(PowerManager::class.java)?.isInteractive == true &&
                getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        } catch (_: RuntimeException) { false }
    }

    private fun activeRoot(): AccessibilityNodeInfo? = try {
        // API33+ suppresses descendant prefetch. Earlier platforms control their own prefetch.
        if (Build.VERSION.SDK_INT >= 33) getRootInActiveWindow(0) else rootInActiveWindow
    } catch (_: RuntimeException) { null }

    private fun foregroundOnly(): ForegroundIdentity? {
        val root = activeRoot() ?: return null
        return try {
            root.packageName?.toString()?.takeIf { it in profile.packages }?.let { ForegroundIdentity(it, root.windowId) }
        } finally { release(root) }
    }

    private fun readEvidence(): StationEvidence {
        lastScanAt = SystemClock.elapsedRealtime()
        val root = activeRoot() ?: return StationEvidence(null, null, "前面の画面を取得できません")
        return try {
            val pkg = root.packageName?.toString()
            if (pkg == null || pkg !in profile.packages) StationEvidence(null, null, "テレビアプリが前面にありません")
            else StationEvidenceReader.read(AndroidEvidenceNode(root), ForegroundIdentity(pkg, root.windowId), profile)
        } catch (_: RuntimeException) {
            StationEvidence(null, null, "画面情報を読み取れません")
        } finally { release(root) }
    }

    private fun cancelConfirmation() { confirmation?.let(handler::removeCallbacks); confirmation = null }
    private fun suspendObservation(reason: String) {
        handler.removeCallbacks(scan)
        cancelConfirmation()
        policy.invalidate(reason)
    }

    override fun onInterrupt() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        confirmation = null
        policy.authorize(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        onInterrupt()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        onInterrupt()
        if (::preferences.isInitialized) preferences.unregisterOnSharedPreferenceChangeListener(this)
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        super.onDestroy()
    }

    private class AndroidEvidenceNode(private val node: AccessibilityNodeInfo) : EvidenceNode {
        override val packageName get() = node.packageName?.toString()
        override val resourceId get() = node.viewIdResourceName
        override val visible get() = node.isVisibleToUser
        override val childCount get() = node.childCount
        override val collection get() = node.collectionInfo != null || node.collectionItemInfo != null ||
            node.className?.toString() in collectionClasses
        override fun text() = node.text
        override fun description() = node.contentDescription
        override fun child(index: Int): EvidenceNode? = node.getChild(index)?.let(::AndroidEvidenceNode)
        override fun close() = release(node)
    }

    companion object {
        private const val DISABLED_PACKAGE = "dev.nicotv.detection.disabled"
        private val supportedEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        private val configurationKeys = setOf(PreferenceContract.SESSION_ACTIVE, PreferenceContract.DETECTION_MODE,
            PreferenceContract.TV_PACKAGES, PreferenceContract.OSD_RESOURCE_IDS, PreferenceContract.LIVE_RESOURCE_IDS,
            PreferenceContract.CUSTOM_ALIASES)
        private val collectionClasses = setOf("android.widget.ListView", "android.widget.GridView",
            "androidx.recyclerview.widget.RecyclerView", "android.support.v7.widget.RecyclerView")
        @Suppress("DEPRECATION") private fun release(node: AccessibilityNodeInfo) { node.recycle() }
    }
}
