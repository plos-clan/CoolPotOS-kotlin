package org.plos_clan.cpos.mem.dmabuf

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ResidentPage
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal class ResidentDmaBuffer private constructor(
    pages: List<ResidentPage>,
) : CpuDmaBuffer(pages) {
    override fun close() = pages.forEach(ResidentPage::release)

    companion object {
        fun allocate(size: ULong): VfsResult<ResidentDmaBuffer> {
            if (size == 0uL || size > Long.MAX_VALUE.toULong() || size % PAGE_SIZE_BYTES != 0uL) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val count = size / PAGE_SIZE_BYTES
            if (count > Int.MAX_VALUE.toULong()) return VfsResult.Err(VfsError.NO_MEMORY)
            val pages = try {
                ArrayList<ResidentPage>(count.toInt())
            } catch (_: OutOfMemoryError) {
                return VfsResult.Err(VfsError.NO_MEMORY)
            }
            try {
                repeat(count.toInt()) {
                    val page = ResidentPage.allocate() ?: throw OutOfMemoryError()
                    pages.add(page)
                }
                val buffer = ResidentDmaBuffer(pages)
                return VfsResult.Ok(buffer)
            } catch (_: OutOfMemoryError) {
                pages.forEach(ResidentPage::release)
                return VfsResult.Err(VfsError.NO_MEMORY)
            }
        }
    }
}
