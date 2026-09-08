package dev.nicotv.app

import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.StationObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/** Main-scope gate: visibility is independent of the REST station response. No Android dependencies. */
class BraviaVisibilityGate(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val visibility: Flow<StationObservation>,
    private val platformReady: () -> Boolean,
    private val rest: () -> Flow<StationObservation>,
    private val deliver: (StationObservation) -> Unit,
    private val tickMs: Long = 250L
) {
    init { require(tickMs in 1L..250L) }
    private var started = false
    private var closed = false
    private var epoch = 0L
    private var generation = 0L
    private var latest: StationObservation? = null
    private var allowed = false
    private var observer: Job? = null
    private var ticker: Job? = null
    private val collectors = mutableSetOf<Job>()

    fun start() {
        if (started || closed) return
        started = true; epoch = now()
        unknown()
        observer = scope.launch {
            try { visibility.collect { latest = it; refresh() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { latest = null; refresh() }
        }
        ticker = scope.launch {
            while (isActive) {
                val remaining = currentVisibility()?.let { it.observedAtMs + VISIBILITY_TTL_MS - now() }
                val wait = if (allowed && remaining != null) remaining.coerceIn(1L, tickMs) else tickMs
                delay(wait); refresh()
            }
        }
    }
    private fun currentVisibility(): StationObservation? = (visibility as? StateFlow<StationObservation>)?.value ?: latest
    private fun validNow(): Boolean = started && !closed &&
        runCatching(platformReady).getOrDefault(false) && fresh(currentVisibility(), epoch, now())
    private fun current(token: Long): Boolean = allowed && generation == token && validNow()
    private fun refresh() {
        val next = validNow()
        if (next == allowed) return // boolean distinct: true heartbeats do not restart polling.
        allowed = next; ++generation
        if (!next) {
            // This must precede cancel/join: a non-cooperative old response cannot keep a window alive.
            unknown()
            collectors.toList().forEach { it.cancel() }
            return
        }
        val token = generation
        val previous = collectors.toList()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Do not overlap pollers while an old collector is finishing cancellation.
                previous.joinAll()
                if (!current(token)) return@launch
                rest().collect { value ->
                    if (current(token) && value.origin == DetectionOrigin.BRAVIA &&
                        value.observedAtMs >= epoch && value.observedAtMs <= now()) deliver(value)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(token)) unknown() }
        }
        collectors += job
        job.invokeOnCompletion { collectors.remove(job) }
        job.start()
    }
    private fun unknown() = deliver(StationObservation(null, DetectionOrigin.BRAVIA, now(), false, "前面証拠なし・期限切れ・停止"))
    fun close() {
        if (closed) return
        closed = true; allowed = false; ++generation
        unknown()
        observer?.cancel(); ticker?.cancel()
        collectors.toList().forEach { it.cancel() }
    }
    companion object {
        const val VISIBILITY_TTL_MS = 2_500L
        fun fresh(value: StationObservation?, epoch: Long, time: Long): Boolean = value != null &&
            value.origin == DetectionOrigin.ACCESSIBILITY && value.stationId == null && value.watchingTv &&
            value.observedAtMs >= epoch && value.observedAtMs <= time && time - value.observedAtMs < VISIBILITY_TTL_MS
    }
}
