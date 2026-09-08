package dev.nicotv.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.nicotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvUiTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun tenStationCardsLargeStopTargetsAndHonestDemoScreens() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val screen = activity.get()
        val decor = screen.window.decorView
        val cards = descendants(decor).filter { it.tag?.toString()?.startsWith("station:") == true }
        assertEquals(10, cards.size)
        assertTrue(cards.all { it.isFocusable && it.minimumHeight >= 48 * screen.resources.displayMetrics.density })
        assertTrue(descendants(decor).filterIsInstance<TextView>().any { it.text.toString() == "■ 停止" })
        assertFalse(RuntimeSession.state.value.active)
        assertFalse(SettingsRepository(screen).preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false))
        val folder = System.getenv("APP_UI_SNAPSHOT_DIR")
        for ((index, page) in listOf("視聴", "表示設定", "詳細・権限", "デモ").withIndex()) {
            screen.showPage(page)
            decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1920, 1080)
            if (page == "デモ") assertTrue(descendants(decor).filterIsInstance<TextView>().any { it.text.toString() == "デモ / 通信なし" })
            if (folder != null) {
                val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                File(folder).mkdirs()
                File(folder, "app-ui-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        activity.pause().stop().destroy()
    }
}
