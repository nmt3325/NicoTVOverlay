package dev.nicotv.detection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.StationObservation
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
    private val guardExpiry = Runnable { policy.invalidate("ライブ画面ガードの証拠が失効しました") }
    private fun publishObservation(value: dev.nicotv.core.StationObservation) {
        StationDetectionBus.publish(value)
        handler.removeCallbacks(expiry)
        handler.removeCallbacks(guardExpiry)
        if (value.stationId == null && value.watchingTv) handler.postDelayed(guardExpiry, DetectionLimits.GUARD_TTL_MS)
        if (value.stationId != null) handler.postDelayed(expiry,
            (value.observedAtMs + DetectionLimits.EVIDENCE_TTL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    private lateinit var preferences: SharedPreferences
    private var connected = false // Actual system binding only; feedback interruption is different.
    private var interrupted = false // Latches until an observed Stop; Start must then explicitly reauthorize.
    private var receiverRegistered = false
    private var lastScanAt = -DetectionLimits.MIN_SCAN_MS
    private var settleUntil = 0L
    private var confirmation: Runnable? = null
    private var profile = DetectionProfile.parse(false, "", "", "", "", "{}")

    private val scan = Runnable {
        if (canObserve()) {
            if (profile.guardEnabled) publishObservation(readLiveForegroundGuard())
            else if (!profile.enabled && profile.recordedEnabled) publishRecorded()
            else {
                val pending = policy.pending
                val fresh = readEvidence()
                // Event storms cannot indefinitely postpone a due confirmation.
                scheduleConfirmation(if (pending != null && SystemClock.elapsedRealtime() >= pending.dueAt)
                    policy.confirm(pending.generation, fresh) else policy.evidence(fresh))
                if (profile.recordedEnabled) publishRecorded()
            }
        } else suspendObservation("自動検出は停止中、または画面が無効です")
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!connected || interrupted || !profile.collecting) return
            if (!canObserve()) suspendObservation("画面が消灯・ロック中、または検出が停止中です")
            else if (profile.guardEnabled || profile.transientOsd || profile.recordedEnabled) requestScan() // A NEW window + marker check, not package-only evidence.
            else {
                // No child/text/description access; package-filtered events miss Home departure.
                val identity = foregroundOnly()
                policy.heartbeat(identity, identity != null)
                if (policy.pending == null) cancelConfirmation()
            }
            if (connected && !interrupted && profile.collecting) handler.postDelayed(this, DetectionLimits.HEARTBEAT_MS)
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
        settleUntil = 0L
        // Only an actual inactive authorization resets the interruption latch, not an invalid profile.
        val stopped = try { !preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false) }
            catch (_: ClassCastException) { false }
        if (stopped) interrupted = false
        profile = try {
            val mode = preferences.getString(PreferenceContract.DETECTION_MODE, "") ?: ""
            val guard = mode == PreferenceContract.MODE_BRAVIA
            DetectionProfile.parse(
                preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false), mode,
                preferences.getString(PreferenceContract.TV_PACKAGES,
                    if (guard) "" else PreferenceContract.DEFAULT_TV_PACKAGES.joinToString(",")) ?: "",
                if (guard) "" else preferences.getString(PreferenceContract.OSD_RESOURCE_IDS, "") ?: "",
                preferences.getString(PreferenceContract.LIVE_RESOURCE_IDS, "") ?: "",
                if (guard) "{}" else preferences.getString(PreferenceContract.CUSTOM_ALIASES, "{}") ?: "{}",
                if (guard) "" else preferences.getString(PreferenceContract.RECORDED_RESOURCE_IDS, "") ?: "",
                if (guard) "" else preferences.getString("manual_station", "") ?: "",
            )
        } catch (_: ClassCastException) { DetectionProfile.parse(false, "", "", "", "", "{}") }
        policy.authorize(connected && !interrupted && profile.enabled)
        if (!connected) return
        val info = serviceInfo ?: AccessibilityServiceInfo()
        val enabled = !interrupted && profile.collecting
        info.eventTypes = if (enabled) supportedEvents else 0
        // null/empty framework filters can mean all packages. Explicit sentinel plus code gate instead.
        info.packageNames = if (enabled) profile.packages.toTypedArray() else arrayOf(DISABLED_PACKAGE)
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            (if (enabled && profile.guardEnabled) AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS else 0) or
            (if (enabled && profile.transientOsd) AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS else 0) or
            (if (enabled && profile.recordedEnabled) AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS else 0) // AQUOS otherwise exposes only its root.
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = DetectionLimits.MIN_SCAN_MS
        serviceInfo = info
        if (enabled) {
            handler.post(heartbeat)
            requestScan()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || interrupted || !profile.collecting || event == null) return
        if (!canObserve()) { suspendObservation("自動検出は停止中、または画面が無効です"); return }
        if (event.eventType and supportedEvents == 0) return
        // Event text/source is NEVER read. Delayed events only trigger a fresh active-root read.
        if (event.packageName?.toString() !in profile.packages) {
            suspendObservation("テレビアプリが前面にありません"); return
        }
        requestScan()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Observe only tuning keys while the explicitly selected AQUOS profile is active.
        // Never consume, store or log keys; all ordinary remote input passes through unchanged.
        val tuning = event.keyCode in setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_TV_TERRESTRIAL_DIGITAL,
            KeyEvent.KEYCODE_TV_TERRESTRIAL_ANALOG, KeyEvent.KEYCODE_TV_SATELLITE,
            KeyEvent.KEYCODE_TV_SATELLITE_BS, KeyEvent.KEYCODE_TV_SATELLITE_CS) ||
            event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9
        if (tuning && event.action == KeyEvent.ACTION_DOWN && profile.transientOsd && canObserve() &&
            foregroundOnly()?.packageName == dev.nicotv.core.AquosProfile.PACKAGE) {
            cancelConfirmation()
            policy.forget() // a tuning action may land on another station without any OSD read
            policy.invalidate("選局操作を検出・新しい局のOSDを待機中")
            settleUntil = SystemClock.elapsedRealtime() + 400L
            requestScan()
        }
        return false
    }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        val o = policy.observation
        writer.println("NicoTVDetection connected=$connected active=${profile.collecting} aquos=${profile.transientOsd} interrupted=$interrupted")
        writer.println("station=${o.stationId} watchingTv=${o.watchingTv} evidenceAgeMs=${SystemClock.elapsedRealtime() - o.observedAtMs} pending=${policy.pending?.stationId}")
        writer.println("reason=${o.detail}")
    }

    private fun requestScan() {
        handler.removeCallbacks(scan)
        val now = SystemClock.elapsedRealtime()
        val wait = maxOf(0L, DetectionLimits.MIN_SCAN_MS - (now - lastScanAt), settleUntil - now)
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
        if (!connected || interrupted || !profile.collecting || !::preferences.isInitialized) return false
        return try {
            preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false) &&
                preferences.getString(PreferenceContract.DETECTION_MODE, "") == profile.mode &&
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

    /** 録画再生画面の校正済みIDだけを読み、放送日時・放送局・再生位置を公開する。 */
    private fun publishRecorded() {
        lastScanAt = SystemClock.elapsedRealtime()
        fun publish(evidence: RecordedEvidence) =
            RecordedDetectionBus.publish(recordedObservation(evidence, SystemClock.elapsedRealtime()))
        val root = activeRoot()
        if (root == null) { publish(RecordedEvidence(null, 0L, 0L, "前面の画面を取得できません")); return }
        val evidence = try {
            val pkg = root.packageName?.toString()
            if (pkg == null || pkg !in profile.packages) RecordedEvidence(null, 0L, 0L, "テレビアプリが前面にありません")
            else RecordedEvidenceReader.read(AndroidEvidenceNode(root), ForegroundIdentity(pkg, root.windowId),
                profile, System.currentTimeMillis())
        } catch (_: RuntimeException) { RecordedEvidence(null, 0L, 0L, "録画画面を読み取れません") }
        finally { release(root) }
        publish(evidence)
    }

    private fun readLiveForegroundGuard(): StationObservation {
        lastScanAt = SystemClock.elapsedRealtime()
        fun unknown() = StationObservation(null, DetectionOrigin.ACCESSIBILITY, SystemClock.elapsedRealtime(), false,
            "校正済みライブ画面の前面・表示先を確認できません")
        if (!profile.guardEnabled) return unknown()
        val root = activeRoot() ?: return unknown()
        return try {
            val pkg = root.packageName?.toString()
            if (pkg !in profile.packages || !verifiedDefaultWindow(root) ||
                !LiveForegroundReader.read(AndroidEvidenceNode(root, true), requireNotNull(pkg), profile) || !canObserve()) unknown()
            else StationObservation(null, DetectionOrigin.ACCESSIBILITY, SystemClock.elapsedRealtime(), true,
                "前面のライブ画面を確認（局はBRAVIAで別途確認）")
        } catch (_: RuntimeException) { unknown() }
        finally { release(root) }
    }

    /** Public getWindows() is DEFAULT-display only. Inspect metadata, NEVER titles or other window roots. */
    private fun verifiedDefaultWindow(root: AccessibilityNodeInfo): Boolean {
        if (root.windowId < 0 || !root.isVisibleToUser) return false
        val currentWindows = windows
        return try {
            if (currentWindows.isEmpty() || currentWindows.size > 32) return false
            val window = currentWindows.first() // Descending layer order: reject a higher interactive/modal window.
            if (window.id != root.windowId || window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                !window.isActive || !window.isFocused || window.isInPictureInPictureMode ||
                currentWindows.count { it.isActive } != 1) return false
            if (Build.VERSION.SDK_INT >= 30 && window.displayId != Display.DEFAULT_DISPLAY) return false
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            !bounds.isEmpty
        } finally {
            @Suppress("DEPRECATION")
            currentWindows.forEach { it.recycle() }
        }
    }

    private fun cancelConfirmation() { confirmation?.let(handler::removeCallbacks); confirmation = null }
    private fun suspendObservation(reason: String) {
        handler.removeCallbacks(scan)
        cancelConfirmation()
        policy.invalidate(reason)
    }

    override fun onInterrupt() {
        interrupted = true
        handler.removeCallbacksAndMessages(null)
        confirmation = null
        policy.authorize(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        onInterrupt()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        onInterrupt()
        if (::preferences.isInitialized) preferences.unregisterOnSharedPreferenceChangeListener(this)
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        super.onDestroy()
    }

    private class AndroidEvidenceNode(private val node: AccessibilityNodeInfo, private val requireBounds: Boolean = false) : EvidenceNode {
        override val packageName get() = node.packageName?.toString()
        override val resourceId get() = node.viewIdResourceName
        override val visible get(): Boolean {
            if (!node.isVisibleToUser) return false
            if (!requireBounds) return true
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return !bounds.isEmpty
        }
        override val bounds: EvidenceBounds get() {
            val r = Rect(); node.getBoundsInScreen(r)
            return EvidenceBounds(r.left, r.top, r.right, r.bottom)
        }
        override val childCount get() = node.childCount
        override val collection get() = node.collectionInfo != null || node.collectionItemInfo != null ||
            node.className?.toString() in collectionClasses
        override fun text() = node.text
        override fun description() = node.contentDescription
        override fun child(index: Int): EvidenceNode? = node.getChild(index)?.let { AndroidEvidenceNode(it, requireBounds) }
        override fun close() = release(node)
    }

    companion object {
        private const val DISABLED_PACKAGE = "dev.nicotv.detection.disabled"
        private val supportedEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        private val configurationKeys = setOf(PreferenceContract.SESSION_ACTIVE, PreferenceContract.DETECTION_MODE,
            PreferenceContract.TV_PACKAGES, PreferenceContract.OSD_RESOURCE_IDS, PreferenceContract.LIVE_RESOURCE_IDS,
            PreferenceContract.CUSTOM_ALIASES, PreferenceContract.RECORDED_RESOURCE_IDS)
        private val collectionClasses = setOf("android.widget.ListView", "android.widget.GridView",
            "androidx.recyclerview.widget.RecyclerView", "android.support.v7.widget.RecyclerView")
        @Suppress("DEPRECATION") private fun release(node: AccessibilityNodeInfo) { node.recycle() }
    }
}
