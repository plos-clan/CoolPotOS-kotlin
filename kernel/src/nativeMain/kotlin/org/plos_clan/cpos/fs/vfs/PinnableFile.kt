package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.ResidentPage
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal interface PinnableFile {
    fun pin(file: OpenFileDescription, range: FilePageRange): VfsResult<PinnedFilePages>
}

internal class FilePageRange private constructor(val offset: ULong, val size: ULong) {
    val pageCount: Int get() = (size / PAGE_SIZE_BYTES).toInt()

    companion object {
        fun create(offset: ULong, size: ULong): VfsResult<FilePageRange> {
            val aligned = offset % PAGE_SIZE_BYTES == 0uL && size % PAGE_SIZE_BYTES == 0uL
            val limit = Long.MAX_VALUE.toULong()
            if (!aligned || size == 0uL || offset > limit || size > limit - offset) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (size / PAGE_SIZE_BYTES > Int.MAX_VALUE.toULong()) return VfsResult.Err(VfsError.NO_MEMORY)
            val range = FilePageRange(offset, size)
            return VfsResult.Ok(range)
        }
    }
}

internal abstract class PinnedFilePages : AutoCloseable {
    abstract val pages: List<ResidentPage>
}
