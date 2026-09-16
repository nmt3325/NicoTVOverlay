package dev.nicotv.app

import android.content.*
import android.app.Service
import android.os.Looper
import android.os.PowerManager
import org.robolectric.shadows.ShadowSettings
import java.time.Duration
import dev.nicotv.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsAndSecurityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences(PreferenceContract.STORE, 0).edit().clear().commit(); context.getSharedPreferences("nicotv_credentials", 0).edit().clear().commit(); RuntimeSession.reset() }
    @Test fun privateHostsOnlyAndStrictJsonMapping() {
        listOf("10.0.0.1", "172.16.0.2", "172.31.255.2", "192.168.1.20").forEach { assertTrue(it, SettingsValidator.isPrivateIpv4(it)) }
        listOf("127.0.0.1", "169.254.1.1", "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.168.1.1:80", "http://192.168.1.1", "192.168.001.1", "tv.local", "::1").forEach { assertFalse(it, SettingsValidator.isPrivateIpv4(it)) }
        assertEquals(mapOf("地方テレビ" to "jk4"), SettingsValidator.stationMap("{\"地方テレビ\":\"jk4\"}"))
        assertEquals(mapOf("tv:terrestrial?sid=42" to "jk4"), SettingsValidator.stationMap("{\"tv:terrestrial?sid=42\":\"jk4\"}", true))
        listOf("[]", "{\"x\":42}", "{\"x\":\"jk999\"}", "{\"4\":\"jk4\"}").forEach { raw ->
            try { SettingsValidator.stationMap(raw); fail(raw) } catch (_: IllegalArgumentException) { }
        }
        try { SettingsValidator.stationMap("{\"extInput:hdmi?port=1\":\"jk4\"}", true); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun preferencesRoundTripAndInvalidSettingsDoNotOverwrite() {
        val repo = SettingsRepository(context)
        val s = AppSettings(stationId = "jk211", backend = Backend.NX, overlay = OverlayPreferences(1.5f, 0.6f, 1.2f, delayMs = 30_000, ngWords = listOf("NG"), showFixed = false))
        repo.save(s); assertEquals(s, repo.read())
        try { repo.save(s.copy(overlay = s.overlay.copy(delayMs = 30_001))); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(s, repo.read()); assertFalse(repo.preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false))
        assertTrue(SettingsValidator.validate(s.copy(overlay = s.overlay.copy(fontScale = Float.NaN))).isNotEmpty())
        assertTrue(SettingsValidator.validate(s.copy(osdIds = "station")).isNotEmpty())
    }
    @Test fun pskIsEncryptedAuthenticatedAndRemovedExplicitly() {
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val store = EncryptedPskStore(context) { key }
        store.save("synthetic-psk-not-real")
        assertTrue(store.contains()); assertEquals("synthetic-psk-not-real", store.read())
        val prefs = context.getSharedPreferences("nicotv_credentials", 0)
        val encoded = prefs.getString("psk_v1", "")!!
        assertFalse(encoded.contains("synthetic")); assertFalse(SettingsRepository(context).preferences.all.values.any { it.toString().contains("synthetic-psk") })
        val other = EncryptedPskStore(context) { SecretKeySpec(ByteArray(32) { 0 }, "AES") }
        try { other.read(); fail() } catch (e: SecretStorageException) { assertFalse(e.toString().contains("synthetic-psk")) }
        prefs.edit().putString("psk_v1", "tampered").commit()
        try { store.read(); fail() } catch (_: SecretStorageException) { }
        store.save(null); assertFalse(store.contains()); assertNull(store.read())
    }
    @Test fun authorizationIsVisibleSingleUseAndNotRestoredAtProcessStartup() {
        assertNull(RuntimeSession.authorize(false))
        val ticket = RuntimeSession.authorize(true)
        assertFalse(RuntimeSession.consume("incorrect")); assertTrue(RuntimeSession.consume(ticket)); assertFalse(RuntimeSession.consume(ticket))
        val pending = RuntimeSession.authorize(true)
        SettingsRepository(context).setSessionActive(true)
        (context as NicoTvApplication).onCreate()
        assertFalse(SettingsRepository(context).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, true))
        assertFalse(RuntimeSession.consume(pending)); assertFalse(RuntimeSession.state.value.active)
    }
    @Test fun missingSettingsActivityIsHandledAndNotificationIsNotAStartGate() {
        val missing = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { throw ActivityNotFoundException() } }
        assertFalse(PlatformPermissions.open(missing, Intent("missing")))
        assertNotNull(PlatformPermissions.startBlock(false, true, true, PreferenceContract.MODE_MANUAL))
        assertNotNull(PlatformPermissions.startBlock(true, false, true, PreferenceContract.MODE_MANUAL))
        assertNull(PlatformPermissions.startBlock(true, true, false, PreferenceContract.MODE_MANUAL))
        assertEquals(PlatformPermissions.ACCESSIBILITY_BLOCK,
            PlatformPermissions.startBlock(true, true, false, PreferenceContract.MODE_ACCESSIBILITY))
    }
    @Test fun alphaIsCappedAndInvalidValuesAreTransparent() {
        assertEquals(0.8f, OverlayWindow.safeAlpha(1f, 0.8f), 0f)
        assertEquals(0.5f, OverlayWindow.safeAlpha(0.7f, 0.5f), 0f)
        assertEquals(0f, OverlayWindow.safeAlpha(Float.NaN, 0.8f), 0f)
    }
    private fun runningService() = Robolectric.buildService(OverlayService::class.java).apply {
        ShadowSettings.setCanDrawOverlays(true)
        Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        create()
        val ticket = RuntimeSession.authorize(true)
        get().onStartCommand(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START).putExtra(OverlayService.EXTRA_TICKET, ticket), 0, 1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun explicitStartThenTaskRemovalClearsAuthorizationAndSession() {
        val service = runningService()
        assertTrue(RuntimeSession.state.value.active)
        assertTrue(SettingsRepository(context).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false))
        service.get().onTaskRemoved(null)
        assertFalse(RuntimeSession.state.value.active)
        assertFalse(SettingsRepository(context).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, true))
        service.destroy(); ShadowSettings.setCanDrawOverlays(false)
    }
    @Test fun screenOffAndOverlayPermissionRevocationStopTheSession() {
        val service = runningService()
        assertTrue(RuntimeSession.state.value.active)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(RuntimeSession.state.value.active); service.destroy()
        val second = runningService(); assertTrue(RuntimeSession.state.value.active)
        ShadowSettings.setCanDrawOverlays(false)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        assertFalse(RuntimeSession.state.value.active)
        assertFalse(SettingsRepository(context).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, true))
        second.destroy()
    }
    @Test fun stableStopHandlerCannotBeRemovedByAnOlderService() {
        var oldCalls = 0; var currentCalls = 0
        val old = Runnable { oldCalls++ }; val current = Runnable { currentCalls++ }
        RuntimeSession.registerStopHandler(old)
        RuntimeSession.registerStopHandler(current)
        RuntimeSession.unregisterStopHandler(old)
        RuntimeSession.stopRegisteredService()
        assertEquals(0, oldCalls); assertEquals(1, currentCalls)
        RuntimeSession.unregisterStopHandler(current)
        RuntimeSession.stopRegisteredService(); assertEquals(1, currentCalls)
    }
    @Test fun launcherStopCancelsTheLiveControllerBeforePlatformStop() {
        val service = runningService()
        val oldGeneration = RuntimeSession.state.value.generation
        var platformStops = 0
        val checkingContext = object : ContextWrapper(context) {
            override fun stopService(intent: Intent): Boolean {
                platformStops++
                assertEquals(OverlayService::class.java.name, intent.component?.className)
                assertFalse(RuntimeSession.state.value.active)
                assertTrue(RuntimeSession.state.value.generation > oldGeneration)
                assertFalse(SettingsRepository(context).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, true))
                return true
            }
        }
        ServiceCommands.stop(checkingContext)
        assertEquals(1, platformStops)
        assertFalse(RuntimeSession.state.value.active)
        service.destroy(); ShadowSettings.setCanDrawOverlays(false)
    }
    @Test fun stopRevokesAnAlreadyQueuedStartTicket() {
        val ticket = RuntimeSession.authorize(true)
        ServiceCommands.stop(context)
        val service = Robolectric.buildService(OverlayService::class.java).create()
        service.get().onStartCommand(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START).putExtra(OverlayService.EXTRA_TICKET, ticket), 0, 1)
        assertFalse(RuntimeSession.state.value.active)
        assertFalse(SettingsRepository(context).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, true))
        service.destroy()
    }
    @Test fun nullAndUnauthorizedServiceStartsStopWithoutSession() {
        val controller = Robolectric.buildService(OverlayService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(RuntimeSession.state.value.active)
        assertFalse(SettingsRepository(context).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false))
        controller.destroy()
        val unauthorized = Robolectric.buildService(OverlayService::class.java).create()
        unauthorized.get().onStartCommand(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START), 0, 2)
        assertFalse(RuntimeSession.state.value.active); unauthorized.destroy()
    }
}
