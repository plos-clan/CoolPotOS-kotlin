package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.utils.IrqSpinLock

internal class TmpfsRegularFile(
    private val fileSystem: TmpfsInstance,
) : RegularFileBackend(), MutableInodeBackend, ContentBackedFile {

    private val lock = IrqSpinLock()
    private val pages = mutableMapOf<ULong, ByteArray>()
    private var content: FileContent? = null
    private var contentOffset = 0
    private var contentSize = 0

    override fun attachContent(
        inode: Inode,
        content: FileContent,
        offset: Int,
        size: Int,
    ): Boolean {
        val attached = lock.withLock {
            if (this.content != null || pages.isNotEmpty() ||
                offset < 0 || size < 0 || offset > content.size - size
            ) {
                return@withLock false
            }
            this.content = content
            contentOffset = offset
            contentSize = size
            true
        }
        if (attached) {
            inode.updateMetadata { it.copy(size = size.toULong()) }
        }
        return attached
    }

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(TmpfsRegularHandle(this))

    override fun resize(inode: Inode, size: ULong): VfsResult<Unit> = lock.withLock {
        if (size < inode.metadata().size) {
            val pageSize = fileSystem.pageSize.toULong()
            val firstRemovedPage = if (size == 0uL) 0uL else (size - 1uL) / pageSize + 1uL
            var removedPages = 0uL
            val iterator = pages.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key >= firstRemovedPage) {
                    iterator.remove()
                    removedPages++
                }
            }
            if (removedPages != 0uL) {
                fileSystem.release(removedPages * pageSize)
            }

            if (size != 0uL) {
                val tail = (size % pageSize).toInt()
                if (tail != 0) {
                    pages[size / pageSize]?.fill(0, tail)
                }
            }
            contentSize = minOf(contentSize.toULong(), size).toInt()
        }
        inode.updateMetadata { it.copy(size = size) }
        VfsResult.Ok(Unit)
    }

    override fun allocate(
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> = lock.withLock {
        val pageSize = fileSystem.pageSize.toULong()
        val end = offset + length
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
                if (pageIndex >= firstPage && pageIndex <= lastPage) existingPages++
            }
        }
        val missingPages = requestedPages - existingPages
        if (missingPages == 0uL) {
            if (!mode.keepsSize) inode.updateMetadata { it.copy(size = maxOf(it.size, end)) }
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
                    val page = ByteArray(fileSystem.pageSize)
                    val destination = checkNotNull(ByteArrayBuffer(page).prepareWrite(0, page.size))
                    copyContent(pageIndex * pageSize, destination, 0, page.size)
                    added += pageIndex
                    pages[pageIndex] = page
                }
                if (pageIndex == lastPage) break
                pageIndex++
            }
        } catch (_: OutOfMemoryError) {
            added.forEach(pages::remove)
            fileSystem.release(reservedBytes)
            return@withLock VfsResult.Err(VfsError.NO_MEMORY)
        }
        if (!mode.keepsSize) inode.updateMetadata { it.copy(size = maxOf(it.size, end)) }
        VfsResult.Ok(Unit)
    }

    override fun allocatedBlocks(inode: Inode): ULong = lock.withLock {
        val pageSize = fileSystem.pageSize.toULong()
        val contentPages = if (contentSize == 0) {
            0uL
        } else {
            (contentSize.toULong() - 1uL) / pageSize + 1uL
        }
        var allocatedPages = contentPages
        for (pageIndex in pages.keys) {
            if (pageIndex >= contentPages) allocatedPages++
        }
        val allocatedBytes = allocatedPages * pageSize
        allocatedBytes / ALLOCATION_BLOCK_SIZE +
            if (allocatedBytes % ALLOCATION_BLOCK_SIZE == 0uL) 0uL else 1uL
    }

    override fun evict(inode: Inode) {
        val releasedPages = lock.withLock {
            val count = pages.size
            pages.clear()
            content = null
            count
        }
        if (releasedPages != 0) {
            fileSystem.release(releasedPages.toULong() * fileSystem.pageSize.toULong())
        }
    }

    fun read(
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
            val pageIndex = absolute / fileSystem.pageSize.toULong()
            val pageOffset = (absolute % fileSystem.pageSize.toULong()).toInt()
            val chunk = minOf(available - copied, fileSystem.pageSize - pageOffset)
            val transferred = pages[pageIndex]?.let { page ->
                destination.copyFrom(destinationOffset + copied, page, pageOffset, chunk)
            } ?: readContentOrZero(absolute, destination, destinationOffset + copied, chunk)
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

    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = lock.withLock {
        var cursor = if (append) inode.metadata().size else position.value.toULong()
        if (position.value < 0 || cursor > Long.MAX_VALUE.toULong() ||
            count.toLong() > Long.MAX_VALUE - cursor.toLong()
        ) {
            return@withLock IoResult.failure(VfsError.FILE_TOO_LARGE)
        }

        var copied = 0
        var noSpace = false
        while (copied < count) {
            val pageIndex = cursor / fileSystem.pageSize.toULong()
            val pageOffset = (cursor % fileSystem.pageSize.toULong()).toInt()
            val chunk = minOf(count - copied, fileSystem.pageSize - pageOffset)
            var page = pages[pageIndex]
            if (page == null) {
                if (!fileSystem.reserve(fileSystem.pageSize.toULong())) {
                    noSpace = true
                    break
                }
                page = ByteArray(fileSystem.pageSize)
                val destination = checkNotNull(ByteArrayBuffer(page).prepareWrite(0, page.size))
                copyContent(
                    pageIndex * fileSystem.pageSize.toULong(),
                    destination,
                    0,
                    page.size,
                )
                pages[pageIndex] = page
            }
            val transferred = source.copyTo(sourceOffset + copied, page, pageOffset, chunk)
            if (transferred == 0) break
            cursor += transferred.toULong()
            copied += transferred
            if (transferred < chunk) break
        }

        if (copied == 0 && count != 0) {
            return@withLock IoResult.failure(if (noSpace) VfsError.NO_SPACE else VfsError.FAULT)
        }
        position.value = cursor.toLong()
        inode.updateMetadata { it.copy(size = maxOf(it.size, cursor)) }
        IoResult.success(copied)
    }

    private fun copyContent(
        position: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): Int {
        val source = content ?: return 0
        if (position > Int.MAX_VALUE.toULong()) return 0
        val sourcePosition = position.toInt()
        val copied = minOf(count, contentSize - sourcePosition).coerceAtLeast(0)
        if (copied != 0) {
            return source.copyInto(
                destination,
                destinationOffset,
                contentOffset + sourcePosition,
                copied,
            )
        }
        return 0
    }

    private fun readContentOrZero(
        position: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): Int {
        val contentBytes = contentBytes(position, count)
        val copied = copyContent(position, destination, destinationOffset, contentBytes)
        if (copied < contentBytes) return copied
        return copied + destination.fill(destinationOffset + copied, count - copied)
    }

    private fun contentBytes(position: ULong, count: Int): Int {
        if (content == null || position > Int.MAX_VALUE.toULong()) return 0
        return minOf(count, contentSize - position.toInt()).coerceAtLeast(0)
    }
}

private class TmpfsRegularHandle(private val file: TmpfsRegularFile) : OpenFileBackend {
    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = file.read(inode, destination, destinationOffset, count, position)

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = file.write(inode, source, sourceOffset, count, position, append)
}
