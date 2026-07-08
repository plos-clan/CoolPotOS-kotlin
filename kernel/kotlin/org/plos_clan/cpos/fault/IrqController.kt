@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.apic.LocalApic
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.experimental.ExperimentalNativeApi

const val ARCH_MAX_IRQ_NUM = 256

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("do_irq")
fun doIrqHandler(frame: COpaquePointer?, irqNum: ULong) {
    IrqController.doIrq(PtraceRegisters(requireNotNull(frame).reinterpret()), irqNum)
}

typealias IrqHandler = (regs: PtraceRegisters, irqNum: ULong) -> Unit

object IrqController {
    private val irqHandlers = arrayOfNulls<IrqHandler>(ARCH_MAX_IRQ_NUM)

    fun doIrq(regs: PtraceRegisters, irqNum: ULong) {
        val irqIndex = irqIndexOf(irqNum) ?: run {
            println("IrqController: out-of-range irq_num=$irqNum")
            bridge.disable_interrupt()
            return
        }

        val handler = irqHandlers[irqIndex] ?: run {
            println("empty irq action: $irqNum")
            bridge.disable_interrupt()
            return
        }
        handler(regs, irqNum)
        LocalApic.endOfInterrupt()
    }

    fun registerAction(irq: Int, handle: IrqHandler): Boolean {
        val irqIndex = irqIndexOf(irq) ?: run {
            println("IrqController: Invalid irq num: $irq")
            return false
        }
        irqHandlers[irqIndex] = handle
        return true
    }

    private fun irqIndexOf(irq: Int): Int? =
        (irq - 1).takeIf { it in irqHandlers.indices }

    private fun irqIndexOf(irq: ULong): Int? =
        irq.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()?.let(::irqIndexOf)
}
