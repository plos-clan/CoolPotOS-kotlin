@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.set
import org.plos_clan.cpos.drivers.usb.xhci.regs.Interrupter
import org.plos_clan.cpos.mem.MmioRegion

fun Xhci.setupCommandRing() {
    commandRing = CommandRing()
    operational.setCrcr(commandRing.physicalAddress or 1uL)
}

fun Xhci.setupDcbaa(maxSlots: UByte) {
    val dcbaaBuffer = requireNotNull(MmioRegion.allocate())
    dcbaa = dcbaaBuffer

    val scratchpadCount = capability.maxScratchpadBuffers
    if (scratchpadCount > 0u) {
        setupScratchpads(scratchpadCount)
    }

    operational.setDcbaa(dcbaaBuffer.physicalAddress)
}

private fun Xhci.setupScratchpads(count: UInt) {
    val array = requireNotNull(MmioRegion.allocate())
    val arrayView = array.view<ULongVar>()
    repeat(count.toInt()) { index ->
        val buffer = requireNotNull(MmioRegion.allocate())
        arrayView[index] = buffer.physicalAddress
    }
    dcbaa!!.view<ULongVar>()[0] = array.physicalAddress
}

fun Xhci.setupInterrupter() {
    val erst = requireNotNull(MmioRegion.allocate())

    val runtimeOffset = capability.runtimeOffset
    val runtimeBase = capability.baseAddress + runtimeOffset.toULong()
    val interrupter = Interrupter(runtimeBase, 0)

    eventRing = EventRing(interrupter.eventRingDequeuePointerAddress)

    val entry = ErstEntry(erst)
    entry.baseAddress = eventRing.physicalAddress
    entry.size = eventRing.capacity.toUInt()

    interrupter.setErstsz(1u)
    interrupter.setErdp(eventRing.physicalAddress)
    interrupter.setErstba(erst.physicalAddress)
    interrupter.enable()
}
