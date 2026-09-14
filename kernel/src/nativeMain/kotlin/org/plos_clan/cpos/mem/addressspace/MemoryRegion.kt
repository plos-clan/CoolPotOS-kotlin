package org.plos_clan.cpos.mem.addressspace

import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheAcquireResult

const val USER_MMAP_START = 0x0000_0000_0001_0000uL
const val USER_MMAP_END = 0x0000_7f00_0000_0000uL

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

    override fun close() = file.release()
}
