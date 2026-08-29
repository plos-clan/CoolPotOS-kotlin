@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt

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
) : WaitableOpenFileBackend, SpliceSourceOpenFileBackend, SpliceDestinationOpenFileBackend {
    private companion object {
        val pipeSpliceLock = KernelMutex()
    }

    override val readinessVersion: Int
        get() = state.readinessVersion

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

    override fun spliceTo(
        caller: VfsOperationContext,
        inode: Inode,
        destination: OpenFileDescription,
        destinationOffset: ULong?,
        count: Int,
        sourceMode: IoMode,
        destinationMode: IoMode,
    ): IoResult {
        if (!access.canRead) return IoResult.failure(VfsError.BAD_DESCRIPTOR)
        val target = destination.backend as? PipeEndpoint
        if (target?.state === state) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        return if (target == null) {
            state.spliceTo(
                caller,
                destination,
                destinationOffset,
                count,
                sourceMode,
                destinationMode,
            )
        } else {
            pipeSpliceLock.withLock {
                state.spliceTo(
                    caller,
                    destination,
                    destinationOffset,
                    count,
                    sourceMode,
                    destinationMode,
                )
            }
        }
    }

    override fun spliceFrom(
        caller: VfsOperationContext,
        inode: Inode,
        source: OpenFileDescription,
        sourceOffset: ULong?,
        count: Int,
        sourceMode: IoMode,
        destinationMode: IoMode,
    ): IoResult = if (!access.canWrite) {
        IoResult.failure(VfsError.BAD_DESCRIPTOR)
    } else {
        state.spliceFrom(
            caller,
            source,
            sourceOffset,
            count,
            sourceMode,
            destinationMode,
        )
    }

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
    private val readTransferLock = KernelMutex()
    private val writeTransferLock = KernelMutex()
    private val buffer = ByteCircularBuffer(CAPACITY_BYTES)
    private val readWaiters = IoWaitQueue()
    private val writeWaiters = IoWaitQueue()
    private val readerOpenWaiters = IoWaitQueue()
    private val writerOpenWaiters = IoWaitQueue()
    private val version = AtomicInt(0)

    val readinessVersion: Int
        get() = version.load()

    fun open(access: AccessMode, nonBlocking: Boolean): VfsResult<OpenFileBackend> {
        val thread = ProcessManager.currentThread()
        var waiter: IoWaitQueue.Waiter? = null
        var waitQueue: IoWaitQueue? = null
        var wakeReaders = false
        var wakeWriters = false
        val error = lock.withLock {
            when (access) {
                AccessMode.READ -> {
                    readers++
                    wakeWriters = true
                    if (!nonBlocking && writers == 0) {
                        waitQueue = readerOpenWaiters
                        waiter = readerOpenWaiters.add(checkNotNull(thread))
                    }
                }
                AccessMode.WRITE -> {
                    if (nonBlocking && readers == 0) return@withLock VfsError.NO_SUCH_DEVICE_OR_ADDRESS
                    writers++
                    wakeReaders = true
                    if (readers == 0) {
                        waitQueue = writerOpenWaiters
                        waiter = writerOpenWaiters.add(checkNotNull(thread))
                    }
                }
                AccessMode.READ_WRITE -> {
                    readers++
                    writers++
                    wakeReaders = true
                    wakeWriters = true
                }
                AccessMode.PATH -> return@withLock VfsError.BAD_DESCRIPTOR
            }
            version.fetchAndAdd(1)
            null
        }
        if (wakeReaders) wakeAllOutsideLock(readerOpenWaiters)
        if (wakeWriters) wakeAllOutsideLock(writerOpenWaiters)
        if (error != null) return VfsResult.Err(error)
        val queued = waiter ?: return VfsResult.Ok(PipeEndpoint(this, access))
        if (!checkNotNull(waitQueue).await(lock, queued)) {
            lock.withLock {
                if (access == AccessMode.READ) readers-- else writers--
                version.fetchAndAdd(1)
            }
            return VfsResult.Err(VfsError.INTERRUPTED)
        }
        return VfsResult.Ok(PipeEndpoint(this, access))
    }

    fun read(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
    ): IoResult = readTransferLock.withLock {
        var writerToWake: Thread? = null
        var readerToWake: Thread? = null
        val result = lock.withLock {
            if (count == 0) return@withLock IoResult.success(0)
            if (buffer.size == 0) {
                return@withLock if (writers == 0) IoResult.success(0)
                else IoResult.failure(VfsError.WOULD_BLOCK)
            }
            val transferred = buffer.read(destination, offset, count)
            if (transferred == 0) return@withLock IoResult.failure(VfsError.FAULT)
            writerToWake = writeWaiters.takeReady(buffer.remaining)
            if (buffer.size != 0) readerToWake = readWaiters.takeReady(buffer.size)
            version.fetchAndAdd(1)
            IoResult.success(transferred)
        }
        writerToWake?.let(Scheduler::wake)
        readerToWake?.let(Scheduler::wake)
        result
    }

    fun write(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult = writeTransferLock.withLock {
        var readerToWake: Thread? = null
        var brokenPipe = false
        val result = lock.withLock {
            if (count == 0) return@withLock IoResult.success(0)
            if (readers == 0) {
                brokenPipe = true
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
            readerToWake = readWaiters.takeReady(buffer.size)
            version.fetchAndAdd(1)
            IoResult.success(transferred)
        }
        if (brokenPipe) {
            ProcessManager.currentThread()?.let { thread ->
                SignalRouter.sendThread(
                    sender = thread.process,
                    target = thread,
                    info = SignalInfo.fromSender(Signal.PIPE, thread.process),
                )
            }
        }
        readerToWake?.let(Scheduler::wake)
        result
    }

    fun spliceTo(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
        destinationOffset: ULong?,
        count: Int,
        sourceMode: IoMode,
        destinationMode: IoMode,
    ): IoResult {
        while (true) {
            var endOfFile = false
            var available = 0
            val result = readTransferLock.withLock {
                val source = lock.withLock {
                    endOfFile = buffer.size == 0 && writers == 0
                    available = minOf(count, buffer.size)
                    buffer.prepareRead(0, available)
                        ?.takeIf { buffer.size != 0 }
                } ?: return@withLock null
                val written = destination.writeForSplice(
                    caller,
                    source,
                    available,
                    destinationOffset,
                    destinationMode,
                )
                if (written.isSuccess && written.bytesTransferred != 0) {
                    var writerToWake: Thread? = null
                    lock.withLock {
                        buffer.discard(written.bytesTransferred)
                        writerToWake = writeWaiters.takeReady(buffer.remaining)
                        version.fetchAndAdd(1)
                    }
                    writerToWake?.let(Scheduler::wake)
                }
                if (written.error == VfsError.NOT_SUPPORTED ||
                    written.error == VfsError.IS_DIRECTORY
                ) {
                    IoResult.failure(VfsError.INVALID_ARGUMENT)
                } else {
                    written
                }
            }
            if (result != null) return result
            if (endOfFile) return IoResult.success(0)
            if (sourceMode == IoMode.NON_BLOCKING) {
                return IoResult.failure(VfsError.WOULD_BLOCK)
            }
            if (!await(IoEvent.READABLE, 1)) {
                return IoResult.failure(VfsError.INTERRUPTED)
            }
        }
    }

    fun spliceFrom(
        caller: VfsOperationContext,
        source: OpenFileDescription,
        sourceOffset: ULong?,
        count: Int,
        sourceMode: IoMode,
        destinationMode: IoMode,
    ): IoResult {
        while (true) {
            var brokenPipe = false
            val result = writeTransferLock.withLock {
                val reservation = lock.withLock {
                    if (readers == 0) {
                        brokenPipe = true
                        null
                    } else {
                        buffer.reserveWrite(minOf(count, buffer.remaining))
                            .takeIf { it.capacity != 0 }
                    }
                } ?: return@withLock null
                val read = source.readForSplice(
                    caller,
                    reservation.destination,
                    reservation.capacity,
                    sourceOffset,
                    sourceMode,
                )
                if (read.isSuccess && read.bytesTransferred != 0) {
                    var readerToWake: Thread? = null
                    lock.withLock {
                        reservation.commit(read.bytesTransferred)
                        readerToWake = readWaiters.takeReady(buffer.size)
                        version.fetchAndAdd(1)
                    }
                    readerToWake?.let(Scheduler::wake)
                }
                if (read.error == VfsError.NOT_SUPPORTED || read.error == VfsError.IS_DIRECTORY) {
                    IoResult.failure(VfsError.INVALID_ARGUMENT)
                } else {
                    read
                }
            }
            if (result != null) return result
            if (brokenPipe) {
                ProcessManager.currentThread()?.let { thread ->
                    SignalRouter.sendThread(
                        sender = thread.process,
                        target = thread,
                        info = SignalInfo.fromSender(Signal.PIPE, thread.process),
                    )
                }
                return IoResult.failure(VfsError.BROKEN_PIPE)
            }
            if (destinationMode == IoMode.NON_BLOCKING) {
                return IoResult.failure(VfsError.WOULD_BLOCK)
            }
            if (!await(IoEvent.WRITABLE, count)) {
                return IoResult.failure(VfsError.INTERRUPTED)
            }
        }
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
        if (access.canRead && writers == 0) available = available or PollEvents.POLLHUP
        (available and (events or PollEvents.UNCONDITIONALLY_REPORTED)).toLong()
    }

    fun close(access: AccessMode) {
        var wakeReaders = false
        var wakeWriters = false
        lock.withLock {
            if (access.canWrite) {
                check(writers > 0)
                writers--
                if (writers == 0) wakeReaders = true
            }
            if (access.canRead) {
                check(readers > 0)
                readers--
                if (readers == 0) wakeWriters = true
            }
            version.fetchAndAdd(1)
        }
        if (wakeReaders) wakeAllOutsideLock(readWaiters)
        if (wakeWriters) wakeAllOutsideLock(writeWaiters)
    }

    private fun wakeAllOutsideLock(queue: IoWaitQueue) {
        while (true) {
            val thread = lock.withLock { queue.takeOne() } ?: return
            Scheduler.wake(thread)
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
