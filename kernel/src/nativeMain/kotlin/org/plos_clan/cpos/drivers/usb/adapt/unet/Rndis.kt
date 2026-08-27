@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.unet

import kotlinx.coroutines.delay
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelOneShot
import org.plos_clan.cpos.coroutines.KernelSemaphore
import org.plos_clan.cpos.drivers.net.EthernetDevice
import org.plos_clan.cpos.drivers.net.EthernetDevices
import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.GeneralTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbDriver
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.bus.submitTransfer
import org.plos_clan.cpos.drivers.usb.defs.CLASS_COMM
import org.plos_clan.cpos.drivers.usb.defs.CLASS_DATA
import org.plos_clan.cpos.drivers.usb.defs.CLASS_WIRELESS
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_BULK
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_INT
import org.plos_clan.cpos.mem.MmioRegion

internal data class RndisUsbFunction(
    val controlInterface: UsbInterface,
    val dataInterface: UsbInterface,
    val notificationEndpoint: UByte,
    val bulkInEndpoint: UByte,
    val bulkOutEndpoint: UByte,
) {
    init {
        require(controlInterface.device === dataInterface.device)
    }
}

class RndisDevice private constructor(
    private val function: RndisUsbFunction,
    private val control: RndisControlChannel,
    private val receiveBuffer: MmioRegion,
    private val transmitBuffer: MmioRegion,
) : EthernetDevice(), UsbDriver {
    private enum class State {
        NEW,
        INITIALIZED,
        RUNNING,
        QUIESCING,
        DISCONNECTED,
    }

    private val transmitLock = KernelSemaphore(1)
    private val receiveCompletion = KernelOneShot<CompletionEvent>()
    private val receiveStopped = KernelOneShot<Unit>()
    private val transmitCompletion = KernelOneShot<CompletionEvent>()
    private lateinit var transfer: RndisTransferParameters
    private var receiveActive = false
    private var state = State.NEW

    override var macAddress = MacAddress.ZERO
        private set
    override var maximumFrameSize = 0u
        private set
    override var linkSpeedBitsPerSecond = 0uL
        private set
    override val linkUp: Boolean
        get() = control.mediaConnected

    internal suspend fun start(): Boolean {
        check(state == State.NEW)
        val initialized = control.initialize(receiveBuffer.byteLength.toUInt()) ?: return false
        transfer = RndisTransferParameters.negotiate(
            initialized,
            receiveBuffer.byteLength.toUInt(),
        ) ?: run {
            println("RNDIS: invalid initialize completion")
            return false
        }
        state = State.INITIALIZED

        val maximumPayload = control.queryUInt(RndisOid.MAXIMUM_FRAME_SIZE) ?: return false
        val maximumTotal = control.queryUInt(RndisOid.MAXIMUM_TOTAL_SIZE) ?: return false
        maximumFrameSize = transfer.maximumFrameSize(
            maximumPayload,
            maximumTotal,
            transmitBuffer.byteLength,
        ) ?: run {
            println("RNDIS: device cannot carry an Ethernet frame")
            return false
        }

        val address = control.query(RndisOid.CURRENT_ADDRESS)
            ?: control.query(RndisOid.PERMANENT_ADDRESS)
            ?: return false
        macAddress = MacAddress.from(address)?.takeIf(MacAddress::isUnicast) ?: run {
            println("RNDIS: invalid Ethernet address")
            return false
        }

        linkSpeedBitsPerSecond = control.queryUInt(RndisOid.LINK_SPEED)
            ?.toULong()?.times(100uL) ?: return false
        if (!control.refreshMediaState() ||
            !control.setPacketFilter(RndisPacketFilter.DEFAULT)
        ) return false

        state = State.RUNNING
        EthernetDevices.register(this)
        control.startNotifications()
        launchReceiveLoop()
        launchKeepAliveLoop()
        println(
            "RNDIS: $macAddress, frame $maximumFrameSize, " +
                "link ${linkSpeedBitsPerSecond / 1_000_000uL} Mbit/s",
        )
        return true
    }

    override suspend fun transmit(frame: ByteArray): Boolean {
        val length = frame.size.toUInt()
        if (state != State.RUNNING ||
            length < EthernetDevice.HEADER_SIZE.toUInt() || length > maximumFrameSize
        ) return false

        transmitLock.acquire()
        try {
            if (state != State.RUNNING) return false
            val messageLength = RndisPacketCodec.encode(
                transmitBuffer.view(),
                transmitBuffer.byteLength.toUInt(),
                frame,
                transfer.alignmentFactor,
            ) ?: return false
            function.dataInterface.device.submitTransfer(
                GeneralTransferArgs(
                    endpointAddress = function.bulkOutEndpoint,
                    bufferPhysicalAddress = transmitBuffer.physicalAddress,
                    length = messageLength,
                ),
            ) ?: return false

            val event = transmitCompletion.recv()
            return state == State.RUNNING &&
                event.status == TransferStatus.COMPLETED && event.residualLength == 0u
        } finally {
            transmitLock.release()
        }
    }

    suspend fun reset(): Boolean {
        if (state != State.RUNNING) return false
        val response = control.reset() ?: return false
        return response.status == RndisStatus.SUCCESS &&
            (!response.addressingReset || control.setPacketFilter(RndisPacketFilter.DEFAULT))
    }

    internal suspend fun stop() {
        if (state != State.INITIALIZED) return
        control.halt()
        state = State.NEW
    }

    override fun quiesce() {
        if (state == State.QUIESCING || state == State.DISCONNECTED) return
        state = State.QUIESCING
        EthernetDevices.unregister(this)
        control.quiesce()
        val stopped = CompletionEvent(0u, TransferStatus.DRIVER_ERROR, 0u)
        receiveCompletion.trySend(stopped)
        transmitCompletion.trySend(stopped)
    }

    override suspend fun disconnect() {
        if (state == State.DISCONNECTED) return
        quiesce()
        if (receiveActive) receiveStopped.recv()
        transmitLock.acquire()
        transmitLock.release()
        control.close()
        receiveBuffer.free()
        transmitBuffer.free()
        state = State.DISCONNECTED
        println("RNDIS: disconnected")
    }

    override fun handleCompletion(event: CompletionEvent) {
        if (control.handleCompletion(event)) return
        when (event.endpointAddress) {
            function.bulkInEndpoint -> receiveCompletion.trySend(event)
            function.bulkOutEndpoint -> transmitCompletion.trySend(event)
            else -> {}
        }
    }

    private fun launchReceiveLoop() {
        receiveActive = true
        KernelCoroutines.launch("rndis-rx-${function.controlInterface.device.slotId}") {
            try {
                while (state == State.RUNNING) {
                    function.dataInterface.device.submitTransfer(
                        GeneralTransferArgs(
                            endpointAddress = function.bulkInEndpoint,
                            bufferPhysicalAddress = receiveBuffer.physicalAddress,
                            length = transfer.transferSize,
                        ),
                    ) ?: run {
                        println("RNDIS: failed to submit receive transfer")
                        return@launch
                    }

                    val event = receiveCompletion.recv()
                    if (state != State.RUNNING) return@launch
                    if (!event.status.successful) {
                        println("RNDIS: receive transfer failed (${event.status})")
                        continue
                    }

                    val received = transfer.transferSize -
                        minOf(event.residualLength, transfer.transferSize)
                    if (received != 0u && !RndisPacketCodec.consume(
                            receiveBuffer.view(),
                            received,
                            maximumFrameSize,
                        ) { frame, length -> receive(frame, length) }
                    ) println("RNDIS: malformed packet message")
                }
            } finally {
                receiveActive = false
                receiveStopped.trySend(Unit)
            }
        }
    }

    private fun launchKeepAliveLoop() {
        KernelCoroutines.launch("rndis-keepalive-${function.controlInterface.device.slotId}") {
            while (state == State.RUNNING) {
                delay(KEEP_ALIVE_INTERVAL_MILLIS)
                if (state == State.RUNNING && !control.keepAlive()) {
                    println("RNDIS: keep-alive failed")
                }
            }
        }
    }

    companion object {
        private const val TRANSFER_BUFFER_PAGES = 4uL
        private const val KEEP_ALIVE_INTERVAL_MILLIS = 5_000L

        internal fun create(function: RndisUsbFunction): RndisDevice? {
            val receiveBuffer = MmioRegion.allocate(TRANSFER_BUFFER_PAGES) ?: return null
            val transmitBuffer = MmioRegion.allocate(TRANSFER_BUFFER_PAGES) ?: run {
                receiveBuffer.free()
                return null
            }
            val control = RndisControlChannel.create(
                function.controlInterface,
                function.notificationEndpoint,
            ) ?: run {
                transmitBuffer.free()
                receiveBuffer.free()
                return null
            }
            return RndisDevice(function, control, receiveBuffer, transmitBuffer)
        }
    }
}

suspend fun probeRndis(interface_: UsbInterface): UsbDriver? {
    val descriptor = interface_.desc
    val matchesWireless = interface_.matches(
        CLASS_WIRELESS,
        0x01u.toUByte(),
        0x03u.toUByte(),
    )
    val matchesCdc = interface_.matches(CLASS_COMM, 0x02u.toUByte(), 0xffu.toUByte())
    if (!matchesWireless && !matchesCdc) return null

    val dataInterface = interface_.findAssociatedInterface(CLASS_DATA) ?: run {
        println("RNDIS: control interface ${descriptor.interfaceNumber} has no CDC data interface")
        return null
    }
    val notification = interface_.findEndpoint(EP_TYPE_INT, true) ?: run {
        println("RNDIS: no response notification endpoint")
        return null
    }
    val bulkIn = dataInterface.findEndpoint(EP_TYPE_BULK, true) ?: run {
        println("RNDIS: no bulk IN endpoint")
        return null
    }
    val bulkOut = dataInterface.findEndpoint(EP_TYPE_BULK, false) ?: run {
        println("RNDIS: no bulk OUT endpoint")
        return null
    }

    val function = RndisUsbFunction(
        interface_,
        dataInterface,
        notification.desc.endpointAddress,
        bulkIn.desc.endpointAddress,
        bulkOut.desc.endpointAddress,
    )
    val device = RndisDevice.create(function) ?: return null
    interface_.driver = device
    dataInterface.driver = device
    if (device.start()) return device

    device.stop()
    if (interface_.driver === device) interface_.driver = null
    if (dataInterface.driver === device) dataInterface.driver = null
    device.disconnect()
    return null
}
