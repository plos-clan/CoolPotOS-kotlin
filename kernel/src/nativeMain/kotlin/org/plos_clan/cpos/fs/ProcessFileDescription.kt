package org.plos_clan.cpos.fs

import kotlinx.collections.immutable.persistentHashMapOf
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.Volatile

class FileDescriptorTable(private val descriptorLimit: () -> ULong) {
    internal sealed interface Entry

    private data class FileDescriptor(
        val file: OpenFileDescription,
        val flags: ULong,
    ) : Entry

    internal inner class Reservation internal constructor(
        val fd: Int,
        private var file: OpenFileDescription?,
        private val flags: ULong,
    ) : Entry, AutoCloseable {
        fun install(): Int = install(checkNotNull(file))

        fun install(file: OpenFileDescription): Int = lock.withLock {
            check(entries.slot(fd) === this) { "Descriptor reservation is no longer pending" }
            check(this.file == null || this.file === file) { "A different file was reserved" }
            entries[fd] = FileDescriptor(file, flags)
            this.file = null
            fd
        }

        override fun close() {
            val cancelled = lock.withLock {
                if (entries.slot(fd) !== this) return@withLock false
                entries[fd] = null
                true
            }
            if (cancelled) file?.release()
            file = null
        }
    }

    enum class CloseRangeAction {
        CLOSE,
        MARK_CLOSE_ON_EXEC,
    }

    private val entries = DescriptorEntries()
    private val lock = IrqSpinLock()

    internal val limit: Int
        get() = minOf(descriptorLimit(), Int.MAX_VALUE.toULong()).toInt()

    fun installExact(
        fd: Int, file: OpenFileDescription, flags: ULong
    ): Boolean = lock.withLock {
        if (fd !in 0 until limit || entries.slot(fd) != null) {
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
        val fd = entries.firstEmpty(minimum, limit) ?: return@withLock null
        entries[fd] = FileDescriptor(file, flags)
        fd
    }

    internal fun reserve(
        flags: ULong,
        file: OpenFileDescription? = null,
    ): Reservation? = lock.withLock {
        val fd = entries.firstEmpty(0, limit) ?: return@withLock null
        Reservation(fd, file, flags).also { entries[fd] = it }
    }

    fun installAll(
        files: List<OpenFileDescription>,
        flags: ULong,
        minimum: Int = 0,
    ): IntArray? = lock.withLock {
        val descriptors = entries.available(files.size, minimum, limit)
        if (descriptors.size != files.size) return@withLock null
        entries.install(descriptors, files, flags)
        descriptors
    }

    fun installAvailable(files: List<OpenFileDescription>, flags: ULong): IntArray = lock.withLock {
        val descriptors = entries.available(files.size, 0, limit)
        entries.install(descriptors, files, flags)
        descriptors
    }

    fun contains(fd: Int): Boolean = entries[fd] != null

    fun descriptorFlags(fd: Int): ULong? = entries[fd]?.flags

    fun setDescriptorFlags(
        fd: Int,
        flags: ULong,
        mask: ULong = ULong.MAX_VALUE,
    ): Boolean = lock.withLock {
        val descriptor = entries[fd] ?: return@withLock false
        val updated = descriptor.flags and mask.inv() or (flags and mask)
        entries[fd] = descriptor.copy(flags = updated)
        true
    }

    fun duplicate(fd: Int, minimum: Int, flags: ULong): VfsResult<Int> = lock.withLock {
        val source = entries[fd] ?: return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        val target = entries.firstEmpty(minimum, limit)
            ?: return@withLock VfsResult.Err(VfsError.TOO_MANY_OPEN_FILES)
        if (!source.file.retain()) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        entries[target] = FileDescriptor(source.file, flags)
        VfsResult.Ok(target)
    }

    fun acquire(fd: Int): OpenFileDescription? {
        val file = entries[fd]?.file ?: return null
        return if (file.retain()) file else null
    }

    fun acquire(fd: ULong): OpenFileDescription? {
        val file = entries[fd]?.file ?: return null
        return if (file.retain()) file else null
    }

    fun snapshotDescriptors(): IntArray = entries.occupiedIndices()

    fun duplicateTo(
        caller: VfsOperationContext,
        oldFd: Int,
        newFd: Int,
        flags: ULong = 0uL,
    ): VfsResult<Int> {
        var replaced: OpenFileDescription? = null
        val result = lock.withLock {
            val source = entries[oldFd] ?: return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (oldFd == newFd) return@withLock VfsResult.Ok(newFd)
            if (newFd !in 0 until limit) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            val target = entries.slot(newFd)
            if (target is Reservation) return@withLock VfsResult.Err(VfsError.BUSY)
            if (!source.file.retain()) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)

            replaced = (target as? FileDescriptor)?.file
            entries[newFd] = FileDescriptor(source.file, flags)
            VfsResult.Ok(newFd)
        }
        replaced?.closeDescriptor(caller)
        return result
    }

    fun close(caller: VfsOperationContext, fd: Int): VfsResult<Unit> {
        val file = lock.withLock {
            val openFile = entries[fd]?.file ?: return@withLock null
            entries[fd] = null
            openFile
        } ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)

        return file.closeDescriptor(caller)
    }

    fun closeRange(
        caller: VfsOperationContext,
        first: UInt,
        last: UInt,
        action: CloseRangeAction,
    ) {
        val descriptors = lock.withLock {
            entries.replace { fd, descriptor ->
                if (fd.toUInt() !in first..last) return@replace descriptor
                when (action) {
                    CloseRangeAction.CLOSE -> null
                    CloseRangeAction.MARK_CLOSE_ON_EXEC ->
                        if (descriptor.flags and FileDescriptorFlags.FD_CLOEXEC != 0uL) descriptor
                        else descriptor.copy(
                            flags = descriptor.flags or FileDescriptorFlags.FD_CLOEXEC,
                        )
                }
            }
        }
        descriptors.forEach { it.file.closeDescriptor(caller) }
    }

    internal fun inherit(source: FileDescriptorTable) {
        source.lock.withLock { entries.inherit(source.entries) }
    }

    fun closeOnExec(caller: VfsOperationContext) = closeMatching(caller) { descriptor ->
        descriptor.flags and FileDescriptorFlags.FD_CLOEXEC != 0uL
    }

    fun closeAll(caller: VfsOperationContext) = closeMatching(caller) { true }

    private fun closeMatching(
        caller: VfsOperationContext,
        predicate: (FileDescriptor) -> Boolean,
    ) {
        val descriptors = lock.withLock {
            entries.replace { _, descriptor -> if (predicate(descriptor)) null else descriptor }
        }
        descriptors.forEach { it.file.closeDescriptor(caller) }
    }

    private fun OpenFileDescription.closeDescriptor(
        caller: VfsOperationContext,
    ): VfsResult<Unit> {
        inode.superBlock.fileLocks.release(this, caller.processId.toInt())
        val result = flush(caller)
        release()
        return result
    }

    private class DescriptorEntries {
        @Volatile private var slots = persistentHashMapOf<Int, Entry>()
        private var firstFree = 0

        fun slot(fd: Int): Entry? = slots[fd]

        operator fun get(fd: Int): FileDescriptor? = slot(fd) as? FileDescriptor

        operator fun get(fd: ULong): FileDescriptor? =
            if (fd <= Int.MAX_VALUE.toULong()) get(fd.toInt()) else null

        operator fun set(fd: Int, entry: Entry?) {
            if (entry == null) {
                slots = slots.removing(fd)
                firstFree = minOf(firstFree, fd)
            } else {
                slots = slots.putting(fd, entry)
                if (fd == firstFree) firstFree++
            }
        }

        fun firstEmpty(minimum: Int, limit: Int): Int? {
            if (minimum < 0) return null
            var fd = maxOf(minimum, firstFree)
            while (fd < limit) {
                if (slot(fd) == null) break
                fd++
            }
            if (minimum <= firstFree) firstFree = maxOf(firstFree, fd)
            return fd.takeIf { it < limit }
        }

        fun available(count: Int, minimum: Int, limit: Int): IntArray {
            val result = IntArray(count)
            var next = minimum
            for (index in result.indices) {
                val fd = firstEmpty(next, limit) ?: return result.copyOf(index)
                result[index] = fd
                next = fd + 1
            }
            return result
        }

        fun install(indices: IntArray, files: List<OpenFileDescription>, flags: ULong) {
            val updated = slots.builder()
            indices.forEachIndexed { index, fd ->
                updated[fd] = FileDescriptor(files[index], flags)
                if (fd == firstFree) firstFree++
            }
            slots = updated.build()
        }

        fun occupiedIndices(): IntArray {
            val snapshot = slots
            val result = IntArray(snapshot.size)
            var count = 0
            for ((fd, entry) in snapshot) {
                if (entry is FileDescriptor) result[count++] = fd
            }
            result.sort(0, count)
            return if (count == result.size) result else result.copyOf(count)
        }

        fun inherit(source: DescriptorEntries) {
            val snapshot = source.slots
            val inherited = snapshot.builder()
            firstFree = source.firstFree
            for ((fd, entry) in snapshot) {
                if (entry is FileDescriptor) {
                    check(entry.file.retain())
                } else {
                    inherited.remove(fd)
                    firstFree = minOf(firstFree, fd)
                }
            }
            slots = inherited.build()
        }

        fun replace(
            transform: (Int, FileDescriptor) -> FileDescriptor?,
        ): List<FileDescriptor> {
            val updated = slots.builder()
            val removed = mutableListOf<FileDescriptor>()
            for ((fd, entry) in slots) {
                if (entry !is FileDescriptor) continue
                val replacement = transform(fd, entry)
                if (replacement === entry) continue
                if (replacement != null) {
                    updated[fd] = replacement
                    continue
                }
                updated.remove(fd)
                firstFree = minOf(firstFree, fd)
                removed.add(entry)
            }
            slots = updated.build()
            return removed
        }
    }
}
