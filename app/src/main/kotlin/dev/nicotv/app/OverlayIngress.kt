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
    private var scheduledAt: Long? = null
    private val wake = Runnable { scheduledAt = null; pump() }
    internal val pendingCount: Int get() = pending.size
    internal val isClosed: Boolean get() = closed

    fun offer(comment: LiveComment, delayMs: Long, receivedAt: Long = now()): Boolean {
        if (closed || comment.text.length > MAX_TEXT_UTF16 || comment.id.length > MAX_ID_UTF16) return false
        if (receivedAt < createdAt - MAX_READY_WAIT_MS || receivedAt > now()) return false
        return try {
            checkReadiness()
            if (closed) return false
            if (!readyOnce && pending.size >= MAX_PRE_READY) return false // preserve first accepted comments
            while (pending.size >= MAX_PENDING) pending.removeFirst() // steady state favors current reactions
            val delay = delayMs.coerceIn(0L, MAX_DELAY_MS)
            val due = if (receivedAt > Long.MAX_VALUE - delay) Long.MAX_VALUE else receivedAt + delay
            pending.addLast(Pending(comment, due))
            requestWake() // never drain inline: a producer burst cannot bypass the main-loop budget
            !closed
        } catch (_: RuntimeException) { fail(); false }
    }
    /** The deadline applies BEFORE first readiness is accepted, including time spent in platform calls. */
    private fun checkReadiness(): Boolean {
        if (closed) return false
        if (!validTime(now())) { fail(); return false }
        if (!allowed()) { fail(); return false }
        if (closed) return false
        val visible = ready()
        val checkedAt = now()
        if (closed) return false
        if (!validTime(checkedAt)) { fail(); return false }
        if (!visible) {
            if (readyOnce) fail() // never buffer a previously-visible surface across hide/detach
            return false
        }
        readyOnce = true
        return true
    }
    private fun validTime(time: Long): Boolean = time >= createdAt &&
        (readyOnce || time - createdAt < MAX_READY_WAIT_MS)

    private fun pump() {
        if (closed) return
        cancel(wake); scheduledAt = null
        try {
            val batchStartedAt = now()
            if (!checkReadiness()) { if (!closed) requestWake(); return }
            var processed = 0
            while (!closed && pending.isNotEmpty()) {
                // Count expired entries too. One expensive renderer call cannot be preempted,
                // but the next item always yields once either budget has been spent.
                if (processed >= MAX_BATCH_ITEMS ||
                    (processed > 0 && now() - batchStartedAt >= MAX_BATCH_MS)) {
                    requestWake(yieldBatch = true); return
                }
                if (!checkReadiness()) { if (!closed) requestWake(); return }
                // Re-sample AFTER all potentially expensive permission/readiness checks.
                val deliveryTime = now()
                if (closed) return
                if (!validTime(deliveryTime)) { fail(); return }
                val item = pending.first
                if (item.due > deliveryTime) { requestWake(); return }
                pending.removeFirst(); processed++
                if (deliveryTime - item.due <= MAX_LATENESS_MS) deliver(item.comment)
            }
        } catch (_: RuntimeException) { fail() }
    }
    /** Coalesce at most one callback and compute every wait from a fresh monotonic sample. */
    private fun requestWake(yieldBatch: Boolean = false) {
        if (closed || pending.isEmpty()) return
        val time = now()
        if (!validTime(time)) { fail(); return }
        val wait = when {
            yieldBatch -> 1L
            !readyOnce -> minOf(16L, MAX_READY_WAIT_MS - (time - createdAt))
            else -> (pending.first.due - time).coerceIn(1L, 250L)
        }
        val at = if (time > Long.MAX_VALUE - wait) Long.MAX_VALUE else time + wait
        if (scheduledAt?.let { it <= at } == true) return
        cancel(wake); scheduledAt = at
        schedule(wake, wait)
    }
    private fun fail() { if (!closed) { close(); invalid() } }
    fun close() {
        if (closed) return
        closed = true
        pending.clear()
        scheduledAt = null
        cancel(wake)
    }
    companion object {
        const val MAX_PRE_READY = 64
        const val MAX_PENDING = 500
        const val MAX_BATCH_ITEMS = 16
        const val MAX_BATCH_MS = 4L
        const val MAX_READY_WAIT_MS = 1_500L
        const val MAX_DELAY_MS = 30_000L
        const val MAX_LATENESS_MS = 5_000L
        const val MAX_TEXT_UTF16 = 2_048
        const val MAX_ID_UTF16 = 256
        fun ready(attached: Boolean, width: Int, height: Int, shown: Boolean, windowVisible: Boolean): Boolean =
            attached && width > 0 && height > 0 && shown && windowVisible
    }
}
