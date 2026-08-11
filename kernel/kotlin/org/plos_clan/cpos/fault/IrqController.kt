@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.acpi.apic.IoApic
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.experimental.ExperimentalNativeApi

const val ARCH_MAX_IRQ_NUM = 256
const val IRQ_BASE_VECTOR = 32u

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("do_irq")
fun doIrqHandler(frame: COpaquePointer?, irqNum: ULong) {
    IrqController.doIrq(PtraceRegisters(requireNotNull(frame).reinterpret()), irqNum)
}

typealias IrqHandler = (regs: PtraceRegisters, irqNum: ULong) -> Unit

data class IrqAction(
    val irq: UInt,
    val vector: UInt,
    val destinationApicId: UInt,
    val name: String,
    val type: IrqControllerType,
    val levelTriggered: Boolean
) {
    val cpuCount = ULongArray(SMProcessor.cpu_count.toInt())
}

enum class IrqControllerType(val displayName: String) {
    IO_APIC("IO-APIC"),
    PCI_MSIX("PCI-MSIX")
    ;
}

object IrqController {
    private val irqHandlers = arrayOfNulls<IrqHandler>(ARCH_MAX_IRQ_NUM)
    private val actions = arrayOfNulls<IrqAction>(ARCH_MAX_IRQ_NUM)


    fun doIrq(regs: PtraceRegisters, irqNum: ULong) {
        val irqIndex = irqIndexOf(irqNum) ?: run {
            println("IrqController: out-of-range irq_num=$irqNum")
            LocalApic.endOfInterrupt()
            return
        }

        val handler = irqHandlers[irqIndex] ?: run {
            LocalApic.endOfInterrupt()
            return
        }
        handler(regs, irqNum)

        val action = actions[irqIndex] ?: run {
            LocalApic.endOfInterrupt()
            return
        }
        action.cpuCount[LocalApic.destinationApicId.toInt()]++

        LocalApic.endOfInterrupt()
    }

    fun registerAction(
        irq: UInt,
        vector: UInt,
        masked: Boolean = false,
        levelTriggered: Boolean = false,
        activeLow: Boolean = false,
        name: String,
        type: IrqControllerType,
        handle: IrqHandler
    ): Boolean {
        val irqNumber = vector
            .takeIf { it in IRQ_BASE_VECTOR..UByte.MAX_VALUE.toUInt() }
            ?.let { (it - IRQ_BASE_VECTOR + 1u).toInt() }
        val irqIndex = irqNumber?.let(::irqIndexOf) ?: run {
            println("IrqController: Invalid interrupt vector: $vector")
            return false
        }
        irqHandlers[irqIndex] = handle
        IoApic.routeIrq(
            irq,
            vector,
            LocalApic.destinationApicId,
            masked = masked,
            levelTriggered = levelTriggered,
            activeLow = activeLow
        )
        actions[irqIndex] =
            IrqAction(irq, vector, LocalApic.destinationApicId, name, type, levelTriggered)
        return true
    }

    fun getActions(): Array<IrqAction?> = actions

    private fun irqIndexOf(irq: Int): Int? =
        (irq - 1).takeIf { it in irqHandlers.indices }

    private fun irqIndexOf(irq: ULong): Int? =
        irq.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()?.let(::irqIndexOf)
}
