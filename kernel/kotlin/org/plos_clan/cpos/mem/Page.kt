@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import natives.invlpg
import natives.read_cr3
import org.plos_clan.cpos.utils.*


val PTE_PRESENT: ULong = 1uL shl 0
val PTE_WRITABLE: ULong = 1uL shl 1
val PTE_USER: ULong = 1uL shl 2
val PTE_NO_CACHE: ULong = 1uL shl 4
val PTE_HUGE: ULong = 1uL shl 7
val PTE_NO_EXECUTE: ULong = 1uL shl 63
val PTE_ADDR_MASK: ULong = 0x000F_FFFF_FFFF_F000u
val PTE_PARENT_FLAGS: ULong = PTE_PRESENT or PTE_WRITABLE or PTE_USER

enum class MappingType(val flags: ULong) {
    UserCode(PTE_PRESENT or PTE_WRITABLE or PTE_USER),
    UserData(PTE_PRESENT or PTE_WRITABLE or PTE_USER or PTE_NO_EXECUTE),
    Mmio(PTE_PRESENT or PTE_WRITABLE or PTE_NO_CACHE or PTE_NO_EXECUTE),
    KernelData(PTE_PRESENT or PTE_WRITABLE or PTE_NO_EXECUTE),
}

data class PageDirectory(
    val pml4PhysicalAddress: ULong,
) {
    fun mapPage(virtualAddress: ULong, physicalAddress: ULong, flags: ULong): Boolean {
        if (!virtualAddress.isPageAligned() || !physicalAddress.isPageAligned()) {
            println(
                "Paging: unaligned map request v=0x${virtualAddress.hex64()} p=0x${physicalAddress.hex64()}",
            )
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

        return if (mapRange(virtualBase, physicalBase, length, MappingType.Mmio.flags)) {
            Hhdm.toVirtual(physicalAddress)
        } else {
            null
        }
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
            println("Paging: frame to pointer conversion failed for 0x${frameAddress.hex64()}")
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

    val current: PageDirectory?
        get() = activeDirectory

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
            println("Paging: active PML4=0x${pml4PhysicalAddress.hex64()}")
        }
    }

    fun mapMmio(
        physicalAddress: ULong,
        byteLength: ULong,
    ): ULong? = (activeDirectory ?: initialize())?.mapMmioRange(physicalAddress, byteLength)
}
