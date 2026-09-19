package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.plos_clan.cpos.drivers.usb.bus.UsbBuffer
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket

class DescriptorTest {
    @Test
    fun splitsAtPhysicalBoundariesAndChainsOneTransfer() {
        val descriptor = TransferDescriptor(listOf(UsbBuffer(0xfff0uL, 0x20030u)), 512u)
        assertEquals(listOf(16u, 65536u, 65536u, 32u), descriptor.trbs.map { it.bufferLength })
        assertEquals(
            listOf(0xfff0uL, 0x10000uL, 0x20000uL, 0x30000uL),
            descriptor.trbs.map { it.parameter },
        )
        assertTrue(
            descriptor.trbs.dropLast(1).all {
                it.control and TRB_CHAIN != 0u && it.control and TRB_IOC == 0u
            }
        )
        assertEquals(TRB_IOC, descriptor.trbs.last().control and (TRB_CHAIN or TRB_IOC))
        assertEquals(0u, (descriptor.trbs.last().status shr 17) and 31u)
    }

    @Test
    fun countsRemainingPacketsAcrossScatterGatherBuffers() {
        val descriptor =
            TransferDescriptor(listOf(UsbBuffer(0x1000uL, 100u), UsbBuffer(0x3000uL, 924u)), 512u)
        assertEquals(2u, (descriptor.trbs[0].status shr 17) and 31u)
        assertEquals(100u + 924u - 800u, descriptor.actualLength(1, 800u))
        assertEquals(0u, descriptor.actualLength(0, 100u))
    }

    @Test
    fun controlDataUsesDirectionAndWaitsForStatus() {
        val setup = SetupPacket(0x80u, 6u, length = 32u)
        val descriptor = TransferDescriptor(listOf(UsbBuffer(0xfff0uL, 32u)), 64u, setup)
        assertEquals(
            listOf(TRB_SETUP_STAGE, TRB_DATA_STAGE, TRB_NORMAL, TRB_STATUS_STAGE),
            descriptor.trbs.map { it.type },
        )
        assertEquals(1u shl 16, descriptor.trbs[1].control and (1u shl 16))
        assertEquals(0u, descriptor.trbs.last().control and (1u shl 16))
        assertEquals(24u, descriptor.actualLength(2, 8u))
        assertEquals(32u, descriptor.actualLength(3, 0u))
    }

    @Test
    fun zeroLengthTransfersHaveValidStages() {
        val bulk = TransferDescriptor(emptyList(), 512u)
        assertEquals(1, bulk.trbs.size)
        assertEquals(0u, bulk.trbs.single().bufferLength)
        val control = TransferDescriptor(emptyList(), 64u, SetupPacket(0u, 9u))
        assertEquals(listOf(TRB_SETUP_STAGE, TRB_STATUS_STAGE), control.trbs.map { it.type })
        assertEquals(1u shl 16, control.trbs.last().control and (1u shl 16))
    }

    @Test
    fun rejectsUnrepresentableBuffers() {
        assertFailsWith<IllegalArgumentException> {
            TransferDescriptor(listOf(UsbBuffer(ULong.MAX_VALUE, 2u)), 512u)
        }
        assertFailsWith<IllegalArgumentException> {
            TransferDescriptor(listOf(UsbBuffer(0uL, UInt.MAX_VALUE), UsbBuffer(0uL, 1u)), 512u)
        }
        assertFailsWith<IllegalArgumentException> { Trb.newNormal(0xffffuL, 2u) }
        assertFailsWith<IllegalArgumentException> { Trb.newNormal(0uL, 0x10001u) }
    }

    @Test
    fun dequeueCommandPreservesCycleAndStreamContextType() {
        val command = Trb.newSetDequeue(3u, 4, 0x1000uL, 7)
        assertEquals(0x1002uL, command.parameter)
        assertEquals(7u shl 16, command.status)
        assertEquals(4u, command.endpointId)
        assertEquals(3u.toUByte(), command.slotId)
        assertEquals(0x1001uL, Trb.newSetDequeue(3u, 4, 0x1001uL, 0).parameter)
    }
}
