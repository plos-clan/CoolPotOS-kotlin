@file:OptIn(ExperimentalForeignApi::class)
package org.plos_clan.cpos.fault

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.apic.LocalApic
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.experimental.ExperimentalNativeApi

const val IRQ_BASE_VECTOR = 32
const val ARCH_MAX_IRQ_NUM = 256

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("do_irq")
fun doIrqHandler(frame: COpaquePointer?,irqNum: ULong) {
    IrqController.doIrq(PtraceRegisters(requireNotNull(frame).reinterpret()),irqNum)
}

enum class IrqType {
    PCI_MSI,
    IO_APIC
}

data class IrqAction(var handle: (regs: PtraceRegisters, irqNum: ULong) -> Unit, var type: IrqType)

object IrqController {
    private val irqActions: MutableList<IrqAction?> = MutableList(ARCH_MAX_IRQ_NUM) { null }

    fun doIrq(regs: PtraceRegisters, irqNum: ULong) {
        val irqIndex = irqNum.toInt() - 1
        if (irqIndex !in irqActions.indices) {
            println("IrqController: out-of-range irq_num=$irqNum")
            bridge.disable_interrupt()
            return
        }

        val action = irqActions[irqIndex] ?: run {
            println("empty irq action: $irqNum")
            bridge.disable_interrupt()
            return
        }
        action.handle(regs, irqNum)
        LocalApic.endOfInterrupt()
    }

    fun registerAction(irq: Int, handle: (regs: PtraceRegisters, irqNum: ULong) -> Unit, type: IrqType) : Boolean {
        if (irq !in 1..irqActions.size) {
            println("IrqController: Invalid irq num: $irq")
            return false
        }
        irqActions[irq - 1] = IrqAction(handle, type)
        return true
    }

    fun initialize() {

    }
}
