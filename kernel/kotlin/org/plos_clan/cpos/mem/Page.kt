@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import bridge.invlpg
import bridge.read_cr3
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.utils.*
import platform.posix.memcpy

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

internal object UserFrameReferences {
    private const val PAGE_SHIFT = 12
    private const val SECTION_FRAME_BITS = 12
    private const val SECTION_FRAME_COUNT = 1 shl SECTION_FRAME_BITS
    private const val SECTION_FRAME_MASK = SECTION_FRAME_COUNT - 1

    private val sections = mutableMapOf<ULong, IntArray>()
    private val lock = IrqSpinLock()

    fun retain(frame: ULong) = lock.withLock {
        val counts = section(frame)
        val index = sectionIndex(frame)
        counts[index]++
    }

    fun shareAll(frames: Iterable<ULong>) = lock.withLock {
        var currentKey = ULong.MAX_VALUE
        var current = IntArray(0)
        frames.forEach { frame ->
            val key = sectionKey(frame)
            if (key != currentKey) {
                currentKey = key
                current = sections.getOrPut(key) { IntArray(SECTION_FRAME_COUNT) }
            }
            val index = sectionIndex(frame)
            current[index] = maxOf(current[index], 1) + 1
        }
    }

    fun release(frame: ULong) {
        if (lock.withLock { releaseLocked(frame) }) {
            BuddyFrameAllocator.freeFrames(frame, 1uL)
        }
    }

    fun releaseAll(frames: Iterable<ULong>) {
        val reclaimed = lock.withLock {
            val result = mutableListOf<ULong>()
            var currentKey = ULong.MAX_VALUE
            var current: IntArray? = null
            frames.forEach { frame ->
                val key = sectionKey(frame)
                if (key != currentKey) {
                    currentKey = key
                    current = sections[key]
                }
                val counts = current
                val index = sectionIndex(frame)
                if (counts == null || counts[index] <= 1) {
                    counts?.set(index, 0)
                    result += frame
                } else {
                    counts[index]--
                }
            }
            result
        }
        BuddyFrameAllocator.freeFrames(reclaimed)
    }

    fun isExclusive(frame: ULong): Boolean = lock.withLock {
        referenceCount(frame) <= 1
    }

    fun copyOnWrite(frame: ULong): ULong? = lock.withLock {
        if (referenceCount(frame) <= 1) {
            return@withLock frame
        }

        val replacement = BuddyFrameAllocator.allocateFrames(1uL)
            ?: return@withLock null
        val source = Hhdm.toVirtualPointer<UByteVar>(frame)
        val destination = Hhdm.toVirtualPointer<UByteVar>(replacement)
        if (source == null || destination == null) {
            BuddyFrameAllocator.freeFrames(replacement, 1uL)
            return@withLock null
        }

        memcpy(destination, source, PAGE_SIZE_BYTES)
        section(replacement)[sectionIndex(replacement)] = 1
        replacement
    }

    private fun releaseLocked(frame: ULong): Boolean {
        val counts = sections[sectionKey(frame)] ?: return true
        val index = sectionIndex(frame)
        if (counts[index] <= 1) {
            counts[index] = 0
            return true
        }
        counts[index]--
        return false
    }

    private fun referenceCount(frame: ULong): Int =
        sections[sectionKey(frame)]?.get(sectionIndex(frame)) ?: 0

    private fun section(frame: ULong): IntArray =
        sections.getOrPut(sectionKey(frame)) { IntArray(SECTION_FRAME_COUNT) }

    private fun sectionKey(frame: ULong): ULong =
        frame shr (PAGE_SHIFT + SECTION_FRAME_BITS)

    private fun sectionIndex(frame: ULong): Int =
        ((frame shr PAGE_SHIFT).toInt() and SECTION_FRAME_MASK)
}

private enum class PageTableLevel(val shift: Int) {
    PML4(39),
    PDPT(30),
    PD(21),
    PT(12);

    fun index(address: ULong): Int = ((address shr shift) and PAGE_TABLE_INDEX_MASK).toInt()
}

private data class ModifiedPage(
    val address: ULong,
    val entry: ULong,
)

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
        if (!mapPage(virtualAddress, physicalAddress, flags)) {
            return false
        }
        UserFrameReferences.retain(physicalAddress)
        return true
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

    internal fun releaseUserPage(virtualAddress: ULong): Boolean =
        unmapPage(virtualAddress)?.let { physicalAddress ->
            UserFrameReferences.release(physicalAddress)
            true
        } ?: false

    internal fun clearUserMappings() {
        val pml4 = pml4Table() ?: return
        val userFrames = mutableListOf<ULong>()
        val tableFrames = mutableListOf<ULong>()
        for (index in 0 until KERNEL_PML4_START_INDEX) {
            val entry = pml4[index]
            if ((entry and PTE_PRESENT) == 0uL) continue
            check((entry and PTE_HUGE) == 0uL) { "Paging: huge page in user PML4" }
            collectUserTable(
                physicalAddress = entry and PTE_ADDR_MASK,
                level = PageTableLevel.PDPT,
                userFrames = userFrames,
                tableFrames = tableFrames,
            )
            pml4[index] = 0uL
        }
        UserFrameReferences.releaseAll(userFrames)
        BuddyFrameAllocator.freeFrames(tableFrames)
        if ((read_cr3() and PTE_ADDR_MASK) == pml4PhysicalAddress) {
            bridge.write_cr3(pml4PhysicalAddress)
        }
    }

    internal fun destroyUserDirectory() {
        check((read_cr3() and PTE_ADDR_MASK) != pml4PhysicalAddress) {
            "Paging: cannot destroy the active directory"
        }
        clearUserMappings()
        BuddyFrameAllocator.freeFrames(pml4PhysicalAddress, 1uL)
    }

    internal fun protectUserPage(
        virtualAddress: ULong,
        accessible: Boolean,
        writable: Boolean,
        executable: Boolean,
        privateMapping: Boolean,
    ): Boolean {
        if (!virtualAddress.isPageAligned() || virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return false
        }
        val pt = userPageTable(virtualAddress) ?: return false
        val index = PageTableLevel.PT.index(virtualAddress)
        val entry = pt[index]
        val physicalAddress = entry and PTE_ADDR_MASK
        if (physicalAddress == 0uL) {
            return false
        }

        val writeEnabled = writable &&
            (!privateMapping || UserFrameReferences.isExclusive(physicalAddress))
        pt[index] = physicalAddress or PTE_USER or
            (if (accessible) PTE_PRESENT else 0uL) or
            (if (writeEnabled) PTE_WRITABLE else 0uL) or
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

    internal fun makeUserPageWritable(
        virtualAddress: ULong,
        privateMapping: Boolean,
    ): Boolean {
        if (!virtualAddress.isPageAligned() || virtualAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return false
        }
        val pt = userPageTable(virtualAddress) ?: return false
        val index = PageTableLevel.PT.index(virtualAddress)
        val entry = pt[index]
        if ((entry and PTE_USER) == 0uL || (entry and PTE_ADDR_MASK) == 0uL) {
            return false
        }
        val frame = entry and PTE_ADDR_MASK
        if ((entry and PTE_PRESENT) != 0uL && (entry and PTE_WRITABLE) != 0uL) {
            return true
        }

        val replacement = if (privateMapping) {
            UserFrameReferences.copyOnWrite(frame)
        } else {
            frame
        } ?: return false
        pt[index] = (entry and PTE_ADDR_MASK.inv()) or replacement or
            PTE_PRESENT or PTE_WRITABLE
        if (replacement != frame) {
            UserFrameReferences.release(frame)
        }
        invlpg(virtualAddress)
        return true
    }

    internal fun cloneDirectory(sharedRegions: List<MemoryRegion>): PageDirectory {
        val sourcePml4 = pml4Table()
            ?: error("Paging: source PML4 is unavailable")
        val allocatedFrames = mutableListOf<ULong>()
        val sharedFrames = mutableListOf<ULong>()
        val modifiedPages = mutableListOf<ModifiedPage>()

        val clonedPml4PhysicalAddress = allocateTableFrame(allocatedFrames)
            ?: error("Paging: failed to allocate cloned PML4")
        val clonedPml4 = clonedPml4PhysicalAddress.toVirtualPointer<ULongVar>() ?: run {
            releaseTableFrames(allocatedFrames)
            error("Paging: cloned PML4 is unavailable")
        }
        memcpy(clonedPml4, sourcePml4, PAGE_SIZE_BYTES)

        for (index in 0 until KERNEL_PML4_START_INDEX) {
            val entry = sourcePml4[index]
            if ((entry and PTE_PRESENT) == 0uL) continue
            if ((entry and PTE_HUGE) != 0uL) {
                rollbackClone(allocatedFrames, modifiedPages)
                error("Paging: invalid huge-page bit in user PML4")
            }
            val child = cloneTable(
                sourcePhysicalAddress = entry and PTE_ADDR_MASK,
                level = PageTableLevel.PDPT,
                virtualBase = index.toULong() shl PageTableLevel.PML4.shift,
                sharedRegions = sharedRegions,
                allocatedFrames = allocatedFrames,
                sharedFrames = sharedFrames,
                modifiedPages = modifiedPages,
            )
            if (child == null) {
                rollbackClone(allocatedFrames, modifiedPages)
                error("Paging: failed to clone user page-table hierarchy")
            }
            clonedPml4[index] = replaceEntryAddress(entry, child)
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

        UserFrameReferences.shareAll(sharedFrames)
        if (modifiedPages.isNotEmpty() &&
            (read_cr3() and PTE_ADDR_MASK) == pml4PhysicalAddress
        ) {
            bridge.write_cr3(pml4PhysicalAddress)
        }
        return PageDirectory(clonedPml4PhysicalAddress)
    }

    private fun pml4Table(): CPointer<ULongVar>? = pml4PhysicalAddress.toVirtualPointer()

    private fun userPageTable(virtualAddress: ULong): CPointer<ULongVar>? {
        val pml4 = pml4Table() ?: return null
        val pml4Entry = pml4[PageTableLevel.PML4.index(virtualAddress)]
        if (!pml4Entry.allowsUserAccess(false) || (pml4Entry and PTE_HUGE) != 0uL) {
            return null
        }
        val pdpt = (pml4Entry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdptEntry = pdpt[PageTableLevel.PDPT.index(virtualAddress)]
        if (!pdptEntry.allowsUserAccess(false) || (pdptEntry and PTE_HUGE) != 0uL) {
            return null
        }
        val pd = (pdptEntry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>() ?: return null
        val pdEntry = pd[PageTableLevel.PD.index(virtualAddress)]
        if (!pdEntry.allowsUserAccess(false) || (pdEntry and PTE_HUGE) != 0uL) {
            return null
        }
        return (pdEntry and PTE_ADDR_MASK).toVirtualPointer()
    }

    private fun cloneTable(
        sourcePhysicalAddress: ULong,
        level: PageTableLevel,
        virtualBase: ULong,
        sharedRegions: List<MemoryRegion>,
        allocatedFrames: MutableList<ULong>,
        sharedFrames: MutableList<ULong>,
        modifiedPages: MutableList<ModifiedPage>,
    ): ULong? {
        val source = sourcePhysicalAddress.toVirtualPointer<ULongVar>() ?: return null
        val destinationPhysicalAddress = allocateTableFrame(allocatedFrames) ?: return null
        val destination = destinationPhysicalAddress.toVirtualPointer<ULongVar>() ?: return null
        memcpy(destination, source, PAGE_SIZE_BYTES)
        for (index in 0 until PTE_COUNT) {
            val entry = source[index]
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
                    source[index] = readOnlyEntry
                    destination[index] = readOnlyEntry
                    modifiedPages += ModifiedPage(address, entry)
                }
                continue
            }

            if ((entry and PTE_PRESENT) == 0uL) continue
            if ((entry and PTE_HUGE) != 0uL) {
                return null
            }
            val nextLevel = when (level) {
                PageTableLevel.PDPT -> PageTableLevel.PD
                PageTableLevel.PD -> PageTableLevel.PT
                else -> return null
            }
            val clonedChild = cloneTable(
                sourcePhysicalAddress = child,
                level = nextLevel,
                virtualBase = virtualBase + (index.toULong() shl level.shift),
                sharedRegions = sharedRegions,
                allocatedFrames = allocatedFrames,
                sharedFrames = sharedFrames,
                modifiedPages = modifiedPages,
            ) ?: return null
            destination[index] = replaceEntryAddress(entry, clonedChild)
        }
        return destinationPhysicalAddress
    }

    private fun rollbackClone(
        allocatedFrames: List<ULong>,
        modifiedPages: List<ModifiedPage>,
    ) {
        modifiedPages.asReversed().forEach { page ->
            val table = userPageTable(page.address) ?: return@forEach
            table[PageTableLevel.PT.index(page.address)] = page.entry
        }
        if (modifiedPages.isNotEmpty() &&
            (read_cr3() and PTE_ADDR_MASK) == pml4PhysicalAddress
        ) {
            bridge.write_cr3(pml4PhysicalAddress)
        }
        releaseTableFrames(allocatedFrames)
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

    private fun collectUserTable(
        physicalAddress: ULong,
        level: PageTableLevel,
        userFrames: MutableList<ULong>,
        tableFrames: MutableList<ULong>,
    ) {
        val table = physicalAddress.toVirtualPointer<ULongVar>()
            ?: error("Paging: user table is unavailable at ${physicalAddress.hex()}")
        for (index in 0 until PTE_COUNT) {
            val entry = table[index]
            val child = entry and PTE_ADDR_MASK
            if (child == 0uL) continue
            if (level == PageTableLevel.PT) {
                if ((entry and PTE_USER) != 0uL) userFrames += child
                continue
            }
            if ((entry and PTE_PRESENT) == 0uL) continue
            check((entry and PTE_HUGE) == 0uL) { "Paging: user huge pages are unsupported" }
            collectUserTable(
                physicalAddress = child,
                level = when (level) {
                    PageTableLevel.PDPT -> PageTableLevel.PD
                    PageTableLevel.PD -> PageTableLevel.PT
                    else -> error("Paging: invalid user table level $level")
                },
                userFrames = userFrames,
                tableFrames = tableFrames,
            )
        }
        tableFrames += physicalAddress
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
