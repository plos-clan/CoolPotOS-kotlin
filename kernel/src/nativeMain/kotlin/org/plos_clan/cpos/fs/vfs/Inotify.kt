@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt

internal object InotifyMask {
    const val ACCESS = 0x0000_0001u
    const val MODIFY = 0x0000_0002u
    const val ATTRIB = 0x0000_0004u
    const val CLOSE_WRITE = 0x0000_0008u
    const val CLOSE_NOWRITE = 0x0000_0010u
    const val OPEN = 0x0000_0020u
    const val MOVED_FROM = 0x0000_0040u
    const val MOVED_TO = 0x0000_0080u
    const val CREATE = 0x0000_0100u
    const val DELETE = 0x0000_0200u
    const val DELETE_SELF = 0x0000_0400u
    const val MOVE_SELF = 0x0000_0800u
    const val UNMOUNT = 0x0000_2000u
    const val QUEUE_OVERFLOW = 0x0000_4000u
    const val IGNORED = 0x0000_8000u
    const val ONLY_DIRECTORY = 0x0100_0000u
    const val DONT_FOLLOW = 0x0200_0000u
    const val EXCLUDE_UNLINKED = 0x0400_0000u
    const val MASK_CREATE = 0x1000_0000u
    const val MASK_ADD = 0x2000_0000u
    const val IS_DIRECTORY = 0x4000_0000u
    const val ONE_SHOT = 0x8000_0000u

    const val ALL_EVENTS = 0x0000_0fffu
    const val PERSISTENT = 0x8400_0fffu
    const val SUPPORTED = 0xf700_efffu
}

internal value class InotifyWatchRequest private constructor(private val bits: UInt) {
    val onlyDirectory: Boolean
        get() = bits and InotifyMask.ONLY_DIRECTORY != 0u

    val followFinalSymlink: Boolean
        get() = bits and InotifyMask.DONT_FOLLOW == 0u

    val createOnly: Boolean
        get() = bits and InotifyMask.MASK_CREATE != 0u

    val add: Boolean
        get() = bits and InotifyMask.MASK_ADD != 0u

    val watchMask: UInt
        get() = bits and InotifyMask.PERSISTENT

    companion object {
        fun from(mask: UInt): InotifyWatchRequest? {
            if (mask == 0u || mask and InotifyMask.SUPPORTED.inv() != 0u ||
                mask and InotifyMask.MASK_CREATE != 0u && mask and InotifyMask.MASK_ADD != 0u
            ) return null
            return InotifyWatchRequest(mask)
        }
    }
}

internal class Inotify(
    private val maximumQueuedEvents: Int = 16_384,
) : AnonymousFileBackend(InodeType.INOTIFY, "inotify", AccessMode.READ),
    ModeAwareOpenFileBackend {
    private class Watch(
        val owner: Inotify,
        val descriptor: Int,
        val inode: Inode,
        var mask: UInt,
    ) : InodeObserver {
        override fun notify(event: FileSystemNotification) = owner.notify(this, event)

        override fun removed(reason: InodeObserverRemoval) = owner.removed(this, reason)
    }

    private data class Event(
        val descriptor: Int,
        val mask: UInt,
        val cookie: UInt = 0u,
        val name: VfsName? = null,
    ) {
        val nameBytes = name?.let {
            (it.size + HEADER_SIZE) and (HEADER_SIZE - 1).inv()
        } ?: 0
        val size = HEADER_SIZE + nameBytes

        fun writeTo(destination: ByteArray, offset: Int) {
            LittleEndianBuffer(destination).apply {
                writeU32(offset, descriptor.toUInt())
                writeU32(offset + 4, mask)
                writeU32(offset + 8, cookie)
                writeU32(offset + 12, nameBytes.toUInt())
            }
            name?.copyInto(destination, offset + HEADER_SIZE)
        }
    }

    private data class ReadBatch(val events: Array<Event>, val size: Int)

    init {
        require(maximumQueuedEvents > 0)
    }

    private val lock = IrqSpinLock()
    private val readLock = KernelMutex()
    private val watchesByDescriptor = mutableMapOf<Int, Watch>()
    private val watchesByInode = mutableMapOf<Inode, Watch>()
    private val events = ArrayDeque<Event>()
    private val readWaiters = IoWaitQueue()
    private val version = AtomicInt(0)
    private var nextWatchDescriptor = 1
    private var queuedBytes = 0
    private var overflowQueued = false

    override val readinessVersion: Int
        get() = version.load()

    override val seekable: Boolean
        get() = false

    fun addWatch(inode: Inode, request: InotifyWatchRequest): VfsResult<Int> = lock.withLock {
        val current = watchesByInode[inode]
        if (current != null) {
            if (request.createOnly) return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
            if (request.add) {
                current.mask = current.mask or request.watchMask
            } else {
                current.mask = request.watchMask
            }
            return@withLock VfsResult.Ok(current.descriptor)
        }

        val descriptor = allocateWatchDescriptor()
            ?: return@withLock VfsResult.Err(VfsError.NO_SPACE)
        val watch = Watch(
            this,
            descriptor,
            inode,
            request.watchMask,
        )
        try {
            watchesByDescriptor[descriptor] = watch
            watchesByInode[inode] = watch
            if (!inode.observe(watch)) {
                watchesByDescriptor.remove(descriptor)
                watchesByInode.remove(inode)
                return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            }
        } catch (_: OutOfMemoryError) {
            watchesByDescriptor.remove(descriptor)
            watchesByInode.remove(inode)
            inode.stopObserving(watch)
            return@withLock VfsResult.Err(VfsError.NO_MEMORY)
        }
        VfsResult.Ok(descriptor)
    }

    fun removeWatch(descriptor: Int): VfsResult<Unit> {
        val watch = lock.withLock {
            val current = watchesByDescriptor.remove(descriptor)
                ?: return@withLock null
            watchesByInode.remove(current.inode)
            enqueue(Event(descriptor, InotifyMask.IGNORED))
            current
        } ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        watch.inode.stopObserving(watch)
        return VfsResult.Ok(Unit)
    }

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult {
        val thread = ProcessManager.currentThread()
            ?: return IoResult.failure(VfsError.INTERRUPTED)
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = readLock.withLock {
                var immediate: IoResult? = null
                val batch = lock.withLock {
                    val first = events.firstOrNull()
                    if (first == null) {
                        if (mode == IoMode.NON_BLOCKING) {
                            immediate = IoResult.failure(VfsError.WOULD_BLOCK)
                        } else {
                            waiter = readWaiters.add(thread)
                        }
                        return@withLock null
                    }
                    if (first.size > count) {
                        immediate = IoResult.failure(VfsError.INVALID_ARGUMENT)
                        return@withLock null
                    }

                    var eventCount = 0
                    var byteCount = 0
                    for (event in events) {
                        if (event.size > count - byteCount) break
                        byteCount += event.size
                        eventCount++
                    }
                    try {
                        ReadBatch(Array(eventCount) { events[it] }, byteCount)
                    } catch (_: OutOfMemoryError) {
                        immediate = IoResult.failure(VfsError.NO_MEMORY)
                        null
                    }
                }
                immediate ?: batch?.let { transfer(it, destination, destinationOffset) }
            }
            if (result != null) return result
            if (!readWaiters.await(lock, checkNotNull(waiter))) {
                return IoResult.failure(VfsError.INTERRUPTED)
            }
        }
    }

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = IoResult.failure(VfsError.BAD_DESCRIPTOR)

    override fun ioctl(
        caller: VfsOperationContext,
        inode: Inode,
        command: Int,
        args: UserMemory,
    ): Long {
        if (command != FIONREAD) return -VfsError.NOT_TTY.errno.toLong()
        val bytes = ByteArray(UInt.SIZE_BYTES)
        LittleEndianBuffer(bytes).writeU32(0, lock.withLock { queuedBytes }.toUInt())
        return if (args.copyToUser(bytes)) 0L else -VfsError.FAULT.errno.toLong()
    }

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long = lock.withLock {
        if (this.events.isEmpty()) 0L else (events and PollEvents.NORMAL_INPUT).toLong()
    }

    override fun release() {
        while (true) {
            val watch = lock.withLock {
                val current = watchesByDescriptor.values.firstOrNull()
                    ?: return@withLock null
                watchesByDescriptor.remove(current.descriptor)
                watchesByInode.remove(current.inode)
                current
            } ?: break
            watch.inode.stopObserving(watch)
        }
        lock.withLock {
            events.clear()
            queuedBytes = 0
            overflowQueued = false
        }
    }

    private fun notify(watch: Watch, notification: FileSystemNotification) {
        var removed = false
        lock.withLock {
            if (watchesByDescriptor[watch.descriptor] !== watch) return@withLock
            if (watch.mask and InotifyMask.EXCLUDE_UNLINKED != 0u &&
                notification.name != null && notification.unlinked
            ) {
                return@withLock
            }
            val eventMask = notification.event.mask
            if (watch.mask and eventMask == 0u) return@withLock
            val type = if (notification.directory && notification.event.reportsDirectory) {
                InotifyMask.IS_DIRECTORY
            } else {
                0u
            }
            enqueue(Event(watch.descriptor, eventMask or type, notification.cookie, notification.name))
            if (watch.mask and InotifyMask.ONE_SHOT != 0u) {
                watchesByDescriptor.remove(watch.descriptor)
                watchesByInode.remove(watch.inode)
                enqueue(Event(watch.descriptor, InotifyMask.IGNORED))
                removed = true
            }
        }
        if (removed) watch.inode.stopObserving(watch)
    }

    private fun removed(watch: Watch, reason: InodeObserverRemoval) = lock.withLock {
        if (watchesByDescriptor.remove(watch.descriptor) !== watch) return@withLock
        watchesByInode.remove(watch.inode)
        when (reason) {
            InodeObserverRemoval.DELETED -> if (watch.mask and InotifyMask.DELETE_SELF != 0u) {
                enqueue(Event(watch.descriptor, InotifyMask.DELETE_SELF))
            }
            InodeObserverRemoval.UNMOUNTED -> enqueue(
                Event(watch.descriptor, InotifyMask.UNMOUNT),
            )
        }
        enqueue(Event(watch.descriptor, InotifyMask.IGNORED))
    }

    private fun transfer(
        batch: ReadBatch,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
    ): IoResult {
        val output = try {
            ByteArray(batch.size)
        } catch (_: OutOfMemoryError) {
            return IoResult.failure(VfsError.NO_MEMORY)
        }
        var offset = 0
        batch.events.forEach { event ->
            event.writeTo(output, offset)
            offset += event.size
        }
        if (destination.copyFrom(destinationOffset, output, 0, batch.size) != batch.size) {
            return IoResult.failure(VfsError.FAULT)
        }
        lock.withLock {
            batch.events.forEach { expected ->
                val event = events.removeFirst()
                check(event === expected)
                queuedBytes -= event.size
                if (event.mask == InotifyMask.QUEUE_OVERFLOW) overflowQueued = false
            }
            version.fetchAndAdd(1)
        }
        return IoResult.success(batch.size)
    }

    private fun enqueue(event: Event) {
        if (events.lastOrNull() == event) return
        if (events.size >= maximumQueuedEvents) {
            if (!overflowQueued) {
                events.addLast(OVERFLOW_EVENT)
                queuedBytes += OVERFLOW_EVENT.size
                overflowQueued = true
                version.fetchAndAdd(1)
                readWaiters.wakeOne()
            }
            return
        }
        events.addLast(event)
        queuedBytes += event.size
        version.fetchAndAdd(1)
        readWaiters.wakeOne()
    }

    private fun allocateWatchDescriptor(): Int? {
        val start = nextWatchDescriptor
        do {
            val candidate = nextWatchDescriptor
            nextWatchDescriptor = if (candidate == Int.MAX_VALUE) 1 else candidate + 1
            if (!watchesByDescriptor.containsKey(candidate)) return candidate
        } while (nextWatchDescriptor != start)
        return null
    }

    private val FileSystemEvent.mask: UInt
        get() = when (this) {
            FileSystemEvent.ACCESSED -> InotifyMask.ACCESS
            FileSystemEvent.MODIFIED -> InotifyMask.MODIFY
            FileSystemEvent.ATTRIBUTES_CHANGED -> InotifyMask.ATTRIB
            FileSystemEvent.CLOSED_WRITE -> InotifyMask.CLOSE_WRITE
            FileSystemEvent.CLOSED_READ -> InotifyMask.CLOSE_NOWRITE
            FileSystemEvent.OPENED -> InotifyMask.OPEN
            FileSystemEvent.ENTRY_MOVED_FROM -> InotifyMask.MOVED_FROM
            FileSystemEvent.ENTRY_MOVED_TO -> InotifyMask.MOVED_TO
            FileSystemEvent.ENTRY_CREATED -> InotifyMask.CREATE
            FileSystemEvent.ENTRY_DELETED -> InotifyMask.DELETE
            FileSystemEvent.MOVED -> InotifyMask.MOVE_SELF
        }

    private companion object {
        const val HEADER_SIZE = 16
        const val FIONREAD = 0x541b
        val OVERFLOW_EVENT = Event(-1, InotifyMask.QUEUE_OVERFLOW)
    }
}
