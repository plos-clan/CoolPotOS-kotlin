@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.input

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.drivers.usb.adapt.hid.HidKind
import org.plos_clan.cpos.drivers.usb.adapt.hid.HidParser
import kotlin.test.Test
import kotlin.test.assertEquals

class HidPointerTest {
    @Test
    fun numberedAbsoluteReportPreservesApplicationAndUnsignedRange() {
        val descriptor = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xa1.toByte(), 0x01,
            0x85.toByte(), 0x07, 0x09, 0x01, 0xa1.toByte(), 0x00,
            0x15, 0x00, 0x26, 0xff.toByte(), 0xff.toByte(),
            0x75, 0x10, 0x95.toByte(), 0x02, 0x09, 0x30, 0x09, 0x31,
            0x81.toByte(), 0x02, 0xc0.toByte(), 0xc0.toByte(),
            0x09, 0x04, 0xa1.toByte(), 0x01, 0x09, 0x30,
            0x95.toByte(), 0x01, 0x81.toByte(), 0x02, 0xc0.toByte(),
        )
        val parsed = descriptor.usePinned { pinned ->
            val parser = HidParser(pinned.addressOf(0).reinterpret(), descriptor.size.toUShort())
            parser.parse()
        }
        val report = checkNotNull(parsed.reports[7u])
        assertEquals(7u, report.sizeBytes(HidKind.INPUT))
        assertEquals(listOf(0x10002u, 0x10002u, 0x10004u), report.fields.map { it.applicationUsage })
        assertEquals(listOf(8u, 24u, 40u), report.fields.map { it.bitOffset })
        assertEquals(listOf(65535, 65535, 65535), report.fields.map { it.logicalMax })
        val packet = byteArrayOf(7, 0xfe.toByte(), 0xff.toByte(), 0, 0, 0, 0)
        packet.usePinned { pinned ->
            val data = pinned.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>()
            assertEquals(65534u, report.fields[0].value(data, 0u))
            assertEquals(0u, report.fields[1].value(data, 0u))
        }
    }
}
