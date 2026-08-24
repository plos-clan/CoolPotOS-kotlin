package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.fs.sock.IoWaitQueue
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents

private class PipeInode(
    private val state: PipeState,
) : InodeBackend {
    override val type: InodeType
        get() = InodeType.PIPE

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
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

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
        state.open(options.access, options.nonBlocking)
}

private class PipeEndpoint(
    private val state: PipeState,
    private val access: AccessMode,
) : WaitableOpenFileBackend {
    override fun read(
        caller: VfsOperationContext,
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
        caller: VfsOperationContext,
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

    override fun await(event: IoEvent, count: Int): Boolean {
        check(if (event == IoEvent.WRITABLE) access.canWrite else access.canRead)
        return state.await(event, count)
    }

    override fun poll(
        caller: VfsOperationContext,
        inode: Inode,
        events: Int,
    ): Long = state.poll(events, access)

    override fun release() = state.close(access)
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
    private val buffer = ByteCircularBuffer(CAPACITY_BYTES)
    private val readWaiters = IoWaitQueue()
    private val writeWaiters = IoWaitQueue()
    private val readerOpenWaiters = IoWaitQueue()
    private val writerOpenWaiters = IoWaitQueue()

    fun open(access: AccessMode, nonBlocking: Boolean): VfsResult<OpenFileBackend> {
        val thread = ProcessManager.currentThread()
        var waiter: IoWaitQueue.Waiter? = null
        var waitQueue: IoWaitQueue? = null
        val error = lock.withLock {
            when (access) {
                AccessMode.READ -> {
                    readers++
                    writerOpenWaiters.wakeAll()
                    if (!nonBlocking && writers == 0) {
                        waitQueue = readerOpenWaiters
                        waiter = readerOpenWaiters.add(checkNotNull(thread))
                    }
                }
                AccessMode.WRITE -> {
                    if (nonBlocking && readers == 0) return@withLock VfsError.NO_SUCH_DEVICE_OR_ADDRESS
                    writers++
                    readerOpenWaiters.wakeAll()
                    if (readers == 0) {
                        waitQueue = writerOpenWaiters
                        waiter = writerOpenWaiters.add(checkNotNull(thread))
                    }
                }
                AccessMode.READ_WRITE -> {
                    readers++
                    writers++
                    readerOpenWaiters.wakeAll()
                    writerOpenWaiters.wakeAll()
                }
                AccessMode.PATH -> return@withLock VfsError.BAD_DESCRIPTOR
            }
            null
        }
        if (error != null) return VfsResult.Err(error)
        val queued = waiter ?: return VfsResult.Ok(PipeEndpoint(this, access))
        if (!checkNotNull(waitQueue).await(lock, queued)) {
            lock.withLock {
                if (access == AccessMode.READ) readers-- else writers--
            }
            return VfsResult.Err(VfsError.INTERRUPTED)
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
        writeWaiters.wakeReady(buffer.remaining)
        if (buffer.size != 0) readWaiters.wakeReady(buffer.size)
        IoResult.success(transferred)
    }

    fun write(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = lock.withLock {
        if (count == 0) return@withLock IoResult.success(0)
        if (readers == 0) {
            ProcessManager.currentThread()?.let { thread ->
                SignalRouter.sendThread(
                    sender = thread.process,
                    target = thread,
                    info = SignalInfo.fromSender(Signal.PIPE, thread.process),
                )
            }
            return@withLock IoResult.failure(VfsError.BROKEN_PIPE)
        }
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
        readWaiters.wakeReady(buffer.size)
        IoResult.success(transferred)
    }

    fun await(event: IoEvent, count: Int): Boolean {
        val thread = checkNotNull(ProcessManager.currentThread())
        val minimumBytes = if (event == IoEvent.READABLE) {
            1
        } else {
            minOf(count, buffer.capacity)
        }
        val queue = if (event == IoEvent.READABLE) readWaiters else writeWaiters
        var waiter: IoWaitQueue.Waiter? = null
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
                waiter = queue.add(thread, minimumBytes)
            }
        }
        val queued = waiter ?: return true

        return queue.await(lock, queued)
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
            if (writers == 0) readWaiters.wakeAll()
        }
        if (access.canRead) {
            check(readers > 0)
            readers--
            if (readers == 0) writeWaiters.wakeAll()
        }
    }
}

internal class PipeFactory(
    private val anonymousFiles: AnonymousFileFactory,
) {

    fun create(
        caller: VfsOperationContext,
        context: FileSystemContext,
    ): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> {
        val path = context.root
        val state = PipeState(readers = 1, writers = 1)
        val inode = anonymousFiles.createInode(
            context,
            PipeInode(state),
            InodeMetadata(mode = FileMode(0x1A4u), linkCount = 0u),
        )
        val readFile = when (val result = OpenFileDescription.open(
            caller,
            path,
            inode,
            OpenOptions(access = AccessMode.READ),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return VfsResult.Err(VfsError.IO)
        }
        val writeFile = when (val result = OpenFileDescription.open(
            caller,
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
}
