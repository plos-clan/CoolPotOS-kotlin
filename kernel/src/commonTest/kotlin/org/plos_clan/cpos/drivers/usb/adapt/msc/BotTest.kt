package org.plos_clan.cpos.drivers.usb.adapt.msc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.plos_clan.cpos.drivers.scsi.ScsiDirection
import org.plos_clan.cpos.drivers.scsi.ScsiSense
import org.plos_clan.cpos.drivers.scsi.ScsiStatus
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.utils.LittleEndianBuffer

class BotTest {
    private class Device {
        val host = StorageHost().also { it.onSubmit = ::submit }
        val transport = checkNotNull(BotTransport.create(host.interfaceFor(false), host::allocate))
        val commands = mutableListOf<ByteArray>()
        var maxLun = 0
        var tag = 0u
        var length = 0
        var phase = 0
        var opcode = 0
        var residue = 0
        var fail = false
        var badTag = false
        var stallData = false
        var stallStatus = false
        var resets = 0

        fun submit(request: UsbTransfer) {
            val setup = request.setup
            if (setup != null) {
                when (setup.request.toInt()) {
                    0xfe ->
                        if (maxLun < 0) host.complete(request, status = TransferStatus.STALL)
                        else host.complete(request, byteArrayOf(maxLun.toByte()))
                    0xff -> {
                        resets++
                        host.complete(request)
                    }
                }
                return
            }
            if (request.endpointAddress == 2u.toUByte() && request.length == 31u) {
                val bytes = host.bytes(request)
                val fields = LittleEndianBuffer(bytes)
                assertEquals(0x43425355u, fields.readU32(0))
                tag = fields.readU32(4)
                length = fields.readU32(8).toInt()
                opcode = bytes[15].toUByte().toInt()
                commands.add(bytes.copyOfRange(15, 15 + bytes[14]))
                phase = if (length == 0) 2 else 1
                host.complete(request)
            } else if (phase == 1) {
                phase = 2
                if (stallData) {
                    stallData = false
                    residue = length
                    host.complete(request, status = TransferStatus.STALL)
                    return
                }
                val bytes =
                    if (opcode == 3)
                        ByteArray(18).also {
                            it[0] = 0x70
                            it[2] = 7
                            it[7] = 10
                            it[12] = 0x27
                        }
                    else ByteArray(length - residue) { 0x5a }
                if (opcode == 3) residue = length - bytes.size
                host.complete(request, bytes)
            } else {
                if (stallStatus) {
                    stallStatus = false
                    host.complete(request, status = TransferStatus.STALL)
                    return
                }
                val bytes = ByteArray(13)
                LittleEndianBuffer(bytes).apply {
                    writeU32(0, 0x53425355u)
                    writeU32(4, if (badTag) tag + 1u else tag)
                    writeU32(8, residue.toUInt())
                }
                bytes[12] = if (fail && opcode != 3) 1 else 0
                host.complete(request, bytes)
                residue = 0
            }
        }
    }

    @Test
    fun shortReadsUseCswResidueAndActualLength() = runBlocking {
        val device = Device().also { it.residue = 12 }
        val data = ByteArray(64)
        val result =
            device.transport.execute(
                0uL,
                byteArrayOf(0x12, 0, 0, 0, 64, 0),
                ScsiDirection.IN,
                ByteArrayBuffer(data),
                0,
                64,
            )
        assertEquals(ScsiStatus.GOOD, result.status)
        assertEquals(52, result.actualLength)
        assertContentEquals(ByteArray(52) { 0x5a }, data.copyOf(52))
        device.transport.close()
        assertTrue(device.host.memory.all { it.closed })
    }

    @Test
    fun failedCommandRetrievesSenseBeforeNextCommand() = runBlocking {
        val device = Device().also { it.fail = true }
        val result = device.transport.execute(0uL, ByteArray(6))
        assertEquals(ScsiStatus.CHECK_CONDITION, result.status)
        assertEquals(ScsiSense(7, 0x27, 0), result.sense)
        assertEquals(listOf(0, 3), device.commands.map { it[0].toInt() })
    }

    @Test
    fun dataAndStatusStallsAreCleared() = runBlocking {
        val device =
            Device().also {
                it.stallData = true
                it.stallStatus = true
            }
        val result =
            device.transport.execute(
                0uL,
                byteArrayOf(0x12),
                ScsiDirection.IN,
                ByteArrayBuffer(ByteArray(64)),
                0,
                64,
            )
        assertEquals(ScsiStatus.GOOD, result.status)
        assertEquals(0, result.actualLength)
        assertEquals(listOf<UByte>(0x81u, 0x81u), device.host.cleared)
        assertEquals(0, device.resets)
    }

    @Test
    fun invalidStatusRunsResetRecoveryWithoutReplayingWrite() = runBlocking {
        val device = Device().also { it.badTag = true }
        val result =
            device.transport.execute(
                0uL,
                byteArrayOf(0x2a),
                ScsiDirection.OUT,
                ByteArrayBuffer(ByteArray(512)),
                0,
                512,
            )
        assertEquals(ScsiStatus.IO_ERROR, result.status)
        assertEquals(1, device.resets)
        assertEquals(listOf<UByte>(0x81u, 2u), device.host.cleared)
        assertEquals(1, device.commands.size)
    }

    @Test
    fun maxLunStallMeansLunZeroAndMultiLunAddressesAreEncoded() = runBlocking {
        val device = Device().also { it.maxLun = -1 }
        assertEquals(listOf(0uL), device.transport.logicalUnits())
        assertEquals(listOf<UByte>(0u), device.host.cleared)
        device.maxLun = 2
        assertEquals(listOf(0uL, 1uL shl 48, 2uL shl 48), device.transport.logicalUnits())
    }
}
