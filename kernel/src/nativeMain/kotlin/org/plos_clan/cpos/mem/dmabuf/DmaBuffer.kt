@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.dmabuf

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AnonymousFileFactory
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.MappedFile
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PageCacheAcquireResult
import org.plos_clan.cpos.mem.addressspace.MemoryRegionBacking
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal enum class DmaCpuAccess(val bits: ULong) {
    READ(1uL), WRITE(2uL), READ_WRITE(3uL);
}

internal abstract class DmaBuffer(val size: ULong) : MemoryRegionBacking() {
    private val exportLock = KernelMutex()
    private var exported: OpenFileDescription? = null

    init {
        require(size > 0uL && size <= Long.MAX_VALUE.toULong() && size % PAGE_SIZE_BYTES == 0uL)
    }

    abstract fun copyTo(offset: ULong, destination: CPointer<UByteVar>, count: Int)
    abstract fun synchronize(access: DmaCpuAccess, end: Boolean): VfsResult<Unit>
    abstract fun poll(events: Int): Long
    abstract fun subscribe(subscription: PollSubscription)

    fun export(
        caller: VfsOperationContext,
        context: FileSystemContext,
        access: AccessMode,
    ): VfsResult<OpenFileDescription> = exportLock.withLock {
        val current = exported
        if (current != null && current.retain()) return@withLock VfsResult.Ok(current)
        val backend = DmaBufferFile(this, access)
        val options = OpenOptions(access = access)
        val mode = FileMode(0x180u)
        val metadata = InodeMetadata(mode = mode, size = size, linkCount = 0u)
        val result = AnonymousFileFactory().open(caller, context, backend, options, metadata)
        if (result is VfsResult.Ok) {
            check(retain())
            exported = result.value
        }
        result
    }

    fun releaseExport(backend: DmaBufferFile) {
        exportLock.withLock {
            if (exported?.backend === backend) exported = null
        }
        release()
    }
}

internal class DmaBufferMapping(
    file: OpenFileDescription,
    maximumAccess: ULong,
    private val buffer: DmaBuffer,
    offset: ULong = 0uL,
) : MappedFile(file, maximumAccess, offset) {
    init {
        check(buffer.retain())
    }

    override val identity: Any get() = buffer
    override val sharedMemoryIdentity: Any get() = buffer
    override val cacheSource get() = this

    override fun isPageResident(offset: ULong): Boolean = buffer.isPageResident(offset)

    override fun acquirePage(offset: ULong, scratch: ByteArray): PageCacheAcquireResult =
        buffer.acquirePage(offset, scratch)

    override fun read(offset: ULong, destination: ByteArray): Int = buffer.read(offset, destination)

    override fun close() {
        buffer.release()
        super.close()
    }
}
