@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.toPointer
import platform.posix.memset

internal class MappedUserMemory(byteCount: Int) {
    private companion object {
        const val PAGE_TABLE_COUNT = 4
        const val USER_ADDRESS = 0x0010_0080uL
    }

    private val allocation: CPointer<UByteVar>
    private val pageDirectory: PageDirectory

    init {
        require(byteCount > 0)

        val pageSize = PAGE_SIZE_BYTES.toInt()
        val firstVirtualPage = USER_ADDRESS.alignDown(PAGE_SIZE_BYTES)
        val mappedByteCount = (USER_ADDRESS - firstVirtualPage).toInt() + byteCount
        val pageCount = (mappedByteCount + pageSize - 1) / pageSize
        val allocatedPageCount = PAGE_TABLE_COUNT + pageCount
        allocation = nativeHeap.allocArray(allocatedPageCount * pageSize + pageSize - 1)

        val allocationAddress = allocation.rawValue.toLong().toULong()
        val pml4Address = checkNotNull(allocationAddress.alignUp(PAGE_SIZE_BYTES))
        val pdptAddress = pml4Address + PAGE_SIZE_BYTES
        val pdAddress = pdptAddress + PAGE_SIZE_BYTES
        val ptAddress = pdAddress + PAGE_SIZE_BYTES
        val dataAddress = ptAddress + PAGE_SIZE_BYTES
        val allocatedBytes = allocatedPageCount * pageSize
        memset(checkNotNull(pml4Address.toPointer<UByteVar>()), 0, allocatedBytes.toULong())

        val flags = PTE_PRESENT or PTE_WRITABLE or PTE_USER
        val pml4 = checkNotNull(pml4Address.toPointer<ULongVar>())
        val pdpt = checkNotNull(pdptAddress.toPointer<ULongVar>())
        val pd = checkNotNull(pdAddress.toPointer<ULongVar>())
        val pt = checkNotNull(ptAddress.toPointer<ULongVar>())
        pml4[PageTableLevel.PML4.index(USER_ADDRESS)] = pdptAddress or flags
        pdpt[PageTableLevel.PDPT.index(USER_ADDRESS)] = pdAddress or flags
        pd[PageTableLevel.PD.index(USER_ADDRESS)] = ptAddress or flags
        repeat(pageCount) { index ->
            val virtualPage = firstVirtualPage + index.toULong() * PAGE_SIZE_BYTES
            val physicalPage = dataAddress + index.toULong() * PAGE_SIZE_BYTES
            pt[PageTableLevel.PT.index(virtualPage)] = physicalPage or flags
        }
        memset(
            checkNotNull(dataAddress.toPointer<UByteVar>()),
            0x5A,
            pageCount.toULong() * PAGE_SIZE_BYTES,
        )
        pageDirectory = PageDirectory(pml4Address)
    }

    fun create(): UserMemory = UserMemory(pageDirectory, USER_ADDRESS)

    fun close() = nativeHeap.free(allocation.rawValue)
}
