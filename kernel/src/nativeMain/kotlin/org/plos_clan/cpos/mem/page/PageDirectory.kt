@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import bridge.invlpg
import bridge.read_cr3
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.PageFaultResult
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PTE_COUNT
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.isPageAligned
import org.plos_clan.cpos.utils.toVirtualPointer
import platform.posix.memset

internal const val PTE_PRESENT = 0x001uL
internal const val PTE_WRITABLE = 0x002uL
internal const val PTE_USER = 0x004uL
private const val PTE_NO_CACHE = 0x010uL
private const val PTE_ACCESSED_DIRTY = 0x060uL
internal const val PTE_HUGE = 0x080uL
internal const val PTE_NO_EXECUTE = 0x8000_0000_0000_0000uL
internal const val PTE_ADDR_MASK = 0x000F_FFFF_FFFF_F000uL
private const val INDEX_BITS = 9
private val INDEX_MASK = (PTE_COUNT - 1).toULong()
internal const val KERNEL_PML4_START_INDEX = PTE_COUNT / 2
internal const val USER_VIRTUAL_ADDRESS_LIMIT = 0x0000_8000_0000_0000uL
private val PTE_PARENT_FLAGS = PTE_PRESENT or PTE_WRITABLE or PTE_USER
internal val MMIO_PTE_FLAGS = PTE_PRESENT or PTE_WRITABLE or PTE_NO_CACHE or PTE_NO_EXECUTE

internal enum class PageTableLevel(val shift: Int) {
    PML4(39),
    PDPT(30),
    PD(21),
    PT(12);

    fun index(address: ULong): Int = ((address shr shift) and INDEX_MASK).toInt()
}

class PageDirectory(val pml4PhysicalAddress: ULong) {
    internal val root: CPointer<ULongVar> = checkNotNull(pml4PhysicalAddress.toVirtualPointer())

    internal val translations = TranslationCache(pml4PhysicalAddress)

    companion object {
        internal fun allocateTable(): ULong {
            val frame = BuddyFrameAllocator.allocate(1uL)
            if (frame != INVALID_FRAME) {
                val table = checkNotNull(frame.toVirtualPointer<ULongVar>())
                memset(table, 0, PAGE_SIZE_BYTES)
            }
            return frame
        }
    }

    fun createUserDirectory(): PageDirectory {
        val frame = allocateTable()
        check(frame != INVALID_FRAME) { "Paging: failed to allocate user PML4" }
        val child = try {
            PageDirectory(frame)
        } catch (failure: Throwable) {
            BuddyFrameAllocator.free(frame, 1uL)
            throw failure
        }
        for (index in KERNEL_PML4_START_INDEX until PTE_COUNT) {
            val entry = root[index]
            val recursive = entry and PTE_PRESENT != 0uL &&
                entry and PTE_ADDR_MASK == pml4PhysicalAddress
            child.root[index] = if (recursive) (entry and PTE_ADDR_MASK.inv()) or frame else entry
        }
        return child
    }

    fun mapPage(virtualAddress: ULong, physicalAddress: ULong, flags: ULong): Boolean {
        if (!virtualAddress.isPageAligned() || !physicalAddress.isPageAligned()) {
            println("Paging: unaligned map request v=${virtualAddress.hex()} p=${physicalAddress.hex()}")
            return false
        }

        if (flags and PTE_USER != 0uL && virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) return false
        val pt = leafTable(virtualAddress, PTE_PRESENT, create = true) { _, _ -> } ?: return false
        val index = PageTableLevel.PT.index(virtualAddress)
        val previous = pt[index]
        val mapping = (physicalAddress and PTE_ADDR_MASK) or flags or PTE_PRESENT
        if (previous and PTE_ACCESSED_DIRTY.inv() == mapping) return true
        if (flags and PTE_USER != 0uL) UserFrameReferences.retain(physicalAddress)
        pt[index] = mapping
        if (previous != 0uL) invalidate(virtualAddress, virtualAddress + PAGE_SIZE_BYTES)
        if (previous and PTE_USER != 0uL) UserFrameReferences.release(previous and PTE_ADDR_MASK)
        return true
    }

    internal inline fun update(edit: (PageTableUpdate) -> Unit) {
        val batch = PageTableUpdate(this)
        try {
            edit(batch)
        } finally {
            batch.flush()
        }
    }

    internal fun releasePages(start: ULong, end: ULong, releaseFrames: Boolean = true) =
        update { it.remove(start, end, releaseFrames) }

    internal fun invalidate(start: ULong = 0uL, end: ULong = USER_VIRTUAL_ADDRESS_LIMIT) =
        translations.invalidate(start, end)

    internal fun clearUserMappings() {
        val roots = ULongArray(KERNEL_PML4_START_INDEX)
        var count = 0
        for (index in roots.indices) {
            val entry = root[index]
            if (entry and PTE_PRESENT == 0uL) continue
            check(entry and PTE_HUGE == 0uL)
            roots[count++] = entry and PTE_ADDR_MASK
            root[index] = 0uL
        }
        if (count == 0) return
        invalidate()
        for (index in 0 until count) releaseTable(roots[index], PageTableLevel.PDPT)
    }

    internal fun destroyUserDirectory() {
        check((read_cr3() and PTE_ADDR_MASK) != pml4PhysicalAddress) {
            "Paging: cannot destroy the active directory"
        }
        translations.close()
        releaseTable(pml4PhysicalAddress, PageTableLevel.PML4, KERNEL_PML4_START_INDEX)
    }

    internal fun resolveUserPhysicalAddress(
        virtualAddress: ULong,
        requireWritable: Boolean,
    ): ULong {
        if (virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) return INVALID_FRAME
        val required = PTE_PRESENT or PTE_USER or (if (requireWritable) PTE_WRITABLE else 0uL)
        val table = leafTable(virtualAddress, required) { entry, shift ->
            val offset = (1uL shl shift) - 1uL
            return (entry and PTE_ADDR_MASK and offset.inv()) or (virtualAddress and offset)
        } ?: return INVALID_FRAME
        val entry = table[PageTableLevel.PT.index(virtualAddress)]
        if (entry and required != required) return INVALID_FRAME
        return (entry and PTE_ADDR_MASK) or (virtualAddress and (PAGE_SIZE_BYTES - 1uL))
    }

    internal fun userPageFrame(virtualAddress: ULong): ULong? {
        if (!virtualAddress.isPageAligned() || virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return null
        }

        val pt = userPageTable(virtualAddress) ?: return null
        val entry = pt[PageTableLevel.PT.index(virtualAddress)]
        if ((entry and PTE_USER) == 0uL) {
            return null
        }
        return (entry and PTE_ADDR_MASK).takeIf { it != 0uL }
    }

    internal fun resolveFault(
        page: ULong,
        write: Boolean,
        permissions: PagePermissions,
        privateMapping: Boolean,
    ): PageFaultResult? {
        if (page >= USER_VIRTUAL_ADDRESS_LIMIT) return null
        val table = userPageTable(page) ?: return null
        val index = PageTableLevel.PT.index(page)
        val entry = table[index]
        if (entry and PTE_USER == 0uL) return null
        val frame = entry and PTE_ADDR_MASK
        if (!write) {
            val updated = permissions.apply(entry, privateMapping)
            if (updated != entry) {
                table[index] = updated
                invalidate(page, page + PAGE_SIZE_BYTES)
            }
            return PageFaultResult.RESOLVED
        }
        val writable = entry or PTE_PRESENT or PTE_WRITABLE
        if (writable == entry) {
            invlpg(page)
            return PageFaultResult.RESOLVED
        }
        val replacement = if (privateMapping) UserFrameReferences.copyOnWrite(frame) else frame
        if (replacement == null) return PageFaultResult.MAPPING_FAILED
        table[index] = (writable and PTE_ADDR_MASK.inv()) or replacement
        if (replacement == frame) invlpg(page)
        else {
            invalidate(page, page + PAGE_SIZE_BYTES)
            UserFrameReferences.release(frame)
        }
        return PageFaultResult.RESOLVED
    }

    internal fun cloneDirectory(sharedRegions: List<MemoryRegion>): PageDirectory =
        PageDirectoryCloner(this, sharedRegions).clone()

    internal fun userPageTable(address: ULong) =
        leafTable(address, PTE_PRESENT or PTE_USER) { _, _ -> }

    private inline fun leafTable(
        address: ULong,
        required: ULong,
        create: Boolean = false,
        huge: (ULong, Int) -> Unit,
    ): CPointer<ULongVar>? {
        var table = root
        for (shift in PageTableLevel.PML4.shift downTo PageTableLevel.PD.shift step INDEX_BITS) {
            val index = ((address shr shift) and INDEX_MASK).toInt()
            var entry = table[index]
            if (create && entry and PTE_PRESENT == 0uL) {
                val frame = allocateTable()
                if (frame == INVALID_FRAME) return null
                entry = frame or PTE_PARENT_FLAGS
                table[index] = entry
            }
            if (entry and required != required) return null
            if (entry and PTE_HUGE != 0uL) {
                if (shift != PageTableLevel.PML4.shift) huge(entry, shift)
                return null
            }
            table = (entry and PTE_ADDR_MASK).toVirtualPointer() ?: return null
        }
        return table
    }

    private fun releaseTable(frame: ULong, level: PageTableLevel, count: Int = PTE_COUNT) {
        val table = checkNotNull(frame.toVirtualPointer<ULongVar>())
        for (index in 0 until count) {
            val entry = table[index]
            if (entry == 0uL) continue
            val child = entry and PTE_ADDR_MASK
            if (level == PageTableLevel.PT) {
                if (entry and PTE_USER != 0uL) UserFrameReferences.release(child)
            } else if (entry and PTE_PRESENT != 0uL) {
                check(entry and PTE_HUGE == 0uL)
                releaseTable(child, PageTableLevel.entries[level.ordinal + 1])
            }
        }
        check(BuddyFrameAllocator.free(frame, 1uL))
    }
}
