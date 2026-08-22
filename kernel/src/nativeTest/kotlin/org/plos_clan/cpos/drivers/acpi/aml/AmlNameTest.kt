package org.plos_clan.cpos.drivers.acpi.aml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmlNameTest {
    @Test
    fun buildsAndNavigatesNames() {
        val pci = AmlName.ROOT.child("_SB_").child("PCI0")
        val usb = pci.child("USB0")

        assertEquals("\\_SB_.PCI0.USB0", usb.toString())
        assertEquals(pci, usb.parent)
        assertTrue(usb.startsWith(pci))
        assertFalse(pci.startsWith(usb))
    }

    @Test
    fun resolvesRelativeAndAbsolutePaths() {
        val scope = AmlName(listOf("_SB_", "PCI0", "USB0"))

        assertEquals(
            AmlName(listOf("_SB_", "PCI0", "RHUB")),
            AmlNamePath(
                absolute = false,
                parentPrefixCount = 1,
                segments = listOf("RHUB"),
            ).resolveFrom(scope),
        )
        assertEquals(
            AmlName(listOf("_TZ_")),
            AmlNamePath(
                absolute = true,
                parentPrefixCount = 0,
                segments = listOf("_TZ_"),
            ).resolveFrom(scope),
        )
    }

    @Test
    fun rejectsInvalidSegments() {
        listOf("PCI", "pci0", "1PCI", "PC-I").forEach { segment ->
            assertFailsWith<IllegalArgumentException>(message = "segment=$segment") {
                AmlName.ROOT.child(segment)
            }
        }
    }

    @Test
    fun consumesDualAndMultiNamePathsWithoutLosingSegments() {
        val dual = AmlByteReader(
            AmlArraySource(byteArrayOf(0x2E, *"_SB_".encodeToByteArray(), *"PCI0".encodeToByteArray())),
        )
        assertEquals(
            listOf("_SB_", "PCI0"),
            dual.readNamePath()?.segments,
        )

        val multi = AmlByteReader(
            AmlArraySource(
                byteArrayOf(
                    0x2F, 0x03,
                    *"_SB_".encodeToByteArray(),
                    *"PCI0".encodeToByteArray(),
                    *"XHC0".encodeToByteArray(),
                ),
            ),
        )
        assertEquals(
            listOf("_SB_", "PCI0", "XHC0"),
            multi.readNamePath()?.segments,
        )
    }

    @Test
    fun leavesReaderUntouchedWhenNamePathIsTruncated() {
        val reader = AmlByteReader(AmlArraySource(byteArrayOf(0x2E, *"_SB_".encodeToByteArray())))

        assertEquals(null, reader.readNamePath())
        assertEquals(0, reader.position)
    }
}
