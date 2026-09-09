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
class LiveCounterTest {
    @Test fun connectionWithoutCommentsIsZeroAndStationSwitchResetsCount() = runTest {
        val sink = object : CommentSink {
            override fun preferences(value: OverlayPreferences) {}
            override fun clear() {}
            override fun comment(value: LiveComment) {}
        }
        val source = object : CommentSource {
            override fun stream(station: Station): Flow<StreamEvent> = flow {
                emit(StreamEvent.State(ConnectionState.LIVE, "connected", CommentOrigin.NICONICO))
                awaitCancellation()
            }
        }
        val c = SessionController(backgroundScope, { testScheduler.currentTime }, { source }, sink)
        c.start(AppSettings()); runCurrent()
        assertEquals(0L, c.state.receivedComments)
        val generation = c.state.generation
        c.accept(generation, StreamEvent.Comment(LiveComment("demo", "not live", 0, origin = CommentOrigin.DEMO)))
        assertEquals(0L, c.state.receivedComments)
        repeat(3) { c.accept(generation, StreamEvent.Comment(LiveComment("$it", "live", 0))) }
        assertEquals(3L, c.state.receivedComments)
        c.configure(AppSettings(stationId = "jk1")); runCurrent()
        assertEquals(0L, c.state.receivedComments)
        c.accept(generation, StreamEvent.Comment(LiveComment("late", "old station", 0)))
        assertEquals(0L, c.state.receivedComments)
        c.stop(); assertEquals(0L, c.state.receivedComments)
    }
}
