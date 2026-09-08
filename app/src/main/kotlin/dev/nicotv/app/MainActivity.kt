package dev.nicotv.app
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(TextView(this).apply { text = "NicoTVOverlay — 開発中"; textSize = 28f; setPadding(48,48,48,48) }) }
}
