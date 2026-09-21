package org.plos_clan.cpos.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KernelMutexTest {
    @Test
    fun rejectsReentryWithoutLosingOwnership() {
        val mutex = KernelMutex()
        mutex.withLock {
            assertNull(mutex.tryWithLock { 1 })
            assertFailsWith<IllegalStateException> { mutex.withLock { 2 } }
            assertNull(mutex.tryWithLock { 3 })
        }
        assertEquals(4, mutex.tryWithLock { 4 })
    }

    @Test
    fun releasesAfterExceptionsAndNonlocalReturns() {
        val mutex = KernelMutex()
        assertFailsWith<IllegalArgumentException> {
            mutex.withLock { throw IllegalArgumentException() }
        }
        assertEquals(7, leaveScope(mutex))
        assertEquals(8, mutex.tryWithLock { 8 })
    }

    private fun leaveScope(mutex: KernelMutex): Int {
        mutex.withLock { return 7 }
    }
}
