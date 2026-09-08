package dev.nicotv.comment
import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
/** Explicit network opt-in. No URL/token/user id/comment text is printed or saved. */
class LiveSmokeTest {
    @Test fun officialJk4LiveReceive() {
        assumeTrue("Set NICOTV_LIVE_SMOKE=1 to use the public live service", System.getenv("NICOTV_LIVE_SMOKE") == "1")
        val station = liveSmokeStation(System.getenv("NICOTV_LIVE_STATION"))
        val started = Instant.now(); val counts = mutableMapOf<ConnectionState, Int>(); var comments = 0; val client = OkHttpClient()
        runBlocking { withTimeoutOrNull(80000) {
            NicoLiveCommentSource(client).stream(station).collect { event -> when (event) {
                is StreamEvent.State -> counts[event.state] = (counts[event.state] ?: 0) + 1
                is StreamEvent.Comment -> { assertEquals(CommentOrigin.NICONICO, event.comment.origin); comments++ }
            } }
        } }
        println("LIVE_SMOKE start=$started end=${Instant.now()} origin=NICONICO station=${station.id} states=$counts comments=$comments")
        assertTrue("Official LIVE state not reached", (counts[ConnectionState.LIVE] ?: 0) > 0)
        assertTrue("No live comments in the opt-in observation window", comments > 0)
    }
}

internal fun liveSmokeStation(id: String?): Station = requireNotNull(StationCatalog.find(id ?: "jk4")) {
    "NICOTV_LIVE_STATION must name a catalog station"
}
