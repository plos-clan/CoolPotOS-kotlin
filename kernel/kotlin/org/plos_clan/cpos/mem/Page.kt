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
private val PTE_PARENT_FLAGS = PTE_PRESENT or PTE_WRITABLE or PTE_USER
private val MMIO_PTE_FLAGS = PTE_PRESENT or PTE_WRITABLE or PTE_NO_CACHE or PTE_NO_EXECUTE

data class PageDirectory(val pml4PhysicalAddress: ULong) {

    fun mapPage(virtualAddress: ULong, physicalAddress: ULong, flags: ULong): Boolean {
        if (!virtualAddress.isPageAligned() || !physicalAddress.isPageAligned()) {
            println("Paging: unaligned map request v=${virtualAddress.hex()} p=${physicalAddress.hex()}")
            return false
        }

        val pml4 = pml4Table() ?: return false
        val pdpt = ensureChildTable(pml4, pml4Index(virtualAddress)) ?: return false
        val pd = ensureChildTable(pdpt, pdptIndex(virtualAddress)) ?: return false
        val pt = ensureChildTable(pd, pdIndex(virtualAddress)) ?: return false

        pt[ptIndex(virtualAddress)] = (physicalAddress and PTE_ADDR_MASK) or flags or PTE_PRESENT
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

    private fun pml4Table(): CPointer<ULongVar>? = pml4PhysicalAddress.toVirtualPointer()

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
            return null
        }

        tablePointer.clear()
        parentTable[index] = (frameAddress and PTE_ADDR_MASK) or PTE_PARENT_FLAGS
        return tablePointer
    }

    private fun pml4Index(address: ULong): Int = ((address shr 39) and 0x1ffuL).toInt()
    private fun pdptIndex(address: ULong): Int = ((address shr 30) and 0x1ffuL).toInt()
    private fun pdIndex(address: ULong): Int = ((address shr 21) and 0x1ffuL).toInt()
    private fun ptIndex(address: ULong): Int = ((address shr 12) and 0x1ffuL).toInt()
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

    fun getDirectory() : PageDirectory = activeDirectory!!

    fun mapMmio(
        physicalAddress: ULong,
        byteLength: ULong,
    ): ULong? = (activeDirectory ?: initialize())?.mapMmioRange(physicalAddress, byteLength)
}
