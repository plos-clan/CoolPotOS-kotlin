package org.plos_clan.cpos.utils

import kotlin.random.Random
import kotlin.test.*

class IndexedHeapTest {
    @Test
    fun arbitraryCancellationPreservesPriorityOrder() {
        val random = Random(719)
        val heap = IndexedHeap<Int>(naturalOrder())
        val retained = ArrayList<IndexedHeap.Entry<Int>>()
        repeat(5000) {
            if (retained.isNotEmpty() && random.nextBoolean()) {
                val entry = retained.removeAt(random.nextInt(retained.size))
                assertTrue(heap.remove(entry))
                assertFalse(heap.remove(entry))
            } else retained += heap.add(random.nextInt())
            assertEquals(retained.minOfOrNull { it.value }, heap.peek())
        }
        val expected = retained.map { it.value }.sorted()
        assertEquals(expected, List(heap.size) { heap.removeFirst() })
        assertNull(heap.peek())
        assertNull(heap.removeFirst())
    }

    @Test
    fun rejectsForeignAndRemovedHandles() {
        val first = IndexedHeap<Int>(naturalOrder())
        val second = IndexedHeap<Int>(naturalOrder())
        val entry = first.add(7)
        second.add(11)
        assertFalse(second.remove(entry))
        assertEquals(7, first.removeFirst())
        first.add(8)
        assertFalse(first.remove(entry))
        assertEquals(8, first.peek())
    }
}
