@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicReference

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
    private val entries = DescriptorEntries(LIMIT)
    private val lock = IrqSpinLock()

    fun installExact(
        fd: Int,
        file: OpenFileDescription,
        flags: ULong
    ): Boolean = lock.withLock {
        if (fd !in entries.indices || entries[fd] != null) {
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
        val fd = entries.firstEmpty(minimum) ?: return@withLock null
        entries[fd] = FileDescriptor(file, flags)
        fd
    }

    fun contains(fd: Int): Boolean = entries[fd] != null

    fun descriptorFlags(fd: Int): ULong? = entries[fd]?.flags

    fun setDescriptorFlags(fd: Int, flags: ULong): Boolean = lock.withLock {
        val descriptor = entries[fd] ?: return@withLock false
        entries[fd] = descriptor.copy(flags = flags)
        true
    }

    fun duplicate(fd: Int, minimum: Int, flags: ULong): Int? = lock.withLock {
        val source = entries[fd] ?: return@withLock null
        if (!source.file.retain()) {
            return@withLock null
        }
        val target = entries.firstEmpty(minimum)
        if (target == null) {
            source.file.release()
            return@withLock null
        }
        entries[target] = FileDescriptor(source.file, flags)
        target
    }

    fun acquire(fd: Int): OpenFileDescription? {
        val file = entries[fd]?.file ?: return null
        return if (file.retain()) file else null
    }

    fun acquire(fd: ULong): OpenFileDescription? {
        val file = entries[fd]?.file ?: return null
        return if (file.retain()) file else null
    }

    fun dup2(oldFd: Int, newFd: Int): Boolean = lock.withLock {
        if (newFd !in entries.indices) {
            return@withLock false
        }
        val source = entries[oldFd] ?: return@withLock false

        if (oldFd == newFd) {
            return@withLock true
        }

        if (!source.file.retain()) {
            return@withLock false
        }

        val replaced = entries[newFd]
        entries[newFd] = FileDescriptor(source.file, 0uL)
        replaced?.file?.release()
        true
    }

    fun close(fd: Int): Boolean {
        val file = lock.withLock {
            val openFile = entries[fd]?.file ?: return@withLock null
            entries[fd] = null
            openFile
        } ?: return false

        file.release()
        return true
    }

    fun copyInto(destination: FileDescriptorTable): Boolean {
        val descriptors = lock.withLock {
            entries.indices.mapNotNull { fd ->
                val descriptor = entries[fd] ?: return@mapNotNull null
                check(descriptor.file.retain())
                fd to descriptor
            }
        }
        return destination.lock.withLock {
            if (descriptors.any { (fd, _) -> destination.entries[fd] != null }) {
                descriptors.forEach { (_, descriptor) -> descriptor.file.release() }
                false
            } else {
                descriptors.forEach { (fd, descriptor) -> destination.entries[fd] = descriptor }
                true
            }
        }
    }

    fun closeOnExec() {
        val files = lock.withLock {
            entries.indices.mapNotNull { fd ->
                val descriptor = entries[fd]
                descriptor?.takeIf {
                    it.flags and FileDescriptorFlags.FD_CLOEXEC != 0uL
                }?.file?.also { entries[fd] = null }
            }
        }
        files.forEach(OpenFileDescription::release)
    }

    private class DescriptorEntries(val size: Int) {
        val indices = 0 until size
        private val segments = Array((size + SEGMENT_SIZE - 1) / SEGMENT_SIZE) { index ->
            val remaining = size - index * SEGMENT_SIZE
            AtomicReference(arrayOfNulls<FileDescriptor>(minOf(SEGMENT_SIZE, remaining)))
        }

        operator fun get(index: Int): FileDescriptor? {
            if (index !in indices) return null
            return segments[index / SEGMENT_SIZE].load()[index % SEGMENT_SIZE]
        }

        operator fun get(index: ULong): FileDescriptor? {
            if (index >= size.toULong()) return null
            val validIndex = index.toInt()
            return segments[validIndex / SEGMENT_SIZE].load()[validIndex % SEGMENT_SIZE]
        }

        operator fun set(index: Int, descriptor: FileDescriptor?) {
            require(index in indices)
            val segment = index / SEGMENT_SIZE
            val updated = segments[segment].load().copyOf()
            updated[index % SEGMENT_SIZE] = descriptor
            segments[segment].store(updated)
        }

        fun firstEmpty(minimum: Int): Int? {
            if (minimum !in indices) return null
            var index = minimum
            while (index < size) {
                val segmentIndex = index / SEGMENT_SIZE
                val snapshot = segments[segmentIndex].load()
                for (offset in index % SEGMENT_SIZE until snapshot.size) {
                    if (snapshot[offset] == null) return segmentIndex * SEGMENT_SIZE + offset
                }
                index = (segmentIndex + 1) * SEGMENT_SIZE
            }
            return null
        }

        companion object {
            private const val SEGMENT_SIZE = 64
        }
    }

    companion object {
        const val LIMIT = 1024
    }
}
