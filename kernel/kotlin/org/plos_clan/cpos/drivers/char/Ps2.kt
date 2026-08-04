package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.acpi.aml.AcpiIoResource
import org.plos_clan.cpos.drivers.acpi.aml.AcpiIrqResource
import org.plos_clan.cpos.drivers.acpi.aml.AmlDeviceInfo

data class Ps2KeyboardConfiguration(
    val dataPort: UInt,
    val commandPort: UInt,
    val irq: UInt,
)

object Ps2Keyboard {
    var configuration: Ps2KeyboardConfiguration? = null
        private set

    val isDiscovered: Boolean
        get() = configuration != null

    fun initialize(device: AmlDeviceInfo): Boolean {
        val ioResources = device.resources.filterIsInstance<AcpiIoResource>()
        val dataPort = ioResources.firstNotNullOfOrNull { resource ->
            0x60u.takeIf { it in resource.minimum..resource.maximum }
        } ?: 0x60u
        val commandPort = ioResources.firstNotNullOfOrNull { resource ->
            0x64u.takeIf { it in resource.minimum..resource.maximum }
        } ?: 0x64u
        val irq = device.resources
            .filterIsInstance<AcpiIrqResource>()
            .flatMap(AcpiIrqResource::interrupts)
            .firstOrNull()
            ?: 1u

        configuration = Ps2KeyboardConfiguration(dataPort, commandPort, irq)
        println(
            "PS/2: ACPI keyboard ${device.path} data=0x${dataPort.toString(16)} " +
                "command=0x${commandPort.toString(16)} irq=$irq",
        )
        return true
    }
}
