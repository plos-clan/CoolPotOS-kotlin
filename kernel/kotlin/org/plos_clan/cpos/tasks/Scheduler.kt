@file:OptIn(ExperimentalForeignApi::class)
package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.fault.IrqType
import org.plos_clan.cpos.utils.PtraceRegisters

object Scheduler {
    fun scheduler(regs: PtraceRegisters, irqNum: ULong) {
    }

    fun initialize() {
        IrqController.registerAction(1, ::scheduler, IrqType.IO_APIC)
    }
}
