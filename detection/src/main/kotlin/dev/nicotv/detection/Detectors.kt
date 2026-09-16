package dev.nicotv.detection

import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.RecordedObservation
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

/** 録画再生画面の観測。放送日時・放送局・再生位置のみで、画面本文は保持しない。 */
object RecordedDetectionBus {
    private val state = MutableStateFlow(
        RecordedObservation(null, 0L, 0L, DetectionOrigin.ACCESSIBILITY, 0L, "録画情報は未検出"),
    )
    val observation: StateFlow<RecordedObservation> = state.asStateFlow()

    fun publish(value: RecordedObservation) {
        state.value = value
    }
}
