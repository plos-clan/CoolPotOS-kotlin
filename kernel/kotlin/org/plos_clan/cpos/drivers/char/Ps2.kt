@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.acpi.aml.AcpiIoResource
import org.plos_clan.cpos.drivers.acpi.aml.AcpiIrqResource
import org.plos_clan.cpos.drivers.acpi.aml.AmlDeviceInfo
import org.plos_clan.cpos.fault.IRQ_BASE_VECTOR
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.utils.PtraceRegisters

data class Ps2KeyboardConfiguration(
    val dataPort: UInt,
    val commandPort: UInt,
    val irq: UInt,
    val levelTriggered: Boolean,
    val activeLow: Boolean,
)

object Ps2Keyboard {
    var configuration: Ps2KeyboardConfiguration? = null
        private set

    val isDiscovered: Boolean
        get() = configuration != null

    fun keyboardHandle(regs: PtraceRegisters, irqNum: ULong) {
        val data = bridge.io_in8(0x60u)
        println("kayboard push $data")
    }

    fun initialize(device: AmlDeviceInfo): Boolean {
        val ioResources = device.resources.filterIsInstance<AcpiIoResource>()
        val dataPort = ioResources.firstNotNullOfOrNull { resource ->
            0x60u.takeIf { it in resource.minimum..resource.maximum }
        } ?: 0x60u
        val commandPort = ioResources.firstNotNullOfOrNull { resource ->
            0x64u.takeIf { it in resource.minimum..resource.maximum }
        } ?: 0x64u
        val irqResource = device.resources
            .filterIsInstance<AcpiIrqResource>()
            .firstOrNull { it.interrupts.isNotEmpty() }
        val irq = irqResource?.interrupts?.firstOrNull() ?: 1u
        val levelTriggered = irqResource?.levelTriggered ?: false
        val activeLow = irqResource?.activeLow ?: false

        configuration = Ps2KeyboardConfiguration(
            dataPort = dataPort,
            commandPort = commandPort,
            irq = irq,
            levelTriggered = levelTriggered,
            activeLow = activeLow,
        )
        IrqController.registerAction(
            irq = irq,
            vector = irq + IRQ_BASE_VECTOR,
            masked = false,
            levelTriggered = levelTriggered,
            activeLow = activeLow,
            handle = ::keyboardHandle,
        )
        println(
            "PS/2: ACPI keyboard ${device.path} data=0x${dataPort.toString(16)} " +
                "command=0x${commandPort.toString(16)} irq=$irq " +
                "levelTriggered=$levelTriggered activeLow=$activeLow",
        )
        return true
    }
}
