package org.plos_clan.cpos.mem.dmabuf

import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AnonymousFileBackend
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.MappableFile
import org.plos_clan.cpos.fs.vfs.MappedFile
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.PositionlessOpenFileBackend
import org.plos_clan.cpos.fs.vfs.SeekOrigin
import org.plos_clan.cpos.fs.vfs.SeekingOpenFileBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class DmaBufferFile(
    val buffer: DmaBuffer,
    access: AccessMode,
) : AnonymousFileBackend(InodeType.REGULAR, "dmabuf", access),
    MappableFile, SeekingOpenFileBackend, PositionlessOpenFileBackend {
    override val byteSize: ULong get() = buffer.size
    override val supportsEpoll: Boolean get() = true
    override val fileSystemMagic: ULong get() = 0x444d4142uL

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(VfsError.INVALID_ARGUMENT)

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(VfsError.INVALID_ARGUMENT)

    override fun seek(position: FilePosition, offset: Long, origin: SeekOrigin): VfsResult<Long> {
        if (offset != 0L || origin == SeekOrigin.CURRENT) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val value = if (origin == SeekOrigin.END) buffer.size.toLong() else 0L
        return VfsResult.Ok(value)
    }

    override fun map(
        file: OpenFileDescription,
        shared: Boolean,
        access: ULong,
        maximumAccess: ULong,
        offset: ULong,
        length: ULong,
    ): VfsResult<MappedFile> {
        if (!shared || offset > buffer.size || length > buffer.size - offset) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (access and 4uL != 0uL) return VfsResult.Err(VfsError.PERMISSION_DENIED)
        val mapping = DmaBufferMapping(file, maximumAccess and 4uL.inv(), buffer, offset)
        return VfsResult.Ok(mapping)
    }

    override fun ioctl(caller: VfsOperationContext, inode: Inode, command: Int, args: UserMemory): Long {
        if (command != 0x40086200) return -Errno.ENOTTY.toLong()
        val bytes = args.copyFromUser(8) ?: return -Errno.EFAULT.toLong()
        val flags = LittleEndianBuffer(bytes).readU64(0)
        if (flags and 7uL.inv() != 0uL) return -Errno.EINVAL.toLong()
        val accessBits = flags and 3uL
        val access = DmaCpuAccess.entries.firstOrNull { it.bits == accessBits }
            ?: return -Errno.EINVAL.toLong()
        return when (val result = buffer.synchronize(access, flags and 4uL != 0uL)) {
            is VfsResult.Ok -> 0
            is VfsResult.Err -> -result.error.errno.toLong()
        }
    }

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long = buffer.poll(events)

    override fun subscribe(caller: VfsOperationContext, inode: Inode, subscription: PollSubscription) =
        buffer.subscribe(subscription)

    override fun release() = buffer.releaseExport(this)
}
