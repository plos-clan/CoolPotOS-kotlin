@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.unet

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelOneShot
import org.plos_clan.cpos.coroutines.KernelSemaphore
import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.ControlTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.GeneralTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.bus.submitControl
import org.plos_clan.cpos.drivers.usb.bus.submitTransfer
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_OUT
import org.plos_clan.cpos.drivers.usb.defs.REQ_REC_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.REQ_TYPE_CLASS
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.readU32
import platform.posix.memcpy
import platform.posix.memset

internal class RndisControlChannel private constructor(
    private val controlInterface: UsbInterface,
    private val notificationEndpoint: UByte,
    private val commandBuffer: MmioRegion,
    private val notificationBuffer: MmioRegion,
) {
    private enum class NotificationState {
        NOT_STARTED,
        RUNNING,
        STOPPING,
        STOPPED,
    }

    private sealed interface PollResult {
        data object Retry : PollResult
        data object Failed : PollResult
        data class Complete(val response: RndisResponse) : PollResult
    }

    private val lock = KernelSemaphore(1)
    private val notificationCompletion = KernelOneShot<CompletionEvent>()
    private val notificationStopped = KernelOneShot<Unit>()
    private var acceptingCommands = true
    private var notificationState = NotificationState.NOT_STARTED
    private var nextRequestId = 1u

    var mediaConnected = false
        private set

    suspend fun initialize(maximumTransferSize: UInt): RndisResponse.Initialize? =
        exchange(RndisCommand.Initialize(requestId(), maximumTransferSize))
            as? RndisResponse.Initialize

    suspend fun query(oid: RndisOid, information: ByteArray = ByteArray(0)): ByteArray? {
        val response = exchange(RndisCommand.Query(requestId(), oid.value, information))
            as? RndisResponse.Query
        if (response?.status == RndisStatus.SUCCESS) return response.information
        println("RNDIS: query ${oid.name} failed")
        return null
    }

    suspend fun queryUInt(oid: RndisOid): UInt? {
        val information = query(oid) ?: return null
        if (information.size == UInt.SIZE_BYTES) {
            return LittleEndianBuffer(information).readU32(0)
        }
        println("RNDIS: query ${oid.name} returned ${information.size} bytes")
        return null
    }

    suspend fun set(oid: RndisOid, information: ByteArray): Boolean {
        val response = exchange(RndisCommand.Set(requestId(), oid.value, information))
            as? RndisResponse.Set
        return response?.status == RndisStatus.SUCCESS
    }

    suspend fun setPacketFilter(filter: UInt): Boolean = set(
        RndisOid.CURRENT_PACKET_FILTER,
        ByteArray(UInt.SIZE_BYTES).also { LittleEndianBuffer(it).writeU32(0, filter) },
    )

    suspend fun refreshMediaState(): Boolean {
        val state = queryUInt(RndisOid.MEDIA_CONNECT_STATUS) ?: return false
        mediaConnected = state == MEDIA_STATE_CONNECTED
        return true
    }

    suspend fun reset(): RndisResponse.Reset? =
        exchange(RndisCommand.Reset) as? RndisResponse.Reset

    suspend fun keepAlive(): Boolean =
        (exchange(RndisCommand.KeepAlive(requestId())) as? RndisResponse.KeepAlive)
            ?.status == RndisStatus.SUCCESS

    suspend fun halt() {
        lock.acquire()
        try {
            if (acceptingCommands) sendEncapsulated(RndisCommand.Halt(requestId()).encode())
            acceptingCommands = false
        } finally {
            lock.release()
        }
    }

    fun startNotifications() {
        check(notificationState == NotificationState.NOT_STARTED)
        notificationState = NotificationState.RUNNING
        KernelCoroutines.launch("rndis-status-${controlInterface.device.slotId}") {
            try {
                notificationLoop()
            } finally {
                notificationState = NotificationState.STOPPED
                notificationStopped.trySend(Unit)
            }
        }
    }

    fun handleCompletion(event: CompletionEvent): Boolean {
        if (event.endpointAddress != notificationEndpoint) return false
        notificationCompletion.trySend(event)
        return true
    }

    fun quiesce() {
        acceptingCommands = false
        if (notificationState != NotificationState.RUNNING) return
        notificationState = NotificationState.STOPPING
        notificationCompletion.trySend(DRIVER_STOPPED_EVENT)
    }

    suspend fun close() {
        quiesce()
        if (notificationState == NotificationState.STOPPING) notificationStopped.recv()
        lock.acquire()
        lock.release()
        commandBuffer.free()
        notificationBuffer.free()
    }

    private suspend fun notificationLoop() {
        while (notificationState == NotificationState.RUNNING) {
            memset(notificationBuffer.view<UByteVar>(), 0, RESPONSE_AVAILABLE_SIZE.toULong())
            controlInterface.device.submitTransfer(
                GeneralTransferArgs(
                    endpointAddress = notificationEndpoint,
                    bufferPhysicalAddress = notificationBuffer.physicalAddress,
                    length = RESPONSE_AVAILABLE_SIZE,
                ),
            ) ?: return

            val event = notificationCompletion.recv()
            if (notificationState != NotificationState.RUNNING) return
            if (!isResponseAvailable(event)) {
                println("RNDIS: invalid response notification (${event.status})")
                return
            }
            drainUnsolicitedResponses()
        }
    }

    private fun isResponseAvailable(event: CompletionEvent): Boolean {
        if (!event.status.successful) return false
        val received = RESPONSE_AVAILABLE_SIZE - minOf(event.residualLength, RESPONSE_AVAILABLE_SIZE)
        return received == RESPONSE_AVAILABLE_SIZE &&
            notificationBuffer.view<UByteVar>().readU32(0) == RESPONSE_AVAILABLE
    }

    private suspend fun drainUnsolicitedResponses() {
        lock.acquire()
        try {
            while (acceptingCommands) {
                when (val parsed = receiveEncapsulated()) {
                    RndisParseResult.Empty -> return
                    is RndisParseResult.Invalid -> {
                        println("RNDIS: ${parsed.reason}")
                        return
                    }
                    is RndisParseResult.Valid -> if (!handleUnsolicited(parsed.response)) {
                        println("RNDIS: unexpected ${parsed.response.type}")
                    }
                }
            }
        } finally {
            lock.release()
        }
    }

    private suspend fun exchange(command: RndisCommand): RndisResponse? {
        lock.acquire()
        try {
            if (!acceptingCommands || !sendEncapsulated(command.encode())) return null
            repeat(RESPONSE_POLL_ATTEMPTS) { attempt ->
                if (!acceptingCommands) return null
                when (val result = poll(command)) {
                    PollResult.Failed -> return null
                    PollResult.Retry -> if (attempt != RESPONSE_POLL_ATTEMPTS - 1) {
                        delay(RESPONSE_POLL_INTERVAL_MILLIS)
                    }
                    is PollResult.Complete -> return result.response
                }
            }
            println("RNDIS: ${command.type} response timed out")
            return null
        } finally {
            lock.release()
        }
    }

    private suspend fun poll(command: RndisCommand): PollResult =
        when (val parsed = receiveEncapsulated()) {
            RndisParseResult.Empty -> PollResult.Retry
            is RndisParseResult.Invalid -> {
                println("RNDIS: ${parsed.reason}")
                PollResult.Failed
            }
            is RndisParseResult.Valid -> when {
                handleUnsolicited(parsed.response) -> PollResult.Retry
                command.accepts(parsed.response) -> PollResult.Complete(parsed.response)
                else -> PollResult.Retry
            }
        }

    private suspend fun handleUnsolicited(response: RndisResponse): Boolean = when (response) {
        is RndisResponse.IndicateStatus -> {
            when (response.status) {
                RndisStatus.MEDIA_CONNECT -> mediaConnected = true
                RndisStatus.MEDIA_DISCONNECT -> mediaConnected = false
                else -> println("RNDIS: status 0x${response.status.toString(16)}")
            }
            true
        }
        is RndisResponse.DeviceKeepAlive -> {
            if (!sendEncapsulated(RndisCommand.KeepAliveComplete(response.requestId).encode())) {
                println("RNDIS: failed to answer device keep-alive")
            }
            true
        }
        else -> false
    }

    private suspend fun sendEncapsulated(message: ByteArray): Boolean {
        if (message.size > commandBuffer.byteLength.toInt() || message.size > UShort.MAX_VALUE.toInt()) {
            return false
        }
        message.usePinned { bytes ->
            memcpy(commandBuffer.view<UByteVar>(), bytes.addressOf(0), message.size.toULong())
        }
        return controlInterface.device.submitControl(
            ControlTransferArgs(
                setup = SetupPacket(
                    requestType = REQ_DIR_OUT or REQ_TYPE_CLASS or REQ_REC_INTERFACE,
                    request = SEND_ENCAPSULATED_COMMAND,
                    index = controlInterface.desc.interfaceNumber.toUShort(),
                    length = message.size.toUShort(),
                ),
                bufferPhysicalAddress = commandBuffer.physicalAddress,
            ),
        ) != null
    }

    private suspend fun receiveEncapsulated(): RndisParseResult {
        val pointer = commandBuffer.view<UByteVar>()
        memset(pointer, 0, commandBuffer.byteLength)
        val transferLength = minOf(commandBuffer.byteLength, UShort.MAX_VALUE.toULong()).toUShort()
        controlInterface.device.submitControl(
            ControlTransferArgs(
                setup = SetupPacket(
                    requestType = REQ_DIR_IN or REQ_TYPE_CLASS or REQ_REC_INTERFACE,
                    request = GET_ENCAPSULATED_RESPONSE,
                    index = controlInterface.desc.interfaceNumber.toUShort(),
                    length = transferLength,
                ),
                bufferPhysicalAddress = commandBuffer.physicalAddress,
            ),
        ) ?: return RndisParseResult.Invalid("failed to fetch control response")

        val type = pointer.readU32(0)
        if (type == 0u) return RndisParseResult.Empty
        val messageLength = pointer.readU32(4).toULong()
        if (messageLength < RNDIS_HEADER_SIZE.toULong() || messageLength > commandBuffer.byteLength) {
            return RndisParseResult.Invalid("invalid message length $messageLength")
        }
        return RndisControlCodec.decode(pointer.readBytes(messageLength.toInt()))
    }

    private fun requestId(): UInt {
        val current = nextRequestId
        nextRequestId++
        if (nextRequestId == 0u) nextRequestId = 1u
        return current
    }

    companion object {
        private const val SEND_ENCAPSULATED_COMMAND: UByte = 0x00u
        private const val GET_ENCAPSULATED_RESPONSE: UByte = 0x01u
        private const val RESPONSE_AVAILABLE = 1u
        private const val RESPONSE_AVAILABLE_SIZE = 8u
        private const val RNDIS_HEADER_SIZE = 8
        private const val MEDIA_STATE_CONNECTED = 0u
        private const val RESPONSE_POLL_ATTEMPTS = 10
        private const val RESPONSE_POLL_INTERVAL_MILLIS = 40L
        private val DRIVER_STOPPED_EVENT = CompletionEvent(0u, TransferStatus.DRIVER_ERROR, 0u)

        fun create(
            controlInterface: UsbInterface,
            notificationEndpoint: UByte,
        ): RndisControlChannel? {
            val commandBuffer = MmioRegion.allocate() ?: return null
            val notificationBuffer = MmioRegion.allocate() ?: run {
                commandBuffer.free()
                return null
            }
            return RndisControlChannel(
                controlInterface,
                notificationEndpoint,
                commandBuffer,
                notificationBuffer,
            )
        }
    }
}
