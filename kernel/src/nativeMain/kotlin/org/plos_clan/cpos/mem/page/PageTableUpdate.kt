@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_EXECUTABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PTE_COUNT
import org.plos_clan.cpos.utils.toVirtualPointer

internal value class PagePermissions(val flags: ULong) {
    fun apply(entry: ULong, privateMapping: Boolean): ULong {
        if (entry and PTE_USER == 0uL) return entry
        val updated = (entry and ACCESS_BITS.inv()) or flags
        if (!privateMapping || flags and PTE_WRITABLE == 0uL) return updated
        val exclusive = UserFrameReferences.isExclusive(entry and PTE_ADDR_MASK)
        return if (exclusive) updated else updated and PTE_WRITABLE.inv()
    }

    companion object {
        private val ACCESS_BITS = PTE_PRESENT or PTE_WRITABLE or PTE_NO_EXECUTE

        fun user(access: ULong, allowWrite: Boolean = true): PagePermissions {
            val present = if (access != 0uL) PTE_PRESENT else 0uL
            val write = allowWrite && access and MEMORY_REGION_WRITABLE != 0uL
            val nx = if (access and MEMORY_REGION_EXECUTABLE == 0uL) PTE_NO_EXECUTE else 0uL
            return PagePermissions(PTE_USER or present or nx or (if (write) PTE_WRITABLE else 0uL))
        }
    }
}

internal class PageTableUpdate(private val directory: PageDirectory) {
    private fun interface Edit {
        fun apply(entry: ULong): ULong
    }

    private var first = ULong.MAX_VALUE
    private var end = 0uL
    private var frames = EMPTY
    private var retired = 0

    fun remove(start: ULong, limit: ULong, release: Boolean = true, selected: Set<ULong>? = null) {
        if (start >= limit) return
        val pages = (limit - start - 1uL) / PAGE_SIZE_BYTES + 1uL
        if (release) {
            val needed = minOf(retired.toULong() + pages, PTE_COUNT.toULong()).toInt()
            if (frames.size < needed) {
                val capacity = maxOf(needed, minOf(frames.size * 2, PTE_COUNT))
                frames = frames.copyOf(capacity)
            }
        }
        walk(directory.root, PageTableLevel.PML4, start, limit) { entry ->
            val frame = entry and PTE_ADDR_MASK
            if (selected != null && frame !in selected) return@walk entry
            if (release && entry and PTE_USER != 0uL) {
                if (retired == frames.size) flush()
                frames[retired++] = frame
            }
            0uL
        }
    }

    fun protect(start: ULong, limit: ULong, permissions: PagePermissions, privateMapping: Boolean) =
        walk(directory.root, PageTableLevel.PML4, start, limit) { permissions.apply(it, privateMapping) }

    private fun walk(
        table: CPointer<ULongVar>,
        level: PageTableLevel,
        start: ULong,
        limit: ULong,
        edit: Edit,
    ) {
        val span = 1uL shl level.shift
        var address = start
        while (address < limit) {
            val index = level.index(address)
            val entry = table[index]
            val remaining = span - (address and (span - 1uL))
            val next = address + minOf(remaining, limit - address)
            if (entry != 0uL && level == PageTableLevel.PT) {
                val updated = edit.apply(entry)
                if (entry != updated) {
                    table[index] = updated
                    changed(address)
                }
            } else if (entry and PTE_PRESENT != 0uL) {
                check(entry and PTE_HUGE == 0uL)
                val child = checkNotNull((entry and PTE_ADDR_MASK).toVirtualPointer<ULongVar>())
                walk(child, PageTableLevel.entries[level.ordinal + 1], address, next, edit)
            }
            address = next
        }
    }

    fun changed(address: ULong) {
        first = minOf(first, address)
        end = maxOf(end, address + PAGE_SIZE_BYTES)
    }

    fun flush() {
        if (first >= end) return
        directory.invalidate(first, end)
        for (index in 0 until retired) UserFrameReferences.release(frames[index])
        first = ULong.MAX_VALUE
        end = 0uL
        retired = 0
    }

    private companion object {
        val EMPTY = ULongArray(0)
    }
}
