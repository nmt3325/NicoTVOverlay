package dev.nicotv.comment

import dev.nicotv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CancellationRaceTest {
    private fun state(value: ConnectionState) = StreamEvent.State(value, "synthetic", CommentOrigin.NICONICO)

    @Test fun closedMailboxRefusesBeginWithoutRestoringStateOrComments() = runBlocking {
        val output = EventMailbox { BASE }
        val epoch = requireNotNull(output.begin(state(ConnectionState.LIVE)))
        output.offer(epoch, StreamEvent.Comment(LiveComment("synthetic", "synthetic", BASE)))
        output.close()
        assertNull(output.begin(state(ConnectionState.RESOLVING)))
        output.offer(epoch, state(ConnectionState.ERROR)); output.finish()
        assertNull(output.next())
    }

    @Test fun finishedMailboxRefusesBeginButRetainsItsTerminalState() = runBlocking {
        val output = EventMailbox { BASE }
        val epoch = requireNotNull(output.begin(state(ConnectionState.LIVE)))
        output.offer(epoch, state(ConnectionState.ERROR)); output.finish()
        assertNull(output.begin(state(ConnectionState.RESOLVING)))
        assertEquals(ConnectionState.ERROR, (output.next() as StreamEvent.State).state)
        assertNull(output.next()); output.close()
    }

    @Test fun startupCancellationBeforeBeginDoesNotEscapeAndCanRestart() = runBlocking {
        repeat(5) { cancelAtBegin(nx = false, retry = false) }
        println("CANCEL_STARTUP official cycles=5 handlerErrors=0 lateStarts=0 restartLive=true")
    }

    @Test fun nxStartupCancellationBeforeBeginDoesNotStartSocket() = runBlocking {
        repeat(5) { cancelAtBegin(nx = true, retry = false) }
        println("CANCEL_STARTUP nx cycles=5 handlerErrors=0 lateStarts=0")
    }

    @Test fun retryBoundaryCancellationDoesNotStartAnotherAttempt() = runBlocking {
        repeat(3) { cancelAtBegin(nx = false, retry = true) }
        println("CANCEL_RETRY official cycles=3 handlerErrors=0 lateStarts=0 restartLive=true")
    }

    @Test fun cancelledProducerAlsoStopsWhenMailboxCloseIsStillQueued() = runBlocking {
        repeat(3) {
            cancelAtBegin(nx = false, retry = false, holdClose = true)
            cancelAtBegin(nx = true, retry = false, holdClose = true)
        }
        println("CANCEL_WITH_CLOSE_QUEUED cycles=6 handlerErrors=0 lateStarts=0")
    }

    /** Same wallMs-before-begin barrier as the independent probe; queue fences replace sleeps. */
    private suspend fun cancelAtBegin(nx: Boolean, retry: Boolean, holdClose: Boolean = false) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { collector ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { producer ->
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val closeHeld = CountDownLatch(1); val releaseClose = CountDownLatch(1)
                val errors = CopyOnWriteArrayList<Throwable>()
                val stopped = AtomicBoolean(false); val lateStarts = AtomicInteger()
                val clockCalls = AtomicInteger(); val firstFailure = AtomicBoolean(retry)
                val scope = CoroutineScope(SupervisorJob() + collector + CoroutineExceptionHandler { _, e -> errors += e })
                val fake = FakeWire().apply { onRead = { url ->
                    if (firstFailure.compareAndSet(true, false)) throw IOException("synthetic retry")
                    if (url.host == "live.nicovideo.jp") ReadPlan(html().toByteArray())
                    else ReadPlan(frames(next(1)), after = true)
                } }
                val wire = object : CommentWire {
                    override suspend fun <T> read(url: HttpUrl, block: suspend (InputStream) -> T): T {
                        if (stopped.get()) lateStarts.incrementAndGet()
                        return fake.read(url, block)
                    }
                    override suspend fun socket(url: HttpUrl): JsonSocket {
                        if (stopped.get()) lateStarts.incrementAndGet()
                        return fake.socket(url)
                    }
                }
                val options = CommentOptions(wallMs = {
                    if (clockCalls.incrementAndGet() == (if (retry) 2 else 1)) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "test producer barrier timed out" }
                    }
                    BASE
                }, dispatcher = producer, retry = RetryPolicy { 0.0 })
                val source: CommentSource = if (nx) NxJikkyoCommentSource(wire, options) else NicoLiveCommentSource(wire, options)
                val job = scope.launch { source.stream(STATION).collect() }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    if (holdClose) {
                        scope.launch {
                            closeHeld.countDown()
                            check(releaseClose.await(5, TimeUnit.SECONDS)) { "test collector barrier timed out" }
                        }
                        assertTrue(withContext(Dispatchers.IO) { closeHeld.await(5, TimeUnit.SECONDS) })
                    }
                    job.cancel(); stopped.set(true)
                    if (!holdClose) withContext(collector) { /* mailbox-close continuation has run */ }
                    assertFalse(job.isCompleted) // producer remains held at the reviewer's seam
                    release.countDown()
                    if (holdClose) {
                        withContext(producer) { /* cancellation is observed while close is queued */ }
                        releaseClose.countDown()
                    }
                    withTimeout(5000) { job.join() }
                    assertEquals(if (retry) 1 else 0, fake.reads.size)
                    assertTrue(fake.sockets.isEmpty()); assertEquals(0, fake.activeReads)
                    assertEquals("No new HTTP/WS starts after cancellation", 0, lateStarts.get())
                    assertTrue("Normal cancellation reached handler: ${errors.map { it.javaClass.simpleName }}", errors.isEmpty())
                    if (!nx) {
                        stopped.set(false)
                        val live = CompletableDeferred<Unit>()
                        val restarted = scope.launch { source.stream(STATION).collect { e ->
                            if (e is StreamEvent.State && e.state == ConnectionState.LIVE) live.complete(Unit)
                        } }
                        try { withTimeout(5000) { live.await() } } finally { restarted.cancelAndJoin() }
                        assertEquals(0, fake.activeReads); assertTrue(fake.sockets.all { it.second.closed })
                        assertTrue(errors.isEmpty())
                    }
                } finally {
                    release.countDown(); releaseClose.countDown(); scope.cancel()
                    withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
                }
            }
        }
    }
}
