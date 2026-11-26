package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.tasks.PollSource
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PollEvents

internal enum class EpollControlOperation {
    ADD,
    DELETE,
    MODIFY,
}

internal data class EpollEvent(
    val events: UInt,
    val data: ULong,
) {
    val ioEvents: UInt get() = events and EpollEvents.IO_EVENTS

    fun interface Sink {
        fun write(index: Int, events: UInt, data: ULong)
    }
}

internal object EpollEvents {
    const val EXCLUSIVE = 0x1000_0000u
    const val WAKEUP = 0x2000_0000u
    const val ONE_SHOT = 0x4000_0000u
    const val EDGE_TRIGGERED = 0x8000_0000u

    const val IO_EVENTS = 0x0000_27dfu
    const val ALWAYS_REPORTED = 0x0000_0018u
    const val SUPPORTED = 0xf000_27dfu
    const val EXCLUSIVE_SUPPORTED = 0xb000_001du
}

internal class Epoll : AnonymousFileBackend(InodeType.EPOLL, "eventpoll"),
    PositionlessOpenFileBackend {
    private data class RegistrationKey(val descriptor: Int, val file: OpenFileDescription)

    private inner class Registration(val key: RegistrationKey, var event: EpollEvent) :
        PollSubscription(event.events and EpollEvents.EXCLUSIVE != 0u) {
        override val events: Int = if (event.ioEvents == 0u) -1 else event.events.toInt()
        var queued = false
        var generation = 0uL
        private var enabled = true
        private var removed = false

        override fun changed(): Boolean = schedule()

        fun schedule(rearm: EpollEvent? = null): Boolean {
            lock.withLock {
                if (removed) return false
                if (rearm != null) {
                    event = rearm
                    enabled = true
                }
                if (!enabled && key.file.isOpen) return false
                generation++
                if (queued && rearm == null) return false
                if (!queued) ready.addLast(this)
                queued = true
            }
            changes.signal()
            return true
        }

        override fun onClosed() = lock.withLock {
            removed = true
            dequeue()
        }

        private fun dequeue() {
            if (queued) ready.remove(this)
            queued = false
        }

        fun discard(observed: ULong) = lock.withLock {
            if (!queued || generation != observed) return@withLock
            check(ready.removeFirst() === this)
            queued = false
        }

        fun sample(caller: VfsOperationContext, consume: Boolean): Long {
            val requested = event.ioEvents or EpollEvents.ALWAYS_REPORTED
            val file = key.file
            if (!file.retain()) {
                remove(this)
                return 0
            }
            return try {
                file.poll(caller, requested.toInt(), consume, this)
            } finally {
                file.release()
            }
        }

        fun deliver(caller: VfsOperationContext, index: Int, output: EpollEvent.Sink): Boolean {
            val available = sample(caller, consume = true)
            if (available < 0) remove(this)
            if (available <= 0) return false
            output.write(index, available.toUInt(), event.data)
            when {
                event.events and EpollEvents.ONE_SHOT != 0u -> lock.withLock {
                    enabled = false
                    dequeue()
                }
                event.events and EpollEvents.EDGE_TRIGGERED == 0u -> notify()
            }
            return true
        }
    }

    private companion object {
        val topologyLock = KernelMutex()
    }

    private val operations = KernelMutex()
    private val lock = IrqSpinLock()
    private val registrations = LinkedHashMap<RegistrationKey, Registration>()
    private val ready = ArrayDeque<Registration>()
    private val changes = PollSource()

    override val seekable: Boolean get() = false

    override fun subscribe(
        caller: VfsOperationContext,
        inode: Inode,
        subscription: PollSubscription,
    ) = subscription.watch(changes)

    override fun poll(
        caller: VfsOperationContext,
        inode: Inode,
        events: Int,
    ): Long = operations.withLock {
        while (true) {
            var generation = 0uL
            val registration = lock.withLock {
                val first = ready.firstOrNull() ?: return@withLock null
                generation = first.generation
                first
            } ?: return@withLock 0L
            val available = registration.sample(caller, consume = false)
            if (available > 0) return@withLock (events and PollEvents.NORMAL_INPUT).toLong()
            registration.discard(generation)
            if (available < 0) remove(registration)
        }
        0L
    }

    fun control(
        descriptor: Int,
        file: OpenFileDescription,
        operation: EpollControlOperation,
        event: EpollEvent?,
    ): VfsResult<Unit> {
        val key = RegistrationKey(descriptor, file)
        val target = file.backend
        if (operation != EpollControlOperation.ADD || target !is Epoll) {
            return update(key, operation, event)
        }
        return topologyLock.withLock {
            if (target.reaches(this)) VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
            else update(key, operation, event)
        }
    }

    private fun update(
        key: RegistrationKey,
        operation: EpollControlOperation,
        event: EpollEvent?,
    ): VfsResult<Unit> = operations.withLock {
        val previous = registrations[key]
        when (operation) {
            EpollControlOperation.ADD -> if (previous != null) {
                return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
            }
            EpollControlOperation.DELETE -> {
                remove(previous ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND))
                return@withLock VfsResult.Ok(Unit)
            }
            EpollControlOperation.MODIFY -> {
                if (previous == null) return@withLock VfsResult.Err(VfsError.NOT_FOUND)
                val combined = previous.event.events or checkNotNull(event).events
                if (combined and EpollEvents.EXCLUSIVE != 0u) {
                    return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                if (previous.event.events == event.events) {
                    previous.schedule(event)
                    return@withLock VfsResult.Ok(Unit)
                }
            }
        }
        val interest = checkNotNull(event)
        val registration = Registration(key, interest)
        registrations[key] = registration
        previous?.close()
        registration.notify()
        VfsResult.Ok(Unit)
    }

    fun collect(
        caller: VfsOperationContext,
        maximum: Int,
        subscription: PollSubscription? = null,
        output: EpollEvent.Sink,
    ): Int = operations.withLock {
        subscription?.watch(changes)
        var count = 0
        var remaining = lock.withLock { ready.size }
        while (remaining > 0 && count < maximum) {
            val registration = takeReady() ?: break
            if (registration.deliver(caller, count, output)) count++
            remaining--
        }
        count
    }

    private fun takeReady(): Registration? = lock.withLock {
        val registration = ready.removeFirstOrNull() ?: return@withLock null
        registration.queued = false
        registration
    }

    override fun release() = operations.withLock {
        lock.withLock { ready.clear() }
        registrations.values.forEach { it.close() }
        registrations.clear()
    }

    private fun remove(registration: Registration) {
        registrations.remove(registration.key)
        registration.close()
    }

    private fun reaches(target: Epoll): Boolean {
        val pending = ArrayDeque<Epoll>()
        val visited = HashSet<Epoll>()
        pending.addLast(this)
        while (true) {
            val current = pending.removeFirstOrNull() ?: return false
            if (current === target) return true
            if (!visited.add(current)) continue
            current.operations.withLock {
                val targets = current.registrations.values
                targets.mapNotNullTo(pending) { it.key.file.backend as? Epoll }
            }
        }
    }
}
