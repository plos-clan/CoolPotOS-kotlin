package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.addressspace.FileRegionBacking
import org.plos_clan.cpos.tasks.ProcessManager

internal interface MappableFile {
    fun map(file: OpenFileDescription, shared: Boolean, access: ULong, maximumAccess: ULong):
        VfsResult<MappedFile>
}

internal open class MappedFile(
    file: OpenFileDescription,
    val maximumAccess: ULong,
) : FileRegionBacking(file) {
    override val cacheSource
        get() = file.cacheSource ?: this

    override val identity
        get() = file.inode

    override val sharedMemoryIdentity: Any
        get() = file.inode

    override fun read(offset: ULong, destination: ByteArray): Int {
        val caller = ProcessManager.currentProcess()?.vfsOperationContext ?: VfsOperationContext.KERNEL
        val result = file.readAt(caller, offset, ByteArrayBuffer(destination), 0, destination.size)
        return if (result.isSuccess) result.bytesTransferred else result.raw.toInt()
    }
}
