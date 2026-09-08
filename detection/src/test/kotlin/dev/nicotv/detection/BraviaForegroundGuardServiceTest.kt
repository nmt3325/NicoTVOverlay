package dev.nicotv.detection

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.PreferenceContract
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Synthetic root/window metadata, not an actual TV or display-composition claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], shadows = [FreshRootAccessibilityShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class BraviaForegroundGuardServiceTest {
    private lateinit var controller: ServiceController<NicoTvAccessibilityService>
    private lateinit var service: NicoTvAccessibilityService
    private lateinit var prefs: SharedPreferences
    private var pkg = "test.tv"
    private var nullRoot = false
    private var markerId: String? = "test.tv:id/live"
    private var markerVisible = true
    private var markerBounds = true
    private var guide = false
    private var windowsPresent = true
    private var windowId = 7
    private var active = true
    private var focused = true
    private var pip = false
    private var bounds = true
    private var display = 0
    private var windowType = AccessibilityWindowInfo.TYPE_APPLICATION
    private fun window(id: Int = windowId): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().apply {
        shadowOf(this).apply {
            setId(id); setActive(active); setFocused(focused); setType(windowType); setPictureInPicture(pip)
            setBoundsInScreen(if (bounds) Rect(0, 0, 1920, 1080) else Rect())
            if (Build.VERSION.SDK_INT >= 30) setDisplayId(display)
        }
    }
    private fun node(id: String? = null): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg; viewIdResourceName = id; isVisibleToUser = true
        setBoundsInScreen(Rect(0, 0, 1920, 1080))
    }
    private fun tree(): AccessibilityNodeInfo? {
        if (nullRoot) return null
        return node().apply {
            shadowOf(this).setAccessibilityWindowInfo(window(7))
            if (guide) className = "android.widget.ListView"
            shadowOf(this).addChild(node("test.tv:id/station").apply { text = "TBS" })
            if (markerId != null) shadowOf(this).addChild(node(markerId).apply {
                isVisibleToUser = markerVisible
                if (!markerBounds) setBoundsInScreen(Rect())
            })
        }
    }
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(PreferenceContract.STORE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        FreshRootAccessibilityShadow.rootFactory = ::tree
        FreshRootAccessibilityShadow.windowsFactory = { if (windowsPresent) listOf(window()) else emptyList() }
        FreshRootAccessibilityShadow.reads = 0; FreshRootAccessibilityShadow.windowReads = 0
        controller = Robolectric.buildService(NicoTvAccessibilityService::class.java).create()
        service = controller.get()
    }
    @After fun cleanup() {
        controller.destroy()
        FreshRootAccessibilityShadow.rootFactory = { null }; FreshRootAccessibilityShadow.windowsFactory = { emptyList() }
    }
    private fun configure(start: Boolean = true, explicitPackages: Boolean = true) {
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, start)
            .putString(PreferenceContract.DETECTION_MODE, PreferenceContract.MODE_BRAVIA)
            .putString(PreferenceContract.LIVE_RESOURCE_IDS, "test.tv:id/live")
            // If this mode even READS the OSD/alias setting, these wrong types fail the profile.
            .putInt(PreferenceContract.OSD_RESOURCE_IDS, 42).putInt(PreferenceContract.CUSTOM_ALIASES, 42)
            .apply { if (explicitPackages) putString(PreferenceContract.TV_PACKAGES, "test.tv") }.commit()
        service.onServiceConnected(); advance(0)
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun value() = StationDetectionBus.observation.value
    private fun event() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = "test.tv" }
        service.onAccessibilityEvent(event); event.recycle()
    }
    private fun assertGuard() { assertNull(value().stationId); assertEquals(DetectionOrigin.ACCESSIBILITY, value().origin); assertTrue(value().watchingTv) }

    @Test fun `guard reports independent live foreground without a station and without OSD configuration`() {
        configure(); assertGuard(); assertEquals(SystemClock.elapsedRealtime(), value().observedAtMs)
        assertTrue(service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        assertTrue(FreshRootAccessibilityShadow.windowReads > 0)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Test fun `missing explicit package configuration never falls back to suggested TV packages`() {
        configure(explicitPackages = false); advance(2000)
        assertFalse(value().watchingTv); assertEquals(0, FreshRootAccessibilityShadow.reads)
        assertEquals(0, service.serviceInfo.eventTypes)
    }
    @Test fun `service connection without Start never reads roots or windows`() {
        configure(start = false); advance(2000); event()
        assertFalse(value().watchingTv); assertEquals(0, FreshRootAccessibilityShadow.reads)
        assertEquals(0, FreshRootAccessibilityShadow.windowReads)
    }
    @Test fun `heartbeat rereads marker not just package and renews only actual guard evidence`() {
        configure(); assertGuard(); val first = value().observedAtMs
        advance(1000); assertGuard(); assertTrue(value().observedAtMs > first)
        markerId = null; advance(1000); assertFalse(value().watchingTv)
    }
    @Test fun `Home and null root are detected without package-filtered events`() {
        configure(); assertGuard(); val windowReads = FreshRootAccessibilityShadow.windowReads
        pkg = "test.launcher"; advance(1000); assertFalse(value().watchingTv)
        assertEquals(windowReads, FreshRootAccessibilityShadow.windowReads)
        pkg = "test.tv"; nullRoot = true; advance(1000); assertFalse(value().watchingTv)
    }
    @Test fun `guide recording invisible blank and zero-area markers clear guard`() {
        configure(); assertGuard()
        guide = true; advance(1000); assertFalse(value().watchingTv)
        guide = false; markerId = "test.tv:id/recording"; advance(1000); assertFalse(value().watchingTv)
        markerId = ""; advance(1000); assertFalse(value().watchingTv)
        markerId = "test.tv:id/live"; markerVisible = false; advance(1000); assertFalse(value().watchingTv)
        markerVisible = true; markerBounds = false; advance(1000); assertFalse(value().watchingTv)
    }
    @Test fun `unverified unfocused inactive PiP modal and mismatched windows fail closed`() {
        configure(); assertGuard()
        val cases: List<() -> Unit> = listOf({ windowsPresent = false }, { windowId = 8 },
            { active = false }, { focused = false }, { pip = true }, { bounds = false },
            { windowType = AccessibilityWindowInfo.TYPE_SYSTEM })
        for (change in cases) {
            windowsPresent = true; windowId = 7; active = true; focused = true; pip = false; bounds = true
            windowType = AccessibilityWindowInfo.TYPE_APPLICATION
            change(); advance(1000); assertFalse(value().watchingTv)
        }
    }
    @Test @Config(sdk = [35]) fun `nondefault display is explicitly rejected on modern public SDK`() {
        configure(); assertGuard(); display = 1; advance(1000); assertFalse(value().watchingTv)
    }
    @Test fun `interrupt clears guard and needs explicit Stop Start without rebind`() {
        configure(); assertGuard(); service.onInterrupt(); assertFalse(value().watchingTv)
        val reads = FreshRootAccessibilityShadow.reads
        event(); advance(2000); assertEquals(reads, FreshRootAccessibilityShadow.reads); assertFalse(value().watchingTv)
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, false).commit(); advance(0)
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, true).commit(); advance(0); assertGuard()
    }
    @Test fun `screen lock disconnect Stop and mode changes revoke guard`() {
        configure(); assertGuard()
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        advance(1000); assertFalse(value().watchingTv)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        advance(1000); assertGuard()
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); advance(0); assertFalse(value().watchingTv)
        service.onUnbind(Intent()); advance(2000); assertFalse(value().watchingTv)
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, false).commit(); advance(0)
        prefs.edit().putString(PreferenceContract.DETECTION_MODE, PreferenceContract.MODE_MANUAL).commit(); advance(0)
        assertFalse(value().watchingTv)
    }
    @Test fun `accessibility pending station cannot leak into selected BRAVIA guard mode`() {
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, true)
            .putString(PreferenceContract.DETECTION_MODE, PreferenceContract.MODE_ACCESSIBILITY)
            .putString(PreferenceContract.TV_PACKAGES, "test.tv")
            .putString(PreferenceContract.OSD_RESOURCE_IDS, "test.tv:id/station")
            .putString(PreferenceContract.LIVE_RESOURCE_IDS, "test.tv:id/live").commit()
        service.onServiceConnected(); advance(100); assertNull(value().stationId)
        prefs.edit().putString(PreferenceContract.DETECTION_MODE, PreferenceContract.MODE_BRAVIA).commit(); advance(0)
        assertGuard(); advance(1000); assertGuard()
    }
    @Test fun `higher interactive ambiguous and oversized window metadata never proves default foreground`() {
        configure(); assertGuard()
        FreshRootAccessibilityShadow.windowsFactory = { listOf(window(8), window(7)) }
        advance(1000); assertFalse(value().watchingTv)
        FreshRootAccessibilityShadow.windowsFactory = { List(33) { window(it) } }
        advance(1000); assertFalse(value().watchingTv)
        FreshRootAccessibilityShadow.windowsFactory = { listOf(window(7), window(8)) }
        advance(1000); assertFalse(value().watchingTv)
    }

}
