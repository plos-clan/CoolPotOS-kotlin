package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents

private class PipeInode(
    private val state: PipeState,
) : InodeBackend {
    override val type: InodeType
        get() = InodeType.PIPE

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        when (options.access) {
            AccessMode.READ,
            AccessMode.WRITE,
            -> VfsResult.Ok(PipeEndpoint(state, options.access))
            else -> VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
}

internal class FifoBackend : MutableInodeBackend {
    override val type: InodeType = InodeType.PIPE
    private val state = PipeState(readers = 0, writers = 0)

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        state.open(options.access, options.nonBlocking)
}

internal data object SocketNodeBackend : MutableInodeBackend {
    override val type: InodeType = InodeType.SOCKET

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
}

private class PipeEndpoint(
    private val state: PipeState,
    private val access: AccessMode,
) : WaitableOpenFileBackend {
    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = if (!access.canRead) {
        IoResult.failure(VfsError.BAD_DESCRIPTOR)
    } else {
        state.read(destination, destinationOffset, count)
    }

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = if (!access.canWrite) {
        IoResult.failure(VfsError.BAD_DESCRIPTOR)
    } else {
        state.write(source, sourceOffset, count, mode)
    }

    override fun await(event: IoEvent, count: Int) {
        check(if (event == IoEvent.WRITABLE) access.canWrite else access.canRead)
        state.await(event, count)
    }

    override fun poll(inode: Inode, events: Int): Long = state.poll(events, access)

    override fun release() = state.close(access)
}

private class PipeWaitQueue {
    class Waiter {
        var minimumBytes = 0
        lateinit var thread: Thread
        var ready = false

        fun arm(minimumBytes: Int, thread: Thread) {
            this.minimumBytes = minimumBytes
            this.thread = thread
            ready = false
        }
    }

    private val waiters = ArrayDeque<Waiter>()
    private val recycled = ArrayDeque<Waiter>()

    fun acquire(minimumBytes: Int, thread: Thread): Waiter =
        (recycled.removeFirstOrNull() ?: Waiter()).also { waiter ->
            waiter.arm(minimumBytes, thread)
            waiters.addLast(waiter)
        }

    fun release(waiter: Waiter) {
        check(waiter.ready)
        recycled.addLast(waiter)
    }

    fun notifyReady(availableBytes: Int) {
        val waiter = waiters.firstOrNull() ?: return
        if (availableBytes < waiter.minimumBytes) return
        waiters.removeFirst()
        waiter.ready = true
        Scheduler.wake(waiter.thread)
    }

    fun notifyAllWaiters() {
        while (waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            waiter.ready = true
            Scheduler.wake(waiter.thread)
        }
    }
}

internal class PipeBuffer(capacity: Int) {
    private val bytes = ByteArray(capacity)
    private var readOffset = 0
    private var writeOffset = 0

    val capacity: Int
        get() = bytes.size

    var size = 0
        private set

    val remaining: Int
        get() = capacity - size

    init {
        require(capacity > 0)
    }

    fun read(destination: PreparedBufferDestination, offset: Int, count: Int): Int {
        val requested = minOf(count, size)
        val firstChunk = minOf(requested, capacity - readOffset)
        var transferred = destination.copyFrom(offset, bytes, readOffset, firstChunk)
        val remainingChunk = requested - firstChunk
        if (transferred == firstChunk && remainingChunk != 0) {
            transferred += destination.copyFrom(offset + firstChunk, bytes, 0, remainingChunk)
        }
        readOffset = (readOffset + transferred) % capacity
        size -= transferred
        return transferred
    }

    fun write(source: PreparedBufferSource, offset: Int, count: Int): Int {
        val requested = minOf(count, remaining)
        val firstChunk = minOf(requested, capacity - writeOffset)
        var transferred = source.copyTo(offset, bytes, writeOffset, firstChunk)
        val remainingChunk = requested - firstChunk
        if (transferred == firstChunk && remainingChunk != 0) {
            transferred += source.copyTo(offset + firstChunk, bytes, 0, remainingChunk)
        }
        writeOffset = (writeOffset + transferred) % capacity
        size += transferred
        return transferred
    }
}

private class PipeState(
    private var readers: Int,
    private var writers: Int,
) {
    private companion object {
        const val CAPACITY_PAGES = 16
        val CAPACITY_BYTES = CAPACITY_PAGES * PAGE_SIZE_BYTES.toInt()
        val ATOMIC_WRITE_BYTES = PAGE_SIZE_BYTES.toInt()
    }

    private val lock = IrqSpinLock()
    private val buffer = PipeBuffer(CAPACITY_BYTES)
    private val readWaiters = PipeWaitQueue()
    private val writeWaiters = PipeWaitQueue()
    private val readerOpenWaiters = PipeWaitQueue()
    private val writerOpenWaiters = PipeWaitQueue()

    fun open(access: AccessMode, nonBlocking: Boolean): VfsResult<OpenFileBackend> {
        val thread = ProcessManager.currentThread()
        var waiter: PipeWaitQueue.Waiter? = null
        val error = lock.withLock {
            when (access) {
                AccessMode.READ -> {
                    readers++
                    writerOpenWaiters.notifyAllWaiters()
                    if (!nonBlocking && writers == 0) {
                        waiter = readerOpenWaiters.acquire(1, checkNotNull(thread))
                    }
                }
                AccessMode.WRITE -> {
                    if (nonBlocking && readers == 0) return@withLock VfsError.NO_SUCH_DEVICE_OR_ADDRESS
                    writers++
                    readerOpenWaiters.notifyAllWaiters()
                    if (readers == 0) {
                        waiter = writerOpenWaiters.acquire(1, checkNotNull(thread))
                    }
                }
                AccessMode.READ_WRITE -> {
                    readers++
                    writers++
                    readerOpenWaiters.notifyAllWaiters()
                    writerOpenWaiters.notifyAllWaiters()
                }
                AccessMode.PATH -> return@withLock VfsError.BAD_DESCRIPTOR
            }
            null
        }
        if (error != null) return VfsResult.Err(error)
        val queued = waiter
        if (queued != null) {
            do {
                check(Scheduler.parkCurrent()) { "Cannot park a FIFO opener" }
            } while (lock.withLock { !queued.ready })
            lock.withLock {
                if (access == AccessMode.READ) readerOpenWaiters.release(queued)
                else writerOpenWaiters.release(queued)
            }
        }
        return VfsResult.Ok(PipeEndpoint(this, access))
    }

    fun read(destination: PreparedBufferDestination, offset: Int, count: Int): IoResult = lock.withLock {
        if (count == 0) return@withLock IoResult.success(0)
        if (buffer.size == 0) {
            return@withLock if (writers == 0) IoResult.success(0)
            else IoResult.failure(VfsError.WOULD_BLOCK)
        }
        val transferred = buffer.read(destination, offset, count)
        if (transferred == 0) return@withLock IoResult.failure(VfsError.FAULT)
        writeWaiters.notifyReady(buffer.remaining)
        IoResult.success(transferred)
    }

    fun write(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = lock.withLock {
        if (readers == 0) return@withLock IoResult.failure(VfsError.BROKEN_PIPE)
        if (count == 0) return@withLock IoResult.success(0)
        val available = buffer.remaining
        val minimumWriteSize = when {
            mode == IoMode.BLOCKING -> minOf(count, buffer.capacity)
            count <= ATOMIC_WRITE_BYTES -> count
            else -> 1
        }
        if (available < minimumWriteSize) {
            return@withLock IoResult.failure(VfsError.WOULD_BLOCK)
        }
        val transferred = buffer.write(source, offset, count)
        if (transferred == 0) return@withLock IoResult.failure(VfsError.FAULT)
        readWaiters.notifyReady(buffer.size)
        IoResult.success(transferred)
    }

    fun await(event: IoEvent, count: Int) {
        val thread = checkNotNull(ProcessManager.currentThread())
        val minimumBytes = if (event == IoEvent.READABLE) {
            1
        } else {
            minOf(count, buffer.capacity)
        }
        val queue = if (event == IoEvent.READABLE) readWaiters else writeWaiters
        var waiter: PipeWaitQueue.Waiter? = null
        lock.withLock {
            val availableBytes = when (event) {
                IoEvent.READABLE -> buffer.size
                IoEvent.WRITABLE -> buffer.remaining
            }
            val becameReady = availableBytes >= minimumBytes || when (event) {
                IoEvent.READABLE -> writers == 0
                IoEvent.WRITABLE -> readers == 0
            }
            if (!becameReady) {
                waiter = queue.acquire(minimumBytes, thread)
            }
        }
        val queued = waiter ?: return

        do {
            check(Scheduler.parkCurrent()) { "Cannot park a pipe waiter" }
        } while (lock.withLock { !queued.ready })
        lock.withLock { queue.release(queued) }
    }

    fun poll(events: Int, access: AccessMode): Long = lock.withLock {
        var available = 0
        if (access.canWrite) {
            if (readers == 0) available = PollEvents.POLLERR
            else if (buffer.remaining >= ATOMIC_WRITE_BYTES) {
                available = PollEvents.NORMAL_OUTPUT
            }
        }
        if (access.canRead && (buffer.size != 0 || writers == 0)) {
            available = available or PollEvents.NORMAL_INPUT
        }
        (available and events).toLong()
    }

    fun close(access: AccessMode) = lock.withLock {
        if (access.canWrite) {
            check(writers > 0)
            writers--
            if (writers == 0) readWaiters.notifyAllWaiters()
        }
        if (access.canRead) {
            check(readers > 0)
            readers--
            if (readers == 0) writeWaiters.notifyAllWaiters()
        }
    }
}

internal class PipeFactory {
    private val lock = IrqSpinLock()
    private var nextInode = ULong.MAX_VALUE

    fun create(context: FileSystemContext): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> {
        val path = context.root
        val superBlock = path.mount.superBlock
        val state = PipeState(readers = 1, writers = 1)
        val inode = pipeInode(superBlock, state)
        val readFile = when (val result = OpenFileDescription.open(
            path,
            inode,
            OpenOptions(access = AccessMode.READ),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return VfsResult.Err(VfsError.IO)
        }
        val writeFile = when (val result = OpenFileDescription.open(
            path,
            inode,
            OpenOptions(access = AccessMode.WRITE),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                state.close(AccessMode.WRITE)
                readFile.release()
                return VfsResult.Err(VfsError.IO)
            }
        }
        return VfsResult.Ok(readFile to writeFile)
    }

    private fun pipeInode(
        superBlock: SuperBlock,
        state: PipeState,
    ): Inode = Inode(
        id = lock.withLock { InodeId(nextInode--) },
        superBlock = superBlock,
        backend = PipeInode(state),
        metadata = InodeMetadata(FileMode(0x1A4u)),
    )
}
