package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelSemaphore
import org.plos_clan.cpos.drivers.usb.bus.HostController
import org.plos_clan.cpos.drivers.usb.bus.TransferResult
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.xhci.regs.Capability
import org.plos_clan.cpos.drivers.usb.xhci.regs.Doorbell
import org.plos_clan.cpos.drivers.usb.xhci.regs.Operational
import org.plos_clan.cpos.mem.MmioAddress
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.IrqSpinLock

class Xhci(baseAddress: MmioAddress, private val disableDma: () -> Unit) {
    val capability = Capability(baseAddress)
    val operational = Operational(baseAddress + capability.length.toULong())
    val doorbell = Doorbell(baseAddress + capability.doorbellOffset.toULong())
    val contextSize: Int = if (capability.uses64ByteContext) 64 else 32
    internal val memory =
        DmaMemory(
            if (capability.supports64BitAddressing) ULong.MAX_VALUE else UInt.MAX_VALUE.toULong()
        )

    val hostController: HostController = XhciHostController(this)

    internal var dcbaa: MmioRegion? = null
    internal lateinit var commandRing: ProducerRing
    internal lateinit var eventRing: EventRing
    internal val slots = Array(MAX_SLOTS) { Slot() }

    internal val portSemaphore = KernelSemaphore(0)
    internal val eventLock = IrqSpinLock()
    private val commandSpace = Channel<Unit>(Channel.CONFLATED)
    private val commands = mutableSetOf<CompletableDeferred<Trb>>()
    internal val completions = ArrayDeque<Pair<PendingTransfer, TransferResult>>()
    private val completionMutex = Mutex()
    private var failed = false
    private val events = KernelCoroutines.dispatcher.createEvent()
    internal val portToSlot = UByteArray(MAX_SLOTS)

    fun handleIrq() {
        events.signal()
    }

    internal suspend fun processEvents() {
        while (true) {
            val processed = eventLock.withLock {
                var count = 0
                while (count < 64) {
                    val event = eventRing.pop() ?: break
                    if (!failed) handleOneEvent(event)
                    count++
                }
                if (count != 0) eventRing.updateErdp()
                count
            }
            completeTransfers()
            if (processed == 64) yield() else events.await()
        }
    }

    suspend fun testCommandRing(): Unit? {
        val command = Trb.newNoOpCmd()
        val (code, _) = sendCommand(command)

        if (code == 1u) {
            println("xHCI command ring verified")
        } else {
            println("No op failed with code: $code")
            return null
        }
        return Unit
    }

    internal suspend fun enableSlot(): UByte? {
        val (code, slotId) = sendCommand(Trb.newEnableSlot())

        if (code != 1u) {
            println("Failed to enable slot: $code")
            return null
        }

        return slotId
    }

    internal suspend fun disableSlot(slotId: UByte): Boolean {
        val code = sendCommand(Trb.newDisableSlot(slotId)).first
        if (code != 1u && !failed) fail()
        return code == 1u || failed
    }

    suspend fun sendCommand(trb: Trb): Pair<UInt, UByte> =
        withContext(NonCancellable) {
            val promise = CompletableDeferred<Trb>()
            val entry =
                RingEntry(listOf(trb)) { _, event ->
                    commands.remove(promise)
                    promise.complete(event)
                    true
                }
            val event =
                withTimeoutOrNull(5_000) {
                    while (true) {
                        val submitted =
                            eventLock.withLock {
                                if (failed) return@withLock null
                                if (!commandRing.enqueue(entry)) return@withLock false
                                commands.add(promise)
                                doorbell.ring(0u, 0u)
                                true
                            } ?: return@withTimeoutOrNull null
                        if (submitted) break
                        commandSpace.receive()
                    }
                    promise.await()
                }
            if (event == null) {
                fail()
                return@withContext 0u to 0u.toUByte()
            }
            event.completionCode to event.slotId
        }

    internal suspend fun fail() {
        operational.stop()
        if (!waitHalted()) disableDma()
        eventLock.withLock {
            failed = true
            slots.forEach { slot ->
                slot.active = false
                slot.endpoints.forEach { it?.close(TransferStatus.DISCONNECTED) }
            }
            commands.forEach { it.complete(Trb()) }
            commands.clear()
            commandRing.queue.discard()
            commandSpace.trySend(Unit)
        }
        slots.forEach { it.usbDevice?.quiesce() }
        completeTransfers()
    }

    internal suspend fun completeTransfers() = completionMutex.withLock {
        while (true) {
            val (transfer, result) =
                eventLock.withLock { completions.removeFirstOrNull() } ?: return@withLock
            try {
                transfer.complete(result)
            } catch (failure: Throwable) {
                println("USB completion failed: $failure")
            }
        }
    }

    private fun handleOneEvent(event: Trb) {
        when (event.type) {
            TRB_TRANSFER_EVENT -> {
                val slotId = event.slotId
                val dci = event.endpointId

                val endpoint = slots[slotId.toInt()].endpoints[dci.toInt()] ?: return
                endpoint.complete(event)
            }
            TRB_PORT_STATUS_CHANGE -> {
                portSemaphore.release()
            }
            TRB_CMD_COMPLETION -> {
                val index = commandRing.indexOf(event.parameter, 0) ?: return
                commandRing.queue.complete(index, event)
                commandSpace.trySend(Unit)
            }
            else -> {
                println("Ignored event type ${event.type}")
            }
        }
    }
}
