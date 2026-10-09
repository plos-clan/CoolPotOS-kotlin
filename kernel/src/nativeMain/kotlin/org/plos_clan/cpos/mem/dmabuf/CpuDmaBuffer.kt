@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.dmabuf

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PageCacheAcquireResult
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.ResidentPage
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents

internal abstract class CpuDmaBuffer(
    protected val pages: List<ResidentPage>,
) : DmaBuffer(pages.size.toULong() * PAGE_SIZE_BYTES) {
    override fun isPageResident(offset: ULong): Boolean = offset < size

    override fun acquirePage(offset: ULong, scratch: ByteArray): PageCacheAcquireResult {
        if (offset >= size) return PageCacheAcquireResult.failed(PageCacheFailure.IO_ERROR)
        val page = pages[(offset / PAGE_SIZE_BYTES).toInt()]
        UserFrameReferences.retain(page.frame)
        return PageCacheAcquireResult.acquired(page.frame, PAGE_SIZE_BYTES.toInt())
    }

    override fun read(offset: ULong, destination: ByteArray): Int {
        if (offset >= size) return 0
        val page = pages[(offset / PAGE_SIZE_BYTES).toInt()]
        val withinPage = (offset % PAGE_SIZE_BYTES).toInt()
        val count = minOf(destination.size, PAGE_SIZE_BYTES.toInt() - withinPage)
        return page.copyTo(withinPage, destination, 0, count)
    }

    override fun copyTo(offset: ULong, destination: CPointer<UByteVar>, count: Int) {
        require(offset <= size && count.toULong() <= size - offset)
        var copied = 0
        while (copied < count) {
            val position = offset + copied.toULong()
            val page = pages[(position / PAGE_SIZE_BYTES).toInt()]
            val withinPage = (position % PAGE_SIZE_BYTES).toInt()
            val chunk = minOf(count - copied, PAGE_SIZE_BYTES.toInt() - withinPage)
            val target = checkNotNull(destination + copied)
            page.copyToNative(withinPage, target, chunk)
            copied += chunk
        }
    }

    override fun synchronize(access: DmaCpuAccess, end: Boolean): VfsResult<Unit> = VfsResult.Ok(Unit)

    override fun poll(events: Int): Long =
        (events and (PollEvents.POLLIN or PollEvents.POLLOUT)).toLong()

    override fun subscribe(subscription: PollSubscription) {}

}
