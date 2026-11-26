@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.utils.PTE_COUNT
import org.plos_clan.cpos.utils.toVirtualPointer

internal class PageDirectoryCloner(
    private val source: PageDirectory,
    private val sharedRegions: List<MemoryRegion>,
) {
    private var sharedIndex = 0

    fun clone(): PageDirectory {
        val directory = source.createUserDirectory()
        try {
            source.update { changes ->
                copyTable(source.root, directory.root, PageTableLevel.PML4, 0uL, changes)
            }
        } catch (failure: Throwable) {
            directory.destroyUserDirectory()
            throw failure
        }
        return directory
    }

    private fun copyTable(
        original: CPointer<ULongVar>,
        copy: CPointer<ULongVar>,
        level: PageTableLevel,
        base: ULong,
        changes: PageTableUpdate,
    ) {
        val count = if (level == PageTableLevel.PML4) KERNEL_PML4_START_INDEX else PTE_COUNT
        for (index in 0 until count) {
            val entry = original[index]
            if (entry == 0uL) continue
            val address = base + (index.toULong() shl level.shift)
            if (level == PageTableLevel.PT) {
                val shared = shareEntry(entry, address)
                copy[index] = shared
                if (shared != entry) {
                    original[index] = shared
                    changes.changed(address)
                }
                continue
            }
            if (entry and PTE_PRESENT == 0uL) continue
            check(entry and PTE_HUGE == 0uL) { "Paging: huge user mappings are unsupported" }
            val frame = PageDirectory.allocateTable()
            check(frame != INVALID_FRAME) { "Paging: failed to clone page table" }
            copy[index] = (entry and PTE_ADDR_MASK.inv()) or frame
            val child = checkNotNull((entry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>())
            val destination = checkNotNull(frame.toVirtualPointer<ULongVar>())
            copyTable(child, destination, PageTableLevel.entries[level.ordinal + 1], address, changes)
        }
    }

    private fun shareEntry(entry: ULong, address: ULong): ULong {
        if (entry and PTE_USER == 0uL) return entry
        UserFrameReferences.retain(entry and PTE_ADDR_MASK)
        if (entry and PTE_WRITABLE == 0uL || isShared(address)) return entry
        return entry and PTE_WRITABLE.inv()
    }

    private fun isShared(address: ULong): Boolean {
        while (sharedIndex < sharedRegions.size) {
            val region = sharedRegions[sharedIndex]
            if (address < region.end) return address >= region.start
            sharedIndex++
        }
        return false
    }
}
