package org.plos_clan.cpos.drivers.usb.bus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.plos_clan.cpos.drivers.usb.defs.DESC_PIPE_USAGE

class ConfigurationTest {
    private fun configuration(vararg descriptors: ByteArray): ByteArray {
        val length = 9 + descriptors.sumOf { it.size }
        return byteArrayOf(
            9,
            2,
            length.toByte(),
            (length shr 8).toByte(),
            1,
            1,
            0,
            0x80.toByte(),
            50,
        ) + descriptors.fold(byteArrayOf()) { bytes, descriptor -> bytes + descriptor }
    }

    @Test
    fun preservesAlternateSettingsAndPipeDescriptors() {
        val bytes =
            configuration(
                byteArrayOf(9, 4, 0, 0, 1, 8, 6, 0x50, 0),
                byteArrayOf(7, 5, 0x81.toByte(), 2, 0, 4, 0),
                byteArrayOf(9, 4, 0, 1, 1, 8, 6, 0x62, 0),
                byteArrayOf(7, 5, 0x81.toByte(), 2, 0, 4, 0),
                byteArrayOf(6, 48, 15, 4, 0, 0),
                byteArrayOf(4, 0x24, 2, 0),
            )
        val configuration = assertNotNull(UsbConfiguration.parse(bytes))
        assertEquals(2, configuration.settings.size)
        val endpoint = configuration.settings[1].endpoints.single()
        assertEquals(16, endpoint.ssDesc?.maxStreams)
        assertEquals(DESC_PIPE_USAGE, endpoint.extraDescriptors.single()[1].toUByte())
        assertEquals(2, endpoint.extraDescriptors.single()[2].toInt())
    }

    @Test
    fun rejectsTruncationDuplicateSettingsAndMissingDefault() {
        val setting = byteArrayOf(9, 4, 0, 0, 0, 8, 6, 0x50, 0)
        val bytes = configuration(setting)
        for (size in bytes.indices) assertNull(UsbConfiguration.parse(bytes.copyOf(size)))
        assertNull(UsbConfiguration.parse(configuration(setting, setting)))
        setting[3] = 1
        assertNull(UsbConfiguration.parse(configuration(setting)))
        assertNull(UsbConfiguration.parse(configuration(byteArrayOf(0, 4))))
        assertNull(UsbConfiguration.parse(configuration(byteArrayOf(1))))
    }

    @Test
    fun rejectsEndpointCountAndAddressConflicts() {
        val setting = byteArrayOf(9, 4, 0, 0, 2, 8, 6, 0x50, 0)
        val endpoint = byteArrayOf(7, 5, 0x81.toByte(), 2, 0, 2, 0)
        assertNull(UsbConfiguration.parse(configuration(setting, endpoint)))
        assertNull(UsbConfiguration.parse(configuration(setting, endpoint, endpoint)))
    }

    @Test
    fun derivesHidAndCdcMetadataFromOwnedDescriptors() {
        val hid =
            configuration(
                byteArrayOf(9, 4, 0, 0, 0, 3, 1, 1, 0),
                byteArrayOf(9, 0x21, 0x11, 1, 0, 1, 0x22, 0x34, 0x12),
            )
        assertEquals(
            0x1234u.toUShort(),
            assertNotNull(UsbConfiguration.parse(hid))
                .settings
                .single()
                .extraData
                .hidReportDescriptorLength,
        )
        val cdc =
            configuration(
                byteArrayOf(9, 4, 0, 0, 0, 2, 2, -1, 0),
                byteArrayOf(5, 0x24, 6, 0, 1),
            )
        assertEquals(
            listOf(1u.toUByte()),
            assertNotNull(UsbConfiguration.parse(cdc))
                .settings
                .single()
                .extraData
                .associatedInterfaceNumbers,
        )
    }
}
