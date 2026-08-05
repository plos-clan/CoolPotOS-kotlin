@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import bridge.invlpg
import bridge.read_cr3
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.utils.*

private const val PTE_PRESENT = 0x001uL
private const val PTE_WRITABLE = 0x002uL
private const val PTE_USER = 0x004uL
private const val PTE_NO_CACHE = 0x010uL
private const val PTE_HUGE = 0x080uL
private const val PTE_NO_EXECUTE = 0x8000_0000_0000_0000uL
private const val PTE_ADDR_MASK = 0x000F_FFFF_FFFF_F000uL
private const val PTE_2_MIB_ADDR_MASK = 0x000F_FFFF_FFE0_0000uL
private const val PTE_1_GIB_ADDR_MASK = 0x000F_FFFF_C000_0000uL
private const val PAGE_TABLE_INDEX_MASK = 0x1ffuL
private const val PAGE_2_MIB_OFFSET_MASK = 0x001F_FFFFuL
private const val PAGE_1_GIB_OFFSET_MASK = 0x3FFF_FFFFuL
private const val KERNEL_PML4_START_INDEX = PTE_COUNT / 2
internal const val USER_VIRTUAL_ADDRESS_LIMIT = 0x0000_8000_0000_0000uL
private val PTE_PARENT_FLAGS = PTE_PRESENT or PTE_WRITABLE or PTE_USER
private val MMIO_PTE_FLAGS = PTE_PRESENT or PTE_WRITABLE or PTE_NO_CACHE or PTE_NO_EXECUTE

private enum class PageTableLevel(val shift: Int) {
    PML4(39),
    PDPT(30),
    PD(21),
    PT(12);

    fun index(address: ULong): Int = ((address shr shift) and PAGE_TABLE_INDEX_MASK).toInt()
}

data class PageDirectory(val pml4PhysicalAddress: ULong) {

    fun createUserDirectory(): PageDirectory {
        val sourcePml4 = pml4Table()
            ?: error("Paging: source PML4 is unavailable")
        val allocatedFrames = mutableListOf<ULong>()
        val userPml4PhysicalAddress = allocateTableFrame(allocatedFrames)
            ?: error("Paging: failed to allocate user PML4")
        val userPml4 = userPml4PhysicalAddress.toVirtualPointer<ULongVar>() ?: run {
            releaseTableFrames(allocatedFrames)
            error("Paging: user PML4 is unavailable")
        }

        for (index in KERNEL_PML4_START_INDEX until PTE_COUNT) {
            val entry = sourcePml4[index]
            userPml4[index] =
                if ((entry and PTE_PRESENT) != 0uL &&
                    (entry and PTE_ADDR_MASK) == (pml4PhysicalAddress and PTE_ADDR_MASK)
                ) {
                    replaceEntryAddress(entry, userPml4PhysicalAddress)
                } else {
                    entry
                }
        }

        return PageDirectory(userPml4PhysicalAddress)
    }

    fun mapPage(virtualAddress: ULong, physicalAddress: ULong, flags: ULong): Boolean {
        if (!virtualAddress.isPageAligned() || !physicalAddress.isPageAligned()) {
            println("Paging: unaligned map request v=${virtualAddress.hex()} p=${physicalAddress.hex()}")
            return false
        }

        val pml4 = pml4Table() ?: return false
        val pdpt = ensureChildTable(pml4, PageTableLevel.PML4.index(virtualAddress)) ?: return false
        val pd = ensureChildTable(pdpt, PageTableLevel.PDPT.index(virtualAddress)) ?: return false
        val pt = ensureChildTable(pd, PageTableLevel.PD.index(virtualAddress)) ?: return false

        pt[PageTableLevel.PT.index(virtualAddress)] = (physicalAddress and PTE_ADDR_MASK) or flags or PTE_PRESENT
        invlpg(virtualAddress)
        return true
    }

    fun mapUserPage(
        virtualAddress: ULong,
        physicalAddress: ULong,
        writable: Boolean,
        executable: Boolean,
    ): Boolean {
        if (virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
            println("Paging: user mapping is outside the user half: ${virtualAddress.hex()}")
            return false
        }
        val flags = PTE_USER or
            (if (writable) PTE_WRITABLE else 0uL) or
            (if (executable) 0uL else PTE_NO_EXECUTE)
        return mapPage(virtualAddress, physicalAddress, flags)
    }

    fun mapUserRange(
        virtualAddress: ULong,
        physicalAddress: ULong,
        byteLength: ULong,
        writable: Boolean,
        executable: Boolean,
    ): Boolean {
        if (byteLength == 0uL) {
            return true
        }
        if (virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT ||
            byteLength > USER_VIRTUAL_ADDRESS_LIMIT - virtualAddress
        ) {
            println("Paging: user range exceeds the user half")
            return false
        }
        val flags = PTE_USER or
            (if (writable) PTE_WRITABLE else 0uL) or
            (if (executable) 0uL else PTE_NO_EXECUTE)
        return mapRange(virtualAddress, physicalAddress, byteLength, flags)
    }

    fun unmapPage(virtualAddress: ULong): ULong? {
        if (!virtualAddress.isPageAligned()) {
            return null
        }

        val pml4 = pml4Table() ?: return null
        val pml4Entry = pml4[PageTableLevel.PML4.index(virtualAddress)]
        if ((pml4Entry and PTE_PRESENT) == 0uL || (pml4Entry and PTE_HUGE) != 0uL) {
            return null
        }
        val pdpt = (pml4Entry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdptEntry = pdpt[PageTableLevel.PDPT.index(virtualAddress)]
        if ((pdptEntry and PTE_PRESENT) == 0uL || (pdptEntry and PTE_HUGE) != 0uL) {
            return null
        }
        val pd = (pdptEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdEntry = pd[PageTableLevel.PD.index(virtualAddress)]
        if ((pdEntry and PTE_PRESENT) == 0uL || (pdEntry and PTE_HUGE) != 0uL) {
            return null
        }
        val pt = (pdEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val index = PageTableLevel.PT.index(virtualAddress)
        val entry = pt[index]
        val physicalAddress = entry and PTE_ADDR_MASK
        if (physicalAddress == 0uL) {
            return null
        }

        pt[index] = 0uL
        invlpg(virtualAddress)
        return physicalAddress
    }

    fun protectUserPage(
        virtualAddress: ULong,
        accessible: Boolean,
        writable: Boolean,
        executable: Boolean,
    ): Boolean {
        if (!virtualAddress.isPageAligned() || virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return false
        }

        val pml4 = pml4Table() ?: return false
        val pml4Entry = pml4[PageTableLevel.PML4.index(virtualAddress)]
        if ((pml4Entry and PTE_PRESENT) == 0uL || (pml4Entry and PTE_HUGE) != 0uL) {
            return false
        }
        val pdpt = (pml4Entry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return false
        val pdptEntry = pdpt[PageTableLevel.PDPT.index(virtualAddress)]
        if ((pdptEntry and PTE_PRESENT) == 0uL || (pdptEntry and PTE_HUGE) != 0uL) {
            return false
        }
        val pd = (pdptEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return false
        val pdEntry = pd[PageTableLevel.PD.index(virtualAddress)]
        if ((pdEntry and PTE_PRESENT) == 0uL || (pdEntry and PTE_HUGE) != 0uL) {
            return false
        }
        val pt = (pdEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return false
        val index = PageTableLevel.PT.index(virtualAddress)
        val entry = pt[index]
        val physicalAddress = entry and PTE_ADDR_MASK
        if (physicalAddress == 0uL) {
            return false
        }

        pt[index] = physicalAddress or PTE_USER or
            (if (accessible) PTE_PRESENT else 0uL) or
            (if (writable) PTE_WRITABLE else 0uL) or
            (if (executable) 0uL else PTE_NO_EXECUTE)
        invlpg(virtualAddress)
        return true
    }

    fun mapRange(
        virtualAddress: ULong,
        physicalAddress: ULong,
        byteLength: ULong,
        flags: ULong,
    ): Boolean {
        if (byteLength == 0uL) {
            return true
        }

        val virtualBase = virtualAddress.alignDown(PAGE_SIZE_BYTES)
        val physicalBase = physicalAddress.alignDown(PAGE_SIZE_BYTES)
        val leadingOffset = virtualAddress - virtualBase
        val mappedLength = (byteLength + leadingOffset).alignUp(PAGE_SIZE_BYTES)

        var offset = 0uL
        while (offset < mappedLength) {
            if (!mapPage(virtualBase + offset, physicalBase + offset, flags)) {
                return false
            }
            offset += PAGE_SIZE_BYTES
        }
        return true
    }

    fun mapMmioRange(physicalAddress: ULong, byteLength: ULong): ULong? {
        if (byteLength == 0uL) {
            return Hhdm.toVirtual(physicalAddress)
        }

        val physicalBase = physicalAddress.alignDown(PAGE_SIZE_BYTES)
        val physicalEnd = (physicalAddress + byteLength).alignUp(PAGE_SIZE_BYTES)
        val length = physicalEnd - physicalBase
        val virtualBase = Hhdm.toVirtual(physicalBase)

        if (!mapRange(virtualBase, physicalBase, length, MMIO_PTE_FLAGS)) {
            return null
        }
        return Hhdm.toVirtual(physicalAddress)
    }

    fun activate() {
        bridge.write_cr3(pml4PhysicalAddress)
    }

    internal fun resolveUserPhysicalAddress(
        virtualAddress: ULong,
        requireWritable: Boolean,
    ): ULong? {
        if (virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return null
        }

        val pml4 = pml4Table() ?: return null
        val pml4Entry = pml4[PageTableLevel.PML4.index(virtualAddress)]
        if (!pml4Entry.allowsUserAccess(requireWritable) || (pml4Entry and PTE_HUGE) != 0uL) {
            return null
        }

        val pdpt = (pml4Entry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdptEntry = pdpt[PageTableLevel.PDPT.index(virtualAddress)]
        if (!pdptEntry.allowsUserAccess(requireWritable)) {
            return null
        }
        if ((pdptEntry and PTE_HUGE) != 0uL) {
            return (pdptEntry and PTE_1_GIB_ADDR_MASK) or
                (virtualAddress and PAGE_1_GIB_OFFSET_MASK)
        }

        val pd = (pdptEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdEntry = pd[PageTableLevel.PD.index(virtualAddress)]
        if (!pdEntry.allowsUserAccess(requireWritable)) {
            return null
        }
        if ((pdEntry and PTE_HUGE) != 0uL) {
            return (pdEntry and PTE_2_MIB_ADDR_MASK) or
                (virtualAddress and PAGE_2_MIB_OFFSET_MASK)
        }

        val pt = (pdEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val ptEntry = pt[PageTableLevel.PT.index(virtualAddress)]
        if (!ptEntry.allowsUserAccess(requireWritable)) {
            return null
        }

        return (ptEntry and PTE_ADDR_MASK) or (virtualAddress and (PAGE_SIZE_BYTES - 1uL))
    }

    /** Returns the resident frame even when the leaf PTE is intentionally non-present. */
    internal fun userPageFrame(virtualAddress: ULong): ULong? {
        if (!virtualAddress.isPageAligned() || virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return null
        }

        val pml4 = pml4Table() ?: return null
        val pml4Entry = pml4[PageTableLevel.PML4.index(virtualAddress)]
        if ((pml4Entry and PTE_PRESENT) == 0uL ||
            (pml4Entry and PTE_USER) == 0uL ||
            (pml4Entry and PTE_HUGE) != 0uL
        ) {
            return null
        }

        val pdpt = (pml4Entry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdptEntry = pdpt[PageTableLevel.PDPT.index(virtualAddress)]
        if ((pdptEntry and PTE_PRESENT) == 0uL ||
            (pdptEntry and PTE_USER) == 0uL ||
            (pdptEntry and PTE_HUGE) != 0uL
        ) {
            return null
        }

        val pd = (pdptEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdEntry = pd[PageTableLevel.PD.index(virtualAddress)]
        if ((pdEntry and PTE_PRESENT) == 0uL ||
            (pdEntry and PTE_USER) == 0uL ||
            (pdEntry and PTE_HUGE) != 0uL
        ) {
            return null
        }

        val pt = (pdEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val entry = pt[PageTableLevel.PT.index(virtualAddress)]
        if ((entry and PTE_USER) == 0uL) {
            return null
        }
        return (entry and PTE_ADDR_MASK).takeIf { it != 0uL }
    }

    fun cloneDirectory(): PageDirectory {
        val sourcePml4 = pml4Table()
            ?: error("Paging: source PML4 is unavailable")
        val allocatedFrames = mutableListOf<ULong>()
        val clonedTables = mutableMapOf<ULong, ULong>()

        val clonedPml4PhysicalAddress = allocateTableFrame(allocatedFrames)
            ?: error("Paging: failed to allocate cloned PML4")
        val clonedPml4 = clonedPml4PhysicalAddress.toVirtualPointer<ULongVar>() ?: run {
            releaseTableFrames(allocatedFrames)
            error("Paging: cloned PML4 is unavailable")
        }
        clonedTables[pml4PhysicalAddress and PTE_ADDR_MASK] = clonedPml4PhysicalAddress

        for (index in 0 until KERNEL_PML4_START_INDEX) {
            if (!cloneEntry(
                    sourceTable = sourcePml4,
                    destinationTable = clonedPml4,
                    index = index,
                    level = PageTableLevel.PML4,
                    allocatedFrames = allocatedFrames,
                    clonedTables = clonedTables,
                )
            ) {
                releaseTableFrames(allocatedFrames)
                error("Paging: failed to clone user page-table hierarchy")
            }
        }

        for (index in KERNEL_PML4_START_INDEX until PTE_COUNT) {
            val entry = sourcePml4[index]
            clonedPml4[index] =
                if ((entry and PTE_PRESENT) != 0uL &&
                    (entry and PTE_ADDR_MASK) == (pml4PhysicalAddress and PTE_ADDR_MASK)
                ) {
                    replaceEntryAddress(entry, clonedPml4PhysicalAddress)
                } else {
                    entry
                }
        }

        return PageDirectory(clonedPml4PhysicalAddress)
    }

    private fun pml4Table(): CPointer<ULongVar>? = pml4PhysicalAddress.toVirtualPointer()

    private fun cloneEntry(
        sourceTable: CPointer<ULongVar>,
        destinationTable: CPointer<ULongVar>,
        index: Int,
        level: PageTableLevel,
        allocatedFrames: MutableList<ULong>,
        clonedTables: MutableMap<ULong, ULong>,
    ): Boolean {
        val entry = sourceTable[index]
        if ((entry and PTE_PRESENT) == 0uL ||
            level == PageTableLevel.PT ||
            level != PageTableLevel.PML4 && (entry and PTE_HUGE) != 0uL
        ) {
            destinationTable[index] = entry
            return true
        }

        if (level == PageTableLevel.PML4 && (entry and PTE_HUGE) != 0uL) {
            println("Paging: invalid huge-page bit in PML4 entry index=$index")
            return false
        }

        val sourceChildPhysicalAddress = entry and PTE_ADDR_MASK
        if (sourceChildPhysicalAddress == 0uL) {
            println("Paging: present page-table entry has no frame at level=$level index=$index")
            return false
        }

        val existingClone = clonedTables[sourceChildPhysicalAddress]
        if (existingClone != null) {
            destinationTable[index] = replaceEntryAddress(entry, existingClone)
            return true
        }

        val sourceChild = sourceChildPhysicalAddress.toVirtualPointer<ULongVar>() ?: return false
        val clonedChildPhysicalAddress = allocateTableFrame(allocatedFrames) ?: return false
        val clonedChild = clonedChildPhysicalAddress.toVirtualPointer<ULongVar>() ?: return false
        clonedTables[sourceChildPhysicalAddress] = clonedChildPhysicalAddress
        destinationTable[index] = replaceEntryAddress(entry, clonedChildPhysicalAddress)

        val childLevel = when (level) {
            PageTableLevel.PML4 -> PageTableLevel.PDPT
            PageTableLevel.PDPT -> PageTableLevel.PD
            PageTableLevel.PD -> PageTableLevel.PT
            PageTableLevel.PT -> return false
        }

        for (childIndex in 0 until PTE_COUNT) {
            if (!cloneEntry(
                    sourceTable = sourceChild,
                    destinationTable = clonedChild,
                    index = childIndex,
                    level = childLevel,
                    allocatedFrames = allocatedFrames,
                    clonedTables = clonedTables,
                )
            ) {
                return false
            }
        }
        return true
    }

    private fun allocateTableFrame(allocatedFrames: MutableList<ULong>): ULong? {
        val frameAddress = BuddyFrameAllocator.allocateFrames(1uL) ?: run {
            println("Paging: failed to allocate frame while cloning directory")
            return null
        }
        val table = frameAddress.toVirtualPointer<ULongVar>() ?: run {
            BuddyFrameAllocator.freeFrames(frameAddress, 1uL)
            return null
        }
        table.clear()
        allocatedFrames += frameAddress
        return frameAddress
    }

    private fun releaseTableFrames(allocatedFrames: List<ULong>) {
        for (index in allocatedFrames.lastIndex downTo 0) {
            BuddyFrameAllocator.freeFrames(allocatedFrames[index], 1uL)
        }
    }

    private fun replaceEntryAddress(entry: ULong, physicalAddress: ULong): ULong =
        (entry and PTE_ADDR_MASK.inv()) or (physicalAddress and PTE_ADDR_MASK)

    private fun ULong.allowsUserAccess(requireWritable: Boolean): Boolean =
        (this and PTE_PRESENT) != 0uL &&
            (this and PTE_USER) != 0uL &&
            (!requireWritable || (this and PTE_WRITABLE) != 0uL)

    private fun ensureChildTable(
        parentTable: CPointer<ULongVar>,
        index: Int,
    ): CPointer<ULongVar>? {
        val entry = parentTable[index]
        if (entry and PTE_PRESENT != 0uL) {
            if (entry and PTE_HUGE != 0uL) {
                println("Paging: huge-page entry blocks split at index=$index")
                return null
            }
            return (entry and PTE_ADDR_MASK).toVirtualPointer()
        }

        val frameAddress = BuddyFrameAllocator.allocateFrames(1uL) ?: run {
            println("Paging: failed to allocate frame for paging structure")
            return null
        }

        val tablePointer = frameAddress.toVirtualPointer<ULongVar>() ?: run {
            println("Paging: frame to pointer conversion failed for ${frameAddress.hex()}")
            BuddyFrameAllocator.freeFrames(frameAddress, 1uL)
            return null
        }

        tablePointer.clear()
        parentTable[index] = (frameAddress and PTE_ADDR_MASK) or PTE_PARENT_FLAGS
        return tablePointer
    }

}

object KernelPageDirectory {
    private var activeDirectory: PageDirectory? = null

    fun initialize(): PageDirectory? {
        activeDirectory?.let { return it }

        if (!BuddyFrameAllocator.isReady) {
            BuddyFrameAllocator.initialize()
        }

        val pml4PhysicalAddress = read_cr3() and PTE_ADDR_MASK
        if (pml4PhysicalAddress == 0uL) {
            println("Paging: CR3 is zero")
            return null
        }

        return PageDirectory(pml4PhysicalAddress).also { directory ->
            activeDirectory = directory
            println("Paging: active PML4=${pml4PhysicalAddress.hex()}")
        }
    }

    fun getDirectory(): PageDirectory =
        checkNotNull(activeDirectory ?: initialize()) { "Paging: active directory is unavailable" }

    fun mapMmio(
        physicalAddress: ULong,
        byteLength: ULong,
    ): ULong? = (activeDirectory ?: initialize())?.mapMmioRange(physicalAddress, byteLength)
}
