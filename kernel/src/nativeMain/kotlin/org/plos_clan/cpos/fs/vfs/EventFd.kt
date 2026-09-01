@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt

internal class EventFd(
    initialValue: UInt,
    private val semaphore: Boolean,
) : AnonymousFileBackend(InodeType.EVENTFD, "eventfd"),
    ModeAwareOpenFileBackend,
    FixedSizeIoOpenFileBackend {
    private companion object {
        const val VALUE_BYTES = ULong.SIZE_BYTES
        const val MAX_VALUE = 0xffff_ffff_ffff_fffeuL
    }

    private val lock = IrqSpinLock()
    private val transfer = ByteArray(VALUE_BYTES)
    private val readWaiters = IoWaitQueue()
    private val writeWaiters = IoWaitQueue()
    private val version = AtomicInt(0)
    private var value = initialValue.toULong()

    override val ioSize: Int
        get() = VALUE_BYTES

    override val readinessVersion: Int
        get() = version.load()

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        mode: IoMode,
    ): IoResult {
        val thread = if (mode == IoMode.BLOCKING) {
            ProcessManager.currentThread() ?: return IoResult.failure(VfsError.INTERRUPTED)
        } else {
            null
        }
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                if (value == 0uL) {
                    if (mode == IoMode.NON_BLOCKING) {
                        return@withLock IoResult.failure(VfsError.WOULD_BLOCK)
                    }
                    waiter = readWaiters.add(checkNotNull(thread))
                    return@withLock null
                }

                val resultValue = if (semaphore) 1uL else value
                LittleEndianBuffer(transfer).writeU64(0, resultValue)
                if (destination.copyFrom(destinationOffset, transfer, 0, VALUE_BYTES) != VALUE_BYTES) {
                    return@withLock IoResult.failure(VfsError.FAULT)
                }
                value -= resultValue
                version.fetchAndAdd(1)
                writeWaiters.wakeAll()
                if (semaphore && value != 0uL) readWaiters.wakeOne()
                IoResult.success(VALUE_BYTES)
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
    ): IoResult {
        val increment = lock.withLock {
            if (source.copyTo(sourceOffset, transfer, 0, VALUE_BYTES) != VALUE_BYTES) null
            else LittleEndianBuffer(transfer).readU64(0)
        } ?: return IoResult.failure(VfsError.FAULT)
        if (increment == ULong.MAX_VALUE) return IoResult.failure(VfsError.INVALID_ARGUMENT)

        val thread = if (mode == IoMode.BLOCKING) {
            ProcessManager.currentThread() ?: return IoResult.failure(VfsError.INTERRUPTED)
        } else {
            null
        }
        while (true) {
            var waiter: IoWaitQueue.Waiter? = null
            val result = lock.withLock {
                if (increment <= MAX_VALUE - value) {
                    val wasEmpty = value == 0uL
                    value += increment
                    version.fetchAndAdd(1)
                    if (wasEmpty && value != 0uL) readWaiters.wakeOne()
                    return@withLock IoResult.success(VALUE_BYTES)
                }
                if (mode == IoMode.NON_BLOCKING) {
                    return@withLock IoResult.failure(VfsError.WOULD_BLOCK)
                }
                waiter = writeWaiters.add(checkNotNull(thread))
                null
            }
            if (result != null) return result
            if (!writeWaiters.await(lock, checkNotNull(waiter))) {
                return IoResult.failure(VfsError.INTERRUPTED)
            }
        }
    }

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long = lock.withLock {
        var available = 0
        if (value != 0uL) available = PollEvents.NORMAL_INPUT
        if (value != MAX_VALUE) available = available or PollEvents.NORMAL_OUTPUT
        (available and events).toLong()
    }
}
