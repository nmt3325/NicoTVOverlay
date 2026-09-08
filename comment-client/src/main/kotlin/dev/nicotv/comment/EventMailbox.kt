package dev.nicotv.comment

import dev.nicotv.core.ConnectionState
import dev.nicotv.core.StreamEvent
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/** A conflated control lane plus a bounded, lossy comment lane, never a lossy mixed stream. */
internal class EventMailbox(private val wallMs: () -> Long) {
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val comments = ArrayDeque<StreamEvent.Comment>()
    private var pendingState: StreamEvent.State? = null
    private var live = false
    private var generation = 0L
    private var finished = false

    /** Null means no attempt may start. This decision and the epoch update are atomic with close/finish. */
    @Synchronized fun begin(state: StreamEvent.State): Long? {
        if (finished) return null
        generation++
        updateState(state)
        return generation
    }

    /** Constant bounded work; never suspends an HTTP reader or a watch heartbeat on the UI. */
    @Synchronized fun offer(epoch: Long, event: StreamEvent) {
        if (finished || epoch != generation) return
        when (event) {
            is StreamEvent.State -> updateState(event)
            is StreamEvent.Comment -> if (live) {
                if (comments.size == 256) comments.removeFirst()
                comments.addLast(event)
                changed.trySend(Unit)
            }
        }
    }

    private fun updateState(state: StreamEvent.State) {
        // Invalidate backlog on disconnect/reconnect/terminal AND on a new LIVE boundary.
        // No previously queued comment may resurrect after a later control state.
        comments.clear()
        live = state.state == ConnectionState.LIVE
        pendingState = state
        changed.trySend(Unit)
    }

    suspend fun next(): StreamEvent? {
        while (true) {
            synchronized(this) {
                pendingState?.let { pendingState = null; return it }
                while (comments.isNotEmpty()) {
                    val event = comments.removeFirst()
                    val now = wallMs()
                    // Recheck freshness at delivery, not only before queuing for a slow UI.
                    if (live && event.comment.postedAtMs >= now - 20000 && event.comment.postedAtMs <= now + 5000) return event
                }
                if (finished) return null
            }
            changed.receive()
        }
    }

    @Synchronized fun finish() { finished = true; changed.trySend(Unit) }
    @Synchronized fun close() {
        finished = true
        pendingState = null
        comments.clear()
        changed.cancel()
    }
}
