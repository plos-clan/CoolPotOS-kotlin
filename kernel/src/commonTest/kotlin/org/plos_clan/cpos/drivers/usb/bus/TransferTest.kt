package org.plos_clan.cpos.drivers.usb.bus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TransferTest {
    @Test
    fun requestCanOnlyBeSubmittedAndCompletedOnce() = runBlocking {
        var completions = 0
        val request = UsbTransfer(0x81u, listOf(UsbBuffer(0x1000uL, 512u))) { completions++ }
        assertTrue(request.claim())
        assertFalse(request.claim())
        request.complete(TransferResult(TransferStatus.SHORT_PACKET, 32u))
        request.complete(TransferResult(TransferStatus.DISCONNECTED, 0u))
        assertEquals(1, completions)
        assertEquals(TransferResult(TransferStatus.SHORT_PACKET, 32u), request.await())
    }

    @Test
    fun cancellingWaiterDoesNotCompleteDmaRequest() = runBlocking {
        val request = UsbTransfer(0x81u)
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { request.await() }
        waiter.cancel()
        waiter.join()
        assertFalse(request.isCompleted)
        request.complete(TransferResult(TransferStatus.CANCELLED, 0u))
        assertEquals(TransferStatus.CANCELLED, request.await().status)
    }

    @Test
    fun submittedBuffersAreAnImmutableSnapshot() {
        val buffers = mutableListOf(UsbBuffer(0x1000uL, 512u))
        val request = UsbTransfer(0x81u, buffers)
        buffers.clear()
        assertEquals(512u, request.length)
        assertEquals(1, request.buffers.size)
    }
}
