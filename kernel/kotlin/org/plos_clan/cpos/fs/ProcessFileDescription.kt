package org.plos_clan.cpos.fs

import org.plos_clan.cpos.utils.IrqSpinLock

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

data class FileDescriptor(
    val file: OpenFileDescription,
    val flags: ULong,
)

class FileDescriptorTable {
    private val entries = mutableMapOf<Int, FileDescriptor>()
    private val lock = IrqSpinLock()

    fun installExact(
        fd: Int,
        file: OpenFileDescription,
        flags: ULong
    ): Boolean = lock.withLock {
        if (fd !in 0 until MAX_FILE_DESCRIPTORS || entries.containsKey(fd)) {
            return@withLock false
        }

        entries[fd] = FileDescriptor(file, flags)
        true
    }

    fun install(
        file: OpenFileDescription,
        flags: ULong,
        minimum: Int = 0,
    ): Int? = lock.withLock {
        if (minimum !in 0 until MAX_FILE_DESCRIPTORS) {
            return@withLock null
        }
        for (fd in minimum until MAX_FILE_DESCRIPTORS) {
            if (!entries.containsKey(fd)) {
                entries[fd] = FileDescriptor(file, flags)
                return@withLock fd
            }
        }
        null
    }

    fun get(fd: Int): OpenFileDescription? =
        lock.withLock { entries[fd]?.file }

    /** Returns a temporary reference which the caller must release. */
    fun acquire(fd: Int): OpenFileDescription? = lock.withLock {
        val file = entries[fd]?.file ?: return@withLock null
        if (file.retain()) file else null
    }

    fun dup2(oldFd: Int, newFd: Int): Boolean = lock.withLock {
        if (newFd !in 0 until MAX_FILE_DESCRIPTORS) {
            return@withLock false
        }
        val source = entries[oldFd] ?: return@withLock false

        if (oldFd == newFd) {
            return@withLock true
        }

        if (!source.file.retain()) {
            return@withLock false
        }

        val replaced = entries.put(
            newFd,
            FileDescriptor(source.file, source.flags),
        )
        replaced?.file?.release()
        true
    }

    fun close(fd: Int): Boolean {
        val file = lock.withLock {
            entries.remove(fd)?.file
        } ?: return false

        file.release()
        return true
    }

    private companion object {
        const val MAX_FILE_DESCRIPTORS = 1024
    }
}
