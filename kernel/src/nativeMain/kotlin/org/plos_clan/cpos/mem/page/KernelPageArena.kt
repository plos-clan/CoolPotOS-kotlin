@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.BuddyBlocks
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.mem.addressspace.MMIO_VIRTUAL_BASE
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PTE_COUNT
import org.plos_clan.cpos.utils.toVirtualPointer

internal class KernelPageArena(private val directory: PageDirectory) {
    private val lock = IrqSpinLock()
    private val blocks: BuddyBlocks
    private val base: ULong
    private val tableBytes = PAGE_SIZE_BYTES * PTE_COUNT.toUInt()
    private val directories: Array<CPointer<ULongVar>>

    init {
        var start = KERNEL_PML4_START_INDEX
        var length = 0
        var run = 0
        val limit = PageTableLevel.PML4.index(MMIO_VIRTUAL_BASE)
        for (index in KERNEL_PML4_START_INDEX until limit) {
            run = if (directory.root[index] == 0uL) run + 1 else 0
            if (run <= length) continue
            start = index + 1 - run
            length = run
        }
        check(length > 0)
        val slots = length.toULong() * PTE_COUNT.toUInt() * PTE_COUNT.toUInt()
        val frames = BuddyFrameAllocator.statistics().totalBytes / PAGE_SIZE_BYTES
        val order = ULong.SIZE_BITS - (frames - 1uL).countLeadingZeroBits()
        val availableOrder = ULong.SIZE_BITS - 1 - slots.countLeadingZeroBits()
        blocks = BuddyBlocks(minOf(order, availableOrder))
        base = (start.toLong() - PTE_COUNT).toULong() shl PageTableLevel.PML4.shift
        val count = ((1 shl blocks.maxOrder) - 1) / PTE_COUNT + 1
        directories = Array(count) { index ->
            val address = base + (index.toULong() shl PageTableLevel.PDPT.shift)
            directory.kernelDirectory(address)
        }
    }

    fun allocate(pages: ULong): ULong {
        if (pages == 0uL) return 0uL
        val physicalOrder = ULong.SIZE_BITS - (pages - 1uL).countLeadingZeroBits()
        val padding = (1uL shl physicalOrder) - pages
        val tables = (pages - 1uL) / PTE_COUNT.toUInt() + 1uL
        if (padding > tables) return map(pages)
        val single = pages == 1uL
        val frame = BuddyFrameAllocator.allocate(pages, reclaim = single)
        if (frame != INVALID_FRAME) return Hhdm.toVirtual(frame)
        return if (single) 0uL else map(pages)
    }

    internal fun map(pages: ULong): ULong {
        if (pages == 0uL) return 0uL
        val order = order(pages)
        if (order > blocks.maxOrder) return 0uL
        val start = lock.withLock { blocks.take(order) }
        if (start < 0) return 0uL
        var remaining = pages
        var slot = start
        while (remaining > 0uL) {
            val count = minOf(remaining, PTE_COUNT.toULong()).toInt()
            if (!populate(slot, count)) {
                unmap(start, pages - remaining, order)
                return 0uL
            }
            remaining -= count.toUInt()
            slot++
        }
        return base + start.toULong() * tableBytes
    }

    private fun populate(slot: Int, count: Int): Boolean {
        val table = PageDirectory.allocateTable()
        if (table == INVALID_FRAME) return false
        val entries = checkNotNull(table.toVirtualPointer<ULongVar>())
        for (index in 0 until count) {
            val frame = BuddyFrameAllocator.allocate(1uL)
            if (frame == INVALID_FRAME) {
                freeTable(table, index)
                return false
            }
            entries[index] = frame or PTE_PRESENT or PTE_WRITABLE or PTE_NO_EXECUTE
        }
        val parent = directories[slot / PTE_COUNT]
        parent[slot % PTE_COUNT] = table or PTE_PRESENT or PTE_WRITABLE
        return true
    }

    fun release(address: ULong, pages: ULong) {
        val offset = address - base
        if (offset >= (tableBytes shl blocks.maxOrder)) {
            check(BuddyFrameAllocator.free(address - Hhdm.offset, pages))
            return
        }
        unmap((offset / tableBytes).toInt(), pages, order(pages))
    }

    private fun unmap(start: Int, pages: ULong, order: Int) {
        val slots = (pages + PTE_COUNT.toUInt() - 1uL) / PTE_COUNT.toUInt()
        val end = start + slots.toInt()
        for (slot in start until end) {
            val parent = directories[slot / PTE_COUNT]
            val index = slot % PTE_COUNT
            parent[index] = parent[index] and PTE_ADDR_MASK
        }
        if (pages > 0uL) {
            val address = base + start.toULong() * tableBytes
            directory.invalidate(address, address + pages * PAGE_SIZE_BYTES)
        }
        var remaining = pages
        for (slot in start until end) {
            val parent = directories[slot / PTE_COUNT]
            val index = slot % PTE_COUNT
            val table = parent[index]
            parent[index] = 0uL
            val count = minOf(remaining, PTE_COUNT.toULong()).toInt()
            freeTable(table, count)
            remaining -= count.toUInt()
        }
        lock.withLock { blocks.put(start, order) }
    }

    private fun freeTable(table: ULong, count: Int) {
        val entries = checkNotNull(table.toVirtualPointer<ULongVar>())
        for (index in 0 until count) {
            val frame = entries[index] and PTE_ADDR_MASK
            check(BuddyFrameAllocator.free(frame, 1uL))
        }
        check(BuddyFrameAllocator.free(table, 1uL))
    }

    private fun order(pages: ULong) =
        ULong.SIZE_BITS - ((pages - 1uL) / PTE_COUNT.toUInt()).countLeadingZeroBits()
}
