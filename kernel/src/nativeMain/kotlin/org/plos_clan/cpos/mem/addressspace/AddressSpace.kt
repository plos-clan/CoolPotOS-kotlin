@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.mem.addressspace

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.mem.MMIO_PTE_FLAGS
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.PageDirectory
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.isPageAligned
import platform.posix.memset
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val MMIO_VIRTUAL_BASE = 0xffff_ff00_0000_0000uL
private const val MMIO_VIRTUAL_END = 0xffff_ff80_0000_0000uL

private const val EIO = 5
private const val ENOMEM = 12
private const val EACCES = 13
private const val EFAULT = 14
private const val EEXIST = 17
private const val EINVAL = 22
class AddressSpace internal constructor(
    val pageDirectory: PageDirectory,
    private val start: ULong,
    private val end: ULong,
    private val user: Boolean,
) {
    private val references = AtomicInt(1)
    private val limit = if (user) USER_VIRTUAL_ADDRESS_LIMIT else end

    private val regions = MemoryRegionMap(start, end, limit)
    private val lock = IrqSpinLock()
    private val faultScratch = ByteArray(PAGE_SIZE_BYTES.toInt())

    companion object {
        fun user(pageDirectory: PageDirectory): AddressSpace =
            AddressSpace(pageDirectory, USER_MMAP_START, USER_MMAP_END, true)

        fun kernel(pageDirectory: PageDirectory): AddressSpace =
            AddressSpace(pageDirectory, MMIO_VIRTUAL_BASE, MMIO_VIRTUAL_END, false)
    }

    val used: ULong
        get() = lock.withLock { regions.used }

    fun snapshotRegions(): List<MemoryRegion> = lock.withLock(regions::snapshot)

    fun fork(): AddressSpace = lock.withLock {
        val directory = pageDirectory.cloneDirectory(
            sharedRegions = regions.sharedRegions(),
        )
        AddressSpace(directory, start, end, user).also { child ->
            regions.copyRetainedInto(child.regions)
        }
    }

    internal fun share(): AddressSpace {
        var observed = references.load()
        while (observed in 1 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) return this
            observed = references.load()
        }
        error("Cannot share a released address space")
    }

    fun clear() {
        lock.withLock {
            regions.releaseAll()
            pageDirectory.clearUserMappings()
        }
    }

    internal fun release() {
        var observed = references.load()
        while (observed > 0) {
            if (!references.compareAndSet(observed, observed - 1)) {
                observed = references.load()
                continue
            }
            if (observed != 1) return
            destroyResources()
            return
        }
        error("Address space released more than once")
    }

    private fun destroyResources() {
        lock.withLock {
            regions.releaseAll()
        }
        pageDirectory.destroyUserDirectory()
    }

    fun find(address: ULong): MemoryRegion? = lock.withLock {
        regions.find(address)?.copy()
    }

    fun findIntersection(start: ULong, end: ULong): MemoryRegion? = lock.withLock {
        regions.intersection(start, end)?.copy()
    }

    internal fun sharedMemoryLocation(address: ULong, size: ULong): SharedMemoryLocation? =
        lock.withLock {
            val region = regions.find(address) ?: return@withLock null
            if (!region.shared || size > region.end - address) return@withLock null
            SharedMemoryLocation(
                identity = region.sharedIdentity ?: return@withLock null,
                offset = region.offset + (address - region.start),
            )
        }

    fun insert(region: MemoryRegion): Boolean = insertAll(listOf(region))

    fun insertAll(regionsToInsert: List<MemoryRegion>): Boolean =
        lock.withLock { regions.insertCopies(regionsToInsert) }

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
                    !regions.validMmapRange(request.hint, alignedLength)
                ) {
                    return@withLock null
                }
                if (regions.intersection(request.hint, request.hint + alignedLength) != null) {
                    if (request.noReplace) {
                        return@withLock Pair(ULong.MAX_VALUE, null)
                    }
                    unmapRangeLocked(request.hint, request.hint + alignedLength)
                }
                request.hint
            } else {
                regions.findUnmappedArea(request.hint, alignedLength)
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
            } else if (!regions.insertOwned(region)) {
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
            lock.withLock { regions.mergeAround(region) }
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
            when (materializePage(region, address, write = false, scratch = populateScratch)) {
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

        lock.withLock { regions.mergeAround(region) }
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
        val region = regions.find(address)
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
        materializePage(region, page, write = write, scratch = faultScratch)
    }

    private fun materializePage(
        region: MemoryRegion,
        page: ULong,
        write: Boolean,
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
        if (mapped && write && backing != null &&
            !pageDirectory.makeUserPageWritable(page, privateMapping = !region.shared)
        ) {
            pageDirectory.releaseUserPage(page)
            PageCache.release(physicalAddress)
            return PageFaultResult.MAPPING_FAILED
        }
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
        return lock.withLock {
            val end = address + alignedLength
            val immutable = regions.any { region ->
                !region.type.userMutable && region.start < end && region.end > address
            }
            if (immutable) {
                return@withLock MemoryMapResult.Err(EACCES)
            }
            unmapRangeLocked(address, end)
            MemoryMapResult.Ok(Unit)
        }
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
            val immutable = regions.any { region ->
                !region.type.userMutable && region.start < end && region.end > address
            }
            if (immutable) {
                return@withLock MemoryMapResult.Err(EACCES)
            }
            if (!regions.fullyCovers(address, end)) {
                return@withLock MemoryMapResult.Err(ENOMEM)
            }
            val exceedsAccessLimit = regions.any { region ->
                region.start < end && region.end > address &&
                    access and region.maximumAccess.inv() != 0uL
            }
            if (exceedsAccessLimit) {
                return@withLock MemoryMapResult.Err(EACCES)
            }
            regions.splitAt(address)
            regions.splitAt(end)

            val splitRegions = regions.intersecting(address, end)
            val accessible = access != 0uL
            for (region in splitRegions) {
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

            splitRegions.forEach { region ->
                region.access = access
            }
            splitRegions.forEach(regions::mergeAround)
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
            regions.removeOwned(region)?.backing?.release()
        }
    }

    private fun unmapRangeLocked(start: ULong, end: ULong) {
        regions.removeRange(start, end).forEach { region ->
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
        }
    }

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
