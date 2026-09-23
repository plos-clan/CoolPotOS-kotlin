@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.mem.page

import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal object TranslationCache {
    private val lock = IrqSpinLock()

    fun invalidate(directory: ULong, start: ULong, end: ULong): Unit = lock.withLock {
        val currentDirectory = bridge.read_cr3()
        val target = if (start >= USER_VIRTUAL_ADDRESS_LIMIT) 0uL else directory
        if (target == 0uL || currentDirectory == target) {
            if (end - start == PAGE_SIZE_BYTES) bridge.invlpg(start)
            else bridge.write_cr3(currentDirectory)
        }
        val processors = SMProcessor.locals
        if (processors.size < 2 || SMProcessor.load_done.load() < processors.size) return@withLock
        val current = LocalApic.localApicId
        for (processor in processors.keys) {
            if (processor != current) bridge.tlb_invalidate_remote(target, processor.toULong())
        }
    }
}
