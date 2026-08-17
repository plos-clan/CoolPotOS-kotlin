@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.acpi.apic.IoApic
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.utils.IrqSpinLock
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
    PCI_INTX("PCI-INTx"),
    PCI_MSI("PCI-MSI"),
    PCI_MSIX("PCI-MSIX"),
    ;
}

object IrqController {
    private const val FIRST_DYNAMIC_VECTOR = 128u
    private const val LAST_DYNAMIC_VECTOR = 254u

    private val lock = IrqSpinLock()
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
        val cpu = LocalApic.destinationApicId.toInt()
        if (cpu in action.cpuCount.indices) {
            action.cpuCount[cpu]++
        }

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
        val irqIndex = vectorIndexOf(vector) ?: run {
            println("IrqController: Invalid interrupt vector: $vector")
            return false
        }
        val installed = lock.withLock {
            if (irqHandlers[irqIndex] != null || actions[irqIndex] != null) {
                false
            } else {
                irqHandlers[irqIndex] = handle
                actions[irqIndex] = IrqAction(
                    irq = irq,
                    vector = vector,
                    destinationApicId = LocalApic.destinationApicId,
                    name = name,
                    type = type,
                    levelTriggered = levelTriggered,
                )
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
            LocalApic.destinationApicId,
            masked = masked,
            levelTriggered = levelTriggered,
            activeLow = activeLow
        )
        return true
    }

    fun registerPciAction(
        irq: UInt?,
        name: String,
        type: IrqControllerType,
        handler: IrqHandler,
    ): UByte? {
        var vector: UInt? = null
        val installed = lock.withLock {
            val candidate = (FIRST_DYNAMIC_VECTOR..LAST_DYNAMIC_VECTOR)
                .firstOrNull {
                    val index = vectorIndexOf(it)!!
                    actions[index] == null && irqHandlers[index] == null
                }
                ?: return@withLock false
            val index = vectorIndexOf(candidate) ?: return@withLock false
            vector = candidate
            irqHandlers[index] = handler
            actions[index] = IrqAction(
                irq = irq ?: irqNumberFor(candidate),
                vector = candidate,
                destinationApicId = LocalApic.destinationApicId,
                name = name,
                type = type,
                levelTriggered = false,
            )
            true
        }
        if (!installed) {
            println("IrqController: no free PCI interrupt vector")
            return null
        }

        val allocatedVector = vector ?: return null
        if (irq != null) {
            IoApic.routeIrq(
                irq = irq,
                vector = allocatedVector,
                destinationApicId = LocalApic.destinationApicId,
            )
        }
        return allocatedVector.toUByte()
    }

    fun getActions(): Array<IrqAction?> = actions

    private fun irqIndexOf(irq: Int): Int? =
        (irq - 1).takeIf { it in irqHandlers.indices }

    private fun irqIndexOf(irq: ULong): Int? =
        irq.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()?.let(::irqIndexOf)

    private fun vectorIndexOf(vector: UInt): Int? =
        vector.takeIf { it in IRQ_BASE_VECTOR..UByte.MAX_VALUE.toUInt() }
            ?.let { (it - IRQ_BASE_VECTOR).toInt() }
            ?.takeIf { it in actions.indices }

    private fun irqNumberFor(vector: UInt): UInt = vector - IRQ_BASE_VECTOR + 1u
}
