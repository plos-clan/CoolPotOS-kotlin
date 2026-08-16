@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.fs.OpenFileDescription
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.isPageAligned
import platform.posix.memset
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

const val MEMORY_REGION_READABLE = 0x1uL
const val MEMORY_REGION_WRITABLE = 0x2uL
const val MEMORY_REGION_EXECUTABLE = 0x4uL

internal const val MEMORY_REGION_ACCESS_MASK = 0x7uL

const val USER_MMAP_START = 0x0000_0000_0001_0000uL
const val USER_MMAP_END = 0x0000_7f00_0000_0000uL
private const val MMIO_VIRTUAL_BASE = 0xffff_ff00_0000_0000uL
private const val MMIO_VIRTUAL_END = 0xffff_ff80_0000_0000uL

private const val EIO = 5
private const val ENOMEM = 12
private const val EACCES = 13
private const val EFAULT = 14
private const val EEXIST = 17
private const val EINVAL = 22

enum class MemoryRegionType {
    ANONYMOUS,
    FILE,
    IMAGE,
    STACK,
    VDSO,
    MMIO,
}

data class MemoryRegion(
    var start: ULong,
    var end: ULong,
    var access: ULong,
    val name: String?,
    val maximumAccess: ULong = MEMORY_REGION_ACCESS_MASK,
    val type: MemoryRegionType = MemoryRegionType.ANONYMOUS,
    var offset: ULong = 0uL,
    val shared: Boolean = false,
    internal val backing: MemoryRegionBacking? = null,
    internal val sharedIdentity: Any? = null,
) {
    val length: ULong
        get() = end - start
}

@OptIn(ExperimentalAtomicApi::class)
abstract class MemoryRegionBacking : PageCacheSource {
    private val references = AtomicInt(1)

    internal open val sharedMemoryIdentity: Any
        get() = this

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

    abstract override fun read(offset: ULong, destination: ByteArray): Int

    protected abstract fun close()
}

abstract class FileRegionBacking(
    val file: OpenFileDescription,
) : MemoryRegionBacking() {
    init {
        check(file.retain())
    }

    final override fun close() = file.release()
}

data class MemoryMapRequest(
    val hint: ULong,
    val length: ULong,
    val access: ULong,
    val fixed: Boolean,
    val noReplace: Boolean,
    val shared: Boolean,
    val type: MemoryRegionType,
    val maximumAccess: ULong = MEMORY_REGION_ACCESS_MASK,
    val offset: ULong = 0uL,
    val name: String? = null,
    val backing: MemoryRegionBacking? = null,
    val populate: Boolean = false,
)

sealed interface MemoryMapResult<out T> {
    data class Ok<T>(val value: T) : MemoryMapResult<T>
    data class Err(val errno: Int) : MemoryMapResult<Nothing>
}

internal data class SharedMemoryLocation(
    val identity: Any,
    val offset: ULong,
)

enum class PageFaultResult {
    RESOLVED,
    INVALID_ADDRESS,
    ACCESS_DENIED,
    OUT_OF_MEMORY,
    IO_ERROR,
    MAPPING_FAILED,
}

class AddressSpace internal constructor(
    val pageDirectory: PageDirectory,
    private val start: ULong,
    private val end: ULong,
    private val user: Boolean,
) {
    private val limit = if (user) USER_VIRTUAL_ADDRESS_LIMIT else end

    private val regions = mutableListOf<MemoryRegion>()
    private val lock = IrqSpinLock()
    private val faultScratch = ByteArray(PAGE_SIZE_BYTES.toInt())

    companion object {
        fun user(pageDirectory: PageDirectory): AddressSpace =
            AddressSpace(pageDirectory, USER_MMAP_START, USER_MMAP_END, true)

        fun kernel(pageDirectory: PageDirectory): AddressSpace =
            AddressSpace(pageDirectory, MMIO_VIRTUAL_BASE, MMIO_VIRTUAL_END, false)
    }

    val used: ULong
        get() = lock.withLock { regions.fold(0uL) { total, region -> total + region.length } }

    fun snapshotRegions() : List<MemoryRegion> = lock.withLock {
        List(regions.size) { index ->
            regions[index].copy()
        }
    }

    fun fork(): AddressSpace = lock.withLock {
        val directory = pageDirectory.cloneDirectory(
            sharedRegions = regions.filter(MemoryRegion::shared),
        )
        AddressSpace(directory, start, end, user).also { child ->
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

    internal fun sharedMemoryLocation(address: ULong, size: ULong): SharedMemoryLocation? =
        lock.withLock {
            val region = findLocked(address) ?: return@withLock null
            if (!region.shared || size > region.end - address) return@withLock null
            SharedMemoryLocation(
                identity = region.sharedIdentity ?: return@withLock null,
                offset = region.offset + (address - region.start),
            )
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
            (request.maximumAccess and MEMORY_REGION_ACCESS_MASK.inv()) != 0uL ||
            (request.access and request.maximumAccess.inv()) != 0uL ||
            (request.type == MemoryRegionType.FILE) != (request.backing != null) ||
            request.offset > ULong.MAX_VALUE - alignedLength
        ) {
            return MemoryMapResult.Err(EINVAL)
        }
        if (alignedLength > end - start) {
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
                maximumAccess = request.maximumAccess,
                name = request.name,
                type = request.type,
                offset = request.offset,
                shared = request.shared,
                backing = request.backing,
                sharedIdentity = if (request.shared) {
                    request.backing?.sharedMemoryIdentity ?: Any()
                } else {
                    null
                },
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


    fun faultIn(
        address: ULong,
        write: Boolean,
        execute: Boolean = false,
    ): PageFaultResult = lock.withLock {
        if (address >= limit) {
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
        if (region.type == MemoryRegionType.MMIO) {
            val physicalAddress = region.offset.alignDown(PAGE_SIZE_BYTES) +
                (page - region.start)
            return if (pageDirectory.mapPage(page, physicalAddress, MMIO_PTE_FLAGS)) {
                PageFaultResult.RESOLVED
            } else {
                PageFaultResult.MAPPING_FAILED
            }
        }

        val backing = region.backing
        val backingOffset = region.offset + (page - region.start)
        val physicalAddress = if (backing != null) {
            val cached = PageCache.acquire(
                backing.cacheSource,
                backingOffset,
                scratch ?: faultScratch,
            )
            if (!cached.isSuccess) {
                return when (cached.failure) {
                    PageCacheFailure.OUT_OF_MEMORY -> PageFaultResult.OUT_OF_MEMORY
                    PageCacheFailure.IO_ERROR -> PageFaultResult.IO_ERROR
                }
            }
            cached.frame
        } else {
            val frame = BuddyFrameAllocator.allocate(1uL)
            if (frame == INVALID_FRAME) return PageFaultResult.OUT_OF_MEMORY
            val destination = Hhdm.toVirtualPointer<UByteVar>(frame)
            if (destination == null) {
                BuddyFrameAllocator.free(frame, 1uL)
                return PageFaultResult.MAPPING_FAILED
            }
            memset(destination, 0, PAGE_SIZE_BYTES)
            frame
        }

        val access = region.access
        val writable = backing == null &&
            (access and MEMORY_REGION_WRITABLE) != 0uL
        val executable = (access and MEMORY_REGION_EXECUTABLE) != 0uL
        val mapped = pageDirectory.mapUserPage(
            virtualAddress = page,
            physicalAddress = physicalAddress,
            writable = writable,
            executable = executable,
        )
        if (backing != null) {
            PageCache.release(physicalAddress)
        } else if (!mapped) {
            BuddyFrameAllocator.free(physicalAddress, 1uL)
        }
        return if (mapped) PageFaultResult.RESOLVED else PageFaultResult.MAPPING_FAILED
    }

    fun unmap(address: ULong, length: ULong): MemoryMapResult<Unit> {
        val alignedLength = alignLength(length) ?: return MemoryMapResult.Err(EINVAL)
        if (!address.isPageAligned()) {
            return MemoryMapResult.Err(EINVAL)
        }
        if (!validRange(address, alignedLength)) return MemoryMapResult.Err(EFAULT)
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
        if (!validRange(address, alignedLength)) return MemoryMapResult.Err(EFAULT)

        return lock.withLock {
            val end = address + alignedLength
            if (!rangeFullyCoveredLocked(address, end)) {
                return@withLock MemoryMapResult.Err(ENOMEM)
            }
            val exceedsAccessLimit = regions.any { region ->
                region.start < end && region.end > address &&
                    access and region.maximumAccess.inv() != 0uL
            }
            if (exceedsAccessLimit) {
                return@withLock MemoryMapResult.Err(EACCES)
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
            if (user) {
                pageDirectory.releaseUserPage(address)
            } else {
                pageDirectory.unmapPage(address)
            }
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
                if (user) {
                    pageDirectory.releaseUserPage(address)
                } else {
                    pageDirectory.unmapPage(address)
                }
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
            left.maximumAccess == right.maximumAccess &&
            left.name == right.name &&
            left.type == right.type &&
            left.shared == right.shared &&
            left.sharedIdentity === right.sharedIdentity &&
            left.backing === right.backing &&
            ((left.type != MemoryRegionType.FILE && left.type != MemoryRegionType.MMIO) ||
                left.offset + left.length == right.offset)

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
        if (alignedHint >= start &&
            alignedHint <= end - length &&
            findIntersectionLocked(alignedHint, alignedHint + length) == null
        ) {
            return alignedHint
        }

        if (alignedHint >= start && alignedHint < end - length) {
            findGapLocked(alignedHint + length, end, length)?.let { return it }
            findGapLocked(start, alignedHint, length)?.let { return it }
        }
        return findGapLocked(start, end, length)
    }

    private fun findGapLocked(windowStart: ULong, windowEnd: ULong, length: ULong): ULong? {
        val start = windowStart.alignUp(PAGE_SIZE_BYTES) ?: return null
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
            cursor = maxOf(cursor, region.end.alignUp(PAGE_SIZE_BYTES) ?: return best)
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
            (region.maximumAccess and MEMORY_REGION_ACCESS_MASK.inv()) == 0uL &&
            (region.access and region.maximumAccess.inv()) == 0uL &&
            region.end <= limit

    private fun validMmapRange(address: ULong, length: ULong): Boolean =
        address in start..<end &&
            length <= end - address

    private fun validRange(address: ULong, length: ULong): Boolean =
        address < limit &&
            length <= limit - address

    private fun alignLength(length: ULong): ULong? {
        if (length == 0uL || length > ULong.MAX_VALUE - (PAGE_SIZE_BYTES - 1uL)) {
            return null
        }
        return length.alignUp(PAGE_SIZE_BYTES)
    }
}
