@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import kotlin.concurrent.atomics.AtomicInt

internal enum class FileSystemEvent(val reportsDirectory: Boolean = true) {
    ACCESSED,
    MODIFIED,
    ATTRIBUTES_CHANGED,
    CLOSED_WRITE,
    CLOSED_READ,
    OPENED,
    ENTRY_MOVED_FROM,
    ENTRY_MOVED_TO,
    ENTRY_CREATED,
    ENTRY_DELETED,
    MOVED(reportsDirectory = false),
}

internal data class FileSystemNotification(
    val event: FileSystemEvent,
    val name: VfsName?,
    val cookie: UInt,
    val directory: Boolean,
    val unlinked: Boolean,
)

internal enum class InodeObserverRemoval {
    DELETED,
    UNMOUNTED,
}

internal interface InodeObserver {
    fun notify(event: FileSystemNotification)

    fun removed(reason: InodeObserverRemoval)
}

internal object FileSystemEventObservers {
    private val count = AtomicInt(0)

    val active: Boolean
        get() = count.load() != 0

    fun added() {
        check(count.fetchAndAdd(1) >= 0)
    }

    fun removed(amount: Int = 1) {
        check(amount > 0 && count.fetchAndAdd(-amount) >= amount)
    }
}
