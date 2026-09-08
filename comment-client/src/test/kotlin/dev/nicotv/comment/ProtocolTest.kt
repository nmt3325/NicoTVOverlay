package dev.nicotv.comment
import dev.nicotv.core.*
import dwango.nicolive.chat.data.Atoms.Chat
import dwango.nicolive.chat.service.edge.Payload.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
class ProtocolTest {
    @Test fun partialReadsKeepFrameBoundariesAndNormalEof() {
        val a = chatChunk(text = "x".repeat(300)); val b = chatChunk("two"); val input = OneByteInput(frames(a, b))
        assertEquals(a, decodeMessage(FrameReader.next(input)!!)); assertEquals(b, decodeMessage(FrameReader.next(input)!!)); assertNull(FrameReader.next(input))
    }
    @Test fun rejectsTruncationOverlongAndOversizeVarintsBeforeAllocation() {
        val invalid = listOf(byteArrayOf(0x80.toByte()), byteArrayOf(3, 1), byteArrayOf(0x80.toByte(), 0), ByteArray(6) { 0x80.toByte() }, byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x40), byteArrayOf(-1, -1, -1, -1, 0x7f))
        for (bytes in invalid) assertThrows(IOException::class.java) { FrameReader.next(ByteArrayInputStream(bytes)) }
    }
    @Test fun unknownProtoFieldsAndEmptyPayloadAreSafe() {
        val extended = chatChunk().toByteArray() + byteArrayOf(0x98.toByte(), 0x06, 0x01)
        assertEquals("synthetic", decodeMessage(extended).message.chat.content)
        assertNull(protoComment("lv1", decodeMessage(byteArrayOf())))
        assertFalse(decodeEntry(byteArrayOf(0x98.toByte(), 0x06, 0x01)).hasSegment())
    }
    @Test fun officialGeneratedParserRejectsInvalidUtf8() {
        assertThrows(IOException::class.java) { decodeMessage(byteArrayOf(0x12, 5, 0x0a, 3, 0x0a, 1, 0xff.toByte())) }
        assertThrows(Exception::class.java) { strictUtf8(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertTrue(hasInvalidUnicode("\uD800")); assertFalse(hasInvalidUnicode("実況🙂"))
    }
    @Test fun usesMetaTimestampAndSupportsOverflowStyle() {
        val style = Chat.Modifier.newBuilder().setPosition(Chat.Modifier.Pos.ue).setSize(Chat.Modifier.Size.big).setNamedColor(Chat.Modifier.ColorName.red2).build()
        val c = protoComment("lv123", chatChunk(at = BASE + 123, overflow = true, modifier = style))!!
        assertEquals(BASE + 123, c.postedAtMs); assertEquals(CommentPosition.TOP, c.position); assertEquals(CommentSize.LARGE, c.size)
        assertEquals(0xffcc0033.toInt(), c.color); assertEquals(CommentOrigin.NICONICO, c.origin); assertEquals("lv123:one", c.id)
    }
    @Test fun fullColorClampedAndUnknownEnumDefaultsSafely() {
        val style = Chat.Modifier.newBuilder().setPositionValue(99).setSizeValue(99).setFullColor(Chat.Modifier.FullColor.newBuilder().setR(300).setG(-5).setB(30)).build()
        val c = protoComment("lv1", chatChunk(modifier = style))!!
        assertEquals(0xffff001e.toInt(), c.color); assertEquals(CommentPosition.SCROLL, c.position); assertEquals(CommentSize.NORMAL, c.size)
    }
    @Test fun dedupIsBoundedAndProgramScoped() {
        val ids = BoundedIds(2)
        assertTrue(ids.add("a")); assertFalse(ids.add("a")); ids.add("b"); ids.add("c"); assertEquals(2, ids.size()); assertTrue(ids.add("a"))
        val gate = CommentGate()
        assertNotNull(gate.accept(protoComment("lv1", chatChunk()), BASE - 1000, BASE))
        assertNull(gate.accept(protoComment("lv1", chatChunk()), BASE - 1000, BASE))
        assertNotNull(gate.accept(protoComment("lv2", chatChunk()), BASE - 1000, BASE))
    }
    @Test fun dropsHistoryEmptyInvalidTooLargeAndFutureComments() {
        val gate = CommentGate()
        for (c in listOf(LiveComment("1", " ", BASE), LiveComment("2", "x".repeat(2049), BASE), LiveComment("3", "\u0000", BASE), LiveComment("4", "\uD800", BASE), LiveComment("5", "old", BASE - 1001), LiveComment("6", "future", BASE + 5001))) assertNull(gate.accept(c, BASE - 1000, BASE))
        assertNull(gate.accept(LiveComment("7", "stale", BASE - 20001), BASE - 30000, BASE))
        assertNotNull(gate.accept(LiveComment("8", "実況🙂", BASE), BASE - 1000, BASE))
    }
    @Test fun bootstrapHandlesAttributeOrderAndSingleEntityDecode() {
        val p = Bootstrap.parse(html(watch = "wss://a.live2.nicovideo.jp/watch?a=1&b=2"))
        assertEquals("lv1", p.id); assertEquals("2", p.watchUrl.queryParameter("b")); assertEquals("&quot;", Bootstrap.decodeAttribute("&amp;quot;"))
        assertEquals("A", Bootstrap.decodeAttribute("&#65;")); assertEquals("🙂", Bootstrap.decodeAttribute("&#x1f642;"))
        assertEquals(ConnectionState.NO_PROGRAM, assertThrows(StreamFailure::class.java) { Bootstrap.parse(html(status = "ENDED")) }.state)
    }
    @Test fun rejectsWrongHostDowngradeCredentialsRedirectInjectionAndFragment() {
        for (url in listOf("http://mpn.live.nicovideo.jp/x", "https://evil.example/x", "https://mpn.live.nicovideo.jp.evil.example/x", "https://evilmpn.live.nicovideo.jp/x", "https://mpn.live.nicovideo.jp:444/x", "https://u:p@mpn.live.nicovideo.jp/x", "https://mpn.live.nicovideo.jp/x#fragment", "https://mpn.live.nicovideo.jp\\@evil.example/x")) assertThrows(StreamFailure::class.java) { ServiceUrls.stream(url) }
        assertThrows(StreamFailure::class.java) { ServiceUrls.officialWatch("ws://a.live2.nicovideo.jp/x") }
        assertThrows(StreamFailure::class.java) { ServiceUrls.nxSocket("wss://nx-jikkyo.tsukumijima.net.evil.example/x") }
        assertThrows(StreamFailure::class.java) { ServiceUrls.watchPage("ch123/../../evil") }
    }
    @Test fun retryHonorsServerWaitAndBoundedExponentialJitter() {
        val policy = RetryPolicy { 0.5 }
        assertEquals(listOf(2500L, 4500L, 8500L, 16500L, 30500L, 30500L), (0..5).map { policy.delayMs(it) })
        assertEquals(90500L, policy.delayMs(0, 90000)); assertEquals(120000L, retryAfterMs("120", BASE))
        assertEquals(1000L, retryAfterMs("Tue, 14 Nov 2023 22:13:21 GMT", BASE))
        assertTrue(httpFailure(403, null, BASE).terminal); assertTrue(httpFailure(302, null, BASE).terminal); assertEquals(120000L, httpFailure(429, "120", BASE).waitMs)
    }
    @Test fun jsonLimitsAndNxMappingRejectMalformedValues() {
        assertThrows(StreamFailure::class.java) { parseJson("[".repeat(49) + "]".repeat(49)) }
        assertThrows(StreamFailure::class.java) { parseJson("x".repeat(65537)) }; assertThrows(StreamFailure::class.java) { parseJson("{\"bad\":") }
        val chat = parseJson("""{"thread":"123","no":4,"date":1700000000,"date_usec":250000,"content":"synthetic","mail":"shita small #123456","user_id":"nicolive:synthetic"}""") as JsonObject
        val mapped = nxComment("123", chat)!!
        assertEquals(CommentOrigin.NX_JIKKYO, mapped.origin); assertEquals(CommentPosition.BOTTOM, mapped.position)
        assertEquals(CommentSize.SMALL, mapped.size); assertEquals(0xff123456.toInt(), mapped.color)
        assertEquals(BASE + 250, mapped.postedAtMs); assertNull(nxComment("999", chat))
    }
}
