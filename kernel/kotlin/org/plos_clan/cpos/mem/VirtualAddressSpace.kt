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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

const val MEMORY_REGION_READABLE = 0x1uL
const val MEMORY_REGION_WRITABLE = 0x2uL
const val MEMORY_REGION_EXECUTABLE = 0x4uL

internal const val MEMORY_REGION_ACCESS_MASK = 0x7uL

const val USER_MMAP_START = 0x0000_0000_0001_0000uL
const val USER_MMAP_END = 0x0000_7f00_0000_0000uL

private const val EIO = 5
private const val ENOMEM = 12
private const val EFAULT = 14
private const val EEXIST = 17
private const val EINVAL = 22

enum class MemoryRegionType {
    ANONYMOUS,
    FILE,
    IMAGE,
    STACK,
}

data class MemoryRegion(
    var start: ULong,
    var end: ULong,
    var access: ULong,
    val name: String?,
    val type: MemoryRegionType = MemoryRegionType.ANONYMOUS,
    var offset: ULong = 0uL,
    val shared: Boolean = false,
    internal val backing: MemoryRegionBacking? = null,
) {
    val length: ULong
        get() = end - start
}

@OptIn(ExperimentalAtomicApi::class)
abstract class MemoryRegionBacking {
    private val references = AtomicInt(1)

    /** Non-null only when the backing bytes are immutable for its lifetime. */
    internal open val immutablePageSource: Any?
        get() = null

    internal fun retain(): Boolean {
        var observed = references.load()
        while (observed in 1 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) {
                return true
            }
            observed = references.load()
        }
        return false
    }

    internal fun release() {
        var observed = references.load()
        while (observed > 0) {
            if (!references.compareAndSet(observed, observed - 1)) {
                observed = references.load()
                continue
            }
            if (observed == 1) close()
            return
        }
    }

    abstract fun read(offset: ULong, destination: ByteArray): Int

    protected abstract fun close()
}

private const val FILE_PAGE_CACHE_CAPACITY = 1024

private data class FilePageKey(
    val source: Any,
    val offset: ULong,
)

/** Bounded cache of immutable file pages. Each entry owns one frame reference. */
private object FilePageCache {
    private val lock = IrqSpinLock()
    private val frames = mutableMapOf<FilePageKey, ULong>()
    private val fifo = ArrayDeque<FilePageKey>()

    fun acquire(backing: MemoryRegionBacking, offset: ULong): ULong? {
        val source = backing.immutablePageSource ?: return null
        val key = FilePageKey(source, offset)
        lock.withLock {
            frames[key]?.let { frame ->
                UserFrameReferences.retain(frame)
                return@withLock frame
            }
        }?.let { return it }

        val frame = BuddyFrameAllocator.allocateFrames(1uL) ?: return null
        val destination = Hhdm.toVirtualPointer<UByteVar>(frame)
        if (destination == null) {
            BuddyFrameAllocator.freeFrames(frame, 1uL)
            return null
        }
        memset(destination, 0, PAGE_SIZE_BYTES)
        val data = ByteArray(PAGE_SIZE_BYTES.toInt())
        val count = backing.read(offset, data)
        if (count < 0 || count > data.size) {
            BuddyFrameAllocator.freeFrames(frame, 1uL)
            return null
        }
        if (count != 0) {
            data.usePinned { sourcePointer ->
                memcpy(destination, sourcePointer.addressOf(0), count.toULong())
            }
        }

        UserFrameReferences.retain(frame)
        return lock.withLock {
            frames[key]?.let { existing ->
                UserFrameReferences.retain(existing)
                UserFrameReferences.release(frame)
                return@withLock existing
            }
            while (fifo.size >= FILE_PAGE_CACHE_CAPACITY) {
                val evicted = fifo.removeFirst()
                frames.remove(evicted)?.let(UserFrameReferences::release)
            }
            frames[key] = frame
            fifo.addLast(key)
            UserFrameReferences.retain(frame)
            frame
        }
    }
}

data class MemoryMapRequest(
    val hint: ULong,
    val length: ULong,
    val access: ULong,
    val fixed: Boolean,
    val noReplace: Boolean,
    val shared: Boolean,
    val type: MemoryRegionType,
    val offset: ULong = 0uL,
    val name: String? = null,
    val backing: MemoryRegionBacking? = null,
    /** Requests immediate population instead of demand paging. */
    val populate: Boolean = false,
)

sealed interface MemoryMapResult<out T> {
    data class Ok<T>(val value: T) : MemoryMapResult<T>
    data class Err(val errno: Int) : MemoryMapResult<Nothing>
}

enum class PageFaultResult {
    RESOLVED,
    INVALID_ADDRESS,
    ACCESS_DENIED,
    OUT_OF_MEMORY,
    IO_ERROR,
    MAPPING_FAILED,
}

class VirtualAddressSpace internal constructor(
    val pageDirectory: PageDirectory,
) {
    private val regions = mutableListOf<MemoryRegion>()
    private val lock = IrqSpinLock()
    private val faultScratch = ByteArray(PAGE_SIZE_BYTES.toInt())

    val memoryRegions: List<MemoryRegion>
        get() = lock.withLock { regions.map { it.copy() } }

    val used: ULong
        get() = lock.withLock { regions.fold(0uL) { total, region -> total + region.length } }

    fun fork(): VirtualAddressSpace = lock.withLock {
        val directory = pageDirectory.cloneDirectory(
            sharedRegions = regions.filter(MemoryRegion::shared),
        )
        VirtualAddressSpace(directory).also { child ->
            child.regions += regions.map { region ->
                check(region.backing?.retain() != false)
                region.copy()
            }
        }
    }

    fun clear() {
        lock.withLock {
            regions.forEach { it.backing?.release() }
            regions.clear()
            pageDirectory.clearUserMappings()
        }
    }

    fun destroy() {
        lock.withLock {
            regions.forEach { it.backing?.release() }
            regions.clear()
        }
        pageDirectory.destroyUserDirectory()
    }

    fun find(address: ULong): MemoryRegion? = lock.withLock {
        findLocked(address)?.copy()
    }

    fun findIntersection(start: ULong, end: ULong): MemoryRegion? = lock.withLock {
        findIntersectionLocked(start, end)?.copy()
    }

    fun insert(region: MemoryRegion): Boolean = insertAll(listOf(region))

    fun insertAll(regionsToInsert: List<MemoryRegion>): Boolean {
        if (regionsToInsert.isEmpty()) {
            return true
        }
        val additions = regionsToInsert.map { it.copy() }.sortedBy(MemoryRegion::start)
        if (additions.any { !validRegion(it) } ||
            additions.zipWithNext().any { (left, right) -> left.end > right.start }
        ) {
            return false
        }

        return lock.withLock {
            if (additions.any { findIntersectionLocked(it.start, it.end) != null }) {
                return@withLock false
            }
            val retained = mutableListOf<MemoryRegionBacking>()
            for (addition in additions) {
                val backing = addition.backing
                if (backing != null && !backing.retain()) {
                    retained.asReversed().forEach(MemoryRegionBacking::release)
                    return@withLock false
                }
                if (backing != null) retained += backing
            }
            additions.forEach(::insertLocked)
            true
        }
    }

    fun map(request: MemoryMapRequest): MemoryMapResult<ULong> {
        val alignedLength = alignLength(request.length)
            ?: return MemoryMapResult.Err(EINVAL)
        if ((request.access and MEMORY_REGION_ACCESS_MASK.inv()) != 0uL ||
            (request.type == MemoryRegionType.FILE) != (request.backing != null) ||
            request.offset > ULong.MAX_VALUE - alignedLength
        ) {
            return MemoryMapResult.Err(EINVAL)
        }
        if (alignedLength > USER_MMAP_END - USER_MMAP_START) {
            return MemoryMapResult.Err(ENOMEM)
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

            val region = MemoryRegion(
                start = selected,
                end = selected + alignedLength,
                access = request.access,
                name = request.name,
                type = request.type,
                offset = request.offset,
                shared = request.shared,
                backing = request.backing,
            )
            if (request.backing?.retain() == false) {
                null
            } else if (!insertLocked(region)) {
                request.backing?.release()
                null
            } else {
                Pair(selected, region)
            }
        } ?: return MemoryMapResult.Err(ENOMEM)

        if (selection.first == ULong.MAX_VALUE) {
            return MemoryMapResult.Err(EEXIST)
        }
        val start = selection.first
        val region = requireNotNull(selection.second)

        if (!request.populate || request.access == 0uL) {
            lock.withLock { mergeAroundLocked(region) }
            return MemoryMapResult.Ok(start)
        }

        val populateScratch = if (region.backing != null) {
            ByteArray(PAGE_SIZE_BYTES.toInt())
        } else {
            null
        }
        var address = start
        var failureErrno = ENOMEM
        while (address < start + alignedLength) {
            when (materializePage(region, address, populateScratch)) {
                PageFaultResult.RESOLVED -> Unit
                PageFaultResult.OUT_OF_MEMORY -> break
                PageFaultResult.IO_ERROR -> {
                    failureErrno = EIO
                    break
                }
                else -> break
            }
            address += PAGE_SIZE_BYTES
        }

        if (address != start + alignedLength) {
            rollbackMapping(region, start, address)
            return MemoryMapResult.Err(failureErrno)
        }

        lock.withLock { mergeAroundLocked(region) }
        return MemoryMapResult.Ok(start)
    }

    /** Materializes one page after validating the authoritative memory-region permissions. */
    fun faultIn(
        address: ULong,
        write: Boolean,
        execute: Boolean = false,
    ): PageFaultResult = lock.withLock {
        if (address >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return@withLock PageFaultResult.INVALID_ADDRESS
        }
        val region = findLocked(address)
            ?: return@withLock PageFaultResult.INVALID_ADDRESS
        val access = region.access
        if (access == 0uL ||
            write && (access and MEMORY_REGION_WRITABLE) == 0uL ||
            execute && (access and MEMORY_REGION_EXECUTABLE) == 0uL
        ) {
            return@withLock PageFaultResult.ACCESS_DENIED
        }

        val page = address.alignDown(PAGE_SIZE_BYTES)
        if (pageDirectory.userPageFrame(page) != null) {
            if (write) {
                val resolved = pageDirectory.makeUserPageWritable(
                    page,
                    privateMapping = !region.shared,
                )
                return@withLock if (resolved) {
                    PageFaultResult.RESOLVED
                } else {
                    PageFaultResult.MAPPING_FAILED
                }
            }
            return@withLock if (pageDirectory.protectUserPage(
                    virtualAddress = page,
                    accessible = true,
                    writable = (access and MEMORY_REGION_WRITABLE) != 0uL,
                    executable = (access and MEMORY_REGION_EXECUTABLE) != 0uL,
                    privateMapping = !region.shared,
                )
            ) {
                PageFaultResult.RESOLVED
            } else {
                PageFaultResult.MAPPING_FAILED
            }
        }
        materializePage(region, page, faultScratch)
    }

    private fun materializePage(
        region: MemoryRegion,
        page: ULong,
        scratch: ByteArray? = null,
    ): PageFaultResult {
        val backing = region.backing
        if (backing?.immutablePageSource != null) {
            FilePageCache.acquire(backing, region.offset + (page - region.start))?.let { frame ->
                val access = region.access
                val mapped = pageDirectory.mapUserPage(
                    virtualAddress = page,
                    physicalAddress = frame,
                    writable = false,
                    executable = (access and MEMORY_REGION_EXECUTABLE) != 0uL,
                )
                UserFrameReferences.release(frame)
                if (!mapped) return PageFaultResult.MAPPING_FAILED
                return PageFaultResult.RESOLVED
            }
        }

        val physicalAddress = BuddyFrameAllocator.allocateFrames(1uL)
        if (physicalAddress == null) return PageFaultResult.OUT_OF_MEMORY
        val destination = Hhdm.toVirtualPointer<UByteVar>(physicalAddress)
        if (destination == null) {
            BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
            return PageFaultResult.MAPPING_FAILED
        }
        memset(destination, 0, PAGE_SIZE_BYTES)

        if (backing != null) {
            val buffer = scratch ?: ByteArray(PAGE_SIZE_BYTES.toInt())
            if (scratch != null) buffer.fill(0)
            val count = backing.read(region.offset + (page - region.start), buffer)
            if (count < 0 || count > buffer.size) {
                BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
                return PageFaultResult.IO_ERROR
            }
            if (count != 0) {
                buffer.usePinned { source ->
                    memcpy(destination, source.addressOf(0), count.toULong())
                }
            }
        }

        val access = region.access
        val mapped = pageDirectory.mapUserPage(
            virtualAddress = page,
            physicalAddress = physicalAddress,
            writable = (access and MEMORY_REGION_WRITABLE) != 0uL,
            executable = (access and MEMORY_REGION_EXECUTABLE) != 0uL,
        )
        if (!mapped) {
            BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
            return PageFaultResult.MAPPING_FAILED
        }
        return PageFaultResult.RESOLVED
    }

    fun unmap(address: ULong, length: ULong): MemoryMapResult<Unit> {
        val alignedLength = alignLength(length) ?: return MemoryMapResult.Err(EINVAL)
        if (!address.isPageAligned()) {
            return MemoryMapResult.Err(EINVAL)
        }
        if (!validUserRange(address, alignedLength)) return MemoryMapResult.Err(EFAULT)
        lock.withLock {
            unmapRangeLocked(address, address + alignedLength)
        }
        return MemoryMapResult.Ok(Unit)
    }

    fun protect(address: ULong, length: ULong, access: ULong): MemoryMapResult<Unit> {
        val alignedLength = alignLength(length) ?: return MemoryMapResult.Err(EINVAL)
        if (!address.isPageAligned() ||
            (access and MEMORY_REGION_ACCESS_MASK.inv()) != 0uL
        ) {
            return MemoryMapResult.Err(EINVAL)
        }
        if (!validUserRange(address, alignedLength)) return MemoryMapResult.Err(EFAULT)

        return lock.withLock {
            val end = address + alignedLength
            if (!rangeFullyCoveredLocked(address, end)) {
                return@withLock MemoryMapResult.Err(ENOMEM)
            }
            splitAtLocked(address)
            splitAtLocked(end)

            val affected = regions.filter { it.start >= address && it.end <= end }
            val accessible = access != 0uL
            for (region in affected) {
                var page = region.start
                while (page < region.end) {
                    if (pageDirectory.userPageFrame(page) != null &&
                        !pageDirectory.protectUserPage(
                            virtualAddress = page,
                            accessible = accessible,
                            writable = (access and MEMORY_REGION_WRITABLE) != 0uL,
                            executable = (access and MEMORY_REGION_EXECUTABLE) != 0uL,
                            privateMapping = !region.shared,
                        )
                    ) {
                        return@withLock MemoryMapResult.Err(EFAULT)
                    }
                    page += PAGE_SIZE_BYTES
                }
            }

            affected.forEach { region ->
                region.access = access
            }
            affected.forEach(::mergeAroundLocked)
            MemoryMapResult.Ok(Unit)
        }
    }

    private fun rollbackMapping(region: MemoryRegion, start: ULong, end: ULong) {
        var address = start
        while (address < end) {
            pageDirectory.releaseUserPage(address)
            address += PAGE_SIZE_BYTES
        }
        lock.withLock {
            regions.indexOfFirst { it === region }
                .takeIf { it >= 0 }
                ?.let { index ->
                    regions.removeAt(index).backing?.release()
                }
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
                pageDirectory.releaseUserPage(address)
                address += PAGE_SIZE_BYTES
            }
            region.backing?.release()
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
        check(left.backing?.retain() != false)
        val right = left.copy(
            start = address,
            offset = left.offset + (address - left.start),
        )
        left.end = address
        regions.add(index + 1, right)
    }

    private fun mergeAroundLocked(target: MemoryRegion) {
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
        right.backing?.release()
    }

    private fun canMerge(left: MemoryRegion, right: MemoryRegion): Boolean =
        left.end == right.start &&
            left.access == right.access &&
            left.name == right.name &&
            left.type == right.type &&
            left.shared == right.shared &&
            left.backing === right.backing &&
            (left.type != MemoryRegionType.FILE || left.offset + left.length == right.offset)

    private fun insertLocked(region: MemoryRegion): Boolean {
        if (!validRegion(region) || findIntersectionLocked(region.start, region.end) != null) {
            return false
        }
        val index = regions.indexOfFirst { it.start > region.start }
            .let { if (it < 0) regions.size else it }
        regions.add(index, region)
        return true
    }

    private fun findLocked(address: ULong): MemoryRegion? {
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

    private fun findIntersectionLocked(start: ULong, end: ULong): MemoryRegion? =
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

    private fun validRegion(region: MemoryRegion): Boolean =
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
