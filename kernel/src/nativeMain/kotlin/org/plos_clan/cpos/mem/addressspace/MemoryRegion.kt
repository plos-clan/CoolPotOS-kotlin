package org.plos_clan.cpos.mem.addressspace

import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheAcquireResult
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.ResidentPage
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

const val USER_MMAP_START = 0x0000_0000_0001_0000uL
const val USER_MMAP_END = 0x0000_7f00_0000_0000uL

internal class AnonymousRegionBacking : MemoryRegionBacking() {
    private val lock = IrqSpinLock()
    private val pages = mutableMapOf<ULong, ResidentPage>()

    override fun acquirePage(offset: ULong, scratch: ByteArray): PageCacheAcquireResult = lock.withLock {
        val page = pages[offset] ?: ResidentPage.allocate()?.also { pages[offset] = it }
            ?: return PageCacheAcquireResult.failed(PageCacheFailure.OUT_OF_MEMORY)
        UserFrameReferences.retain(page.frame)
        PageCacheAcquireResult.acquired(page.frame, PAGE_SIZE_BYTES.toInt())
    }

    override fun read(offset: ULong, destination: ByteArray): Int = lock.withLock {
        val page = pages[offset]
        if (page == null) destination.fill(0)
        else page.copyTo(0, destination, 0, destination.size)
        destination.size
    }

    override fun close() = pages.values.forEach(ResidentPage::release)
}

abstract class CachedRegionBacking : MemoryRegionBacking() {
    override fun acquirePage(offset: ULong, scratch: ByteArray): PageCacheAcquireResult =
        PageCache.acquire(cacheSource, offset, scratch)
}

abstract class FileRegionBacking(
    val file: OpenFileDescription,
) : CachedRegionBacking() {
    init {
        check(file.retain())
    }

    protected fun readFile(
        offset: ULong,
        destination: ByteArray,
        start: Int = 0,
        count: Int = destination.size - start,
    ): Int {
        val caller = ProcessManager.currentProcess()?.vfsOperationContext
            ?: VfsOperationContext.KERNEL
        val buffer = ByteArrayBuffer(destination)
        val result = file.readAt(caller, offset, buffer, start, count)
        return when {
            result.isSuccess -> result.bytesTransferred
            result.error == VfsError.INTERRUPTED -> PageCacheSource.READ_INTERRUPTED
            else -> PageCacheSource.READ_ERROR
        }
    }

    override fun close() = file.release()
}
