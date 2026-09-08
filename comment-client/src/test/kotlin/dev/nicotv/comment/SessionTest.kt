package dev.nicotv.comment
import dev.nicotv.core.*
import dwango.nicolive.chat.service.edge.Payload.ChunkedEntry
import dwango.nicolive.chat.service.edge.Payload.MessageSegment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private fun TestScope.options() = CommentOptions(wallMs = { BASE + testScheduler.currentTime }, monoMs = { testScheduler.currentTime }, retry = RetryPolicy { 0.0 }, dispatcher = StandardTestDispatcher(testScheduler), interViewDelayMs = 1)
    private fun TestScope.collect(source: CommentSource, events: MutableList<StreamEvent>, slow: Boolean = false): Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.stream(STATION).collect { events += it; if (slow) delay(10) } }
    @Test fun coldFlowFollowsNextOnlyEofAndSkipsArchivesDuplicatesAndHistory() = runTest {
        val wire = FakeWire()
        wire.onRead = { url -> when {
            url.host == "live.nicovideo.jp" -> ReadPlan(html().toByteArray())
            url.encodedPath == "/segment" -> ReadPlan(frames(chatChunk(), chatChunk(), chatChunk("old", BASE - 2000), chatChunk("overflow", overflow = true), chatChunk("empty", text = "")))
            url.queryParameter("at") == "now" -> ReadPlan(frames(next(100)))
            url.queryParameter("at") == "100" -> ReadPlan(frames(segment(), ChunkedEntry.newBuilder().setPrevious(MessageSegment.newBuilder().setUri("https://mpn.live.nicovideo.jp/history")).build(), next(200)))
            else -> ReadPlan(before = true)
        } }
        val source = NicoLiveCommentSource(wire, options()); source.stream(STATION); assertTrue(wire.reads.isEmpty())
        val events = mutableListOf<StreamEvent>(); val job = collect(source, events)
        advanceTimeBy(10); runCurrent()
        assertEquals(listOf("now", "100", "200"), wire.reads.filter { it.encodedPath == "/view" }.map { it.queryParameter("at") })
        assertEquals(listOf("lv1:one", "lv1:overflow"), events.filterIsInstance<StreamEvent.Comment>().map { it.comment.id })
        assertTrue(events.filterIsInstance<StreamEvent.State>().any { it.state == ConnectionState.LIVE })
        assertFalse(wire.reads.any { it.encodedPath == "/history" })
        job.cancelAndJoin(); runCurrent(); assertEquals(0, wire.activeReads); assertTrue(wire.sockets.all { it.second.closed })
    }
    @Test fun seatAndJsonPongAreMaintainedAndCancelled() = runTest {
        val wire = FakeWire()
        wire.onSocket = { _, s -> s.onSend = { if (it.contains("startWatching")) {
            s.push(control("seat", buildJsonObject { put("keepIntervalSec", 2) })); s.push(control("ping")); s.push(endpoint())
        } } }
        val job = collect(NicoLiveCommentSource(wire, options()), mutableListOf())
        advanceTimeBy(4500); runCurrent(); val s = wire.sockets.single().second
        assertEquals(2, s.sent.count { it.contains("keepSeat") }); assertTrue(s.sent.any { it.contains("pong") })
        job.cancelAndJoin(); val before = s.sent.size
        advanceTimeBy(10000); runCurrent(); assertEquals(before, s.sent.size); assertEquals(0, wire.activeReads)
    }
    @Test fun serverReconnectRespectsWaitAndReresolvesRatherThanReuseToken() = runTest {
        val wire = FakeWire()
        wire.onSocket = { _, s -> s.onSend = { if (it.contains("startWatching")) {
            if (wire.sockets.size == 1) s.push(control("reconnect", buildJsonObject { put("waitTimeSec", 7); put("audienceToken", "SYNTHETIC_SECRET") })) else s.push(endpoint())
        } } }
        val events = mutableListOf<StreamEvent>(); val job = collect(NicoLiveCommentSource(wire, options()), events)
        advanceTimeBy(6999); runCurrent(); assertEquals(1, wire.sockets.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, wire.sockets.size)
        assertEquals(2, wire.reads.count { it.host == "live.nicovideo.jp" }); assertTrue(wire.sockets.first().second.closed)
        assertTrue(wire.sockets.all { it.second.sent.first().contains("\"reconnect\":false") })
        assertFalse(events.filterIsInstance<StreamEvent.State>().any { it.message.contains("SYNTHETIC_SECRET") }); job.cancelAndJoin()
    }
    @Test fun scheduleRolloverClosesOldSessionAndResolvesNewProgram() = runTest {
        val wire = FakeWire(); var resolves = 0
        wire.onRead = { if (it.host == "live.nicovideo.jp") ReadPlan(html("lv${++resolves}").toByteArray()) else ReadPlan(before = true) }
        wire.onSocket = { _, s -> s.onSend = { if (it.contains("startWatching")) {
            if (wire.sockets.size == 1) s.push(control("schedule", buildJsonObject { put("end", Instant.ofEpochMilli(BASE + 200).toString()) })); s.push(endpoint())
        } } }
        val events = mutableListOf<StreamEvent>(); val job = collect(NicoLiveCommentSource(wire, options()), events)
        advanceTimeBy(3201); runCurrent(); assertEquals(2, resolves); assertEquals(2, wire.sockets.size); assertTrue(wire.sockets.first().second.closed)
        assertTrue(events.filterIsInstance<StreamEvent.State>().any { it.state == ConnectionState.NO_PROGRAM }); job.cancelAndJoin(); assertEquals(0, wire.activeReads)
    }
    @Test fun malformedCursorRetriesWithIncreasingBackoff() = runTest {
        val wire = FakeWire(); wire.onRead = { if (it.host == "live.nicovideo.jp") ReadPlan(html().toByteArray()) else ReadPlan(frames(next(7))) }
        val events = mutableListOf<StreamEvent>(); val job = collect(NicoLiveCommentSource(wire, options()), events)
        advanceTimeBy(1999); runCurrent(); assertEquals(1, wire.sockets.size)
        advanceTimeBy(3); runCurrent(); assertEquals(2, wire.sockets.size)
        advanceTimeBy(4002); runCurrent(); assertEquals(3, wire.sockets.size)
        assertTrue(events.filterIsInstance<StreamEvent.State>().any { it.state == ConnectionState.RECONNECTING }); job.cancelAndJoin()
    }
    @Test fun unsafeBootstrapTerminatesWithoutAnyWebsocketOrSilentNxFallback() = runTest {
        val wire = FakeWire(); wire.onRead = { ReadPlan(html(watch = "wss://evil.example/secret").toByteArray()) }
        val result = async { NicoLiveCommentSource(wire, options()).stream(STATION).toList() }; runCurrent()
        assertTrue(wire.sockets.isEmpty()); assertEquals(ConnectionState.ERROR, result.await().filterIsInstance<StreamEvent.State>().last().state); assertEquals(1, wire.reads.size)
    }
    @Test fun cancellingDuringResolveReleasesHttpAndDoesNotReconnect() = runTest {
        val wire = FakeWire(); wire.onRead = { ReadPlan(before = true) }
        val events = mutableListOf<StreamEvent>(); val job = collect(NicoLiveCommentSource(wire, options()), events)
        runCurrent(); assertEquals(1, wire.activeReads); job.cancelAndJoin(); advanceTimeBy(120000); runCurrent()
        assertEquals(0, wire.activeReads); assertEquals(1, wire.reads.size)
        assertFalse(events.filterIsInstance<StreamEvent.State>().any { it.state == ConnectionState.ERROR || it.state == ConnectionState.RECONNECTING })
    }
    @Test fun segmentFanoutIsBoundedAndAllCallsCancelledOnOverload() = runTest {
        val wire = FakeWire(); wire.onRead = { url -> when {
            url.host == "live.nicovideo.jp" -> ReadPlan(html().toByteArray())
            url.encodedPath == "/view" -> ReadPlan(frames(*(1..5).map { segment("$SEGMENT/$it") }.toTypedArray(), next(20)))
            else -> ReadPlan(before = true)
        } }
        val events = mutableListOf<StreamEvent>(); val job = collect(NicoLiveCommentSource(wire, options()), events); runCurrent()
        assertEquals(4, wire.reads.count { it.encodedPath.startsWith("/segment/") }); assertEquals(0, wire.activeReads)
        assertTrue(events.filterIsInstance<StreamEvent.State>().any { it.state == ConnectionState.RECONNECTING }); job.cancelAndJoin()
    }
    @Test fun boundedOutputDropsOldCommentsForSlowCollectors() = runTest {
        val wire = FakeWire(); wire.onRead = { url -> when {
            url.host == "live.nicovideo.jp" -> ReadPlan(html().toByteArray())
            url.encodedPath == "/segment" -> ReadPlan(frames(*(1..2000).map { chatChunk("$it") }.toTypedArray()))
            url.queryParameter("at") == "now" -> ReadPlan(frames(segment(), next(1)))
            else -> ReadPlan(before = true)
        } }
        val events = mutableListOf<StreamEvent>(); val job = collect(NicoLiveCommentSource(wire, options()), events, slow = true)
        advanceTimeBy(5000); runCurrent(); val comments = events.filterIsInstance<StreamEvent.Comment>()
        assertTrue(comments.size in 1..256); assertEquals("lv1:2000", comments.last().comment.id)
        assertEquals(ConnectionState.LIVE, events.filterIsInstance<StreamEvent.State>().last().state)
        var state = ConnectionState.IDLE
        for (event in events) when (event) {
            is StreamEvent.State -> state = event.state
            is StreamEvent.Comment -> assertEquals("LIVE must precede every delivered comment", ConnectionState.LIVE, state)
        }
        job.cancelAndJoin(); assertEquals(0, wire.activeReads); assertTrue(wire.sockets.all { it.second.closed })
    }
    @Test fun nxThreadHandshakeNoHistoryAndProvenanceRemainSeparate() = runTest {
        val wire = FakeWire(); wire.onSocket = { url, s -> s.onSend = { sent ->
            if (url.encodedPath.endsWith("watch") && sent.contains("startWatching")) s.push(control("room", buildJsonObject {
                put("threadId", "123"); put("yourPostKey", "SYNTHETIC_KEY"); putJsonObject("messageServer") { put("uri", "wss://nx-jikkyo.tsukumijima.net/api/v1/channels/jk4/ws/comment") }
            }))
            if (url.encodedPath.endsWith("comment")) {
                s.push("""{"thread":{"thread":"123","resultcode":0}}""")
                for ((no, user) in listOf(1 to "nicolive:synthetic", 2 to "nx-original", 2 to "nx-original")) s.push("""{"chat":{"thread":"123","no":$no,"date":1700000000,"content":"synthetic","user_id":"$user"}}""")
            }
        } }
        val events = mutableListOf<StreamEvent>(); val job = collect(NxJikkyoCommentSource(wire, options()), events); runCurrent()
        assertTrue(wire.reads.isEmpty()); assertEquals(2, wire.sockets.size)
        val comments = events.filterIsInstance<StreamEvent.Comment>(); assertEquals(2, comments.size)
        assertTrue(comments.all { it.comment.origin == CommentOrigin.NX_JIKKYO }); assertTrue(wire.sockets.last().second.sent.single().contains("\"res_from\":0"))
        assertTrue(events.filterIsInstance<StreamEvent.State>().all { it.origin == CommentOrigin.NX_JIKKYO }); job.cancelAndJoin(); assertTrue(wire.sockets.all { it.second.closed })
    }
}
