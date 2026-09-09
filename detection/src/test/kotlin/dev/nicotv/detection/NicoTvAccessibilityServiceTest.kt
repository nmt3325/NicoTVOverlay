package dev.nicotv.detection

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.nicotv.core.PreferenceContract
import dev.nicotv.core.AquosProfile
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAccessibilityService

/** This test shadow supplies synthetic fresh roots, not an actual tuner/OEM accessibility tree. */
@Implements(AccessibilityService::class)
class FreshRootAccessibilityShadow : ShadowAccessibilityService() {
    // A service controller has no system accessibility Binder connection. Record the real setter boundary.
    private var configuredInfo: android.accessibilityservice.AccessibilityServiceInfo? = null
    @Implementation protected fun setServiceInfo(info: android.accessibilityservice.AccessibilityServiceInfo) { configuredInfo = info }
    @Implementation protected fun getServiceInfo(): android.accessibilityservice.AccessibilityServiceInfo? = configuredInfo
    @Implementation protected fun getRootInActiveWindow(): AccessibilityNodeInfo? { reads++; return rootFactory() }
    @Implementation(minSdk = 33) protected fun getRootInActiveWindow(prefetchingStrategy: Int): AccessibilityNodeInfo? {
        check(prefetchingStrategy == 0); reads++; return rootFactory()
    }
    @Implementation protected override fun getWindows(): List<android.view.accessibility.AccessibilityWindowInfo> {
        windowReads++; return windowsFactory()
    }
    companion object {
        var rootFactory: () -> AccessibilityNodeInfo? = { null }
        var windowsFactory: () -> List<android.view.accessibility.AccessibilityWindowInfo> = { emptyList() }
        var reads = 0
        var windowReads = 0
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FreshRootAccessibilityShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NicoTvAccessibilityServiceTest {
    private lateinit var controller: ServiceController<NicoTvAccessibilityService>
    private lateinit var service: NicoTvAccessibilityService
    private lateinit var prefs: SharedPreferences
    private var label = "NHK総合"
    private var pkg = "test.tv"
    private var showLive = true
    private var nullRoot = false
    private fun node(id: String? = null, text: String? = null): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = pkg; isVisibleToUser = true; viewIdResourceName = id; this.text = text
        }
    private fun tree(): AccessibilityNodeInfo? {
        if (nullRoot) return null
        val root = node()
        shadowOf(root).addChild(node("test.tv:id/station", label))
        if (showLive) shadowOf(root).addChild(node("test.tv:id/live"))
        return root
    }
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(PreferenceContract.STORE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        FreshRootAccessibilityShadow.rootFactory = ::tree
        FreshRootAccessibilityShadow.reads = 0
        controller = Robolectric.buildService(NicoTvAccessibilityService::class.java).create()
        service = controller.get()
    }
    @After fun cleanup() {
        controller.destroy()
        FreshRootAccessibilityShadow.rootFactory = { null }
    }
    private fun configure(active: Boolean = true) {
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, active)
            .putString(PreferenceContract.DETECTION_MODE, PreferenceContract.MODE_ACCESSIBILITY)
            .putString(PreferenceContract.TV_PACKAGES, "test.tv")
            .putString(PreferenceContract.OSD_RESOURCE_IDS, "test.tv:id/station")
            .putString(PreferenceContract.LIVE_RESOURCE_IDS, "test.tv:id/live").commit()
        service.onServiceConnected()
    }
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun event() {
        val e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = "test.tv" }
        service.onAccessibilityEvent(e); e.recycle()
    }
    private fun selected() = StationDetectionBus.observation.value.stationId
    private fun confirm() { configure(); advance(750); assertEquals("jk1", selected()) }

    @Test fun `AQUOS includes non semantic TV views only while explicitly active`() {
        val include = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        val keys = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        configure()
        assertEquals(0, service.serviceInfo.flags and include)
        assertEquals(0, service.serviceInfo.flags and keys)
        pkg = AquosProfile.PACKAGE
        var showOsd = true
        FreshRootAccessibilityShadow.rootFactory = {
            node().apply {
                setBoundsInScreen(android.graphics.Rect(0, 0, 1920, 1080))
                // Matches the real AQUOS: compressed tree has one root and no live/station nodes.
                if (service.serviceInfo.flags and include != 0) {
                    shadowOf(this).addChild(node(AquosProfile.LIVE).apply {
                        setBoundsInScreen(android.graphics.Rect(0, 0, 1920, 1080))
                    })
                    if (showOsd) shadowOf(this).addChild(node(AquosProfile.STATION, "日テレ１"))
                }
            }
        }
        prefs.edit().putString(PreferenceContract.TV_PACKAGES, AquosProfile.PACKAGE)
            .putString(PreferenceContract.OSD_RESOURCE_IDS, AquosProfile.STATION)
            .putString(PreferenceContract.LIVE_RESOURCE_IDS, AquosProfile.LIVE)
            .putString(PreferenceContract.CUSTOM_ALIASES, AquosProfile.aliasesJson).commit()
        service.onServiceConnected()
        assertEquals(include, service.serviceInfo.flags and include)
        assertEquals(keys, service.serviceInfo.flags and keys)
        advance(750); assertEquals("jk4", selected())
        showOsd = false
        advance(40_000); assertEquals("jk4", selected())
        val keyCallback = service.javaClass.getDeclaredMethod("onKeyEvent", android.view.KeyEvent::class.java)
            .apply { isAccessible = true }
        assertFalse(keyCallback.invoke(service, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_CHANNEL_UP)) as Boolean)
        assertNull(selected())
        advance(1500); assertNull(selected())
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, false).commit()
        service.onSharedPreferenceChanged(prefs, PreferenceContract.SESSION_ACTIVE)
        assertEquals(0, service.serviceInfo.flags and include)
        assertEquals(0, service.serviceInfo.flags and keys)
    }

    @Test fun `connection without Start never reads a root or starts another service`() {
        configure(active = false)
        event(); advance(5000)
        assertEquals(0, FreshRootAccessibilityShadow.reads)
        assertNull(selected())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertEquals(0, service.serviceInfo.eventTypes)
    }
    @Test fun `live label is confirmed only after a fresh 750ms root read`() {
        configure(); assertNull(selected())
        advance(749); assertNull(selected()); advance(1)
        assertEquals("jk1", selected()); assertTrue(FreshRootAccessibilityShadow.reads >= 2)
    }
    @Test fun `A B C events clear old station and confirm only current C`() {
        confirm()
        label = "Eテレ"; event(); advance(250); assertNull(selected())
        label = "日テレ"; event(); advance(250); assertNull(selected())
        advance(750); assertEquals("jk4", selected())
    }
    @Test fun `Home is detected by root heartbeat even without any Home event`() {
        confirm(); pkg = "test.launcher"; advance(1000)
        assertNull(selected())
    }
    @Test fun `root null and absent live marker clear old station`() {
        confirm(); nullRoot = true; advance(1000); assertNull(selected())
        nullRoot = false; showLive = false; event(); advance(1000); assertNull(selected())
    }
    @Test fun `Stop preference cancels pending reread and all future root polling`() {
        configure(); advance(100)
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, false).commit()
        advance(0)
        val reads = FreshRootAccessibilityShadow.reads
        advance(5000); assertNull(selected()); assertEquals(reads, FreshRootAccessibilityShadow.reads)
    }
    @Test fun `mode change clears and never silently falls back to manual`() {
        confirm()
        prefs.edit().putString(PreferenceContract.DETECTION_MODE, PreferenceContract.MODE_MANUAL).commit()
        advance(0); val reads = FreshRootAccessibilityShadow.reads
        event(); advance(2000); assertNull(selected()); assertEquals(reads, FreshRootAccessibilityShadow.reads)
    }
    @Test fun `empty allowlist disables framework events rather than observing all packages`() {
        configure()
        prefs.edit().putString(PreferenceContract.TV_PACKAGES, "").commit(); advance(0)
        assertEquals(0, service.serviceInfo.eventTypes)
        assertArrayEquals(arrayOf("dev.nicotv.detection.disabled"), service.serviceInfo.packageNames)
        advance(1000); assertNull(selected())
    }
    @Test fun `screen off lock interrupt and unbind clear synchronously or by heartbeat`() {
        confirm()
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); advance(0); assertNull(selected())
        service.onServiceConnected(); advance(750)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        advance(1000); assertNull(selected())
        service.onInterrupt(); assertNull(selected())
        service.onUnbind(Intent()); assertNull(selected())
    }
    @Test fun `constant content events cannot starve debounce confirmation`() {
        configure()
        repeat(20) { advance(50); event() }
        assertEquals("jk1", selected())
    }
    @Test fun `foreground alone never refreshes 30 second station TTL`() {
        confirm()
        val evidenceAt = StationDetectionBus.observation.value.observedAtMs
        advance(29_000)
        assertEquals(evidenceAt, StationDetectionBus.observation.value.observedAtMs)
        advance(999); assertEquals("jk1", selected())
        advance(1); assertNull(selected()) // Exact evidence deadline, not rounded to a heartbeat.
    }
    @Test fun `feedback interrupt requires Stop Start but not system rebind`() {
        confirm(); service.onInterrupt(); assertNull(selected())
        val reads = FreshRootAccessibilityShadow.reads
        prefs.edit().putString(PreferenceContract.CUSTOM_ALIASES, "{}").commit()
        event(); advance(5000)
        assertNull(selected()); assertEquals(reads, FreshRootAccessibilityShadow.reads)
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, false).commit(); advance(0)
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, true).commit(); advance(0)
        advance(749); assertNull(selected()); advance(1); assertEquals("jk1", selected())
    }
    @Test fun `interrupted pending generation cannot return after malformed settings are repaired`() {
        configure(); advance(100); service.onInterrupt()
        val reads = FreshRootAccessibilityShadow.reads
        prefs.edit().putInt(PreferenceContract.LIVE_RESOURCE_IDS, 42).commit(); advance(0)
        prefs.edit().putString(PreferenceContract.LIVE_RESOURCE_IDS, "test.tv:id/live").commit(); advance(0)
        event(); advance(2000); assertNull(selected()); assertEquals(reads, FreshRootAccessibilityShadow.reads)
    }
    @Test fun `real unbind still requires real bind after Stop Start`() {
        confirm(); service.onUnbind(Intent())
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, false).commit(); advance(0)
        prefs.edit().putBoolean(PreferenceContract.SESSION_ACTIVE, true).commit(); advance(0)
        val reads = FreshRootAccessibilityShadow.reads
        event(); advance(1000); assertNull(selected()); assertEquals(reads, FreshRootAccessibilityShadow.reads)
        service.onServiceConnected(); advance(750); assertEquals("jk1", selected())
    }

}
