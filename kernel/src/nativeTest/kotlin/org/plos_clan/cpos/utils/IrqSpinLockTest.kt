package org.plos_clan.cpos.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrqSpinLockTest {
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
