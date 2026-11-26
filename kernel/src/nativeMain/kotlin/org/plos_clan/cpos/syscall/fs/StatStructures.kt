@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.FileSystemStatistics
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.time.Instant
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
    val fileSystemDevice: DeviceNumber,
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
            InodeType.TIMERFD,
            InodeType.EPOLL,
            InodeType.INOTIFY,
            InodeType.PIDFD,
            InodeType.SIGNALFD,
            -> 0u
        }

    val specialDeviceMajor: UInt
        get() = (metadata.deviceNumber shr 8 and 0xfffuL).toUInt()

    val specialDeviceMinor: UInt
        get() = ((metadata.deviceNumber and 0xffuL) or
            (metadata.deviceNumber shr 12 and 0xfffff00uL)).toUInt()

    companion object {
        fun snapshot(inode: Inode, attributes: InodeAttributes) = LinuxFileStatus(
            fileSystemDevice = inode.superBlock.deviceNumber,
            inodeId = inode.id.value,
            type = inode.type,
            metadata = attributes.metadata,
            blocks = attributes.allocatedBlocks,
            blockSize = attributes.blockSize,
        )
    }
}

internal class LinuxStat(private val status: LinuxFileStatus) : NativeStruct {
    override val nativeSize: Int get() = STAT_SIZE

    override fun writeTo(output: LittleEndianBuffer) {
        output.writeU64(0, status.fileSystemDevice.value)
        output.writeU64(8, status.inodeId)
        output.writeU64(16, status.metadata.linkCount.toULong())
        output.writeU32(24, status.mode)
        output.writeU32(28, status.metadata.uid)
        output.writeU32(32, status.metadata.gid)
        output.writeU32(36, 0u)
        output.writeU64(40, status.metadata.deviceNumber)
        output.writeU64(48, status.metadata.size)
        output.writeU64(56, status.blockSize)
        output.writeU64(64, status.blocks)
        output.writeU64(72, status.metadata.timestamps.accessTime.seconds.toULong())
        output.writeU64(80, status.metadata.timestamps.accessTime.nanoseconds.toULong())
        output.writeU64(88, status.metadata.timestamps.modificationTime.seconds.toULong())
        output.writeU64(96, status.metadata.timestamps.modificationTime.nanoseconds.toULong())
        output.writeU64(104, status.metadata.timestamps.changeTime.seconds.toULong())
        output.writeU64(112, status.metadata.timestamps.changeTime.nanoseconds.toULong())
    }
}

internal class LinuxStatx(
    private val status: LinuxFileStatus,
    private val isMountRoot: Boolean,
    private val mountId: ULong,
) : NativeStruct {
    override val nativeSize: Int get() = STATX_SIZE

    override fun writeTo(output: LittleEndianBuffer) {
        val birthTime = status.metadata.timestamps.birthTime
        output.writeU32(0, STATX_SUPPORTED_FIELDS or if (birthTime == null) 0u else STATX_BTIME)
        output.writeU32(4, status.blockSize.toUInt())
        output.writeU64(8, if (isMountRoot) STATX_ATTR_MOUNT_ROOT else 0uL)
        output.writeU32(16, status.metadata.linkCount)
        output.writeU32(20, status.metadata.uid)
        output.writeU32(24, status.metadata.gid)
        output.writeU16(28, status.mode.toUShort())
        output.writeU64(32, status.inodeId)
        output.writeU64(40, status.metadata.size)
        output.writeU64(48, status.blocks)
        output.writeU64(56, STATX_ATTR_MOUNT_ROOT)
        output.writeTimestamp(64, status.metadata.timestamps.accessTime)
        birthTime?.let { output.writeTimestamp(80, it) }
        output.writeTimestamp(96, status.metadata.timestamps.changeTime)
        output.writeTimestamp(112, status.metadata.timestamps.modificationTime)
        output.writeU32(128, status.specialDeviceMajor)
        output.writeU32(132, status.specialDeviceMinor)
        output.writeU32(136, status.fileSystemDevice.major)
        output.writeU32(140, status.fileSystemDevice.minor)
        output.writeU64(144, mountId)
    }

    private fun LittleEndianBuffer.writeTimestamp(offset: Int, timestamp: Instant) {
        writeU64(offset, timestamp.seconds.toULong())
        writeU32(offset + Long.SIZE_BYTES, timestamp.nanoseconds)
    }
}

internal class LinuxStatFs(
    private val fileSystemMagic: ULong,
    private val mountFlags: MountFlags,
    private val statistics: FileSystemStatistics,
) : NativeStruct {
    override val nativeSize: Int get() = STATFS_SIZE

    override fun writeTo(output: LittleEndianBuffer) {
        output.writeU64(0, fileSystemMagic)
        output.writeU64(8, statistics.blockSize)
        output.writeU64(16, statistics.blocks)
        output.writeU64(24, statistics.freeBlocks)
        output.writeU64(32, statistics.availableBlocks)
        output.writeU64(40, statistics.files)
        output.writeU64(48, statistics.freeFiles)
        output.writeU64(64, statistics.maximumNameLength)
        output.writeU64(72, statistics.fragmentSize)
        output.writeU64(80, mountFlags.toStatFsFlags())
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
