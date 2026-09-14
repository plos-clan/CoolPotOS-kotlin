package org.plos_clan.cpos.fs

object OpenFlags {
    const val O_ACCMODE = 0x000003
    const val O_RDONLY = 0x000000
    const val O_WRONLY = 0x000001
    const val O_RDWR = 0x000002

    const val O_CREAT = 0x000040
    const val O_EXCL = 0x000080
    const val O_NOCTTY = 0x000100
    const val O_TRUNC = 0x000200
    const val O_APPEND = 0x000400
    const val O_NONBLOCK = 0x000800

    const val O_DSYNC = 0x001000
    const val O_SYNC = 0x101000
    const val O_RSYNC = 0x101000

    const val O_DIRECTORY = 0x010000
    const val O_NOFOLLOW = 0x020000
    const val O_CLOEXEC = 0x080000

    const val O_ASYNC = 0x002000
    const val O_DIRECT = 0x004000
    const val O_LARGEFILE = 0x008000
    const val O_NOATIME = 0x040000

    const val O_PATH = 0x200000
    const val O_TMPFILE = 0x410000

    const val O_NDELAY = O_NONBLOCK
}

object FileDescriptorFlags {
    const val FD_CLOEXEC = 1uL
}
