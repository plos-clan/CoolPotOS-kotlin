package org.plos_clan.cpos.module.elf

import org.plos_clan.cpos.fs.OpenFileDescription
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.addressspace.FileRegionBacking
import org.plos_clan.cpos.module.elf.ElfLayout.checkedAdd

private const val EIO = 5

class ElfBacking(
    file: OpenFileDescription,
    private val segments: List<LoadSegment>,
) : FileRegionBacking(file) {
    override val identity: Any = ElfPageCacheIdentity(
        file.cacheSource?.identity ?: file,
        segments,
    )

    override fun read(offset: ULong, destination: ByteArray): Int {
        val end = checkedAdd(offset, destination.size.toULong()) ?: return -EIO
        for (segment in segments) {
            val fileEnd = checkedAdd(segment.start, segment.header.fileSize) ?: return -EIO
            val start = maxOf(offset, segment.start)
            val segmentEnd = minOf(end, fileEnd)
            if (start >= segmentEnd) continue
            val count = (segmentEnd - start).toInt()
            val result = file.readAt(
                fileOffset = segment.header.fileOffset + (start - segment.start),
                destination = ByteArrayBuffer(destination),
                offset = (start - offset).toInt(),
                count = count,
            )
            if (!result.isSuccess || result.bytesTransferred != count) return -EIO
        }
        return destination.size
    }
}
