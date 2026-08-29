@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.FileSystemStatistics
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.VfsTimestamp
import org.plos_clan.cpos.syscall.fs.FsConstants.DIRENT64_ALIGNMENT
import org.plos_clan.cpos.syscall.fs.FsConstants.DIRENT64_HEADER_SIZE
import org.plos_clan.cpos.syscall.fs.FsConstants.STATFS_SIZE
import org.plos_clan.cpos.syscall.fs.FsConstants.STATX_ATTR_MOUNT_ROOT
import org.plos_clan.cpos.syscall.fs.FsConstants.STATX_BTIME
import org.plos_clan.cpos.syscall.fs.FsConstants.STATX_SIZE
import org.plos_clan.cpos.syscall.fs.FsConstants.STATX_SUPPORTED_FIELDS
import org.plos_clan.cpos.syscall.fs.FsConstants.STAT_BLKSIZE
import org.plos_clan.cpos.syscall.fs.FsConstants.STAT_SIZE
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_NOATIME
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_NODEV
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_NODIRATIME
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_NOEXEC
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_NOSUID
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_NOSYMFOLLOW
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_RDONLY
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_RELATIME
import org.plos_clan.cpos.syscall.fs.FsConstants.ST_SYNCHRONOUS
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFBLK
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFCHR
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFDIR
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFIFO
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFLNK
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFREG
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFSOCK
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct

internal data class LinuxFileStatus(
    val inodeId: ULong,
    val type: InodeType,
    val metadata: InodeMetadata,
    val blocks: ULong,
    val blockSize: ULong = STAT_BLKSIZE,
) {
    val mode: UInt
        get() = metadata.mode.bits or when (type) {
            InodeType.REGULAR -> S_IFREG
            InodeType.DIRECTORY -> S_IFDIR
            InodeType.SYMLINK -> S_IFLNK
            InodeType.CHARACTER_DEVICE -> S_IFCHR
            InodeType.BLOCK_DEVICE -> S_IFBLK
            InodeType.PIPE -> S_IFIFO
            InodeType.SOCKET -> S_IFSOCK
            InodeType.EVENTFD,
            InodeType.EPOLL,
            -> S_IFREG
        }

    val deviceMajor: UInt
        get() = (metadata.deviceNumber shr 8 and 0xfffuL).toUInt()

    val deviceMinor: UInt
        get() = ((metadata.deviceNumber and 0xffuL) or
            (metadata.deviceNumber shr 12 and 0xfffff00uL)).toUInt()

    companion object {
        fun snapshot(inode: Inode, attributes: InodeAttributes) = LinuxFileStatus(
            inodeId = inode.id.value,
            type = inode.type,
            metadata = attributes.metadata,
            blocks = attributes.allocatedBlocks,
            blockSize = attributes.blockSize,
        )
    }
}

internal class LinuxStat(private val status: LinuxFileStatus) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(STAT_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, 0uL) // st_dev
            writeU64(8, status.inodeId)
            writeU64(16, status.metadata.linkCount.toULong())
            writeU32(24, status.mode)
            writeU32(28, status.metadata.uid)
            writeU32(32, status.metadata.gid)
            writeU32(36, 0u) // __pad0
            writeU64(40, status.metadata.deviceNumber)
            writeU64(48, status.metadata.size)
            writeU64(56, status.blockSize)
            writeU64(64, status.blocks)
            writeU64(72, status.metadata.timestamps.accessTime.seconds.toULong())
            writeU64(80, status.metadata.timestamps.accessTime.nanoseconds.toULong())
            writeU64(88, status.metadata.timestamps.modificationTime.seconds.toULong())
            writeU64(96, status.metadata.timestamps.modificationTime.nanoseconds.toULong())
            writeU64(104, status.metadata.timestamps.changeTime.seconds.toULong())
            writeU64(112, status.metadata.timestamps.changeTime.nanoseconds.toULong())
        }
    }
}

internal class LinuxStatx(
    private val status: LinuxFileStatus,
    private val isMountRoot: Boolean,
) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(STATX_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            val birthTime = status.metadata.timestamps.birthTime
            writeU32(0, STATX_SUPPORTED_FIELDS or if (birthTime == null) 0u else STATX_BTIME)
            writeU32(4, status.blockSize.toUInt())
            writeU64(8, if (isMountRoot) STATX_ATTR_MOUNT_ROOT else 0uL)
            writeU32(16, status.metadata.linkCount)
            writeU32(20, status.metadata.uid)
            writeU32(24, status.metadata.gid)
            writeU16(28, status.mode.toUShort())
            writeU64(32, status.inodeId)
            writeU64(40, status.metadata.size)
            writeU64(48, status.blocks)
            writeU64(56, STATX_ATTR_MOUNT_ROOT)
            writeTimestamp(64, status.metadata.timestamps.accessTime)
            birthTime?.let { writeTimestamp(80, it) }
            writeTimestamp(96, status.metadata.timestamps.changeTime)
            writeTimestamp(112, status.metadata.timestamps.modificationTime)
            writeU32(128, status.deviceMajor)
            writeU32(132, status.deviceMinor)
        }
    }

    private fun LittleEndianBuffer.writeTimestamp(offset: Int, timestamp: VfsTimestamp) {
        writeU64(offset, timestamp.seconds.toULong())
        writeU32(offset + Long.SIZE_BYTES, timestamp.nanoseconds)
    }
}

internal class LinuxStatFs(
    private val fileSystemMagic: ULong,
    private val mountFlags: MountFlags,
    private val statistics: FileSystemStatistics,
) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(STATFS_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, fileSystemMagic)
            writeU64(8, statistics.blockSize)
            writeU64(16, statistics.blocks)
            writeU64(24, statistics.freeBlocks)
            writeU64(32, statistics.availableBlocks)
            writeU64(40, statistics.files)
            writeU64(48, statistics.freeFiles)
            writeU64(64, statistics.maximumNameLength)
            writeU64(72, statistics.fragmentSize)
            writeU64(80, mountFlags.toStatFsFlags())
        }
    }

    private fun MountFlags.toStatFsFlags(): ULong = StatFlag.entries.fold(0uL) { bits, flag ->
        if (flag.mountFlag in this) bits or flag.bits else bits
    }

    private enum class StatFlag(val mountFlag: MountFlag, val bits: ULong) {
        READ_ONLY(MountFlag.READ_ONLY, ST_RDONLY),
        NO_SUID(MountFlag.NO_SUID, ST_NOSUID),
        NO_DEVICE(MountFlag.NO_DEVICE, ST_NODEV),
        NO_EXEC(MountFlag.NO_EXEC, ST_NOEXEC),
        SYNCHRONOUS(MountFlag.SYNCHRONOUS, ST_SYNCHRONOUS),
        NO_ATIME(MountFlag.NO_ATIME, ST_NOATIME),
        NO_DIRECTORY_ATIME(MountFlag.NO_DIRECTORY_ATIME, ST_NODIRATIME),
        RELATIVE_ATIME(MountFlag.RELATIVE_ATIME, ST_RELATIME),
        NO_SYMLINK_FOLLOW(MountFlag.NO_SYMLINK_FOLLOW, ST_NOSYMFOLLOW),
    }
}

internal class LinuxDirent64(
    private val entry: DirectoryEntry,
    private val nextOffset: Long,
) : NativeStruct {
    private val name = entry.name.copyBytes()

    val recordSize: Int =
        (DIRENT64_HEADER_SIZE + name.size + 1 + DIRENT64_ALIGNMENT - 1) /
            DIRENT64_ALIGNMENT * DIRENT64_ALIGNMENT

    override fun toNativeBytes(): ByteArray = ByteArray(recordSize).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, entry.inodeId.value)
            writeU64(8, nextOffset.toULong())
            writeU16(16, recordSize.toUShort())
        }
        buffer[18] = entry.type?.directoryEntryType ?: 0
        name.copyInto(buffer, DIRENT64_HEADER_SIZE)
    }

    private val InodeType.directoryEntryType: Byte
        get() = when (this) {
            InodeType.PIPE -> 1
            InodeType.CHARACTER_DEVICE -> 2
            InodeType.DIRECTORY -> 4
            InodeType.BLOCK_DEVICE -> 6
            InodeType.REGULAR -> 8
            InodeType.SYMLINK -> 10
            InodeType.SOCKET -> 12
            InodeType.EVENTFD -> 0
            InodeType.EPOLL -> 0
        }
}
