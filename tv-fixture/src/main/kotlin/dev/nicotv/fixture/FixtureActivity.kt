package dev.nicotv.fixture
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
/** Test-only separate APK. Never receives or pretends to display a TV broadcast. */
class FixtureActivity : Activity() {
 private var station = "jk4"
 private var guide = false
 private val stations = linkedMapOf("jk4" to "日本テレビ", "jk5" to "テレビ朝日", "jk6" to "TBS", "jk8" to "フジテレビ")
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); applyIntent(intent) }
 override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); applyIntent(intent) }
 private fun applyIntent(value: Intent) {
  station = value.getStringExtra("station")?.takeIf { stations.containsKey(it) } ?: station
  guide = value.getStringExtra("screen") == "guide"
  render()
 }
 private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
 private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
  text = value; textSize = size; setTextColor(color); setPadding(0, dp(7), 0, dp(7))
 }
 private fun render() {
  val root = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; setPadding(dp(44), dp(30), dp(44), dp(26)); setBackgroundColor(Color.rgb(10, 28, 40))
  }
  root.addView(label("NicoTVOverlay  /  検証用テレビ画面", 24f))
  root.addView(label("これは放送ではありません。局表示・選局・重ね表示の結合試験専用です。", 16f, Color.rgb(180,205,222)))
  root.addView(label("LIVE VIEW  ·  TEST FIXTURE", 16f, Color.rgb(95,225,187)).apply { id = R.id.live_indicator; visibility = if (guide) View.GONE else View.VISIBLE })
  root.addView(label(stations.getValue(station), 38f).apply { id = R.id.channel_label; visibility = if (guide) View.GONE else View.VISIBLE })
  root.addView(label(if (guide) "番組表（検証用）\n日本テレビ  /  テレビ朝日  /  TBS  / フジテレビ" else "実際のコメントは別アプリのオーバーレイに表示されます。\nこのAPKにはコメント取得・動画再生・局検出機能はありません。", 22f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 0, 1f))
  val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  val ids = listOf(R.id.choose_jk4, R.id.choose_jk5, R.id.choose_jk6, R.id.choose_jk8)
  stations.entries.forEachIndexed { index, entry ->
   row.addView(Button(this).apply {
    id = ids[index]; text = "ch${entry.key.removePrefix("jk")}"; textSize = 18f; isFocusable = true
    setOnClickListener { station = entry.key; guide = false; render() }
   }, LinearLayout.LayoutParams(0, dp(54), 1f))
  }
  row.addView(Button(this).apply { id = R.id.show_guide; text = "番組表"; textSize = 17f; setOnClickListener { guide = true; render() } }, LinearLayout.LayoutParams(0, dp(54), 1f))
  row.addView(Button(this).apply { id = R.id.go_home; text = "Home"; textSize = 17f; setOnClickListener { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) } }, LinearLayout.LayoutParams(0, dp(54), 1f))
  root.addView(row)
  setContentView(root)
  root.findViewById<View>(if (guide) R.id.show_guide else ids[stations.keys.indexOf(station)]).requestFocus()
 }
}
