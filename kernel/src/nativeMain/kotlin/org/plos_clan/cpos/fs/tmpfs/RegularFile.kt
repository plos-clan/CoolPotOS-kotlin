package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.vfs.ALLOCATION_BLOCK_SIZE
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.ContentBackedFile
import org.plos_clan.cpos.fs.vfs.FileAllocationMode
import org.plos_clan.cpos.fs.vfs.FileContent
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.FileSeals
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.MappableFile
import org.plos_clan.cpos.fs.vfs.MappedFile
import org.plos_clan.cpos.fs.vfs.MutableInodeBackend
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.SealableFile
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PageCacheAcquireResult
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.ResidentPage
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp

internal class TmpfsRegularFile(
    private val fileSystem: TmpfsInstance,
    initialSeals: Int = FileSeals.SEAL,
    override val displayName: VfsPathname? = null,
) : RegularFileBackend(), MutableInodeBackend, ContentBackedFile, OpenFileBackend, MappableFile, SealableFile {

    private val lock = IrqSpinLock()
    private val pages = mutableMapOf<ULong, ResidentPage>()
    private val seals = FileSeals(initialSeals)
    private val mappings = mutableMapOf<AddressSpace, Int>()

    override fun attachContent(inode: Inode, content: FileContent, offset: Int, size: Int): Boolean =
        lock.withLock {
            if (pages.isNotEmpty() || offset < 0 || size < 0 || offset > content.size - size) return false
            var copied = 0
            while (copied < size) {
                val page = when (val result = createPage(copied.toULong() / PAGE_SIZE_BYTES)) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> {
                        discardPages(0uL, ULong.MAX_VALUE).forEach { it.release() }
                        return false
                    }
                }
                val count = minOf(size - copied, PAGE_SIZE_BYTES.toInt())
                if (content.copyInto(checkNotNull(page.prepareWrite(0, count)), 0, offset + copied, count) != count) {
                    discardPages(0uL, ULong.MAX_VALUE).forEach { it.release() }
                    return false
                }
                copied += count
            }
            inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED) { it.copy(size = size.toULong()) }
            true
        }

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
        VfsResult.Ok(this)

    override fun resize(
        caller: VfsOperationContext,
        inode: Inode,
        size: ULong,
    ): VfsResult<Unit> {
        val retired = lock.withLock {
            val previousSize = inode.metadata().size
            if (!seals.allowsResize(previousSize, size)) return VfsResult.Err(VfsError.NOT_PERMITTED)
            if (size > Long.MAX_VALUE.toULong()) return VfsResult.Err(VfsError.FILE_TOO_LARGE)
            val removed = if (size < previousSize) discardPages(size, ULong.MAX_VALUE) else null
            inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED) { it.copy(size = size) }
            removed
        } ?: return VfsResult.Ok(Unit)
        val firstRemoved = checkNotNull(size.alignUp(PAGE_SIZE_BYTES))
        invalidateMappings(inode, firstRemoved, ULong.MAX_VALUE, retired, privateCopies = true)
        return VfsResult.Ok(Unit)
    }

    override fun allocate(
        caller: VfsOperationContext,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (length == 0uL || offset > Long.MAX_VALUE.toULong() ||
            length > Long.MAX_VALUE.toULong() - offset
        ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (mode == FileAllocationMode.PUNCH_HOLE) {
            val end = offset + length
            val retired = lock.withLock {
                if (!seals.allowsWrite(inode.metadata().size, 0uL)) {
                    return VfsResult.Err(VfsError.NOT_PERMITTED)
                }
                val removed = discardPages(offset, end)
                inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED)
                removed
            }
            val firstRemoved = checkNotNull(offset.alignUp(PAGE_SIZE_BYTES))
            invalidateMappings(inode, firstRemoved, end.alignDown(PAGE_SIZE_BYTES), retired, privateCopies = false)
            return VfsResult.Ok(Unit)
        }
        return lock.withLock {
            val pageSize = PAGE_SIZE_BYTES
            val end = offset + length
            val size = inode.metadata().size
            if (!seals.allowsResize(size, end.coerceAtLeast(size))) {
                return@withLock VfsResult.Err(VfsError.NOT_PERMITTED)
            }
            val firstPage = offset / pageSize
            val lastPage = (end - 1uL) / pageSize

            val requestedPages = lastPage - firstPage + 1uL
            var existingPages = 0uL
            if (requestedPages <= pages.size.toULong()) {
                var pageIndex = firstPage
                while (true) {
                    if (pageIndex in pages) existingPages++
                    if (pageIndex == lastPage) break
                    pageIndex++
                }
            } else {
                for (pageIndex in pages.keys) {
                    if (pageIndex in firstPage..lastPage) existingPages++
                }
            }
            val missingPages = requestedPages - existingPages
            if (missingPages == 0uL) {
                inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED) {
                    if (mode.keepsSize) it else it.copy(size = maxOf(it.size, end))
                }
                return@withLock VfsResult.Ok(Unit)
            }
            if (missingPages > Int.MAX_VALUE.toULong() ||
                missingPages > ULong.MAX_VALUE / pageSize
            ) {
                return@withLock VfsResult.Err(VfsError.NO_SPACE)
            }

            val reservedBytes = missingPages * pageSize
            if (!fileSystem.reserve(reservedBytes)) {
                return@withLock VfsResult.Err(VfsError.NO_SPACE)
            }
            val added = try {
                ArrayList<ULong>(missingPages.toInt())
            } catch (_: OutOfMemoryError) {
                fileSystem.release(reservedBytes)
                return@withLock VfsResult.Err(VfsError.NO_MEMORY)
            }
            try {
                var pageIndex = firstPage
                while (true) {
                    if (pageIndex !in pages) {
                        val page = ResidentPage.allocate() ?: throw OutOfMemoryError()
                        try {
                            added += pageIndex
                            pages[pageIndex] = page
                        } catch (error: OutOfMemoryError) {
                            page.release()
                            throw error
                        }
                    }
                    if (pageIndex == lastPage) break
                    pageIndex++
                }
            } catch (_: OutOfMemoryError) {
                added.forEach { pages.remove(it)?.release() }
                fileSystem.release(reservedBytes)
                return@withLock VfsResult.Err(VfsError.NO_MEMORY)
            }
            inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED) {
                if (mode.keepsSize) it else it.copy(size = maxOf(it.size, end))
            }
            VfsResult.Ok(Unit)
        }
    }

    override fun loadAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<InodeAttributeSnapshot> = lock.withLock {
        val allocatedBytes = pages.size.toULong() * PAGE_SIZE_BYTES
        val blocks = allocatedBytes / ALLOCATION_BLOCK_SIZE +
            if (allocatedBytes % ALLOCATION_BLOCK_SIZE == 0uL) 0uL else 1uL
        VfsResult.Ok(
            InodeAttributeSnapshot(
                InodeAttributes(inode.metadata(), blocks),
                CacheValidity.Persistent,
            ),
        )
    }

    override fun evict(inode: Inode) {
        val releasedPages = lock.withLock {
            val count = pages.size
            pages.values.forEach { it.release() }
            pages.clear()
            count
        }
        if (releasedPages != 0) {
            fileSystem.release(releasedPages.toULong() * PAGE_SIZE_BYTES)
        }
    }

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = lock.withLock {
        val size = inode.metadata().size
        if (position.value < 0 || position.value.toULong() >= size || count == 0) {
            return@withLock IoResult.success(0)
        }

        val available = minOf(count.toULong(), size - position.value.toULong()).toInt()
        var copied = 0
        while (copied < available) {
            val absolute = position.value.toULong() + copied.toULong()
            val pageIndex = absolute / PAGE_SIZE_BYTES
            val pageOffset = (absolute % PAGE_SIZE_BYTES).toInt()
            val chunk = minOf(available - copied, PAGE_SIZE_BYTES.toInt() - pageOffset)
            val transferred = pages[pageIndex]?.let { page ->
                page.read(destination, destinationOffset + copied, pageOffset, chunk)
            } ?: destination.fill(destinationOffset + copied, chunk)
            if (transferred == 0) {
                if (copied == 0) return@withLock IoResult.failure(VfsError.FAULT)
                break
            }
            copied += transferred
            if (transferred < chunk) break
        }
        position.value += copied
        IoResult.success(copied)
    }

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = lock.withLock {
        if (count == 0) return@withLock IoResult.success(0)
        var cursor = if (append) inode.metadata().size else position.value.toULong()
        if (position.value < 0 || cursor > Long.MAX_VALUE.toULong() ||
            count.toLong() > Long.MAX_VALUE - cursor.toLong()
        ) {
            return@withLock IoResult.failure(VfsError.FILE_TOO_LARGE)
        }

        var copied = 0
        if (!seals.allowsWrite(inode.metadata().size, cursor + count.toULong())) {
            return@withLock IoResult.failure(VfsError.NOT_PERMITTED)
        }
        var failure = VfsError.FAULT
        while (copied < count) {
            val pageIndex = cursor / PAGE_SIZE_BYTES
            val pageOffset = (cursor % PAGE_SIZE_BYTES).toInt()
            val chunk = minOf(count - copied, PAGE_SIZE_BYTES.toInt() - pageOffset)
            var page = pages[pageIndex]
            if (page == null) {
                when (val result = createPage(pageIndex)) {
                    is VfsResult.Ok -> page = result.value
                    is VfsResult.Err -> {
                        failure = result.error
                        break
                    }
                }
            }
            val transferred = page.write(source, sourceOffset + copied, pageOffset, chunk)
            if (transferred == 0) break
            cursor += transferred.toULong()
            copied += transferred
            if (transferred < chunk) break
        }

        if (copied == 0 && count != 0) {
            return@withLock IoResult.failure(failure)
        }
        position.value = cursor.toLong()
        inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED) {
            it.copy(size = maxOf(it.size, cursor))
        }
        IoResult.success(copied)
    }

    override fun getSeals(): Int = lock.withLock { seals.bits }

    override fun addSeals(inode: Inode, seals: Int): VfsResult<Unit> = lock.withLock {
        this.seals.add(seals, inode.metadata().mode)
    }

    override fun setMode(caller: VfsOperationContext, inode: Inode, mode: FileMode): VfsResult<Unit> =
        lock.withLock {
            if (!seals.allowsMode(inode.metadata().mode, mode)) return VfsResult.Err(VfsError.NOT_PERMITTED)
            inode.updateMetadata { it.copy(mode = mode) }
            VfsResult.Ok(Unit)
        }

    override fun map(
        file: OpenFileDescription,
        shared: Boolean,
        access: ULong,
        maximumAccess: ULong,
    ): VfsResult<MappedFile> = lock.withLock {
        val maximum = when (val result = seals.acquireMapping(shared, access, maximumAccess)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        try {
            VfsResult.Ok(Mapping(file, shared, maximum))
        } catch (_: OutOfMemoryError) {
            seals.releaseMapping(shared, maximum)
            VfsResult.Err(VfsError.NO_MEMORY)
        }
    }

    private inner class Mapping(
        file: OpenFileDescription,
        private val shared: Boolean,
        maximumAccess: ULong,
    ) : MappedFile(file, maximumAccess) {
        override fun acquirePage(offset: ULong, scratch: ByteArray): PageCacheAcquireResult = lock.withLock {
            val size = file.inode.metadata().size
            if (offset >= size) return PageCacheAcquireResult.failed(PageCacheFailure.IO_ERROR)
            val page = pages[offset / PAGE_SIZE_BYTES] ?: when (val result = createPage(offset / PAGE_SIZE_BYTES)) {
                is VfsResult.Ok -> result.value.also { file.inode.invalidateAttributes() }
                is VfsResult.Err -> return PageCacheAcquireResult.failed(
                    if (result.error == VfsError.NO_MEMORY) PageCacheFailure.OUT_OF_MEMORY else PageCacheFailure.IO_ERROR,
                )
            }
            UserFrameReferences.retain(page.frame)
            PageCacheAcquireResult.acquired(page.frame, minOf(PAGE_SIZE_BYTES, size - offset).toInt())
        }

        override fun isPageCurrent(offset: ULong, frame: ULong): Boolean = lock.withLock {
            offset < file.inode.metadata().size && pages[offset / PAGE_SIZE_BYTES]?.frame == frame
        }

        override fun attached(addressSpace: AddressSpace) = lock.withLock {
            mappings[addressSpace] = (mappings[addressSpace] ?: 0) + 1
        }

        override fun detached(addressSpace: AddressSpace) = lock.withLock {
            val remaining = checkNotNull(mappings[addressSpace]) - 1
            if (remaining == 0) mappings.remove(addressSpace) else mappings[addressSpace] = remaining
            Unit
        }

        override fun close() {
            lock.withLock { seals.releaseMapping(shared, maximumAccess) }
            super.close()
        }
    }

    private fun createPage(index: ULong): VfsResult<ResidentPage> {
        if (!fileSystem.reserve(PAGE_SIZE_BYTES)) return VfsResult.Err(VfsError.NO_SPACE)
        var page: ResidentPage? = null
        try {
            page = ResidentPage.allocate()
            if (page != null) {
                pages[index] = page
                return VfsResult.Ok(page)
            }
        } catch (_: OutOfMemoryError) {
            page?.release()
        }
        fileSystem.release(PAGE_SIZE_BYTES)
        return VfsResult.Err(VfsError.NO_MEMORY)
    }

    private fun invalidateMappings(
        inode: Inode,
        start: ULong,
        end: ULong,
        retired: List<ResidentPage>,
        privateCopies: Boolean,
    ) {
        try {
            val privateFrames = if (privateCopies) null else retired.mapTo(mutableSetOf()) { it.frame }
            val visited = mutableSetOf<AddressSpace>()
            while (true) {
                val pending = lock.withLock { mappings.keys.filter { it !in visited } }
                if (pending.isEmpty()) break
                for (space in pending) {
                    space.invalidateFile(inode, start, end, privateFrames)
                    visited += space
                }
            }
        } finally {
            retired.forEach { it.release() }
        }
    }

    private fun discardPages(start: ULong, end: ULong): List<ResidentPage> {
        val retired = mutableListOf<ResidentPage>()
        val iterator = pages.iterator()
        while (iterator.hasNext()) {
            val (index, page) = iterator.next()
            val pageStart = index * PAGE_SIZE_BYTES
            val first = maxOf(start, pageStart)
            val last = minOf(end, pageStart + PAGE_SIZE_BYTES)
            if (first >= last) continue
            if (first == pageStart && last == pageStart + PAGE_SIZE_BYTES) {
                retired += page
                iterator.remove()
            } else {
                page.fill((first - pageStart).toInt(), (last - first).toInt())
            }
        }
        fileSystem.release(retired.size.toULong() * PAGE_SIZE_BYTES)
        return retired
    }
}
