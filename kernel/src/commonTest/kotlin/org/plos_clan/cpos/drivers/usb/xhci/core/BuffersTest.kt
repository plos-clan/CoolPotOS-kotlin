package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.plos_clan.cpos.drivers.usb.bus.UsbBuffer

class BuffersTest {
    @Test
    fun alignedStorageBuffersNeedNoCopies() {
        val planned =
            PacketBuffers(listOf(UsbBuffer(0x1000uL, 0x20000u)), 512u) { _, _ ->
                error("Unexpected copy")
            }
        assertEquals(listOf(0xf000u, 0x10000u, 0x1000u), planned.buffers.map { it.length })
    }

    @Test
    fun unalignedPhysicalBoundaryCopiesOnlyCrossingPacket() {
        val copied = mutableListOf<UInt>()
        val planned =
            PacketBuffers(listOf(UsbBuffer(0xfff0uL, 1024u, 0x1fff0uL)), 512u) { fragments, offset
                ->
                copied.add(offset)
                assertEquals(0x1fff0uL, fragments.first().virtualAddress)
                UsbBuffer(0x30000uL, fragments.sumOf { it.length.toULong() }.toUInt())
            }
        assertEquals(listOf(0u), copied)
        assertEquals(listOf(512u, 512u), planned.buffers.map { it.length })
        assertEquals(0x101f0uL, planned.buffers.last().physicalAddress)
    }

    @Test
    fun fragmentedPacketIsGatheredWithoutChangingPayloadOrder() {
        val pieces = mutableListOf<UsbBuffer>()
        val planned =
            PacketBuffers(
                listOf(UsbBuffer(0x1000uL, 100u), UsbBuffer(0x3000uL, 924u)),
                512u,
            ) { fragments, offset ->
                assertEquals(0u, offset)
                pieces.addAll(fragments)
                UsbBuffer(0x5000uL, 512u)
            }
        assertEquals(listOf(UsbBuffer(0x1000uL, 100u), UsbBuffer(0x3000uL, 412u)), pieces)
        assertEquals(listOf(UsbBuffer(0x5000uL, 512u), UsbBuffer(0x319cuL, 512u)), planned.buffers)
    }

    @Test
    fun tinyScatterEntriesCannotSplitPacketsAcrossRingSegments() {
        val source = List(2049) { UsbBuffer(0x1000uL + it.toULong() * 0x1000uL, 1u) }
        val planned =
            PacketBuffers(source, 1024u) { fragments, offset ->
                UsbBuffer(0x1000000uL + offset, fragments.sumOf { it.length.toULong() }.toUInt())
            }
        assertEquals(listOf(1024u, 1024u, 1u), planned.buffers.map { it.length })
        assertTrue(planned.buffers.dropLast(1).all { it.length % 1024u == 0u })
    }
}
