package dev.nicotv.comment

import dev.nicotv.core.CommentOrigin
import dev.nicotv.core.CommentPosition
import dev.nicotv.core.CommentSize
import dev.nicotv.core.LiveComment
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PastLogTest {
    private val broadcast = 1606431600L

    private fun rejected(block: () -> Unit): Boolean = try { block(); false } catch (_: StreamFailure) { true }

    @Test fun archiveUrlIsRestrictedToTheJikkyoArchiveApi() {
        val url = ServiceUrls.kakolog("jk1", broadcast, broadcast + 120)
        assertEquals("jikkyo.tsukumijima.net", url.host)
        assertEquals("/api/kakolog/jk1", url.encodedPath)
        assertEquals(broadcast.toString(), url.queryParameter("starttime"))
        assertEquals((broadcast + 120).toString(), url.queryParameter("endtime"))
        assertEquals("json", url.queryParameter("format"))
        ServiceUrls.transport(url)
        assertTrue(rejected { ServiceUrls.kakolog("jk1x", broadcast, broadcast + 60) })
        assertTrue(rejected { ServiceUrls.kakolog("jk1", broadcast, broadcast + 7200) })
        assertTrue(rejected { ServiceUrls.kakolog("jk1", broadcast, broadcast) })
    }

    @Test fun mapsArchiveCommentsAndRejectsDeleted() {
        val chat = parseJson(
            "{\"thread\":\"1606417201\",\"no\":\"5\",\"date\":\"" + broadcast +
                "\",\"date_usec\":\"500000\",\"mail\":\"shita big\",\"content\":\"固定コメント\"}",
        ) as JsonObject
        val mapped = requireNotNull(kakologComment("jk1", chat))
        // 放送時刻は date + date_usec でミリ秒まで復元する。
        assertEquals(broadcast * 1000 + 500, mapped.broadcastMs)
        assertEquals(CommentPosition.BOTTOM, mapped.comment.position)
        assertEquals(CommentSize.LARGE, mapped.comment.size)
        assertEquals(CommentOrigin.NX_KAKOLOG, mapped.comment.origin)
        val deleted = parseJson("{\"date\":\"" + broadcast + "\",\"content\":\"削除済み\",\"deleted\":1}") as JsonObject
        assertNull(kakologComment("jk1", deleted))
    }

    @Test fun pastGateKeepsOldBroadcastTimesButFiltersUnsafeText() {
        val gate = PastLogGate()
        val old = LiveComment("kakolog:jk1:1:1", "昔のコメント", 1_000_000_000_000L, origin = CommentOrigin.NX_KAKOLOG)
        // 過去ログは現在時刻と無関係なので、古い放送時刻でも通す。
        assertNotNull(gate.accept(old))
        assertNull(gate.accept(old))
        assertNull(gate.accept(old.copy(id = "kakolog:jk1:1:2", text = "   ")))
    }
}
