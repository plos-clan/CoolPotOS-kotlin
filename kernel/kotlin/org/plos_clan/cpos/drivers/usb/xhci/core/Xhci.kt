package org.plos_clan.cpos.drivers.usb.xhci.core

import org.plos_clan.cpos.coroutines.KernelOneShot
import org.plos_clan.cpos.coroutines.KernelSemaphore
import org.plos_clan.cpos.drivers.usb.bus.HostController
import org.plos_clan.cpos.drivers.usb.xhci.regs.Capability
import org.plos_clan.cpos.drivers.usb.xhci.regs.Doorbell
import org.plos_clan.cpos.drivers.usb.xhci.regs.Operational
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.mem.MmioAddress

class Xhci(baseAddress: MmioAddress) {
    val capability = Capability(baseAddress)
    val operational = Operational(baseAddress + capability.length.toULong())
    val doorbell = Doorbell(baseAddress + capability.doorbellOffset.toULong())
    val contextSize: Int = if (capability.uses64ByteContext) 64 else 32

    val hostController: HostController = XhciHostController(this)

    internal var dcbaa: MmioRegion? = null
    internal lateinit var commandRing: CommandRing
    internal lateinit var eventRing: EventRing
    internal val slots = Array(MAX_SLOTS) { Slot() }

    internal val portSemaphore = KernelSemaphore(0)
    private val commandSemaphore = KernelSemaphore(MAX_SLOTS)
    private val commandPromises = Array(MAX_SLOTS) { KernelOneShot<Trb>() }
    internal val portToSlot = UByteArray(MAX_SLOTS)

    fun handleIrq() {
        var needsUpdate = false
        var processed = 0
        while (processed < 16) {
            val event = eventRing.pop() ?: break
            handleOneEvent(event)
            needsUpdate = true
            processed++
        }
        if (needsUpdate) {
            eventRing.updateErdp()
        }
    }

    suspend fun testCommandRing(): Boolean {
        val command = Trb.newNoOpCmd()
        val (code, _) = sendCommand(command)
            ?: run {
                println("No op command timeout or error")
                return false
            }

        if (code == 1u) {
            println("xHCI command ring verified")
        } else {
            println("No op failed with code: $code")
            return false
        }
        return true
    }

    internal suspend fun enableSlot(): UByte? {
        val (code, slotId) = sendCommand(Trb.newEnableSlot()) ?: return null

        if (code != 1u) {
            println("Failed to enable slot: $code")
            return null
        }

        return slotId
    }

    internal suspend fun disableSlot(slotId: UByte) {
        sendCommand(Trb.newDisableSlot(slotId))
            ?: run {
                println("Failed to disable slot: $slotId")
                return
            }
    }

    suspend fun sendCommand(trb: Trb): Pair<UInt, UByte>? {
        commandSemaphore.acquire()
        val index = commandRing.enqueueIndex
        commandPromises[index].reset()

        commandRing.enqueue(trb)
        doorbell.ring(0u, 0u)

        val event = commandPromises[index].recv()
        commandSemaphore.release()
        return event.completionCode to event.slotId
    }

    private fun handleOneEvent(event: Trb) {
        when (event.type) {
            TRB_TRANSFER_EVENT -> {
                val slotId = event.slotId
                val dci = event.endpointId

                val targetPhysical = (event.paramHigh.toULong() shl 32) or event.paramLow.toULong()
                val endpoint = slots[slotId.toInt()].endpoints[dci.toInt()] ?: return

                if (targetPhysical < endpoint.ring.physicalAddress) {
                    return
                }
                val index =
                    ((targetPhysical - endpoint.ring.physicalAddress) / TRB_SIZE_BYTES.toULong()).toUInt()

                if (dci == 1u) {
                    if (index < endpoint.ring.capacity.toUInt()) {
                        endpoint.promises[index.toInt()].send(event)
                    }
                } else {
                    completeTransfer(slotId, dci, event.completionCode, event.transferLength)
                }
            }
            TRB_PORT_STATUS_CHANGE -> {
                portSemaphore.release()
            }
            TRB_CMD_COMPLETION -> {
                val targetPhysical = (event.paramHigh.toULong() shl 32) or event.paramLow.toULong()
                if (targetPhysical < commandRing.physicalAddress) {
                    return
                }
                val index =
                    ((targetPhysical - commandRing.physicalAddress) / TRB_SIZE_BYTES.toULong()).toUInt()
                if (index < commandRing.capacity.toUInt()) {
                    commandPromises[index.toInt()].send(event)
                }
            }
            else -> {
                println("Ignored event type ${event.type}")
            }
        }
    }
}
