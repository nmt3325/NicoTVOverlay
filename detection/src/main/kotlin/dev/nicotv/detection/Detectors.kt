package dev.nicotv.detection

import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.StationObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process state only. No screen contents, credentials, or observations are persisted. */
object StationDetectionBus {
    private val state = MutableStateFlow(
        StationObservation(null, DetectionOrigin.ACCESSIBILITY, 0L, false, "未検出"),
    )
    val observation: StateFlow<StationObservation> = state.asStateFlow()

    fun publish(value: StationObservation) {
        state.value = value
    }
}
