package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

internal class DrmEvents {
    private val lock = IrqSpinLock()
    private val waiters = IoWaitQueue()
    private val queue = arrayOfNulls<Flip>(4096 / SIZE)
    private var head = 0
    private var count = 0

    inner class Flip(cookie: ULong, crtc: UInt) {
        val bytes = ByteArray(SIZE)

        init {
            val data = LittleEndianBuffer(bytes)
            data.writeU32(0, 2u)
            data.writeU32(4, SIZE.toUInt())
            data.writeU64(8, cookie)
            data.writeU32(28, crtc)
        }

        fun complete(nanoseconds: ULong, sequence: UInt) = lock.withLock {
            check(count < queue.size)
            val data = LittleEndianBuffer(bytes)
            data.writeU32(16, (nanoseconds / 1_000_000_000uL).toUInt())
            data.writeU32(20, (nanoseconds / 1_000uL % 1_000_000uL).toUInt())
            data.writeU32(24, sequence)
            queue[(head + count) % queue.size] = this
            count++
            waiters.wakeAll()
        }
    }

    fun prepare(cookie: ULong, crtc: UInt): Flip? = lock.withLock {
        if (count == queue.size) null else Flip(cookie, crtc)
    }

    fun subscribe(subscription: PollSubscription) = subscription.watch(waiters.events, PollEvents.NORMAL_INPUT)

    fun poll(events: Int): Long = lock.withLock {
        if (count == 0) 0 else (events and PollEvents.NORMAL_INPUT).toLong()
    }

    fun read(buffer: PreparedBufferDestination, offset: Int, size: ULong): Long = lock.withLock {
        if (count == 0) return -Errno.EAGAIN.toLong()
        if (size < SIZE.toULong()) return -Errno.EINVAL.toLong()
        val available = minOf(count.toULong(), size / SIZE.toUInt()).toInt()
        var transferred = 0
        repeat(available) {
            val bytes = checkNotNull(queue[head]).bytes
            val copied = buffer.copyFrom(offset + transferred, bytes, 0, SIZE)
            if (copied != SIZE) {
                return if (transferred == 0) -Errno.EFAULT.toLong() else transferred.toLong()
            }
            queue[head] = null
            head = (head + 1) % queue.size
            count--
            transferred += SIZE
        }
        transferred.toLong()
    }

    fun await(): Boolean {
        val thread = ProcessManager.currentThread() ?: return false
        val waiter = lock.withLock {
            if (count != 0) return true
            waiters.add(thread)
        }
        return waiters.await(lock, waiter)
    }

    private companion object {
        const val SIZE = 32
    }
}
