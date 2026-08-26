package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.OpenFlags

internal object FsConstants {
    const val IO_CHUNK_SIZE = 64 * 1024
    const val MAX_RW_COUNT = 0x7ffff000uL
    const val MAX_IO_VECTORS = 1024
    const val AT_FDCWD = -100
    const val AT_SYMLINK_NOFOLLOW = 0x100
    const val AT_REMOVEDIR = 0x200
    const val AT_EACCESS = 0x200
    const val AT_SYMLINK_FOLLOW = 0x400
    const val AT_NO_AUTOMOUNT = 0x800
    const val AT_EMPTY_PATH = 0x1000
    const val AT_STATX_FORCE_SYNC = 0x2000
    const val AT_STATX_DONT_SYNC = 0x4000
    const val AT_STATX_SYNC_TYPE = AT_STATX_FORCE_SYNC or AT_STATX_DONT_SYNC
    const val UTIME_NOW = 1_073_741_823L
    const val UTIME_OMIT = 1_073_741_822L
    const val O_CLOEXEC = 0x0008_0000uL
    const val O_NONBLOCK = 0x0000_0800uL
    const val POLL_FD_SIZE = 8
    const val MAX_POLL_FDS = 1024
    const val NANOSECONDS_PER_MILLISECOND = 1_000_000uL
    const val SUPPORTED_OPEN_FLAGS =
        OpenFlags.O_ACCMODE or OpenFlags.O_CREAT or OpenFlags.O_EXCL or
            OpenFlags.O_NOCTTY or OpenFlags.O_TRUNC or OpenFlags.O_APPEND or
            OpenFlags.O_NONBLOCK or OpenFlags.O_DSYNC or OpenFlags.O_SYNC or
            OpenFlags.O_ASYNC or OpenFlags.O_DIRECT or OpenFlags.O_LARGEFILE or
            OpenFlags.O_DIRECTORY or OpenFlags.O_NOFOLLOW or OpenFlags.O_NOATIME or
            OpenFlags.O_CLOEXEC or OpenFlags.O_PATH or OpenFlags.O_TMPFILE

    const val F_DUPFD = 0
    const val F_GETFD = 1
    const val F_SETFD = 2
    const val F_GETFL = 3
    const val F_SETFL = 4
    const val F_GETOWN = 9
    const val F_SETOWN = 8
    const val F_DUPFD_CLOEXEC = 1_030
    const val F_GETFD_FLAGS = FileDescriptorFlags.FD_CLOEXEC

    const val CLOSE_RANGE_CLOEXEC = 0x4u

    const val STAT_SIZE = 144
    const val STATX_SIZE = 256
    const val STATFS_SIZE = 120
    const val STAT_BLKSIZE = 4096uL
    const val DIRENT64_HEADER_SIZE = 19
    const val DIRENT64_ALIGNMENT = 8
    const val DIRENT64_MIN_SIZE = 24

    const val S_IFIFO = 0x1000u
    const val S_IFCHR = 0x2000u
    const val S_IFDIR = 0x4000u
    const val S_IFBLK = 0x6000u
    const val S_IFREG = 0x8000u
    const val S_IFLNK = 0xA000u
    const val S_IFSOCK = 0xC000u
    const val S_IFMT = 0xF000u
    const val S_ISGID = 0x400u
    const val S_IALLUGO = 0xFFFu

    const val FALLOC_FL_KEEP_SIZE = 0x01

    const val RENAME_NOREPLACE = 0x1
    const val RENAME_EXCHANGE = 0x2
    const val RENAME_WHITEOUT = 0x4

    const val XATTR_CREATE = 0x1
    const val XATTR_REPLACE = 0x2
    const val STATX_SUPPORTED_FIELDS = 0x7ffu
    const val STATX_BTIME = 0x800u
    const val STATX_RESERVED = 0x8000_0000u
    const val STATX_ATTR_MOUNT_ROOT = 0x2000uL

    const val ST_RDONLY = 0x1uL
    const val ST_NOSUID = 0x2uL
    const val ST_NODEV = 0x4uL
    const val ST_NOEXEC = 0x8uL
    const val ST_SYNCHRONOUS = 0x10uL
    const val ST_NOATIME = 0x400uL
    const val ST_NODIRATIME = 0x800uL
    const val ST_RELATIME = 0x1000uL
    const val ST_NOSYMFOLLOW = 0x2000uL
    const val MS_SILENT = 0x8000uL
    const val MS_MOVE = 0x2000uL
}
