package org.plos_clan.cpos.module.elf

import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.addressspace.FileRegionBacking
import org.plos_clan.cpos.module.elf.ElfLayout.checkedAdd

class ElfBacking(
    file: OpenFileDescription,
    private val segments: List<LoadSegment>,
) : FileRegionBacking(file) {
    override val identity: Any = ElfPageCacheIdentity(
        file.cacheSource?.identity ?: file,
        segments,
    )

    override fun read(offset: ULong, destination: ByteArray): Int {
        val end = checkedAdd(offset, destination.size.toULong())
            ?: return PageCacheSource.READ_ERROR
        for (segment in segments) {
            val fileEnd = checkedAdd(segment.start, segment.header.fileSize)
                ?: return PageCacheSource.READ_ERROR
            val start = maxOf(offset, segment.start)
            val segmentEnd = minOf(end, fileEnd)
            if (start >= segmentEnd) continue
            val count = (segmentEnd - start).toInt()
            val fileOffset = segment.header.fileOffset + (start - segment.start)
            val bufferOffset = (start - offset).toInt()
            val read = readFile(fileOffset, destination, bufferOffset, count)
            if (read < 0) return read
            if (read != count) return PageCacheSource.READ_ERROR
        }
        return destination.size
    }
}
