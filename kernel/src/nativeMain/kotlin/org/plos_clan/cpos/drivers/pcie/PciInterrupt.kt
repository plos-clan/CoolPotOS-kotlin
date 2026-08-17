@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.pcie

import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.fault.IrqControllerType
import org.plos_clan.cpos.fault.IrqHandler

enum class PciIrqType {
    INTX,
    MSI,
    MSIX,
}

sealed interface PciInterrupt {
    val type: PciIrqType

    fun enable()

    fun register(handler: IrqHandler, bars: Array<PciBar?>, index: UInt = 0u): UByte?

    companion object {
        fun resolve(header: EndpointHeader): PciInterrupt? {
            var msi: MsiCapability? = null
            for (capability in header.capabilities()) {
                if (capability.id == PCI_CAPABILITY_MSIX) {
                    MsixCapability.from(capability)?.let { return MsixInterrupt(it) }
                } else if (capability.id == PCI_CAPABILITY_MSI) {
                    msi = MsiCapability.from(capability)
                }
            }

            msi?.let { return MsiInterrupt(it) }
            val pin = header.header.interruptPin
            return pin.takeIf { it != 0u.toUByte() }?.let {
                IntxInterrupt(header.header, it, header.header.interruptLine)
            }
        }

        private fun messageAddress(): ULong =
            0xFEE0_0000uL or (LocalApic.destinationApicId.toULong() shl 12)
    }

    private data class IntxInterrupt(
        val header: PciHeader,
        val pin: UByte,
        val line: UByte,
    ) : PciInterrupt {
        override val type = PciIrqType.INTX

        override fun enable() {
            header.updateCommand(PCI_COMMAND_INTX_DISABLE, false)
        }

        override fun register(handler: IrqHandler, bars: Array<PciBar?>, index: UInt): UByte? {
            if (index != 0u) {
                println("PCIe: INTx does not support multiple vectors")
                return null
            }
            val vector = IrqController.registerPciAction(
                irq = line.toUInt(),
                name = "pci-intx-${line}",
                type = IrqControllerType.PCI_INTX,
                handler = handler,
            ) ?: return null
            enable()
            println("PCIe: PCI INTx configured (line: $line, vector: 0x${vector.toString(16)})")
            return vector
        }
    }

    private data class MsiInterrupt(
        val capability: MsiCapability,
    ) : PciInterrupt {
        override val type = PciIrqType.MSI

        override fun enable() {
            capability.setEnabled(true)
        }

        override fun register(handler: IrqHandler, bars: Array<PciBar?>, index: UInt): UByte? {
            val vector = IrqController.registerPciAction(
                irq = null,
                name = "pci-msi",
                type = IrqControllerType.PCI_MSI,
                handler = handler,
            ) ?: return null
            capability.setAddressData(messageAddress(), vector.toUShort())
            enable()
            println("PCIe: PCI MSI configured (vector: 0x${vector.toString(16)})")
            return vector
        }
    }

    private data class MsixInterrupt(
        val capability: MsixCapability,
    ) : PciInterrupt {
        override val type = PciIrqType.MSIX

        override fun enable() {
            capability.setEnabled(true)
        }

        override fun register(handler: IrqHandler, bars: Array<PciBar?>, index: UInt): UByte? {
            val bar = bars.getOrNull(capability.tableBar.toInt()) ?: return null
            val table = MsiXTable.create(bar, capability.tableOffset, capability.tableSize) ?: return null
            val entry = table.entry(index) ?: return null
            val vector = IrqController.registerPciAction(
                irq = null,
                name = "pci-msix",
                type = IrqControllerType.PCI_MSIX,
                handler = handler,
            ) ?: return null
            entry.write(messageAddress(), vector.toUInt(), masked = false)
            enable()
            println("PCIe: PCI MSI-X configured (vector: 0x${vector.toString(16)})")
            return vector
        }
    }
}
