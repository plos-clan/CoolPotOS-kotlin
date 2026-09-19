@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.set
import org.plos_clan.cpos.drivers.usb.xhci.regs.Interrupter
import org.plos_clan.cpos.mem.MmioRegion

internal class DmaMemory(private val maximumAddress: ULong) {
    class AllocationFailure : RuntimeException("xHCI DMA memory is unavailable")

    fun allocate(pages: ULong = 1uL): MmioRegion {
        val buffer = MmioRegion.allocate(pages) ?: throw AllocationFailure()
        if (
            buffer.physicalAddress <= maximumAddress &&
                buffer.byteLength - 1uL <= maximumAddress - buffer.physicalAddress
        )
            return buffer
        buffer.free()
        throw AllocationFailure()
    }
}

fun Xhci.setupCommandRing() {
    commandRing = ProducerRing(memory)
    operational.setCrcr(commandRing.physicalAddress or 1uL)
}

fun Xhci.setupDcbaa(maxSlots: UByte) {
    val dcbaaBuffer = memory.allocate()
    dcbaa = dcbaaBuffer

    val scratchpadCount = capability.maxScratchpadBuffers
    if (scratchpadCount > 0u) {
        setupScratchpads(scratchpadCount)
    }

    operational.setDcbaa(dcbaaBuffer.physicalAddress)
}

private fun Xhci.setupScratchpads(count: UInt) {
    val array = memory.allocate()
    val arrayView = array.view<ULongVar>()
    repeat(count.toInt()) { index ->
        val buffer = memory.allocate()
        arrayView[index] = buffer.physicalAddress
    }
    dcbaa!!.view<ULongVar>()[0] = array.physicalAddress
}

fun Xhci.setupInterrupter() {
    val erst = memory.allocate()

    val runtimeOffset = capability.runtimeOffset
    val runtimeBase = capability.baseAddress + runtimeOffset.toULong()
    val interrupter = Interrupter(runtimeBase, 0)

    eventRing = EventRing(memory.allocate(), interrupter.eventRingDequeuePointerAddress)

    val entry = ErstEntry(erst)
    entry.baseAddress = eventRing.physicalAddress
    entry.size = eventRing.capacity.toUInt()

    interrupter.setErstsz(1u)
    interrupter.setErdp(eventRing.physicalAddress)
    interrupter.setErstba(erst.physicalAddress)
    interrupter.enable()
}
