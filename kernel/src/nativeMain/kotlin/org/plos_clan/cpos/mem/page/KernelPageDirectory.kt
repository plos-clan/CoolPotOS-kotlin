@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import bridge.read_cr3
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.utils.hex

object KernelPageDirectory {
    private var activeDirectory: PageDirectory? = null

    val addressSpace: AddressSpace by lazy {
        AddressSpace.kernel(getDirectory())
    }

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
}
