package dev.nicotv.app

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    private class Sink : CommentSink {
        var clears = 0; val received = mutableListOf<LiveComment>(); var options = OverlayPreferences()
        override fun clear() { clears++; received.clear() }
        override fun preferences(value: OverlayPreferences) { options = value }
        override fun comment(value: LiveComment) { received += value }
    }
    private class Source(private val origin: CommentOrigin) : CommentSource {
        var cancelled = 0; var subscriptions = 0
        val events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 10)
        override fun stream(station: Station) = flow {
            subscriptions++
            try { emit(StreamEvent.State(ConnectionState.LIVE, "https://invalid.example/secret-token", origin)); emitAll(events) }
            finally { cancelled++ }
        }
    }
    private val calibrated = AppSettings(mode = PreferenceContract.MODE_ACCESSIBILITY,
        tvPackages = "com.example.tv", osdIds = "com.example.tv:id/station", liveIds = "com.example.tv:id/live")
    private fun comment(origin: CommentOrigin = CommentOrigin.NICONICO) = LiveComment("fixture", "合成コメント", 0, origin = origin)

    @Test fun manualStartStopCancelsAndNeverStartsBeforeUserSession() = runTest {
        val source = Source(CommentOrigin.NICONICO); val sink = Sink()
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { source }, sink)
        c.observation(StationObservation("jk4", DetectionOrigin.ACCESSIBILITY, 0, true, "")); runCurrent()
        assertEquals(0, source.subscriptions)
        c.start(AppSettings()); runCurrent()
        assertTrue(c.state.active); assertEquals("jk4", c.state.stationId); assertEquals(ConnectionState.LIVE, c.state.connection)
        source.events.emit(StreamEvent.Comment(comment())); runCurrent(); assertEquals(1, sink.received.size)
        assertFalse(c.state.message.contains("secret-token"))
        val old = c.state.generation
        c.stop(); runCurrent()
        assertFalse(c.state.active); assertNull(c.state.stationId); assertEquals(1, source.cancelled); assertTrue(sink.received.isEmpty())
        c.accept(old, StreamEvent.Comment(comment())); assertTrue(sink.received.isEmpty())
    }
    @Test fun unknownNonTvAndStaleEvidenceClearTheOldStation() = runTest {
        val source = Source(CommentOrigin.NICONICO); val sink = Sink()
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { source }, sink)
        c.start(calibrated); c.observation(StationObservation("jk4", DetectionOrigin.ACCESSIBILITY, 0, true, "")); runCurrent()
        assertEquals("jk4", c.state.stationId)
        advanceTimeBy(29_999); c.tick(); assertEquals("jk4", c.state.stationId)
        advanceTimeBy(1); c.tick(); runCurrent(); assertNull(c.state.stationId); assertEquals(1, source.cancelled)
        c.observation(StationObservation("jk6", DetectionOrigin.ACCESSIBILITY, 30_000, true, "")); runCurrent(); assertEquals("jk6", c.state.stationId)
        c.observation(StationObservation(null, DetectionOrigin.ACCESSIBILITY, 30_000, false, "Home")); runCurrent()
        assertNull(c.state.stationId); assertEquals(2, source.cancelled)
    }
    @Test fun missingCalibrationDoesNotTrustAnOtherwiseValidObservation() = runTest {
        val source = Source(CommentOrigin.NICONICO)
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { source }, Sink())
        c.start(AppSettings(mode = PreferenceContract.MODE_ACCESSIBILITY))
        c.observation(StationObservation("jk4", DetectionOrigin.ACCESSIBILITY, 0, true, "局文字だけ")); runCurrent()
        assertNull(c.state.stationId); assertEquals(0, source.subscriptions); assertTrue(c.state.message.contains("校正"))
    }
    @Test fun backendChangeAndModeChangeBumpGenerationDropLateComments() = runTest {
        val nico = Source(CommentOrigin.NICONICO); val nx = Source(CommentOrigin.NX_JIKKYO); val sink = Sink()
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { if (it == Backend.NX) nx else nico }, sink)
        c.start(AppSettings()); runCurrent(); val old = c.state.generation
        c.configure(AppSettings(backend = Backend.NX)); runCurrent()
        assertTrue(c.state.generation > old); assertEquals(1, nico.cancelled); assertEquals(1, nx.subscriptions)
        c.accept(old, StreamEvent.Comment(comment())); assertTrue(sink.received.isEmpty())
        c.accept(c.state.generation, StreamEvent.Comment(comment(CommentOrigin.NICONICO))); assertTrue(sink.received.isEmpty())
        c.accept(c.state.generation, StreamEvent.Comment(comment(CommentOrigin.DEMO))); assertTrue(sink.received.isEmpty())
        nx.events.emit(StreamEvent.Comment(comment(CommentOrigin.NX_JIKKYO))); runCurrent(); assertEquals(1, sink.received.size)
        c.configure(calibrated); runCurrent(); assertEquals(1, nx.cancelled); assertNull(c.state.stationId); assertTrue(sink.received.isEmpty())
    }
    @Test fun delayedOldStationOriginAndFutureEvidenceAreRejected() = runTest {
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { Source(CommentOrigin.NICONICO) }, Sink())
        advanceTimeBy(1000); c.start(calibrated)
        c.observation(StationObservation("jk4", DetectionOrigin.ACCESSIBILITY, 999, true, "replayed")); assertNull(c.state.stationId)
        c.observation(StationObservation("jk4", DetectionOrigin.BRAVIA, 1000, true, "wrong mode")); assertNull(c.state.stationId)
        c.observation(StationObservation("jk4", DetectionOrigin.ACCESSIBILITY, 1000, true, "")); runCurrent()
        c.observation(StationObservation("jk6", DetectionOrigin.ACCESSIBILITY, 999, true, "old")); assertEquals("jk4", c.state.stationId)
        c.observation(StationObservation("jk6", DetectionOrigin.ACCESSIBILITY, 1001, true, "future")); assertNull(c.state.stationId)
    }
    @Test fun sameStationObservationDoesNotReconnectOrAllowErrorDemoFallback() = runTest {
        val source = Source(CommentOrigin.NICONICO); val sink = Sink()
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { source }, sink)
        c.start(calibrated); c.observation(StationObservation("jk4", DetectionOrigin.ACCESSIBILITY, 0, true, "")); runCurrent()
        advanceTimeBy(100); c.observation(StationObservation("jk4", DetectionOrigin.ACCESSIBILITY, 100, true, "")); runCurrent()
        assertEquals(1, source.subscriptions)
        source.events.emit(StreamEvent.State(ConnectionState.ERROR, "secret", CommentOrigin.NICONICO)); runCurrent()
        assertEquals(Backend.OFFICIAL, c.state.backend); assertEquals(ConnectionState.ERROR, c.state.connection)
        source.events.emit(StreamEvent.Comment(comment(CommentOrigin.DEMO))); runCurrent(); assertTrue(sink.received.isEmpty())
    }
    @Test fun manualStationAndDisplayChangeCancelAndClearBeforeNewGeneration() = runTest {
        val source = Source(CommentOrigin.NICONICO); val sink = Sink()
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { source }, sink)
        c.start(AppSettings()); runCurrent()
        source.events.emit(StreamEvent.Comment(comment())); runCurrent(); assertEquals(1, sink.received.size)
        val old = c.state.generation
        c.configure(AppSettings(stationId = "jk6", overlay = OverlayPreferences(delayMs = 10_000))); runCurrent()
        assertEquals("jk6", c.state.stationId); assertTrue(c.state.generation > old)
        assertEquals(1, source.cancelled); assertEquals(2, source.subscriptions)
        assertEquals(10_000L, sink.options.delayMs); assertTrue(sink.received.isEmpty())
        c.accept(old, StreamEvent.Comment(comment())); assertTrue(sink.received.isEmpty())
    }
    @Test fun manualSelectionHasNoAutomaticEvidenceTtl() = runTest {
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { Source(CommentOrigin.NICONICO) }, Sink())
        c.start(AppSettings(stationId = "jk211")); runCurrent(); advanceTimeBy(300_000); c.tick()
        assertEquals("jk211", c.state.stationId); assertTrue(c.state.active)
    }
}
