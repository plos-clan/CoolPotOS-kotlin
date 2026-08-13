@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.DmaBuffer
import org.plos_clan.cpos.mem.MmioAddress
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

class CommandRing {
    private val buffer = requireNotNull(DmaBuffer.allocate())
    private val words = buffer.view<UIntVar>()

    val physicalAddress = buffer.physicalAddress
    val capacity: Int = (PAGE_SIZE_BYTES / TRB_SIZE_BYTES.toULong()).toInt()

    var enqueueIndex = 0
        private set

    var cycleState = true
        private set

    fun enqueue(trb: Trb) {
        if (enqueueIndex == capacity - 1) {
            linkToStart()
        }

        val targetIndex = enqueueIndex
        val control = if (cycleState) {
            trb.control or TRB_CYCLE
        } else {
            trb.control and TRB_CYCLE.inv()
        }

        val offset = targetIndex * TRB_WORD_COUNT
        words[offset] = trb.paramLow
        words[offset + 1] = trb.paramHigh
        words[offset + 2] = trb.status
        words[offset + 3] = control
        enqueueIndex++
    }

    private fun linkToStart() {
        val linkIndex = enqueueIndex
        var control = (TRB_LINK shl 10) or TRB_ENT
        control = if (cycleState) {
            control or TRB_CYCLE
        } else {
            control and TRB_CYCLE.inv()
        }

        val offset = linkIndex * TRB_WORD_COUNT
        words[offset] = physicalAddress.toUInt()
        words[offset + 1] = (physicalAddress shr 32).toUInt()
        words[offset + 2] = 0u
        words[offset + 3] = control
        enqueueIndex = 0
        cycleState = !cycleState
    }
}

class EventRing(private val erdpRegister: MmioAddress) {
    private val buffer = requireNotNull(DmaBuffer.allocate())
    private val words = buffer.view<UIntVar>()

    val physicalAddress = buffer.physicalAddress
    val capacity: Int = (PAGE_SIZE_BYTES / TRB_SIZE_BYTES.toULong()).toInt()

    var dequeueIndex = 0
        private set

    var cycleState = true
        private set

    fun hasEvent(): Boolean {
        val control = words[dequeueIndex * TRB_WORD_COUNT + 3]
        val expected = if (cycleState) TRB_CYCLE else 0u
        return control and TRB_CYCLE == expected
    }

    fun pop(): Trb? {
        if (!hasEvent()) {
            return null
        }

        val offset = dequeueIndex * TRB_WORD_COUNT
        val event = Trb(
            paramLow = words[offset],
            paramHigh = words[offset + 1],
            status = words[offset + 2],
            control = words[offset + 3],
        )
        dequeueIndex++

        if (dequeueIndex == capacity) {
            dequeueIndex = 0
            cycleState = !cycleState
        }

        return event
    }

    fun updateErdp() {
        val currentPhysical = physicalAddress + dequeueIndex.toULong() * TRB_SIZE_BYTES.toULong()
        erdpRegister.writeSplitU64(currentPhysical, lowMask = 1u shl 3)
    }
}

class TransferRing {
    private val buffer = requireNotNull(DmaBuffer.allocate())
    private val words = buffer.view<UIntVar>()

    val physicalAddress = buffer.physicalAddress
    val capacity: Int = (PAGE_SIZE_BYTES / TRB_SIZE_BYTES.toULong()).toInt()

    var enqueueIndex = 0
        private set

    var cycleState = true
        private set

    fun enqueue(trb: Trb) {
        if (enqueueIndex == capacity - 1) {
            linkToStart()
        }

        val targetIndex = enqueueIndex
        val control = if (cycleState) {
            trb.control or TRB_CYCLE
        } else {
            trb.control and TRB_CYCLE.inv()
        }

        val offset = targetIndex * TRB_WORD_COUNT
        words[offset] = trb.paramLow
        words[offset + 1] = trb.paramHigh
        words[offset + 2] = trb.status
        words[offset + 3] = control
        enqueueIndex++
    }

    private fun linkToStart() {
        val linkIndex = enqueueIndex
        var control = (TRB_LINK shl 10) or TRB_ENT
        control = if (cycleState) {
            control or TRB_CYCLE
        } else {
            control and TRB_CYCLE.inv()
        }

        val offset = linkIndex * TRB_WORD_COUNT
        words[offset] = physicalAddress.toUInt()
        words[offset + 1] = (physicalAddress shr 32).toUInt()
        words[offset + 2] = 0u
        words[offset + 3] = control
        enqueueIndex = 0
        cycleState = !cycleState
    }

    fun free() {
        buffer.free()
    }
}
