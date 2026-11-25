package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RingSpaceTest {
    @Test
    fun completionAndShutdownWakeOnlyTheRegisteredGeneration() {
        val space = RingSpace()
        val first = space.nextChange()
        assertSame(first, space.nextChange())
        space.signal(true)
        val next = space.nextChange()
        assertNotSame(first, next)
        assertFalse(next.isCompleted)
        space.signal(false)
        assertFalse(next.getCompleted())
        assertTrue(first.getCompleted())
        assertFalse(space.nextChange().isCompleted)
    }
}
