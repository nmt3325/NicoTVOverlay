package dev.nicotv.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import dev.nicotv.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic, offline, test-APK-only checks of the actual Android overlay window.
 * Run on an owned test device with SYSTEM_ALERT_WINDOW granted beforehand.
 * No production injection endpoint, Accessibility grants, or live comments are used.
 */
@RunWith(AndroidJUnit4::class)
class OverlayDeviceTest {
    @get:Rule val activityRule = ActivityTestRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var overlay: OverlayWindow
    private val failed = AtomicBoolean(false)

    @Before fun prepare() {
        instrumentation.runOnMainSync {
            ServiceCommands.stop(context)
            assertTrue("Grant overlay access on the owned emulator before this test", PlatformPermissions.overlays(context))
            assertTrue("Default display must be awake", PlatformPermissions.defaultTarget(context) && PlatformPermissions.screenReady(context))
            SettingsRepository(context).setSessionActive(true)
            RuntimeSession.publish(SessionUiState(active = true, stationId = "jk4", connection = ConnectionState.LIVE))
            overlay = OverlayWindow(context) { failed.set(true) }
            overlay.preferences(OverlayPreferences(opacity = 0.8f, showFixed = true))
        }
        instrumentation.waitForIdleSync()
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            if (::overlay.isInitialized) overlay.clear()
            SettingsRepository(context).setSessionActive(false)
            RuntimeSession.reset()
        }
    }

    private fun testComment(id: String) = LiveComment(
        id, "FIRST COMMENT / SYNTHETIC TEST", System.currentTimeMillis(),
        position = CommentPosition.TOP, color = 0xFFFF00FF.toInt(), origin = CommentOrigin.DEMO
    )
    private fun screenshot(): Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    private fun pinkPixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { ((it ushr 16) and 255) > 150 && (it and 255) > 150 && ((it ushr 8) and 255) < 120 }
    }
    private fun countNow(): Int = screenshot().let { image -> try { pinkPixels(image) } finally { image.recycle() } }
    private fun save(name: String) {
        val image = screenshot()
        try {
            val dir = File(context.getExternalFilesDir(null), "device-qa").apply { mkdirs() }
            File(dir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
    private fun awaitPixels(description: String, condition: (Int) -> Boolean): Int {
        val deadline = SystemClock.elapsedRealtime() + 2200
        var last = countNow()
        while (!condition(last) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            last = countNow()
        }
        if (!condition(last)) save("failed-$description.png")
        assertTrue("$description: matching pixel count=$last", condition(last))
        return last
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun firstCommentDrawsImmediatelyAndDpadRemainsWithActivity() {
        val baseline = countNow()
        instrumentation.runOnMainSync { overlay.comment(testComment("synthetic:first")) }
        awaitPixels("first-comment") { it > baseline + 100 }
        assertFalse("Overlay creation must succeed", failed.get())
        save("first-comment.png")
        lateinit var stop: Button
        instrumentation.runOnMainSync {
            val buttons = descendants(activityRule.activity.window.decorView).filterIsInstance<Button>()
            val start = buttons.first { it.text.toString() == "開始" }
            stop = buttons.first { it.text.toString() == "■ 停止" }
            assertTrue(start.requestFocus())
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(activityRule.activity.hasWindowFocus())
            assertSame("Nonfocusable overlay must pass D-pad to the app", stop, activityRule.activity.currentFocus)
            overlay.clear()
        }
        awaitPixels("clear") { it <= baseline + 20 }
        save("cleared.png")
    }

    @Test fun clearBeforeFirstLayoutDoesNotReplayPendingComment() {
        val baseline = countNow()
        instrumentation.runOnMainSync {
            overlay.comment(testComment("synthetic:revoked"))
            overlay.clear()
        }
        instrumentation.waitForIdleSync()
        repeat(5) {
            SystemClock.sleep(80)
            assertTrue("Old readiness callbacks must not resurrect a cleared comment", countNow() <= baseline + 20)
        }
        assertFalse(failed.get())
    }
}
