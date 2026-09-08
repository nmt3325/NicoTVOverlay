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
        var onPermission: () -> Unit = {}
        var onReadiness: () -> Unit = {}
        var onDelivery: (LiveComment) -> Unit = {}
        val calls = mutableListOf<Pair<String, Long>>()
        val tasks = linkedMapOf<Runnable, Long>()
        val allCallbacks = mutableListOf<Runnable>()
        val queue = OverlayIngress({ time },
            { onReadiness(); OverlayIngress.ready(attached, width, height, shown, windowVisible) }, { onPermission(); permitted },
            { callback, delay -> tasks[callback] = time + delay; allCallbacks += callback },
            { tasks.remove(it) }, { calls += it.id to time; onDelivery(it) }, { failures++ })
        fun offer(id: String, delay: Long = 0) = queue.offer(LiveComment(id, id, 123456789L), delay)
        fun makeReady() { attached = true; width = 1920; height = 1080; shown = true; windowVisible = true }
        fun advance(to: Long) {
            while (tasks.values.any { it <= to }) {
                val next = tasks.entries.minBy { it.value }; val callback = next.key
                time = maxOf(time, next.value); tasks.remove(callback); callback.run()
            }
            time = maxOf(time, to)
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
        val r = Rig(); r.makeReady(); r.offer("first"); r.advance(1); r.offer("later", 1000)
        val callback = r.allCallbacks.last(); r.queue.close(); r.time = 1000; callback.run()
        assertEquals(listOf("first" to 1L), r.calls); assertTrue(r.tasks.isEmpty())
    }
    @Test fun readinessQueueRejectsOverflowPreservingFirstAcceptedComments() {
        val r = Rig(); repeat(64) { assertTrue(r.offer("c$it")) }
        assertFalse(r.offer("overflow")); assertEquals(64, r.queue.pendingCount); assertEquals(1, r.tasks.size)
        r.makeReady(); r.advance(32)
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
        r.advance(30100); assertEquals(500, r.calls.size); assertEquals("c1", r.calls.first().first)
    }
    @Test fun staleLateWakeCannotReplayExpiredDelay() {
        val r = Rig(); r.makeReady(); r.offer("old", 1000)
        val callback = r.allCallbacks.last(); r.time = 6001; callback.run()
        assertTrue(r.calls.isEmpty()); assertEquals(0, r.queue.pendingCount); assertTrue(r.tasks.isEmpty())
    }

    @Test fun clockJumpDuringFirstDeliveryCannotDeliverSecondExpiredItem() {
        val r = Rig(); r.makeReady(); r.offer("one", 1000); r.offer("two", 1000)
        r.onDelivery = { if (it.id == "one") r.time += 6001 }
        r.advance(1000)
        assertEquals(listOf("one" to 1000L), r.calls); assertEquals(7001L, r.time)
        assertEquals(1, r.queue.pendingCount); assertEquals(1, r.tasks.size)
        r.advance(7002)
        assertEquals(listOf("one" to 1000L), r.calls); assertEquals(0, r.queue.pendingCount); assertTrue(r.tasks.isEmpty())
    }
    @Test fun clockIsResampledAfterSlowPermissionCheck() {
        val r = Rig(); r.makeReady(); r.offer("expired", 1000)
        var jumped = false
        r.onPermission = { if (!jumped && r.time >= 1000) { jumped = true; r.time += 6001 } }
        r.advance(1000)
        assertTrue(jumped); assertTrue(r.calls.isEmpty()); assertEquals(0, r.queue.pendingCount)
    }
    @Test fun clockIsResampledAfterSlowReadinessCheck() {
        val r = Rig(); r.makeReady(); r.offer("expired", 1000)
        var jumped = false
        r.onReadiness = { if (!jumped && r.time >= 1000) { jumped = true; r.time += 6001 } }
        r.advance(1000)
        assertTrue(jumped); assertTrue(r.calls.isEmpty()); assertEquals(0, r.queue.pendingCount)
    }
    @Test fun firstReadinessObservedAt1499IsAccepted() {
        val r = Rig(); r.offer("first"); val wake = r.allCallbacks.last()
        r.time = 1499; r.makeReady(); wake.run()
        assertEquals(listOf("first" to 1499L), r.calls); assertFalse(r.queue.isClosed)
    }
    @Test fun firstReadinessObservedExactlyAtDeadlineIsRejected() {
        val r = Rig(); r.offer("first"); val wake = r.allCallbacks.last()
        r.time = 1500; r.makeReady(); wake.run()
        assertTrue(r.calls.isEmpty()); assertTrue(r.queue.isClosed); assertEquals(1, r.failures)
    }
    @Test fun firstReadinessObservedAfterDeadlineIsRejectedEvenWhenReady() {
        val r = Rig(); r.offer("first"); val wake = r.allCallbacks.last()
        r.time = 1501; r.makeReady(); wake.run(); wake.run()
        assertTrue(r.calls.isEmpty()); assertTrue(r.queue.isClosed); assertEquals(1, r.failures); assertTrue(r.tasks.isEmpty())
    }
    @Test fun slowPermissionCheckCannotCrossTheFirstReadyDeadline() {
        val r = Rig(); r.offer("first"); r.time = 1499; r.makeReady()
        r.onPermission = { r.time = 1501 }; r.allCallbacks.last().run()
        assertTrue(r.calls.isEmpty()); assertTrue(r.queue.isClosed)
    }
    @Test fun slowReadinessCheckCannotCrossTheFirstReadyDeadline() {
        val r = Rig(); r.offer("first"); r.time = 1499; r.makeReady()
        r.onReadiness = { r.time = 1501 }; r.allCallbacks.last().run()
        assertTrue(r.calls.isEmpty()); assertTrue(r.queue.isClosed)
    }
    @Test fun itemBudgetYieldsAndStopBetweenBatchesCancelsRemainingComments() {
        val r = Rig(); r.makeReady(); repeat(40) { r.offer("c$it", 1000) }
        assertTrue(r.calls.isEmpty()); r.advance(1000)
        assertEquals(16, r.calls.size); assertEquals(24, r.queue.pendingCount); assertEquals(1, r.tasks.size)
        r.offer("new", 1000) // offering more cannot drain inline or bypass the yield
        assertEquals(16, r.calls.size); assertEquals(1, r.tasks.size)
        val old = r.allCallbacks.last(); r.queue.close(); r.advance(1001); old.run()
        assertEquals(16, r.calls.size); assertEquals(0, r.queue.pendingCount); assertTrue(r.tasks.isEmpty())
    }
    @Test fun elapsedBudgetYieldsBeforeTheItemLimitAndRechecksPermission() {
        val r = Rig(); r.makeReady(); repeat(10) { r.offer("c$it", 1000) }
        r.onDelivery = { r.time += 2 }; r.advance(1000)
        assertEquals(2, r.calls.size); assertEquals(1004L, r.time); assertEquals(8, r.queue.pendingCount)
        assertEquals(listOf(1005L), r.tasks.values.toList())
        r.permitted = false; r.advance(1005)
        assertEquals(2, r.calls.size); assertTrue(r.queue.isClosed); assertTrue(r.tasks.isEmpty())
    }
    @Test fun closingDuringDeliveryInvalidatesTheRestOfTheBatch() {
        val r = Rig(); r.makeReady(); r.offer("one", 1000); r.offer("two", 1000)
        r.onDelivery = { r.queue.close() }; r.advance(1000)
        assertEquals(listOf("one" to 1000L), r.calls); assertEquals(0, r.queue.pendingCount); assertTrue(r.tasks.isEmpty())
    }
    @Test fun permissionChangeDuringDeliveryRejectsTheNextItem() {
        val r = Rig(); r.makeReady(); r.offer("one", 1000); r.offer("two", 1000)
        r.onDelivery = { r.permitted = false }; r.advance(1000)
        assertEquals(listOf("one" to 1000L), r.calls); assertTrue(r.queue.isClosed)
    }
    @Test fun readinessLossDuringDeliveryRejectsTheNextItem() {
        val r = Rig(); r.makeReady(); r.offer("one", 1000); r.offer("two", 1000)
        r.onDelivery = { r.shown = false }; r.advance(1000)
        assertEquals(listOf("one" to 1000L), r.calls); assertTrue(r.queue.isClosed)
    }
    @Test fun followingWakeUsesFreshTimeAfterExpensiveDelivery() {
        val r = Rig(); r.makeReady(); r.offer("one", 1000); r.advance(100); r.offer("two", 1000)
        r.onDelivery = { if (it.id == "one") r.time += 50 }; r.advance(1100)
        assertEquals(listOf("one" to 1000L, "two" to 1100L), r.calls); assertTrue(r.tasks.isEmpty())
    }
    @Test fun latenessBoundaryAccepts5000ButRejects5001() {
        val at = Rig(); at.makeReady(); at.offer("edge", 1000); at.time = 6000; at.allCallbacks.last().run()
        assertEquals(listOf("edge" to 6000L), at.calls)
        val after = Rig(); after.makeReady(); after.offer("expired", 1000); after.time = 6001; after.allCallbacks.last().run()
        assertTrue(after.calls.isEmpty()); assertEquals(0, after.queue.pendingCount)
    }
}
