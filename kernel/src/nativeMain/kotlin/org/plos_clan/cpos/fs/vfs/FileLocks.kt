package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock

internal enum class FileLockMode { SHARED, EXCLUSIVE }

internal enum class FileLockDomain { FLOCK, RECORD }

internal data class FileLockRange(val start: Long, val end: Long) {
    fun overlaps(other: FileLockRange): Boolean = start <= other.end && other.start <= end

    companion object {
        val ALL = FileLockRange(0, Long.MAX_VALUE)
    }
}

internal data class FileLock(
    val mode: FileLockMode,
    val range: FileLockRange,
    val processId: Int = -1,
)

internal class FileLocks {
    private class Entry {
        val owners = mutableMapOf<Any, List<FileLock>>()
        val waiters = IoWaitQueue()
        var pending = 0

        fun conflict(owner: Any, requested: FileLock): FileLock? = owners.asSequence()
            .filter { it.key != owner }
            .flatMap { it.value }
            .firstOrNull {
                val exclusive = requested.mode == FileLockMode.EXCLUSIVE || it.mode == FileLockMode.EXCLUSIVE
                exclusive && it.range.overlaps(requested.range)
            }

        fun replace(owner: Any, range: FileLockRange, mode: FileLockMode?) {
            val retained = mutableListOf<FileLock>()
            for (held in owners[owner].orEmpty()) {
                if (!held.range.overlaps(range)) {
                    retained += held
                    continue
                }
                if (held.range.start < range.start) {
                    val left = FileLockRange(held.range.start, range.start - 1)
                    retained += held.copy(range = left)
                }
                if (held.range.end > range.end) {
                    val right = FileLockRange(range.end + 1, held.range.end)
                    retained += held.copy(range = right)
                }
            }
            if (mode != null) retained += FileLock(mode, range, owner as? Int ?: -1)
            retained.sortBy { it.range.start }
            val merged = mutableListOf<FileLock>()
            for (held in retained) {
                val previous = merged.lastOrNull()
                if (previous == null || previous.mode != held.mode ||
                    previous.range.end < held.range.start - 1
                ) {
                    merged += held
                    continue
                }
                val combined = FileLockRange(previous.range.start, maxOf(previous.range.end, held.range.end))
                merged[merged.lastIndex] = held.copy(range = combined)
            }
            if (merged.isEmpty()) owners.remove(owner) else owners[owner] = merged
        }
    }

    private val lock = IrqSpinLock()
    private val tables = Array(FileLockDomain.entries.size) { mutableMapOf<InodeId, Entry>() }

    fun query(
        file: OpenFileDescription,
        requested: FileLock,
        processId: Int? = null,
    ): FileLock? = lock.withLock {
        val owner = processId ?: file
        tables[FileLockDomain.RECORD.ordinal][file.inode.id]?.conflict(owner, requested)
    }

    fun acquire(
        file: OpenFileDescription,
        mode: FileLockMode?,
        nonBlocking: Boolean,
        domain: FileLockDomain = FileLockDomain.FLOCK,
        range: FileLockRange = FileLockRange.ALL,
        processId: Int? = null,
    ): VfsResult<Unit> {
        val owner = processId ?: file
        val id = file.inode.id
        val table = tables[domain.ordinal]
        val requested = mode?.let { FileLock(it, range) }
        if (domain == FileLockDomain.FLOCK) lock.withLock {
            val entry = table[id] ?: return@withLock
            if (entry.owners[owner]?.singleOrNull() == requested) return VfsResult.Ok(Unit)
            if (entry.owners.remove(owner) != null) entry.waiters.wakeAll()
        }
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val entry = lock.withLock {
                val entry = table.getOrPut(id, ::Entry)
                if (requested == null || entry.conflict(owner, requested) == null) {
                    entry.replace(owner, range, mode)
                    entry.waiters.wakeAll()
                    if (entry.owners.isEmpty() && entry.pending == 0) table.remove(id)
                    return VfsResult.Ok(Unit)
                }
                if (nonBlocking) return VfsResult.Err(VfsError.WOULD_BLOCK)
                val thread = ProcessManager.currentThread()
                    ?: return VfsResult.Err(VfsError.INTERRUPTED)
                waiter = entry.waiters.add(thread)
                entry.pending++
                entry
            }
            val completed = entry.waiters.await(lock, checkNotNull(waiter))
            lock.withLock {
                entry.pending--
                if (entry.owners.isEmpty() && entry.pending == 0) table.remove(id)
            }
            if (!completed) return VfsResult.Err(VfsError.INTERRUPTED)
        }
    }

    fun release(file: OpenFileDescription, processId: Int? = null) = lock.withLock {
        val owner = processId ?: file
        val id = file.inode.id
        for (table in tables) {
            val entry = table[id] ?: continue
            if (entry.owners.remove(owner) != null) entry.waiters.wakeAll()
            if (entry.owners.isEmpty() && entry.pending == 0) table.remove(id)
        }
    }
}
