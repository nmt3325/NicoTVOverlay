package dev.nicotv.app

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class BraviaVisibilityGateTest {
    private fun visible(time: Long, on: Boolean = true) = StationObservation(null, DetectionOrigin.ACCESSIBILITY, time, on, "synthetic visibility")
    private fun station(time: Long, id: String = "jk4") = StationObservation(id, DetectionOrigin.BRAVIA, time, true, "synthetic REST")

    @Test fun noVisibilityOrPlatformGuardMeansZeroRest() = runTest {
        val bus = MutableStateFlow(visible(0, false)); var calls = 0; var ready = true
        val values = mutableListOf<StationObservation>()
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { ready },
            { flow { calls++; emit(station(testScheduler.currentTime)); awaitCancellation() } }, values::add)
        gate.start(); runCurrent(); assertEquals(0, calls)
        ready = false; bus.value = visible(1); advanceTimeBy(1000); runCurrent(); assertEquals(0, calls)
        gate.close(); assertTrue(values.none { it.stationId != null })
    }
    @Test fun homeInvalidatesBeforeNonCooperativeCleanupAndDropsLateResponse() = runTest {
        val bus = MutableStateFlow(visible(0, false)); val order = mutableListOf<String>()
        val rest = object : Flow<StationObservation> {
            override suspend fun collect(collector: FlowCollector<StationObservation>) {
                try { collector.emit(station(testScheduler.currentTime)); awaitCancellation() }
                finally {
                    order += "cleanup"
                    withContext(NonCancellable) { delay(1000); order += "late-return"; collector.emit(station(testScheduler.currentTime, "jk6")) }
                }
            }
        }
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true }, { rest },
            { order += it.stationId ?: "unknown" })
        gate.start(); bus.value = visible(0); runCurrent(); assertTrue("jk4" in order)
        order.clear(); bus.value = visible(0, false); runCurrent()
        assertEquals(listOf("unknown", "cleanup"), order)
        advanceTimeBy(1000); runCurrent()
        assertTrue("late-return" in order); assertFalse("jk6" in order)
        gate.close()
    }
    @Test fun ttlTickerClearsAndCancelsWithoutAnotherAccessibilityEvent() = runTest {
        val bus = MutableStateFlow(visible(0)); var cancelled = false; val order = mutableListOf<String>()
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true },
            { flow { try { emit(station(0)); awaitCancellation() } finally { cancelled = true; order += "cancel" } } },
            { order += it.stationId ?: "unknown" })
        gate.start(); runCurrent(); order.clear()
        advanceTimeBy(2499); runCurrent(); assertFalse(cancelled)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf("unknown", "cancel"), order)
        gate.close()
    }
    @Test fun trueHeartbeatsKeepOnePollingSubscription() = runTest {
        val bus = MutableStateFlow(visible(0)); var starts = 0
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true },
            { flow { starts++; emit(station(testScheduler.currentTime)); awaitCancellation() } }, {})
        gate.start(); runCurrent()
        repeat(10) { advanceTimeBy(1000); bus.value = visible(testScheduler.currentTime); runCurrent() }
        assertEquals(1, starts); gate.close()
    }
    @Test fun oldEpochFutureStationBearingAndWrongOriginEvidenceCannotOpenGate() = runTest {
        advanceTimeBy(10_000)
        val bus = MutableStateFlow(visible(9999)); var calls = 0
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true },
            { flow { calls++; awaitCancellation() } }, {})
        gate.start(); runCurrent(); assertEquals(0, calls)
        bus.value = visible(10_001); runCurrent(); assertEquals(0, calls)
        bus.value = visible(10_000).copy(stationId = "jk4"); runCurrent(); assertEquals(0, calls)
        bus.value = visible(10_000).copy(origin = DetectionOrigin.BRAVIA); runCurrent(); assertEquals(0, calls)
        bus.value = visible(10_000); runCurrent(); assertEquals(1, calls)
        gate.close()
    }
    @Test fun closeAndReplacementNeverReviveOldEpochOrOverlapCollectors() = runTest {
        val bus = MutableStateFlow(visible(0)); val result = mutableListOf<String>(); var calls = 0
        val late = object : Flow<StationObservation> {
            override suspend fun collect(collector: FlowCollector<StationObservation>) {
                calls++
                try { collector.emit(station(testScheduler.currentTime)); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(1000); collector.emit(station(testScheduler.currentTime, "jk6")) } }
            }
        }
        val old = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true }, { late }, { result += it.stationId ?: "unknown" })
        old.start(); runCurrent(); old.close(); result.clear()
        advanceTimeBy(1)
        val replacement = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true },
            { flow { emit(station(testScheduler.currentTime, "jk8")); awaitCancellation() } }, { result += it.stationId ?: "unknown" })
        replacement.start(); runCurrent(); assertFalse("jk8" in result) // replay predates new epoch
        bus.value = visible(testScheduler.currentTime); runCurrent(); assertTrue("jk8" in result)
        advanceTimeBy(1000); runCurrent(); assertFalse("jk6" in result); assertEquals(1, calls)
        replacement.close(); bus.value = visible(testScheduler.currentTime); runCurrent(); assertEquals("unknown", result.last())
    }
    @Test fun returnChecksFreshnessEvenBeforeTheNextTicker() = runTest {
        var clock = 0L
        val bus = MutableStateFlow(visible(0)); val incoming = MutableSharedFlow<StationObservation>(); val seen = mutableListOf<StationObservation>()
        val gate = BraviaVisibilityGate(backgroundScope, { clock }, bus, { true }, { incoming }, seen::add)
        gate.start(); runCurrent(); clock = 2500
        incoming.emit(station(clock)); runCurrent()
        assertTrue(seen.none { it.stationId != null })
        gate.close()
    }
    @Test fun platformRevocationAndFailedPlatformReadFailClosed() = runTest {
        val bus = MutableStateFlow(visible(0)); var ready = true; var cancelled = false
        val seen = mutableListOf<StationObservation>()
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { if (!ready) error("unavailable") else true },
            { flow { try { emit(station(0)); awaitCancellation() } finally { cancelled = true } } }, seen::add)
        gate.start(); runCurrent(); ready = false
        advanceTimeBy(250); runCurrent(); assertTrue(cancelled); assertNull(seen.last().stationId)
        gate.close()
    }
    @Test fun restReturnReadsTheCurrentBusValueBeforeObserverResumes() = runTest {
        val bus = MutableStateFlow(visible(0)); val seen = mutableListOf<StationObservation>()
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true },
            { flow { bus.value = visible(testScheduler.currentTime, false); emit(station(testScheduler.currentTime)) } }, seen::add)
        gate.start(); runCurrent()
        assertTrue(seen.none { it.stationId != null })
        gate.close()
    }
    @Test fun reopeningWaitsForOldCollectorWhileInvalidationDoesNot() = runTest {
        val bus = MutableStateFlow(visible(0)); var starts = 0
        val flow = object : Flow<StationObservation> {
            override suspend fun collect(collector: FlowCollector<StationObservation>) {
                starts++
                try { collector.emit(station(testScheduler.currentTime)); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(1000) } }
            }
        }
        val seen = mutableListOf<StationObservation>()
        val gate = BraviaVisibilityGate(backgroundScope, { testScheduler.currentTime }, bus, { true }, { flow }, seen::add)
        gate.start(); runCurrent(); bus.value = visible(0, false); runCurrent(); assertNull(seen.last().stationId)
        bus.value = visible(0); runCurrent(); assertEquals(1, starts)
        advanceTimeBy(1000); runCurrent(); assertEquals(2, starts)
        gate.close(); advanceTimeBy(1000); runCurrent()
    }
}
