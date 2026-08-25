package org.plos_clan.cpos.drivers.pcie

import kotlin.test.Test
import kotlin.test.assertEquals

class PciSysfsTest {
    @Test
    fun formatsCanonicalPciNames() {
        val address = PciAddress.of(
            segment = 0x1234u,
            bus = 0xabu,
            device = 0x1fu,
            function = 0x7u,
        )

        assertEquals("1234:ab:1f.7", address.sysfsName)
        assertEquals("pci1234:ab", PciSysfsPublisher.rootName(address.segment, address.bus))
    }

    @Test
    fun combinesPciClassFieldsAsLinuxClassValue() {
        val function = PciFunctionInfo(
            address = PciAddress.of(0u, 0u, 20u, 0u),
            vendorId = 0x8086u,
            deviceId = 0x1234u,
            classCode = 0x0cu,
            subClass = 0x03u,
            progIf = 0x30u,
            revision = 1u,
            interruptLine = 11u,
        )

        assertEquals(0x0c0330u, function.classValue)
        assertEquals("0000:00:14.0", function.address.sysfsName)
    }
}
