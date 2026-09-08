package dev.nicotv.comment

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRegressionTest {
    private fun state(s: ConnectionState) = StreamEvent.State(s, "synthetic", CommentOrigin.NICONICO)
    private fun comment(id: String, at: Long = BASE) = StreamEvent.Comment(LiveComment(id, "synthetic", at))
    private fun TestScope.options() = CommentOptions(wallMs = { BASE + testScheduler.currentTime }, monoMs = { testScheduler.currentTime }, retry = RetryPolicy { 0.0 }, dispatcher = StandardTestDispatcher(testScheduler), interViewDelayMs = 1)
    private fun TestScope.slowCollect(wire: FakeWire, events: MutableList<StreamEvent>) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        NicoLiveCommentSource(wire, options()).stream(STATION).collect { events += it; delay(10) }
    }
    private fun TestScope.burstWire(): FakeWire {
        val wire = FakeWire(); var program = 0
        wire.onRead = { url -> when {
            url.host == "live.nicovideo.jp" -> ReadPlan(html("lv${++program}").toByteArray())
            url.encodedPath == "/segment" -> ReadPlan(frames(*(1..if (program == 1) 2000 else 1).map { chatChunk("$it", BASE + testScheduler.currentTime) }.toTypedArray()))
            url.queryParameter("at") == "now" -> ReadPlan(frames(segment(), next(1)))
            else -> ReadPlan(before = true)
        } }
        return wire
    }

    @Test fun mailboxNeverEvictsLiveAndBoundsOnlyComments() = runTest {
        val output = EventMailbox { BASE }; val epoch = output.begin(state(ConnectionState.RESOLVING))
        output.offer(epoch, state(ConnectionState.CONNECTING)); output.offer(epoch, state(ConnectionState.LIVE))
        repeat(2000) { output.offer(epoch, comment("$it")) }; output.finish()
        assertEquals(ConnectionState.LIVE, (output.next() as StreamEvent.State).state)
        val comments = mutableListOf<StreamEvent.Comment>()
        while (true) comments += (output.next() ?: break) as StreamEvent.Comment
        assertEquals(256, comments.size); assertEquals("1999", comments.last().comment.id)
        output.close()
    }
    @Test fun controlTransitionAndGenerationFenceCannotResurrectOldComments() = runTest {
        val output = EventMailbox { BASE }; val old = output.begin(state(ConnectionState.LIVE))
        repeat(2000) { output.offer(old, comment("old:$it")) }
        output.offer(old, state(ConnectionState.RECONNECTING)); output.offer(old, comment("late-during-reconnect"))
        assertEquals(ConnectionState.RECONNECTING, (output.next() as StreamEvent.State).state)
        val fresh = output.begin(state(ConnectionState.CONNECTING)); output.offer(fresh, state(ConnectionState.LIVE))
        output.offer(old, comment("late-old-producer")); output.offer(old, state(ConnectionState.ERROR))
        output.offer(fresh, comment("new"))
        assertEquals(ConnectionState.LIVE, (output.next() as StreamEvent.State).state)
        assertEquals("new", (output.next() as StreamEvent.Comment).comment.id)
        output.offer(fresh, comment("discard-on-terminal")); output.offer(fresh, state(ConnectionState.ERROR)); output.finish()
        assertEquals(ConnectionState.ERROR, (output.next() as StreamEvent.State).state)
        assertNull(output.next()); output.close()
    }
    @Test fun stalledCollectorDoesNotReceiveExpiredBacklog() = runTest {
        var now = BASE; val output = EventMailbox { now }; val epoch = output.begin(state(ConnectionState.LIVE))
        output.offer(epoch, comment("old")); assertTrue(output.next() is StreamEvent.State)
        now += 20001; output.finish(); assertNull(output.next()); output.close()
    }
    @Test fun slowCollectorGetsReconnectBeforeNewLiveWithoutOldBacklog() = runTest {
        val wire = burstWire(); val events = mutableListOf<StreamEvent>(); val job = slowCollect(wire, events)
        advanceTimeBy(100); runCurrent()
        assertTrue(events.any { it is StreamEvent.State && it.state == ConnectionState.LIVE })
        wire.sockets.first().second.push(control("reconnect", buildJsonObject { put("waitTimeSec", 3) }))
        advanceTimeBy(4000); runCurrent()
        val reconnect = events.indexOfFirst { it is StreamEvent.State && it.state == ConnectionState.RECONNECTING }
        assertTrue(reconnect >= 0)
        assertTrue(events.drop(reconnect + 1).filterIsInstance<StreamEvent.Comment>().all { !it.comment.id.startsWith("lv1:") })
        assertTrue(events.filterIsInstance<StreamEvent.Comment>().any { it.comment.id.startsWith("lv2:") })
        assertEquals(ConnectionState.LIVE, events.filterIsInstance<StreamEvent.State>().last().state)
        job.cancelAndJoin(); assertEquals(0, wire.activeReads); assertTrue(wire.sockets.all { it.second.closed })
    }
    @Test fun slowCollectorGetsTerminalDuringBurstAndFlowCompletes() = runTest {
        val wire = burstWire(); val events = mutableListOf<StreamEvent>(); val job = slowCollect(wire, events)
        advanceTimeBy(100); runCurrent()
        assertTrue(events.any { it is StreamEvent.State && it.state == ConnectionState.LIVE })
        wire.sockets.single().second.push(control("error", buildJsonObject { put("code", "NO_PERMISSION") }))
        advanceTimeBy(1000); runCurrent()
        assertTrue(job.isCompleted); assertEquals(ConnectionState.ERROR, (events.last() as StreamEvent.State).state)
        assertTrue(events.filterIsInstance<StreamEvent.Comment>().size < 256)
        assertEquals(0, wire.activeReads); assertTrue(wire.sockets.all { it.second.closed })
    }
    @Test fun validReplacementScalarIsNotAnEncodingError() {
        assertFalse(hasInvalidUnicode("\uFFFD")); assertTrue(hasInvalidUnicode("\uD800")); assertTrue(hasInvalidUnicode("\uDC00"))
        assertEquals("\uFFFD", strictUtf8(byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte())))
        assertThrows(Exception::class.java) { strictUtf8(byteArrayOf(0xff.toByte())) }
        assertEquals("\uFFFD", (parseJson("\"\uFFFD\"") as JsonPrimitive).content)
        assertEquals("\uFFFD", (parseJson("\"\\uFFFD\"") as JsonPrimitive).content)
        assertNotNull(CommentGate().accept(protoComment("lv1", decodeMessage(chatChunk(text = "\uFFFD").toByteArray())), BASE - 1000, BASE))
    }
    @Test fun realWebsocketSurvivesReplacementAndPerCommentSurrogateRejection() = runBlocking {
        fun chat(no: Int, jsonString: String) = """{"chat":{"thread":"123","no":$no,"date":1700000000,"content":$jsonString}}"""
        val inputs = listOf(chat(1, "\"\uFFFD\""), chat(2, "\"\\uFFFD\""), chat(3, "\"\\uD800\""), chat(4, "\"after\""))
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { inputs.forEach { webSocket.send(it) } }
            }))
            val socket = OkHttpWire(OkHttpClient()) { }.socket(server.url("/synthetic"))
            try {
                val gate = CommentGate(); val accepted = mutableListOf<LiveComment>()
                withTimeout(3000) {
                    repeat(4) {
                        val root = parseJson(socket.receive()) as JsonObject
                        gate.accept(nxComment("123", root.obj("chat")!!), BASE - 1000, BASE)?.let { accepted += it }
                    }
                }
                assertEquals(listOf("\uFFFD", "\uFFFD", "after"), accepted.map { it.text })
                assertTrue(accepted.all { it.origin == CommentOrigin.NX_JIKKYO })
            } finally { socket.close() }
        }
    }
    @Test fun bundledLicenseIsByteIdenticalAndAvailableAsResource() {
        val bytes = javaClass.classLoader.getResourceAsStream("third-party-licenses/nicolive-comment-protobuf.txt")!!.use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("33ad152ceb35f498c54c441cfeb5a01bc7606396959356e8585cfab3e8c45eb7", sha)
    }
}
