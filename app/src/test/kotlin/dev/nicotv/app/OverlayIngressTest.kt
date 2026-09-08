package dev.nicotv.app

import dev.nicotv.core.LiveComment
import org.junit.Assert.*
import org.junit.Test

/** Contract spies, NOT proof of the actual renderer: this worktree contains an overlay stub. */
class OverlayIngressTest {
    private class Rig {
        var time = 0L
        var attached = false
        var width = 0
        var height = 0
        var shown = false
        var windowVisible = false
        var permitted = true
        var failures = 0
        val calls = mutableListOf<Pair<String, Long>>()
        val tasks = linkedMapOf<Runnable, Long>()
        val allCallbacks = mutableListOf<Runnable>()
        val queue = OverlayIngress({ time },
            { OverlayIngress.ready(attached, width, height, shown, windowVisible) }, { permitted },
            { callback, delay -> tasks[callback] = time + delay; allCallbacks += callback },
            { tasks.remove(it) }, { calls += it.id to time }, { failures++ })
        fun offer(id: String, delay: Long = 0) = queue.offer(LiveComment(id, id, 123456789L), delay)
        fun makeReady() { attached = true; width = 1920; height = 1080; shown = true; windowVisible = true }
        fun advance(to: Long) {
            while (tasks.values.any { it <= to }) {
                val next = tasks.entries.minBy { it.value }; val callback = next.key
                time = next.value; tasks.remove(callback); callback.run()
            }
            time = to
        }
    }
    @Test fun soleFirstCommentWaitsForAttachmentRealDimensionsAndShownThenDeliversOnce() {
        val r = Rig(); assertTrue(r.offer("first")); assertTrue(r.calls.isEmpty())
        r.attached = true; r.advance(16); assertTrue(r.calls.isEmpty())
        r.width = 1920; r.height = 1080; r.advance(32); assertTrue(r.calls.isEmpty())
        r.shown = true; r.advance(48); assertTrue(r.calls.isEmpty())
        r.windowVisible = true; r.advance(64)
        assertEquals(listOf("first" to 64L), r.calls); assertTrue(r.tasks.isEmpty())
        r.allCallbacks.first().run(); assertEquals(1, r.calls.size)
    }
    @Test fun clearImmediatelyBeforeAttachInvalidatesQueuedAndHeldCallbacks() {
        val r = Rig(); r.offer("old"); val old = r.allCallbacks.first()
        r.queue.close(); r.makeReady(); old.run(); r.advance(2000)
        assertTrue(r.calls.isEmpty()); assertEquals(0, r.queue.pendingCount); assertTrue(r.tasks.isEmpty())
        assertFalse(r.offer("after-stop")); assertEquals(0, r.failures)
    }
    @Test fun oldViewCallbackCannotTouchANewWindowGeneration() {
        val old = Rig(); old.offer("old"); val callback = old.allCallbacks.first(); old.queue.close()
        val next = Rig(); next.offer("new"); old.makeReady(); callback.run()
        assertTrue(old.calls.isEmpty()); assertEquals(1, next.queue.pendingCount)
        next.makeReady(); next.advance(16); assertEquals(listOf("new" to 16L), next.calls)
        callback.run(); assertEquals(1, next.calls.size)
    }
    @Test fun clearAfterFirstDeliveryDropsPendingDelayAndLaterCallbacks() {
        val r = Rig(); r.makeReady(); r.offer("first"); r.offer("later", 1000)
        val callback = r.allCallbacks.last(); r.queue.close(); r.time = 1000; callback.run()
        assertEquals(listOf("first" to 0L), r.calls); assertTrue(r.tasks.isEmpty())
    }
    @Test fun readinessQueueRejectsOverflowPreservingFirstAcceptedComments() {
        val r = Rig(); repeat(64) { assertTrue(r.offer("c$it")) }
        assertFalse(r.offer("overflow")); assertEquals(64, r.queue.pendingCount); assertEquals(1, r.tasks.size)
        r.makeReady(); r.advance(16)
        assertEquals((0 until 64).map { "c$it" }, r.calls.map { it.first }); assertEquals(0, r.failures)
    }
    @Test fun waitExpiresEvenWithContinuousTrafficAndNeverRevives() {
        val r = Rig(); r.offer("first"); r.advance(1000); r.offer("new")
        val old = r.allCallbacks.first(); r.advance(1500)
        assertTrue(r.queue.isClosed); assertEquals(1, r.failures); assertEquals(0, r.queue.pendingCount)
        r.makeReady(); old.run(); assertFalse(r.offer("too-late")); assertTrue(r.calls.isEmpty())
    }
    @Test fun permissionLossBeforeAttachInvalidatesWithoutDelivery() {
        val r = Rig(); r.offer("first"); r.permitted = false; r.makeReady(); r.advance(16)
        assertTrue(r.calls.isEmpty()); assertEquals(1, r.failures); assertTrue(r.queue.isClosed)
        r.permitted = true; r.allCallbacks.first().run(); assertTrue(r.calls.isEmpty())
    }
    @Test fun hiddenOrDetachedReadyViewDoesNotAccumulateForReshow() {
        val r = Rig(); r.makeReady(); r.offer("delayed", 1000); r.shown = false; r.advance(250)
        assertTrue(r.queue.isClosed); r.makeReady(); r.advance(2000); assertTrue(r.calls.isEmpty())
    }
    @Test fun receiptRelativeDelayIncludesReadinessWaitAndDoesNotDoubleDelay() {
        val r = Rig(); r.offer("first", 1000); r.advance(400); r.makeReady(); r.advance(999)
        assertTrue(r.calls.isEmpty()); r.advance(1000)
        assertEquals(listOf("first" to 1000L), r.calls)
    }
    @Test fun zeroOrAlreadyElapsedDelayDeliversAsSoonAsReady() {
        val r = Rig(); r.offer("first", 100); r.advance(400); r.makeReady(); r.advance(416)
        assertEquals(listOf("first" to 416L), r.calls)
    }
    @Test fun steadyStateQueueAndRawPayloadAreBounded() {
        val r = Rig(); r.makeReady(); repeat(501) { r.offer("c$it", 30000) }
        assertEquals(500, r.queue.pendingCount); assertEquals(1, r.tasks.size)
        assertFalse(r.queue.offer(LiveComment("x", "x".repeat(2049), 0), 0))
        assertFalse(r.queue.offer(LiveComment("i".repeat(257), "x", 0), 0))
        r.advance(30000); assertEquals(500, r.calls.size); assertEquals("c1", r.calls.first().first)
    }
    @Test fun staleLateWakeCannotReplayExpiredDelay() {
        val r = Rig(); r.makeReady(); r.offer("old", 1000)
        val callback = r.allCallbacks.last(); r.time = 6001; callback.run()
        assertTrue(r.calls.isEmpty()); assertEquals(0, r.queue.pendingCount); assertTrue(r.tasks.isEmpty())
    }
}
