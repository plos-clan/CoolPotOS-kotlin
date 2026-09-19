package org.plos_clan.cpos.drivers.usb.adapt.msc

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.plos_clan.cpos.drivers.scsi.ScsiDirection
import org.plos_clan.cpos.drivers.scsi.ScsiResult
import org.plos_clan.cpos.drivers.scsi.ScsiSense
import org.plos_clan.cpos.drivers.scsi.ScsiStatus
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbBuffer
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.drivers.usb.bus.transfer
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_BULK
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.DmaBuffer
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.utils.LittleEndianBuffer

class BotTransport
private constructor(
    iface: UsbInterface,
    allocate: (Int) -> DmaBuffer?,
    private val input: UByte,
    private val output: UByte,
) : StorageTransport(iface, allocate) {
    override val endpoints = listOf(input, output)
    override val slots = listOf(Slot(1))
    private var nextTag = 0u

    override suspend fun logicalUnits(): List<ULong> =
        slots[0].lock.withLock {
            if (!connected) return@withLock emptyList()
            val memory = slots[0].metadata() ?: return@withLock emptyList()
            val setup =
                SetupPacket(
                    0xa1u,
                    0xfeu,
                    index = iface.desc.interfaceNumber.toUShort(),
                    length = 1u,
                )
            val result =
                iface.device.transfer(
                    UsbTransfer(
                        0u,
                        listOf(UsbBuffer(memory.physicalAddress, 1u, memory.virtualAddress)),
                        setup = setup,
                    ),
                    5_000,
                )
            if (result.status == TransferStatus.STALL) {
                if (!iface.device.host.clearHalt(iface.device.slotId, 0u))
                    return@withLock emptyList()
                return@withLock listOf(0uL)
            }
            if (!result.successful || result.actualLength != 1u) return@withLock emptyList()
            val bytes = ByteArray(1)
            memory.copyTo(0, bytes, 0, 1)
            val maximum = bytes[0].toUByte().toInt()
            if (maximum > 15) return@withLock emptyList()
            List(maximum + 1) { it.toULong() shl 48 }
        }

    override suspend fun execute(
        lun: ULong,
        command: ByteArray,
        direction: ScsiDirection,
        buffer: IoBuffer?,
        offset: Int,
        length: Int,
    ): ScsiResult {
        if (
            command.size > 16 ||
                lun and 0xfff0ffffffffffffuL != 0uL ||
                !valid(command, direction, buffer, offset, length)
        )
            return ScsiResult(ScsiStatus.IO_ERROR)
        return slots[0].lock.withLock {
            withContext(NonCancellable) {
                if (!connected) return@withContext unavailable
                val result = exchange(lun, command, direction, buffer, offset, length)
                if (result.status != ScsiStatus.CHECK_CONDITION) return@withContext result
                val sense = ByteArray(252)
                val response =
                    exchange(
                        lun,
                        byteArrayOf(3, 0, 0, 0, 252.toByte(), 0),
                        ScsiDirection.IN,
                        ByteArrayBuffer(sense),
                        0,
                        sense.size,
                    )
                result.copy(
                    sense =
                        if (response.status == ScsiStatus.GOOD) {
                            ScsiSense.parse(sense.copyOf(response.actualLength))
                        } else null
                )
            }
        }
    }

    private suspend fun exchange(
        lun: ULong,
        command: ByteArray,
        direction: ScsiDirection,
        buffer: IoBuffer?,
        offset: Int,
        length: Int,
    ): ScsiResult {
        val slot = slots[0]
        val memory = slot.metadata() ?: return ScsiResult(ScsiStatus.NO_MEMORY)
        val tag = ++nextTag
        val wrapper = ByteArray(31)
        val fields = LittleEndianBuffer(wrapper)
        fields.writeU32(0, 0x43425355u)
        fields.writeU32(4, tag)
        fields.writeU32(8, length.toUInt())
        wrapper[12] = if (direction == ScsiDirection.IN) 0x80.toByte() else 0
        wrapper[13] = (lun shr 48).toByte()
        wrapper[14] = command.size.toByte()
        command.copyInto(wrapper, 15)
        memory.copyFrom(0, wrapper, 0, wrapper.size)
        val sent = slot.transfer(output, memory, 0, wrapper.size)
        if (!sent.successful || sent.actualLength != 31u) return recover()
        var actual = 0
        if (length != 0) {
            val endpoint = if (direction == ScsiDirection.IN) input else output
            val data = slot.data(endpoint, direction, checkNotNull(buffer), offset, length)
            actual = data.actualLength.toInt()
            if (data.status == TransferStatus.STALL) {
                if (!iface.device.host.clearHalt(iface.device.slotId, endpoint)) return recover()
            } else if (!data.successful) return recover()
        }
        var status = slot.transfer(input, memory, 64, 13)
        if (status.status == TransferStatus.STALL) {
            if (!iface.device.host.clearHalt(iface.device.slotId, input)) return recover()
            status = slot.transfer(input, memory, 64, 13)
        }
        if (!status.successful || status.actualLength != 13u) return recover()
        val bytes = ByteArray(13)
        memory.copyTo(64, bytes, 0, bytes.size)
        val csw = LittleEndianBuffer(bytes)
        val residue = csw.readU32(8)
        val code = bytes[12].toInt()
        if (
            csw.readU32(0) != 0x53425355u ||
                csw.readU32(4) != tag ||
                residue > length.toUInt() ||
                code !in 0..1
        )
            return recover()
        val completed = length - residue.toInt()
        if (completed > actual) return recover()
        return ScsiResult(if (code == 0) ScsiStatus.GOOD else ScsiStatus.CHECK_CONDITION, completed)
    }

    private suspend fun recover(): ScsiResult {
        if (!connected) return unavailable
        val setup = SetupPacket(0x21u, 0xffu, index = iface.desc.interfaceNumber.toUShort())
        val reset = iface.device.transfer(UsbTransfer(0u, setup = setup), 5_000).successful
        val host = iface.device.host
        val slot = iface.device.slotId
        val clearedIn = host.clearHalt(slot, input)
        val clearedOut = host.clearHalt(slot, output)
        if (!reset || !clearedIn || !clearedOut) stop()
        return unavailable
    }

    companion object {
        fun create(iface: UsbInterface, allocate: (Int) -> DmaBuffer?): BotTransport? {
            val input = iface.findEndpoint(EP_TYPE_BULK, true) ?: return null
            val output = iface.findEndpoint(EP_TYPE_BULK, false) ?: return null
            return BotTransport(
                iface,
                allocate,
                input.desc.endpointAddress,
                output.desc.endpointAddress,
            )
        }
    }
}
