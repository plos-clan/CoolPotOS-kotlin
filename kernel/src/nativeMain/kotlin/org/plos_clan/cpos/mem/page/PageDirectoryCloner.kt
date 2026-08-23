@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import bridge.read_cr3
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PTE_COUNT
import org.plos_clan.cpos.utils.toVirtualPointer
import platform.posix.memcpy

internal class PageDirectoryCloner(
    private val source: PageDirectory,
    private val sharedRegions: List<MemoryRegion>,
) {
    private data class ModifiedPage(
        val address: ULong,
        val entry: ULong,
    )

    private val allocation = PageTableAllocation()
    private val sharedFrames = mutableListOf<ULong>()
    private val modifiedPages = mutableListOf<ModifiedPage>()

    fun clone(): PageDirectory {
        val sourcePml4 = source.pml4Table()
            ?: error("Paging: source PML4 is unavailable")
        val clonedAddress = allocation.allocate()
            ?: error("Paging: failed to allocate cloned PML4")
        val clonedPml4 = clonedAddress.toVirtualPointer<ULongVar>() ?: run {
            allocation.release()
            error("Paging: cloned PML4 is unavailable")
        }
        memcpy(clonedPml4, sourcePml4, PAGE_SIZE_BYTES)

        for (index in 0 until KERNEL_PML4_START_INDEX) {
            val entry = sourcePml4[index]
            if ((entry and PTE_PRESENT) == 0uL) continue
            if ((entry and PTE_HUGE) != 0uL) fail("invalid huge-page bit in user PML4")
            val child = cloneTable(
                sourcePhysicalAddress = entry and PTE_ADDR_MASK,
                level = PageTableLevel.PDPT,
                virtualBase = index.toULong() shl PageTableLevel.PML4.shift,
            ) ?: fail("failed to clone user page-table hierarchy")
            clonedPml4[index] = entry.withAddress(child)
        }

        for (index in KERNEL_PML4_START_INDEX until PTE_COUNT) {
            val entry = sourcePml4[index]
            clonedPml4[index] =
                if ((entry and PTE_PRESENT) != 0uL &&
                    (entry and PTE_ADDR_MASK) == (source.pml4PhysicalAddress and PTE_ADDR_MASK)
                ) {
                    entry.withAddress(clonedAddress)
                } else {
                    entry
                }
        }

        UserFrameReferences.shareAll(sharedFrames)
        flushModifiedMappings()
        return PageDirectory(clonedAddress)
    }

    private fun cloneTable(
        sourcePhysicalAddress: ULong,
        level: PageTableLevel,
        virtualBase: ULong,
    ): ULong? {
        val sourceTable = sourcePhysicalAddress.toVirtualPointer<ULongVar>() ?: return null
        val destinationAddress = allocation.allocate() ?: return null
        val destination = destinationAddress.toVirtualPointer<ULongVar>() ?: return null
        memcpy(destination, sourceTable, PAGE_SIZE_BYTES)
        for (index in 0 until PTE_COUNT) {
            val entry = sourceTable[index]
            val child = entry and PTE_ADDR_MASK
            if (child == 0uL) continue

            if (level == PageTableLevel.PT) {
                if ((entry and PTE_USER) == 0uL) continue
                val address = virtualBase + (index.toULong() shl PageTableLevel.PT.shift)
                sharedFrames += child
                val privateWritable = (entry and PTE_WRITABLE) != 0uL &&
                    sharedRegions.none { address >= it.start && address < it.end }
                if (privateWritable) {
                    val readOnlyEntry = entry and PTE_WRITABLE.inv()
                    sourceTable[index] = readOnlyEntry
                    destination[index] = readOnlyEntry
                    modifiedPages += ModifiedPage(address, entry)
                }
                continue
            }

            if ((entry and PTE_PRESENT) == 0uL) continue
            if ((entry and PTE_HUGE) != 0uL) return null
            val nextLevel = when (level) {
                PageTableLevel.PDPT -> PageTableLevel.PD
                PageTableLevel.PD -> PageTableLevel.PT
                else -> return null
            }
            val clonedChild = cloneTable(
                sourcePhysicalAddress = child,
                level = nextLevel,
                virtualBase = virtualBase + (index.toULong() shl level.shift),
            ) ?: return null
            destination[index] = entry.withAddress(clonedChild)
        }
        return destinationAddress
    }

    private fun fail(message: String): Nothing {
        modifiedPages.asReversed().forEach { page ->
            val table = source.userPageTable(page.address) ?: return@forEach
            table[PageTableLevel.PT.index(page.address)] = page.entry
        }
        flushModifiedMappings()
        allocation.release()
        error("Paging: $message")
    }

    private fun flushModifiedMappings() {
        if (modifiedPages.isNotEmpty() &&
            (read_cr3() and PTE_ADDR_MASK) == source.pml4PhysicalAddress
        ) {
            bridge.write_cr3(source.pml4PhysicalAddress)
        }
    }

    private fun ULong.withAddress(address: ULong): ULong =
        (this and PTE_ADDR_MASK.inv()) or (address and PTE_ADDR_MASK)
}
