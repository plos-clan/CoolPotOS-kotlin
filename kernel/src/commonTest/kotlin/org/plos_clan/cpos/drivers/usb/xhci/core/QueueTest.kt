package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueTest {
    @Test
    fun leavesOneUnownedSlotAcrossRepeatedWraps() {
        val queue = RingQueue(8)
        repeat(32) {
            val entry = RingEntry(List(7) { Trb() }) { _, _ -> true }
            assertTrue(queue.enqueue(entry) { _, _, _, _ -> })
            assertFalse(queue.enqueue(RingEntry(listOf(Trb())) { _, _ -> true }) { _, _, _, _ -> })
            queue.complete((entry.first + 6) % queue.capacity, Trb())
            assertEquals(0, queue.used)
        }
    }

    @Test
    fun reservesWholeDescriptorsAndReclaimsOnlyCompletedPrefix() {
        val queue = RingQueue(8)
        val first = RingEntry(List(3) { Trb() }) { _, _ -> true }
        val second = RingEntry(List(4) { Trb() }) { _, _ -> true }
        assertTrue(queue.enqueue(first) { _, _, _, _ -> })
        assertTrue(queue.enqueue(second) { _, _, _, _ -> })
        assertEquals(7, queue.used)
        assertFalse(queue.enqueue(RingEntry(listOf(Trb())) { _, _ -> true }) { _, _, _, _ -> })
        queue.complete(6, Trb())
        assertEquals(7, queue.used)
        queue.complete(2, Trb())
        assertEquals(0, queue.used)
    }

    @Test
    fun matchesEveryTrbAcrossWrapAndPublishesCorrectCycles() {
        val queue = RingQueue(7)
        val prefix = RingEntry(List(6) { Trb() }) { _, _ -> true }
        queue.enqueue(prefix) { _, _, _, _ -> }
        queue.complete(5, Trb())
        var completedIndex = -1
        val wrapped =
            RingEntry(List(3) { Trb() }) { index, _ ->
                completedIndex = index
                true
            }
        val writes = mutableListOf<Pair<Int, Boolean>>()
        queue.enqueue(wrapped) { index, cycle, _, _ -> writes.add(index to cycle) }
        assertEquals(listOf(6 to true, 0 to false, 1 to false), writes)
        assertEquals(6, wrapped.first)
        queue.complete(0, Trb())
        assertEquals(1, completedIndex)
        assertEquals(0, queue.used)
        assertEquals(2, queue.enqueueIndex)
        assertFalse(queue.cycleState)
    }

    @Test
    fun intermediateAndDuplicateEventsDoNotReleaseCapacityTwice() {
        val queue = RingQueue(7)
        var events = 0
        val entry =
            RingEntry(List(3) { Trb() }) { index, _ ->
                events++
                index == 2
            }
        queue.enqueue(entry) { _, _, _, _ -> }
        queue.complete(0, Trb())
        assertEquals(3, queue.used)
        queue.complete(2, Trb())
        queue.complete(2, Trb())
        assertEquals(2, events)
        assertEquals(0, queue.used)
    }

    @Test
    fun stoppedQueueDiscardsOwnershipWithoutRewindingHardwarePosition() {
        val queue = RingQueue(7)
        queue.enqueue(RingEntry(List(4) { Trb() }) { _, _ -> false }) { _, _, _, _ -> }
        queue.discard()
        assertEquals(4, queue.enqueueIndex)
        assertEquals(0, queue.used)
        queue.complete(3, Trb())
        val entry = RingEntry(List(4) { Trb() }) { _, _ -> true }
        queue.enqueue(entry) { _, _, _, _ -> }
        assertEquals(4, entry.first)
        queue.complete(0, Trb())
        assertEquals(0, queue.used)
        assertFalse(queue.cycleState)
    }
}
