@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.mem.addressspace

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.page.MMIO_PTE_FLAGS
import org.plos_clan.cpos.mem.page.PageDirectory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.isPageAligned
import platform.posix.memset
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val MMIO_VIRTUAL_BASE = 0xffff_ff00_0000_0000uL
private const val MMIO_VIRTUAL_END = 0xffff_ff80_0000_0000uL

private const val EINTR = 4
private const val EIO = 5
private const val EAGAIN = 11
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
) : MemoryRegionOwner {
    private var futureMemoryLock = MemoryLock.NONE
    private val references = AtomicInt(1)
    private val limit = if (user) USER_VIRTUAL_ADDRESS_LIMIT else end

    private val regions = MemoryRegionMap(start, end, limit, PAGE_SIZE_BYTES, this)
    private val lock = IrqSpinLock()
    private var executable: OpenFileDescription? = null
    private var argumentLayout = ProcessArguments.EMPTY
    internal var arguments: ProcessArguments
        get() = lock.withLock { argumentLayout }
        set(value) = lock.withLock { argumentLayout = value }

    private val reusableFaultScratch = AtomicReference<ByteArray?>(null)

    private sealed interface FaultPlan {
        data class Complete(val result: PageFaultResult) : FaultPlan
        data class Load(val target: FaultTarget) : FaultPlan
    }

    private data class FaultTarget(
        val regionIdentity: Any,
        val page: ULong,
        val backingOffset: ULong,
        val mmioPhysicalAddress: ULong?,
        val backing: MemoryRegionBacking?,
    ) {
        fun release() = backing?.release()
    }

    private enum class PageOrigin {
        MMIO,
        CACHE,
        ANONYMOUS,
    }

    private data class PreparedPage(
        val frame: ULong,
        val origin: PageOrigin,
    ) {
        fun release(consumed: Boolean) {
            when (origin) {
                PageOrigin.MMIO -> Unit
                PageOrigin.CACHE -> PageCache.release(frame)
                PageOrigin.ANONYMOUS -> if (!consumed) BuddyFrameAllocator.free(frame, 1uL)
            }
        }
    }

    private sealed interface PagePreparation {
        data class Ready(val page: PreparedPage) : PagePreparation
        data class Failed(val result: PageFaultResult) : PagePreparation
    }

    private data class PageCommit(
        val result: PageFaultResult,
        val consumed: Boolean = false,
        val retry: Boolean = false,
    )

    companion object {
        fun user(pageDirectory: PageDirectory): AddressSpace =
            AddressSpace(pageDirectory, USER_MMAP_START, USER_MMAP_END, true)

        fun kernel(pageDirectory: PageDirectory): AddressSpace =
            AddressSpace(pageDirectory, MMIO_VIRTUAL_BASE, MMIO_VIRTUAL_END, false)
    }

    val used: ULong
        get() = lock.withLock { regions.used }

    val lockedMemory: ULong
        get() = lock.withLock { regions.lockedBytesOutside() }

    fun snapshotRegions(): List<MemoryRegion> = lock.withLock(regions::snapshot)

    internal fun acquireExecutable(): OpenFileDescription? = lock.withLock {
        executable?.takeIf(OpenFileDescription::retain)
    }

    internal fun setExecutable(file: OpenFileDescription?) {
        val previous = lock.withLock {
            check(file == null || file.retain())
            executable.also { executable = file }
        }
        previous?.release()
    }

    fun fork(): AddressSpace = lock.withLock {
        val directory = pageDirectory.cloneDirectory(
            sharedRegions = regions.sharedRegions(),
        )
        AddressSpace(directory, start, end, user).also { child ->
            child.lock.withLock { regions.copyRetainedInto(child.regions) }
            child.executable = executable?.also { check(it.retain()) }
            child.argumentLayout = argumentLayout
        }
    }

    internal fun share(): AddressSpace {
        check(retain()) { "Cannot share a released address space" }
        return this
    }

    internal fun retain(): Boolean {
        var observed = references.load()
        while (observed in 1 until Int.MAX_VALUE) {
            val retained = references.compareAndSet(observed, observed + 1)
            if (retained) return true
            observed = references.load()
        }
        return false
    }

    internal fun relocateArguments(
        boundary: ProcessArguments.Boundary,
        address: ULong,
    ): Int = lock.withLock {
        if (address < PAGE_SIZE_BYTES || address >= limit) return@withLock -EINVAL
        val replacement = argumentLayout.relocate(boundary, address)
        val invalidArguments = replacement.start > replacement.end
        val invalidEnvironment = replacement.environmentStart > replacement.environmentEnd
        if (invalidArguments || invalidEnvironment) return@withLock -EINVAL
        if (regions.intersection(address, limit) == null) return@withLock -EFAULT
        argumentLayout = replacement
        0
    }

    fun clear() {
        val backings = lock.withLock {
            val removed = regions.removeAll()
            pageDirectory.clearUserMappings()
            removed
        }
        backings.forEach { it.release(this) }
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
        setExecutable(null)
        val backings = lock.withLock(regions::removeAll)
        pageDirectory.destroyUserDirectory()
        backings.forEach { it.release(this) }
    }

    fun find(address: ULong): MemoryRegion? = lock.withLock {
        regions.find(address)?.copy()
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

    internal fun findUnmappedArea(length: ULong, alignment: ULong): ULong? =
        lock.withLock { regions.findUnmappedArea(0uL, length, alignment) }

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

        val hint = request.hint
        val validHint = hint.isPageAligned() && regions.validMmapRange(hint, alignedLength)
        if (request.fixed && !validHint) return MemoryMapResult.Err(ENOMEM)

        var replacedBackings = emptyList<MemoryRegionBacking>()
        val selection = lock.withLock {
            val requestedLock = request.memoryLock
            val memoryLock = requestedLock.takeUnless { it == MemoryLock.NONE } ?: futureMemoryLock
            val selected = if (request.fixed) hint else {
                regions.findUnmappedArea(hint, alignedLength) ?: return@withLock null
            }
            val selectedEnd = selected + alignedLength
            val overlap = regions.intersection(selected, selectedEnd)
            val conflicts = request.fixed && request.noReplace && overlap != null
            if (conflicts) return@withLock Pair(ULong.MAX_VALUE, null)

            val locksMemory = memoryLock != MemoryLock.NONE
            val locked = if (locksMemory) regions.lockedBytesOutside(selected, selectedEnd) else 0uL
            val maximum = request.lockedMemoryLimit
            val available = maximum - minOf(locked, maximum)
            if (locksMemory && alignedLength > available) return MemoryMapResult.Err(EAGAIN)
            if (request.fixed) replacedBackings = unmapRangeLocked(selected, selectedEnd)

            val sharedAnonymous = request.shared && request.type == MemoryRegionType.ANONYMOUS
            val backing = request.backing ?: if (sharedAnonymous) AnonymousRegionBacking() else null
            val sharedIdentity = when {
                !request.shared -> null
                backing != null -> backing.sharedMemoryIdentity
                else -> Any()
            }
            val region = MemoryRegion(
                start = selected,
                end = selectedEnd,
                access = request.access,
                maximumAccess = request.maximumAccess,
                name = request.name,
                type = request.type,
                offset = request.offset,
                shared = request.shared,
                memoryLock = memoryLock,
                backing = backing,
                sharedIdentity = sharedIdentity,
            )
            if (backing?.retain(this) == false) return@withLock null
            if (backing !== request.backing) backing?.release()
            if (regions.insertOwned(region)) return@withLock Pair(selected, region)
            backing?.release(this)
            null
        }
        replacedBackings.forEach { it.release(this) }
        selection ?: return MemoryMapResult.Err(ENOMEM)

        if (selection.first == ULong.MAX_VALUE) {
            return MemoryMapResult.Err(EEXIST)
        }
        val start = selection.first
        val region = requireNotNull(selection.second)

        val populate = request.populate || region.memoryLock == MemoryLock.EAGER
        val failure = if (populate) populate(region) else 0
        if (failure != 0) {
            rollbackMapping(region, region.start, region.end)
            return MemoryMapResult.Err(failure)
        }
        lock.withLock { regions.mergeAround(region) }
        return MemoryMapResult.Ok(start)
    }

    private fun populate(region: MemoryRegion): Int {
        if (region.access == 0uL || region.type == MemoryRegionType.MMIO) return 0
        val writable = region.access and MEMORY_REGION_WRITABLE != 0uL
        var address = region.start
        while (address < region.end) {
            val result = faultIn(address, writable)
            when (result) {
                PageFaultResult.RESOLVED -> address += PAGE_SIZE_BYTES
                PageFaultResult.IO_ERROR -> return EIO
                PageFaultResult.INTERRUPTED -> return EINTR
                else -> return ENOMEM
            }
        }
        return 0
    }

    internal fun lockMemory(
        address: ULong,
        length: ULong,
        mode: MemoryLock,
        maximum: ULong,
    ): Int {
        if (length == 0uL) return 0
        if (!validRange(address, length)) return -ENOMEM
        val start = address.alignDown(PAGE_SIZE_BYTES)
        val end = (address + length).alignUp(PAGE_SIZE_BYTES) ?: return -ENOMEM
        val selected = lock.withLock {
            if (!regions.fullyCovers(start, end)) return -ENOMEM
            val locked = regions.lockedBytesOutside()
            val retained = regions.lockedBytesOutside(start, end)
            val additions = end - start - (locked - retained)
            val available = maximum - minOf(locked, maximum)
            if (mode != MemoryLock.NONE && additions > available) return -ENOMEM
            regions.splitAt(start)
            regions.splitAt(end)
            val affected = regions.intersecting(start, end)
            val snapshot = affected.map { region ->
                region.memoryLock = mode
                region.copy()
            }
            affected.forEach(regions::mergeAround)
            snapshot
        }
        if (mode != MemoryLock.EAGER) return 0
        for (region in selected) {
            val failure = populate(region)
            if (failure != 0) return -failure
        }
        return 0
    }

    internal fun lockAllMemory(
        mode: MemoryLock,
        current: Boolean,
        future: Boolean,
        maximum: ULong,
    ): Int {
        val selected = lock.withLock {
            val exceedsLimit = current && mode != MemoryLock.NONE && regions.used > maximum
            if (exceedsLimit) return -ENOMEM
            futureMemoryLock = if (future) mode else MemoryLock.NONE
            if (!current) return 0
            for (region in regions) {
                region.memoryLock = mode
            }
            regions.snapshot()
        }
        if (mode != MemoryLock.EAGER) return 0
        for (region in selected) {
            val failure = populate(region)
            if (failure != 0) return -failure
        }
        return 0
    }

    fun faultIn(
        address: ULong,
        write: Boolean,
        execute: Boolean = false,
    ): PageFaultResult {
        while (true) {
            val plan = lock.withLock { planFaultLocked(address, write, execute) }
            val target = when (plan) {
                is FaultPlan.Complete -> return plan.result
                is FaultPlan.Load -> plan.target
            }
            val page = when (val preparation = preparePage(target)) {
                is PagePreparation.Ready -> preparation.page
                is PagePreparation.Failed -> {
                    target.release()
                    return preparation.result
                }
            }
            val commit = lock.withLock { commitPageLocked(target, page, write, execute) }
            page.release(commit.consumed)
            target.release()
            if (!commit.retry) return commit.result
        }
    }

    internal fun acquireUserFrame(address: ULong, writable: Boolean): ULong? {
        while (true) {
            val frame = lock.withLock {
                val physical = pageDirectory.resolveUserPhysicalAddress(address, writable)
                    ?: return@withLock null
                UserFrameReferences.retain(physical.alignDown(PAGE_SIZE_BYTES))
                physical
            }
            if (frame != null) return frame
            if (faultIn(address, writable) != PageFaultResult.RESOLVED) return null
        }
    }

    internal inline fun accessUserPage(
        address: ULong,
        writable: Boolean,
        pin: Boolean,
        operation: (ULong) -> Int,
    ): Int {
        if (pin) {
            val physical = acquireUserFrame(address, writable) ?: return 0
            return try {
                operation(physical)
            } finally {
                UserFrameReferences.release(physical.alignDown(PAGE_SIZE_BYTES))
            }
        }
        while (true) {
            lock.withLock {
                val physical = pageDirectory.resolveUserPhysicalAddress(address, writable)
                if (physical != null) return operation(physical)
            }
            if (faultIn(address, writable) != PageFaultResult.RESOLVED) return 0
        }
    }

    private fun planFaultLocked(
        address: ULong,
        write: Boolean,
        execute: Boolean,
    ): FaultPlan {
        if (address >= limit) {
            return FaultPlan.Complete(PageFaultResult.INVALID_ADDRESS)
        }
        val region = regions.find(address)
            ?: return FaultPlan.Complete(PageFaultResult.INVALID_ADDRESS)
        val access = region.access
        if (access == 0uL ||
            write && (access and MEMORY_REGION_WRITABLE) == 0uL ||
            execute && (access and MEMORY_REGION_EXECUTABLE) == 0uL
        ) {
            return FaultPlan.Complete(PageFaultResult.ACCESS_DENIED)
        }

        val page = address.alignDown(PAGE_SIZE_BYTES)
        resolveMappedPage(region, page, write)?.let { return FaultPlan.Complete(it) }

        val backing = region.backing
        if (backing?.retain() == false) {
            return FaultPlan.Complete(PageFaultResult.IO_ERROR)
        }
        val backingOffset = region.offset + (page - region.start)
        val mmioPhysicalAddress = if (region.type == MemoryRegionType.MMIO) {
            region.offset.alignDown(PAGE_SIZE_BYTES) + (page - region.start)
        } else {
            null
        }
        return FaultPlan.Load(
            FaultTarget(
                regionIdentity = region.identity,
                page = page,
                backingOffset = backingOffset,
                mmioPhysicalAddress = mmioPhysicalAddress,
                backing = backing,
            ),
        )
    }

    private fun resolveMappedPage(
        region: MemoryRegion,
        page: ULong,
        write: Boolean,
    ): PageFaultResult? {
        if (pageDirectory.userPageFrame(page) == null) return null
        if (write) {
            val resolved = pageDirectory.makeUserPageWritable(
                page,
                privateMapping = !region.shared,
            )
            return if (resolved) PageFaultResult.RESOLVED else PageFaultResult.MAPPING_FAILED
        }
        return if (pageDirectory.protectUserPage(
                virtualAddress = page,
                accessible = true,
                writable = (region.access and MEMORY_REGION_WRITABLE) != 0uL,
                executable = (region.access and MEMORY_REGION_EXECUTABLE) != 0uL,
                privateMapping = !region.shared,
            )
        ) {
            PageFaultResult.RESOLVED
        } else {
            PageFaultResult.MAPPING_FAILED
        }
    }

    private fun preparePage(target: FaultTarget): PagePreparation {
        target.mmioPhysicalAddress?.let {
            return PagePreparation.Ready(PreparedPage(it, PageOrigin.MMIO))
        }
        val backing = target.backing
        if (backing != null) {
            val scratch = reusableFaultScratch.exchange(null) ?: try {
                ByteArray(PAGE_SIZE_BYTES.toInt())
            } catch (_: OutOfMemoryError) {
                return PagePreparation.Failed(PageFaultResult.OUT_OF_MEMORY)
            }
            val cached = try {
                backing.acquirePage(target.backingOffset, scratch)
            } finally {
                reusableFaultScratch.compareAndSet(null, scratch)
            }
            if (!cached.isSuccess) {
                val failure = when (cached.failure) {
                    PageCacheFailure.OUT_OF_MEMORY -> PageFaultResult.OUT_OF_MEMORY
                    PageCacheFailure.IO_ERROR -> PageFaultResult.IO_ERROR
                    PageCacheFailure.INTERRUPTED -> PageFaultResult.INTERRUPTED
                }
                return PagePreparation.Failed(failure)
            }
            return PagePreparation.Ready(PreparedPage(cached.frame, PageOrigin.CACHE))
        }

        val frame = BuddyFrameAllocator.allocate(1uL)
        if (frame == INVALID_FRAME) {
            return PagePreparation.Failed(PageFaultResult.OUT_OF_MEMORY)
        }
        val destination = Hhdm.toVirtualPointer<UByteVar>(frame)
        if (destination == null) {
            BuddyFrameAllocator.free(frame, 1uL)
            return PagePreparation.Failed(PageFaultResult.MAPPING_FAILED)
        }
        memset(destination, 0, PAGE_SIZE_BYTES)
        return PagePreparation.Ready(PreparedPage(frame, PageOrigin.ANONYMOUS))
    }

    private fun commitPageLocked(
        target: FaultTarget,
        prepared: PreparedPage,
        write: Boolean,
        execute: Boolean,
    ): PageCommit {
        val region = regions.find(target.page)
            ?.takeIf { it.identity === target.regionIdentity }
            ?: return PageCommit(PageFaultResult.INVALID_ADDRESS)
        val access = region.access
        if (access == 0uL ||
            write && (access and MEMORY_REGION_WRITABLE) == 0uL ||
            execute && (access and MEMORY_REGION_EXECUTABLE) == 0uL
        ) {
            return PageCommit(PageFaultResult.ACCESS_DENIED)
        }
        resolveMappedPage(region, target.page, write)?.let { return PageCommit(it) }

        if (prepared.origin == PageOrigin.MMIO) {
            val mapped = pageDirectory.mapPage(target.page, prepared.frame, MMIO_PTE_FLAGS)
            return PageCommit(
                if (mapped) PageFaultResult.RESOLVED else PageFaultResult.MAPPING_FAILED,
            )
        }

        val cached = prepared.origin == PageOrigin.CACHE
        if (cached && target.backing?.isPageCurrent(target.backingOffset, prepared.frame) == false) {
            return PageCommit(PageFaultResult.IO_ERROR, retry = true)
        }
        val writable = (!cached || region.shared) && (access and MEMORY_REGION_WRITABLE) != 0uL
        val mapped = pageDirectory.mapUserPage(
            virtualAddress = target.page,
            physicalAddress = prepared.frame,
            writable = writable,
            executable = (access and MEMORY_REGION_EXECUTABLE) != 0uL,
        )
        if (mapped && write && cached &&
            !pageDirectory.makeUserPageWritable(target.page, privateMapping = !region.shared)
        ) {
            pageDirectory.releaseUserPage(target.page)
            return PageCommit(PageFaultResult.MAPPING_FAILED)
        }
        return PageCommit(
            if (mapped) PageFaultResult.RESOLVED else PageFaultResult.MAPPING_FAILED,
            consumed = mapped && !cached,
        )
    }

    override fun invalidateFile(
        identity: Any,
        offset: ULong,
        end: ULong,
        privateFrames: Set<ULong>?,
    ) {
        lock.withLock {
            for (region in regions) {
                if (region.backing?.sharedMemoryIdentity !== identity) continue
                val first = maxOf(offset, region.offset)
                val last = minOf(end, region.offset + region.length)
                if (first >= last) continue
                var page = region.start + first - region.offset
                val limit = region.start + last - region.offset
                while (page < limit) {
                    if (region.shared || privateFrames == null || pageDirectory.userPageFrame(page) in privateFrames) {
                        pageDirectory.releaseUserPage(page)
                    }
                    page += PAGE_SIZE_BYTES
                }
            }
        }
    }

    fun unmap(address: ULong, length: ULong): MemoryMapResult<Unit> {
        val alignedLength = alignLength(length) ?: return MemoryMapResult.Err(EINVAL)
        if (!address.isPageAligned()) {
            return MemoryMapResult.Err(EINVAL)
        }
        if (!validRange(address, alignedLength)) return MemoryMapResult.Err(EFAULT)
        var removedBackings = emptyList<MemoryRegionBacking>()
        val result = lock.withLock {
            val end = address + alignedLength
            val immutable = regions.any { region ->
                !region.type.userMutable && region.start < end && region.end > address
            }
            if (immutable) {
                return@withLock MemoryMapResult.Err(EACCES)
            }
            removedBackings = unmapRangeLocked(address, end)
            MemoryMapResult.Ok(Unit)
        }
        removedBackings.forEach { it.release(this) }
        return result
    }

    internal fun advise(address: ULong, length: ULong, advice: Int): Int {
        if (advice !in 0..4 || !address.isPageAligned()) return -EINVAL
        if (length == 0uL) return 0
        val size = alignLength(length) ?: return -EINVAL
        if (address > ULong.MAX_VALUE - size) return -EINVAL
        val end = address + size
        return lock.withLock {
            var cursor = address
            var result = 0
            for (region in regions.intersecting(address, end)) {
                if (region.start > cursor) result = -ENOMEM
                val first = maxOf(address, region.start)
                cursor = minOf(end, region.end)
                if (advice != 4) continue
                val locked = region.memoryLock != MemoryLock.NONE
                if (locked || region.type == MemoryRegionType.MMIO) return@withLock -EINVAL
                if (!region.type.userMutable) return@withLock -EINVAL
                pageDirectory.releasePages(first, cursor)
            }
            if (cursor < end) -ENOMEM else result
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
        val backing = lock.withLock {
            val removed = regions.removeOwned(region) ?: return@withLock null
            pageDirectory.releasePages(start, end, user)
            removed.backing
        }
        backing?.release(this)
    }

    private fun unmapRangeLocked(start: ULong, end: ULong): List<MemoryRegionBacking> =
        buildList {
            regions.removeRange(start, end).forEach { region ->
                pageDirectory.releasePages(region.start, region.end, user)
                region.backing?.let(::add)
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
