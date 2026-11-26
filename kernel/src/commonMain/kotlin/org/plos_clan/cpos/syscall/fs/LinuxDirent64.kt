package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.syscall.fs.FsConstants.DIRENT64_ALIGNMENT
import org.plos_clan.cpos.syscall.fs.FsConstants.DIRENT64_HEADER_SIZE
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class LinuxDirent64(
    private val name: org.plos_clan.cpos.fs.vfs.VfsName,
    private val inode: org.plos_clan.cpos.fs.vfs.InodeId,
    private val type: InodeType?,
    private val nextOffset: Long,
) {
    val recordSize: Int =
        (DIRENT64_HEADER_SIZE + name.size + 1 + DIRENT64_ALIGNMENT - 1) /
            DIRENT64_ALIGNMENT * DIRENT64_ALIGNMENT

    fun writeTo(bytes: ByteArray, offset: Int) {
        val output = LittleEndianBuffer(bytes)
        output.writeU64(offset, inode.value)
        output.writeU64(offset + 8, nextOffset.toULong())
        output.writeU16(offset + 16, recordSize.toUShort())
        bytes[offset + 18] = type?.directoryEntryType ?: 0
        val start = offset + DIRENT64_HEADER_SIZE
        name.copyInto(bytes, start)
        bytes.fill(0, start + name.size, offset + recordSize)
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
            InodeType.TIMERFD -> 0
            InodeType.EPOLL -> 0
            InodeType.INOTIFY -> 0
            InodeType.PIDFD -> 0
            InodeType.SIGNALFD -> 0
        }
}
