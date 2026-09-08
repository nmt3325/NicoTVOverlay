package dev.nicotv.app

import dev.nicotv.core.LiveComment
import java.util.ArrayDeque

/** One main-thread, one-window generation. Clocks/scheduling/readiness are injectable, never exported. */
internal class OverlayIngress(
    private val now: () -> Long,
    private val ready: () -> Boolean,
    private val allowed: () -> Boolean,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val deliver: (LiveComment) -> Unit,
    private val invalid: () -> Unit,
) {
    private data class Pending(val comment: LiveComment, val due: Long)
    private val pending = ArrayDeque<Pending>()
    private val createdAt = now()
    private var readyOnce = false
    private var closed = false
    private val wake = Runnable { pump() }
    internal val pendingCount: Int get() = pending.size
    internal val isClosed: Boolean get() = closed

    fun offer(comment: LiveComment, delayMs: Long, receivedAt: Long = now()): Boolean {
        if (closed || comment.text.length > MAX_TEXT_UTF16 || comment.id.length > MAX_ID_UTF16) return false
        if (receivedAt < createdAt - MAX_READY_WAIT_MS || receivedAt > now()) return false
        pump()
        if (closed) return false
        if (!readyOnce && pending.size >= MAX_PRE_READY) return false // preserve the first accepted comments
        while (pending.size >= MAX_PENDING) pending.removeFirst() // steady state favors current reactions
        val delay = delayMs.coerceIn(0L, MAX_DELAY_MS)
        val due = if (receivedAt > Long.MAX_VALUE - delay) Long.MAX_VALUE else receivedAt + delay
        pending.addLast(Pending(comment, due))
        pump()
        return !closed
    }
    private fun pump() {
        if (closed) return
        cancel(wake)
        try {
            val time = now()
            if (!allowed() || time < createdAt) { fail(); return }
            if (!ready()) {
                // Never buffer a previously-visible surface across hide/detach.
                if (readyOnce || time - createdAt >= MAX_READY_WAIT_MS) { fail(); return }
                schedule(wake, minOf(16L, MAX_READY_WAIT_MS - (time - createdAt)))
                return
            }
            readyOnce = true
            while (pending.isNotEmpty() && pending.first.due <= time) {
                if (closed) return
                if (!allowed() || !ready()) { fail(); return }
                val item = pending.removeFirst()
                if (time - item.due <= MAX_LATENESS_MS) deliver(item.comment)
            }
            if (!closed && pending.isNotEmpty()) schedule(wake, (pending.first.due - time).coerceIn(1L, 250L))
        } catch (_: RuntimeException) { fail() }
    }
    private fun fail() { if (!closed) { close(); invalid() } }
    fun close() {
        if (closed) return
        closed = true
        pending.clear()
        cancel(wake)
    }
    companion object {
        const val MAX_PRE_READY = 64
        const val MAX_PENDING = 500
        const val MAX_READY_WAIT_MS = 1_500L
        const val MAX_DELAY_MS = 30_000L
        const val MAX_LATENESS_MS = 5_000L
        const val MAX_TEXT_UTF16 = 2_048
        const val MAX_ID_UTF16 = 256
        fun ready(attached: Boolean, width: Int, height: Int, shown: Boolean, windowVisible: Boolean): Boolean =
            attached && width > 0 && height > 0 && shown && windowVisible
    }
}
