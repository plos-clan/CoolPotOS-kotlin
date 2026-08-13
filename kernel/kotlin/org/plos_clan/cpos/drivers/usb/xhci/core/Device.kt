@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import org.plos_clan.cpos.drivers.usb.bus.UsbEndpoint
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_CONTROL
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_INT
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_ISO
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.drivers.usb.defs.SPEED_FULL
import org.plos_clan.cpos.drivers.usb.defs.SPEED_HIGH
import org.plos_clan.cpos.drivers.usb.defs.SPEED_LOW
import org.plos_clan.cpos.drivers.usb.defs.SPEED_SUPER
import org.plos_clan.cpos.mem.DmaBuffer
import platform.posix.memcpy

suspend fun Xhci.addressDevice(portId: Int, slotId: UByte, speedId: UInt): Boolean {
    println("Addressing device on slot $slotId...")

    val outContext = requireNotNull(DmaBuffer.allocate())
    dcbaa!!.view<ULongVar>()[slotId.toInt()] = outContext.physicalAddress
    slots[slotId.toInt()] = Slot(
        id = slotId,
        active = true,
        portId = portId,
        speed = speedId,
        outContext = outContext,
    )

    val ep0 = Endpoint()
    slots[slotId.toInt()].endpoints[1] = ep0

    val inContext = requireNotNull(DmaBuffer.allocate())
    try {
        val ctrlContext = InputControlContext(inContext)
        ctrlContext.addFlags = 1u or (1u shl 1)

        val slotContext = SlotContext(inContext, contextSize)
        slotContext.setEntries(1u)
        slotContext.setRootHubPort(portId.toUInt())
        slotContext.setRouteString(0u)
        slotContext.setSpeed(speedId)
        slotContext.setInterrupterTarget(0u)

        val mps = when (speedId) {
            SPEED_SUPER.toUInt() -> 512u
            SPEED_LOW.toUInt() -> 8u
            else -> 64u
        }

        val ep0Context = EndpointContext(inContext, 1, contextSize)
        ep0Context.setEpType(EP_TYPE_CONTROL.toUInt() + 4u)
        ep0Context.setMaxPacketSize(mps)
        ep0Context.setMaxBurst(0u)
        ep0Context.setErrorCount(3u)
        ep0Context.setAverageTrbLen(8u)
        ep0Context.setDequeuePointer(ep0.ring.physicalAddress or 1uL)

        val command = Trb.newAddressDevice(inContext.physicalAddress, slotId)
        val (code, _) = sendCommand(command)
            ?: run {
                println("Address Device command timeout")
                return false
            }

        if (code != 1u) {
            println("Address Device failed code: $code")
            return false
        }
    } finally {
        inContext.free()
    }
    return true
}

suspend fun Xhci.configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Boolean {
    val inContext = requireNotNull(DmaBuffer.allocate())
    try {
        val ctrlContext = InputControlContext(inContext)
        ctrlContext.addFlags = 1u

        var maxDci = 0u
        for (endpoint in endpoints) {
            val dci = setupOneEndpoint(slotId, inContext, endpoint) ?: 0u
            if (dci > maxDci) {
                maxDci = dci
            }
        }

        val slotContext = SlotContext(inContext, contextSize)
        slotContext.setEntries(maxDci)

        val command = Trb.newConfigureEndpoint(inContext.physicalAddress, slotId)
        val (code, _) = sendCommand(command)
            ?: run {
                println("Configure endpoint command timeout")
                return false
            }

        if (code != 1u) {
            println("Configure endpoint failed: $code")
            return false
        }
    } finally {
        inContext.free()
    }
    return true
}

suspend fun Xhci.updateEp0Mps(slotId: UByte, mps: UInt): Boolean {
    val inContext = requireNotNull(DmaBuffer.allocate())
    try {
        val source = slots[slotId.toInt()].outContext ?: return false

        val copyBytes = (contextSize * 2).toULong()
        memcpy(
            inContext.view<UByteVar>() + contextSize,
            source.view<UByteVar>(),
            copyBytes,
        )

        val ctrlContext = InputControlContext(inContext)
        ctrlContext.addFlags = 1u shl 1

        val ep0Context = EndpointContext(inContext, 1, contextSize)
        ep0Context.info2 = (ep0Context.info2 and 0xffff0000u.inv()) or ((mps and 0xffffu) shl 16)

        val command = Trb.newEvaluateContext(inContext.physicalAddress, slotId)
        val (code, _) = sendCommand(command) ?: return false

        if (code != 1u) {
            println("Evaluate Context failed: $code")
            return false
        }
    } finally {
        inContext.free()
    }
    return true
}

internal fun Xhci.setupOneEndpoint(
    slotId: UByte,
    contextBuffer: DmaBuffer,
    endpoint: UsbEndpoint,
): UInt? {
    val address = endpoint.desc.endpointAddress
    val endpointNumber = address and 0x0fu.toUByte()
    val isIn = address and REQ_DIR_IN != 0u.toUByte()

    val dci = if (isIn) {
        endpointNumber.toUInt() * 2u + 1u
    } else {
        endpointNumber.toUInt() * 2u
    }
    if (dci < 2u || dci > 31u) {
        return null
    }

    val newEndpoint = Endpoint()
    slots[slotId.toInt()].endpoints[dci.toInt()] = newEndpoint

    val attributes = endpoint.desc.attributes and 0x3u.toUByte()
    val endpointType = if (isIn) {
        attributes.toUInt() + 4u
    } else {
        attributes.toUInt()
    }

    val rawMps = endpoint.desc.maxPacketSize
    val mps = rawMps and 0x7ffu.toUShort()
    val speed = slots[slotId.toInt()].speed

    val errorCount = if (attributes == EP_TYPE_ISO) 0u else 3u

    val averageTrbLength = when (attributes) {
        EP_TYPE_CONTROL -> 8u
        EP_TYPE_INT -> 1024u
        EP_TYPE_ISO -> mps.toUInt()
        else -> 3072u
    }

    val isIsochronousOrInterrupt = attributes == EP_TYPE_INT || attributes == EP_TYPE_ISO
    val hsBurst = if (isIsochronousOrInterrupt) (rawMps.toUInt() shr 11) and 0x03u else 0u
    val ssBurst = endpoint.ssDesc?.maxBurst?.toUInt() ?: 0u
    val maxBurst = when (speed) {
        SPEED_HIGH.toUInt() -> hsBurst
        SPEED_SUPER.toUInt() -> ssBurst
        else -> 0u
    }

    val rawInterval = endpoint.desc.interval
    val lsFsInterval = if (rawInterval > 0u.toUByte()) {
        (31 - rawInterval.toUInt().countLeadingZeroBits() + 3).toUInt()
    } else {
        0u
    }
    val hsSsInterval = if (rawInterval > 0u.toUByte()) {
        rawInterval.toUInt() - 1u
    } else {
        0u
    }
    var interval = 0u
    if (isIsochronousOrInterrupt) {
        interval = when (speed) {
            SPEED_LOW.toUInt(), SPEED_FULL.toUInt() -> lsFsInterval
            else -> hsSsInterval
        }
    }

    val isSuperSpeedIsochronous = speed == SPEED_SUPER.toUInt() && attributes == EP_TYPE_ISO
    val ssIsoMult = endpoint.ssDesc?.let { (it.attributes and 0x3u.toUByte()).toUInt() } ?: 0u
    val mult = if (isSuperSpeedIsochronous) ssIsoMult else 0u

    var maxEsitPayload = 0u
    if (isIsochronousOrInterrupt) {
        maxEsitPayload = when (speed) {
            SPEED_SUPER.toUInt() -> endpoint.ssDesc?.bytesPerInterval?.toUInt()
                ?: (mps.toUInt() * (maxBurst + 1u))
            else -> mps.toUInt() * (maxBurst + 1u)
        }
    }

    val endpointContext = EndpointContext(contextBuffer, dci.toInt(), contextSize)
    endpointContext.setEpType(endpointType)
    endpointContext.setInterval(interval)
    endpointContext.setMult(mult)
    endpointContext.setMaxBurst(maxBurst)
    endpointContext.setErrorCount(errorCount)
    endpointContext.setDequeuePointer(newEndpoint.ring.physicalAddress or 1uL)
    endpointContext.setMaxPacketSize(mps.toUInt())
    endpointContext.setAverageTrbLen(averageTrbLength)
    endpointContext.setMaxEsitPayload(maxEsitPayload)

    val ctrlContext = InputControlContext(contextBuffer)
    ctrlContext.addFlags = ctrlContext.addFlags or (1u shl dci.toInt())

    return dci
}

suspend fun Xhci.cleanupSlotOnFailure(slotId: UByte) {
    println("Cleaning up resources for slot $slotId")

    val slot = slots[slotId.toInt()]
    if (slot.active) {
        disableSlot(slotId)
    }
    slot.active = false

    slot.outContext?.free()
    slot.outContext = null

    val dcbaa = this.dcbaa
    if (dcbaa != null) {
        dcbaa.view<ULongVar>()[slotId.toInt()] = 0uL
    }

    for (index in 1 until MAX_ENDPOINTS) {
        slot.endpoints[index]?.free()
        slot.endpoints[index] = null
    }

    println("Slot $slotId cleanup complete")
}
