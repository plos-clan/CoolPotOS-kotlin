@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.isPageAligned
import platform.posix.memcpy
import platform.posix.memset

const val VMA_READ = 0x1uL
const val VMA_WRITE = 0x2uL
const val VMA_EXEC = 0x4uL
const val VMA_SHARED = 0x8uL
const val VMA_ANON = 0x10uL
const val VMA_STACK = 0x80uL

const val USER_MMAP_START = 0x0000_0000_0001_0000uL
const val USER_MMAP_END = 0x0000_7f00_0000_0000uL

private const val EIO = 5
private const val ENOMEM = 12
private const val EFAULT = 14
private const val EEXIST = 17
private const val EINVAL = 22

enum class VmaType {
    ANONYMOUS,
    FILE,
    IMAGE,
    STACK,
}

data class MemChunk(
    var start: ULong,
    var end: ULong,
    var flags: ULong,
    val name: String?,
    val type: VmaType = VmaType.ANONYMOUS,
    var offset: ULong = 0uL,
    val shared: Boolean = false,
) {
    val length: ULong
        get() = end - start
}

data class VmaMapRequest(
    val hint: ULong,
    val length: ULong,
    val access: ULong,
    val fixed: Boolean,
    val noReplace: Boolean,
    val shared: Boolean,
    val type: VmaType,
    val offset: ULong = 0uL,
    val name: String? = null,
    /** Returns bytes read, or a negative errno. */
    val pageReader: ((offset: ULong, destination: ByteArray) -> Int)? = null,
)

sealed interface VmaResult<out T> {
    data class Ok<T>(val value: T) : VmaResult<T>
    data class Err(val errno: Int) : VmaResult<Nothing>
}

class VMA internal constructor(
    val pageDirectory: PageDirectory,
) {
    private val regions = mutableListOf<MemChunk>()
    private val lock = IrqSpinLock()

    val chunks: List<MemChunk>
        get() = lock.withLock { regions.map { it.copy() } }

    val used: ULong
        get() = lock.withLock { regions.fold(0uL) { total, region -> total + region.length } }

    fun find(address: ULong): MemChunk? = lock.withLock {
        findLocked(address)?.copy()
    }

    fun findIntersection(start: ULong, end: ULong): MemChunk? = lock.withLock {
        findIntersectionLocked(start, end)?.copy()
    }

    fun insert(chunk: MemChunk): Boolean = insertAll(listOf(chunk))

    fun insertAll(chunks: List<MemChunk>): Boolean {
        if (chunks.isEmpty()) {
            return true
        }
        val additions = chunks.map { it.copy() }.sortedBy(MemChunk::start)
        if (additions.any { !validRegion(it) } ||
            additions.zipWithNext().any { (left, right) -> left.end > right.start }
        ) {
            return false
        }

        return lock.withLock {
            if (additions.any { findIntersectionLocked(it.start, it.end) != null }) {
                return@withLock false
            }
            additions.forEach(::insertLocked)
            true
        }
    }

    fun map(request: VmaMapRequest): VmaResult<ULong> {
        val alignedLength = alignLength(request.length)
            ?: return VmaResult.Err(EINVAL)
        if ((request.access and (VMA_READ or VMA_WRITE or VMA_EXEC).inv()) != 0uL ||
            request.offset > ULong.MAX_VALUE - alignedLength
        ) {
            return VmaResult.Err(EINVAL)
        }
        if (alignedLength > USER_MMAP_END - USER_MMAP_START) {
            return VmaResult.Err(ENOMEM)
        }

        val selection = lock.withLock {
            val selected = if (request.fixed) {
                if (!request.hint.isPageAligned() ||
                    !validMmapRange(request.hint, alignedLength)
                ) {
                    return@withLock null
                }
                if (findIntersectionLocked(request.hint, request.hint + alignedLength) != null) {
                    if (request.noReplace) {
                        return@withLock Pair(ULong.MAX_VALUE, null)
                    }
                    unmapRangeLocked(request.hint, request.hint + alignedLength)
                }
                request.hint
            } else {
                findUnmappedAreaLocked(request.hint, alignedLength)
                    ?: return@withLock null
            }

            val region = MemChunk(
                start = selected,
                end = selected + alignedLength,
                flags = request.access or
                    (if (request.shared) VMA_SHARED else 0uL) or
                    (if (request.type == VmaType.ANONYMOUS) VMA_ANON else 0uL),
                name = request.name,
                type = request.type,
                offset = request.offset,
                shared = request.shared,
            )
            if (!insertLocked(region)) null else Pair(selected, region)
        } ?: return VmaResult.Err(ENOMEM)

        if (selection.first == ULong.MAX_VALUE) {
            return VmaResult.Err(EEXIST)
        }
        val start = selection.first
        val region = requireNotNull(selection.second)

        val mappedPages = mutableListOf<ULong>()
        val pageBuffer = request.pageReader?.let { ByteArray(PAGE_SIZE_BYTES.toInt()) }
        var address = start
        var failureErrno = ENOMEM
        while (address < start + alignedLength) {
            val physicalAddress = BuddyFrameAllocator.allocateFrames(1uL) ?: break
            val destination = Hhdm.toVirtualPointer<UByteVar>(physicalAddress)
            if (destination == null) {
                BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
                break
            }
            memset(destination, 0, PAGE_SIZE_BYTES)

            if (request.pageReader != null && pageBuffer != null) {
                val sourceOffset = request.offset + (address - start)
                val count = request.pageReader.invoke(sourceOffset, pageBuffer)
                if (count < 0 || count > pageBuffer.size) {
                    failureErrno = if (count < 0) -count else EIO
                    BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
                    break
                }
                if (count != 0) {
                    pageBuffer.usePinned { source ->
                        memcpy(destination, source.addressOf(0), count.toULong())
                    }
                }
            }

            val accessible = (request.access and (VMA_READ or VMA_WRITE or VMA_EXEC)) != 0uL
            if (!pageDirectory.mapUserPage(
                    virtualAddress = address,
                    physicalAddress = physicalAddress,
                    writable = (request.access and VMA_WRITE) != 0uL,
                    executable = (request.access and VMA_EXEC) != 0uL,
                ) ||
                !pageDirectory.protectUserPage(
                    virtualAddress = address,
                    accessible = accessible,
                    writable = (request.access and VMA_WRITE) != 0uL,
                    executable = (request.access and VMA_EXEC) != 0uL,
                )
            ) {
                pageDirectory.unmapPage(address)
                BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
                break
            }
            mappedPages += address
            address += PAGE_SIZE_BYTES
        }

        if (address != start + alignedLength) {
            rollbackMapping(region, mappedPages)
            return VmaResult.Err(failureErrno)
        }

        lock.withLock { mergeAroundLocked(region) }
        return VmaResult.Ok(start)
    }

    fun unmap(address: ULong, length: ULong): VmaResult<Unit> {
        val alignedLength = alignLength(length) ?: return VmaResult.Err(EINVAL)
        if (!address.isPageAligned()) {
            return VmaResult.Err(EINVAL)
        }
        if (!validUserRange(address, alignedLength)) return VmaResult.Err(EFAULT)
        lock.withLock {
            unmapRangeLocked(address, address + alignedLength)
        }
        return VmaResult.Ok(Unit)
    }

    fun protect(address: ULong, length: ULong, access: ULong): VmaResult<Unit> {
        val alignedLength = alignLength(length) ?: return VmaResult.Err(EINVAL)
        if (!address.isPageAligned() ||
            (access and (VMA_READ or VMA_WRITE or VMA_EXEC).inv()) != 0uL
        ) {
            return VmaResult.Err(EINVAL)
        }
        if (!validUserRange(address, alignedLength)) return VmaResult.Err(EFAULT)

        return lock.withLock {
            val end = address + alignedLength
            if (!rangeFullyCoveredLocked(address, end)) {
                return@withLock VmaResult.Err(ENOMEM)
            }
            splitAtLocked(address)
            splitAtLocked(end)

            val affected = regions.filter { it.start >= address && it.end <= end }
            val accessible = access != 0uL
            var page = address
            while (page < end) {
                if (!pageDirectory.protectUserPage(
                        virtualAddress = page,
                        accessible = accessible,
                        writable = (access and VMA_WRITE) != 0uL,
                        executable = (access and VMA_EXEC) != 0uL,
                    )
                ) {
                    return@withLock VmaResult.Err(EFAULT)
                }
                page += PAGE_SIZE_BYTES
            }

            affected.forEach { region ->
                region.flags = (region.flags and (VMA_READ or VMA_WRITE or VMA_EXEC).inv()) or access
            }
            affected.toList().forEach(::mergeAroundLocked)
            VmaResult.Ok(Unit)
        }
    }

    private fun rollbackMapping(region: MemChunk, mappedPages: List<ULong>) {
        mappedPages.asReversed().forEach { virtualAddress ->
            pageDirectory.unmapPage(virtualAddress)?.let { physicalAddress ->
                BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
            }
        }
        lock.withLock {
            regions.indexOfFirst { it === region }
                .takeIf { it >= 0 }
                ?.let(regions::removeAt)
        }
    }

    private fun unmapRangeLocked(start: ULong, end: ULong) {
        splitAtLocked(start)
        splitAtLocked(end)
        val iterator = regions.listIterator()
        while (iterator.hasNext()) {
            val region = iterator.next()
            if (region.end <= start) continue
            if (region.start >= end) break

            var address = region.start
            while (address < region.end) {
                pageDirectory.unmapPage(address)?.let { physicalAddress ->
                    BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
                }
                address += PAGE_SIZE_BYTES
            }
            iterator.remove()
        }
    }

    private fun rangeFullyCoveredLocked(start: ULong, end: ULong): Boolean {
        var cursor = start
        while (cursor < end) {
            val region = findLocked(cursor) ?: return false
            if (region.end <= cursor) return false
            cursor = minOf(region.end, end)
        }
        return true
    }

    private fun splitAtLocked(address: ULong) {
        val index = regions.indexOfFirst { address > it.start && address < it.end }
        if (index < 0) return
        val left = regions[index]
        val right = left.copy(
            start = address,
            offset = left.offset + (address - left.start),
        )
        left.end = address
        regions.add(index + 1, right)
    }

    private fun mergeAroundLocked(target: MemChunk) {
        var index = regions.indexOfFirst { it === target }
        if (index < 0) return
        if (index > 0 && canMerge(regions[index - 1], regions[index])) {
            mergeLocked(index - 1)
            index--
        }
        while (index + 1 < regions.size && canMerge(regions[index], regions[index + 1])) {
            mergeLocked(index)
        }
    }

    private fun mergeLocked(leftIndex: Int) {
        val left = regions[leftIndex]
        val right = regions.removeAt(leftIndex + 1)
        left.end = right.end
    }

    private fun canMerge(left: MemChunk, right: MemChunk): Boolean =
        left.end == right.start &&
            left.flags == right.flags &&
            left.name == right.name &&
            left.type == right.type &&
            left.shared == right.shared &&
            (left.type != VmaType.FILE || left.offset + left.length == right.offset)

    private fun insertLocked(chunk: MemChunk): Boolean {
        if (!validRegion(chunk) || findIntersectionLocked(chunk.start, chunk.end) != null) {
            return false
        }
        val index = regions.indexOfFirst { it.start > chunk.start }
            .let { if (it < 0) regions.size else it }
        regions.add(index, chunk)
        return true
    }

    private fun findLocked(address: ULong): MemChunk? {
        var low = 0
        var high = regions.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val region = regions[middle]
            when {
                address < region.start -> high = middle - 1
                address >= region.end -> low = middle + 1
                else -> return region
            }
        }
        return null
    }

    private fun findIntersectionLocked(start: ULong, end: ULong): MemChunk? =
        regions.firstOrNull { start < it.end && it.start < end }

    private fun findUnmappedAreaLocked(hint: ULong, length: ULong): ULong? {
        val alignedHint = hint.alignDown(PAGE_SIZE_BYTES)
        if (alignedHint >= USER_MMAP_START &&
            alignedHint <= USER_MMAP_END - length &&
            findIntersectionLocked(alignedHint, alignedHint + length) == null
        ) {
            return alignedHint
        }

        if (alignedHint >= USER_MMAP_START && alignedHint < USER_MMAP_END - length) {
            findGapLocked(alignedHint + length, USER_MMAP_END, length)?.let { return it }
            findGapLocked(USER_MMAP_START, alignedHint, length)?.let { return it }
        }
        return findGapLocked(USER_MMAP_START, USER_MMAP_END, length)
    }

    private fun findGapLocked(windowStart: ULong, windowEnd: ULong, length: ULong): ULong? {
        val start = windowStart.alignUp(PAGE_SIZE_BYTES)
        val end = windowEnd.alignDown(PAGE_SIZE_BYTES)
        if (start >= end || length > end - start) return null

        var cursor = start
        var best: ULong? = null
        for (region in regions) {
            if (region.end <= cursor) continue
            if (region.start >= end) break
            val gapEnd = minOf(region.start, end)
            if (gapEnd > cursor && gapEnd - cursor >= length) {
                best = gapEnd - length
            }
            cursor = maxOf(cursor, region.end.alignUp(PAGE_SIZE_BYTES))
            if (cursor >= end) return best
        }
        if (end > cursor && end - cursor >= length) {
            best = end - length
        }
        return best
    }

    private fun validRegion(region: MemChunk): Boolean =
        region.start < region.end &&
            region.start.isPageAligned() &&
            region.end.isPageAligned() &&
            region.end <= USER_VIRTUAL_ADDRESS_LIMIT

    private fun validMmapRange(start: ULong, length: ULong): Boolean =
        start >= USER_MMAP_START &&
            start < USER_MMAP_END &&
            length <= USER_MMAP_END - start

    private fun validUserRange(start: ULong, length: ULong): Boolean =
        start < USER_VIRTUAL_ADDRESS_LIMIT &&
            length <= USER_VIRTUAL_ADDRESS_LIMIT - start

    private fun alignLength(length: ULong): ULong? {
        if (length == 0uL || length > ULong.MAX_VALUE - (PAGE_SIZE_BYTES - 1uL)) {
            return null
        }
        return length.alignUp(PAGE_SIZE_BYTES).takeIf { it != 0uL }
    }
}
