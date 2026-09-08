package dev.nicotv.detection
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.nicotv.core.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
// Compile-only worktree contract placeholders; replace before final integration.
object StationDetectionBus {
 private val state = MutableStateFlow(StationObservation(null, DetectionOrigin.ACCESSIBILITY, 0L, false, "未検出"))
 val observation: StateFlow<StationObservation> = state.asStateFlow()
 fun publish(value: StationObservation) { state.value = value }
}
class NicoTvAccessibilityService : AccessibilityService() {
 override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
 override fun onInterrupt() {}
}
class BraviaStationDetector(client: OkHttpClient = OkHttpClient(), channelMap: Map<String, String> = emptyMap()) {
 fun observations(host: String, psk: String): Flow<StationObservation> = flow { emit(StationObservation(null, DetectionOrigin.BRAVIA, 0L, false, "未検出")) }
}
