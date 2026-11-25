package org.plos_clan.cpos.utils

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrqSpinLockTest {
    @Test
    fun pairedLocksHandleAliasingReversedOrderAndExceptions() {
        val left = IrqSpinLock()
        val right = IrqSpinLock()
        assertEquals(1, left.withLock(left) { 1 })
        assertFailsWith<IllegalStateException> { right.withLock(left) { error("abort") } }
        var completed = 0
        suspend fun count(first: IrqSpinLock, second: IrqSpinLock) {
            repeat(32) {
                first.withLock(second) {
                    assertFalse(first.tryWithLock {})
                    assertFalse(second.tryWithLock {})
                    completed++
                }
                yield()
            }
        }
        KernelCoroutines.await {
            coroutineScope {
                launch { count(left, right) }
                launch { count(right, left) }
            }
        }
        assertEquals(64, completed)
    }

    @Test
    fun preservesInterruptStateAcrossNestedAndFailedAcquisitions() {
        val outer = IrqSpinLock()
        val inner = IrqSpinLock()
        val original = saveInterrupts()
        restoreInterrupts(original)
        outer.withLock {
            assertEquals(0uL, saveInterrupts() and 0x200uL)
            assertFalse(outer.tryWithLock { error("Already locked") })
            assertTrue(inner.tryWithLock {
                assertEquals(0uL, saveInterrupts() and 0x200uL)
            })
            assertFailsWith<IllegalArgumentException> {
                inner.withLock { throw IllegalArgumentException() }
            }
            assertEquals(0uL, saveInterrupts() and 0x200uL)
        }
        val restored = saveInterrupts()
        restoreInterrupts(restored)
        assertEquals(original and 0x200uL, restored and 0x200uL)
        assertTrue(outer.tryWithLock {})
    }
}
