package org.plos_clan.cpos.fault

import kotlin.experimental.ExperimentalNativeApi
import org.plos_clan.cpos.drivers.acpi.apic.IoApic
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.utils.IrqSpinLock

internal const val IRQ_BASE_VECTOR = 0x20u
internal const val IRQ_LAST_DEVICE_VECTOR = 0xEFu

@ExperimentalNativeApi
@Suppress("unused")
@CName("do_irq")
fun doIrqHandler(irqNum: ULong) = IrqController.doIrq(irqNum)

typealias IrqHandler = () -> Unit

internal class IrqAction(
    val irq: UInt,
    val cpuIndex: Int,
    val name: String,
    val type: IrqControllerType,
    val levelTriggered: Boolean
) {
    val cpuCount = ULongArray(SMProcessor.cpu_count.toInt())
}

internal enum class IrqControllerType(val displayName: String) {
    IO_APIC("IO-APIC"),
    PCI_INTX("PCI-INTx"),
    PCI_MSI("PCI-MSI"),
    PCI_MSIX("PCI-MSIX"),
    ;
}

internal object IrqController {
    private const val FIRST_DYNAMIC_VECTOR = 0x80u

    private val lock = IrqSpinLock()
    private val descriptors = arrayOfNulls<IrqDescriptor>(
        (IRQ_LAST_DEVICE_VECTOR - IRQ_BASE_VECTOR + 1u).toInt(),
    )

    fun doIrq(irqNum: ULong) {
        if (irqNum == 0uL || irqNum > descriptors.size.toULong()) {
            println("IrqController: out-of-range irq_num=$irqNum")
            return
        }
        val descriptor = descriptors[(irqNum - 1uL).toInt()] ?: return
        descriptor.handler()
        with(descriptor.action) {
            if (cpuIndex in cpuCount.indices) cpuCount[cpuIndex]++
        }
    }

    fun registerIoApic(
        irq: UInt,
        vector: UInt,
        masked: Boolean = false,
        levelTriggered: Boolean = false,
        activeLow: Boolean = false,
        name: String,
        handler: IrqHandler,
    ): Boolean {
        if (vector !in IRQ_BASE_VECTOR..IRQ_LAST_DEVICE_VECTOR) {
            println("IrqController: invalid device interrupt vector: $vector")
            return false
        }

        val index = (vector - IRQ_BASE_VECTOR).toInt()
        val target = currentTarget()
        val descriptor = IrqDescriptor(
            IrqAction(
                irq,
                target.cpuIndex,
                name,
                IrqControllerType.IO_APIC,
                levelTriggered,
            ),
            handler,
        )
        val installed = lock.withLock {
            if (descriptors[index] != null) {
                false
            } else {
                descriptors[index] = descriptor
                true
            }
        }
        if (!installed) {
            println("IrqController: interrupt vector already registered: $vector")
            return false
        }

        IoApic.routeIrq(
            irq,
            vector,
            target.apicId,
            masked = masked,
            levelTriggered = levelTriggered,
            activeLow = activeLow
        )
        return true
    }

    fun registerPci(
        irq: UInt?,
        name: String,
        type: IrqControllerType,
        handler: IrqHandler,
    ): UByte? {
        val target = currentTarget()
        val levelTriggered = type == IrqControllerType.PCI_INTX
        val vector = lock.withLock {
            var index = (FIRST_DYNAMIC_VECTOR - IRQ_BASE_VECTOR).toInt()
            while (index < descriptors.size && descriptors[index] != null) index++
            if (index == descriptors.size) return@withLock null

            val candidate = IRQ_BASE_VECTOR + index.toUInt()
            descriptors[index] = IrqDescriptor(
                IrqAction(
                    irq ?: (candidate - IRQ_BASE_VECTOR + 1u),
                    target.cpuIndex,
                    name,
                    type,
                    levelTriggered,
                ),
                handler,
            )
            candidate
        } ?: run {
            println("IrqController: no free PCI interrupt vector")
            return null
        }

        if (irq != null) {
            IoApic.routeIrq(
                irq = irq,
                vector = vector,
                destinationApicId = target.apicId,
                levelTriggered = levelTriggered,
                activeLow = levelTriggered,
            )
        }
        return vector.toUByte()
    }

    fun snapshotActions(): Array<IrqAction?> {
        val snapshot = arrayOfNulls<IrqAction>(descriptors.size)
        lock.withLock {
            descriptors.forEachIndexed { index, descriptor ->
                snapshot[index] = descriptor?.action
            }
        }
        return snapshot
    }

    private fun currentTarget() = IrqTarget(
        LocalApic.destinationApicId,
        SMProcessor.currentLocal().cpuid.toInt(),
    )

    private data class IrqTarget(val apicId: UInt, val cpuIndex: Int)

    private class IrqDescriptor(val action: IrqAction, val handler: IrqHandler)
}
