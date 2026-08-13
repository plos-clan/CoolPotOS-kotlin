package org.plos_clan.cpos.drivers.usb.xhci.core

import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.ControlTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.GeneralTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.utils.LittleEndianBuffer

suspend fun Xhci.submitTransfer(args: GeneralTransferArgs): Boolean {
    val endpointNumber = args.endpointAddress and 0x0fu.toUByte()
    val isIn = args.endpointAddress and REQ_DIR_IN != 0u.toUByte()

    val dci = if (isIn) {
        endpointNumber.toUInt() * 2u + 1u
    } else {
        endpointNumber.toUInt() * 2u
    }
    if (dci < 2u || dci > 31u) {
        return false
    }

    val endpoint = slots[args.slotId.toInt()].endpoints[dci.toInt()] ?: return false
    endpoint.semaphore.acquire()
    endpoint.ring.enqueue(Trb.newNormal(args.bufferPhysicalAddress, args.length))
    doorbell.ring(args.slotId, dci)
    return true
}

suspend fun Xhci.submitControl(args: ControlTransferArgs): Boolean {
    val isIn = args.setup.requestType and REQ_DIR_IN != 0u.toUByte()

    val setup = args.setup
    val slotId = args.slotId
    val setupBuffer = LittleEndianBuffer(setup.toNativeBytes())
    val paramLow = setupBuffer.readU32(0)
    val paramHigh = setupBuffer.readU32(4)

    val hasDataStage = setup.length.toUInt() > 0u
    val trt = when {
        !hasDataStage -> 0u
        isIn -> 3u
        else -> 2u
    }

    val endpoint = slots[slotId.toInt()].endpoints[1] ?: return false

    val trbCount = if (hasDataStage) 3 else 2
    repeat(trbCount) {
        endpoint.semaphore.acquire()
    }

    endpoint.ring.enqueue(Trb.newSetupStage(paramLow, paramHigh, trt))

    if (hasDataStage) {
        val dataTrb = Trb.newDataStage(args.bufferPhysicalAddress, setup.length.toUInt(), isIn)
        endpoint.ring.enqueue(dataTrb)
    }

    val index = endpoint.ring.enqueueIndex
    endpoint.promises[index].reset()

    val statusDirectionIn = !hasDataStage || !isIn
    endpoint.ring.enqueue(Trb.newStatusStage(statusDirectionIn))
    doorbell.ring(slotId, 1u)

    val event = endpoint.promises[index].recv()
    repeat(trbCount) {
        endpoint.semaphore.release()
    }

    val code = event.completionCode
    if (code != 1u && code != 13u) {
        println("Control transfer failed. Code: $code")
        return false
    }
    return true
}

internal fun Xhci.completeTransfer(slotId: UByte, dci: UInt, code: UInt, length: UInt) {
    val slot = slots[slotId.toInt()]
    val endpoint = slot.endpoints[dci.toInt()] ?: return
    endpoint.semaphore.release()

    val device = slot.usbDevice ?: return

    val endpointNumber = (dci / 2u).toUByte()
    val isIn = dci % 2u != 0u

    val status = when (code) {
        1u -> TransferStatus.COMPLETED
        13u -> TransferStatus.SHORT_PACKET
        4u -> TransferStatus.BABBLE
        5u -> TransferStatus.TRB_ERROR
        6u -> TransferStatus.STALL
        else -> TransferStatus.UNKNOWN
    }

    device.dispatchCompletion(
        CompletionEvent(
            endpointAddress = if (isIn) endpointNumber or REQ_DIR_IN else endpointNumber,
            status = status,
            residualLength = length,
        ),
    )
}
