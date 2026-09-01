@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt

internal enum class EpollControlOperation {
    ADD,
    DELETE,
    MODIFY,
}

internal data class EpollEvent(
    val events: UInt,
    val data: ULong,
)

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
    private data class RegistrationKey(
        val descriptor: Int,
        val file: OpenFileDescription,
    )

    private class Registration(
        val key: RegistrationKey,
        var event: EpollEvent,
    ) {
        var queued = false
        var edgeArmed = true
        var disabled = false
        var readyEvents = 0u
        var observedEvents = 0u
        var observedVersion = key.file.backend.readinessVersion
    }

    private companion object {
        val topologyLock = IrqSpinLock()
    }

    private val lock = IrqSpinLock()
    private val registrations = ArrayList<Registration>()
    private val indices = HashMap<RegistrationKey, Int>()
    private val ready = ArrayDeque<Registration>()
    private val version = AtomicInt(0)

    override val seekable: Boolean
        get() = false

    override val readinessVersion: Int
        get() = version.load()

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long =
        lock.withLock {
            refresh(caller)
            if (ready.isNotEmpty()) {
                (events and PollEvents.NORMAL_INPUT).toLong()
            } else {
                0L
            }
        }

    fun control(
        descriptor: Int,
        file: OpenFileDescription,
        operation: EpollControlOperation,
        event: EpollEvent?,
    ): VfsResult<Unit> {
        val key = RegistrationKey(descriptor, file)
        if (operation != EpollControlOperation.ADD) {
            return lock.withLock { update(key, operation, event) }
        }

        val target = file.backend as? Epoll
        return try {
            topologyLock.withLock {
                if (target === this) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                if (target != null && target.reaches(this)) {
                    return@withLock VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
                }
                lock.withLock { add(key, checkNotNull(event)) }
            }
        } catch (_: OutOfMemoryError) {
            VfsResult.Err(VfsError.NO_MEMORY)
        }
    }

    fun collect(
        caller: VfsOperationContext,
        maximum: Int,
        events: MutableList<EpollEvent>,
    ) = lock.withLock {
        events.clear()
        var refreshed = false
        while (events.size < maximum) {
            val registration = ready.removeFirstOrNull()
            if (registration == null) {
                if (events.isNotEmpty() || refreshed) break
                refresh(caller)
                refreshed = true
                continue
            }
            if (!registration.queued) continue

            registration.queued = false
            version.fetchAndAdd(1)
            val result = registration.key.file.poll(
                caller,
                ((registration.event.events and EpollEvents.IO_EVENTS) or
                    EpollEvents.ALWAYS_REPORTED).toInt(),
            )
            if (result < 0) {
                indices[registration.key]?.let(::removeAt)
                continue
            }
            val current = result.toUInt() and
                ((registration.event.events and EpollEvents.IO_EVENTS) or
                    EpollEvents.ALWAYS_REPORTED)
            registration.observedVersion = registration.key.file.backend.readinessVersion
            registration.observedEvents = current
            if (current == 0u) {
                registration.readyEvents = 0u
                registration.edgeArmed = true
                continue
            }

            val returned = if (registration.event.events and
                EpollEvents.EDGE_TRIGGERED != 0u
            ) registration.readyEvents or current else current
            events += EpollEvent(returned, registration.event.data)
            registration.readyEvents = 0u
            if (registration.event.events and EpollEvents.ONE_SHOT != 0u) {
                registration.disabled = true
            }
        }
    }

    override fun release() = lock.withLock {
        registrations.clear()
        indices.clear()
        ready.clear()
    }

    private fun add(key: RegistrationKey, event: EpollEvent): VfsResult<Unit> {
        if (indices.containsKey(key)) return VfsResult.Err(VfsError.ALREADY_EXISTS)
        return try {
            indices[key] = registrations.size
            registrations += Registration(key, event)
            VfsResult.Ok(Unit)
        } catch (_: OutOfMemoryError) {
            indices.remove(key)
            VfsResult.Err(VfsError.NO_MEMORY)
        }
    }

    private fun update(
        key: RegistrationKey,
        operation: EpollControlOperation,
        event: EpollEvent?,
    ): VfsResult<Unit> {
        val index = indices[key] ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val registration = registrations[index]
        if (operation == EpollControlOperation.DELETE) {
            removeAt(index)
            return VfsResult.Ok(Unit)
        }
        val updatedEvent = checkNotNull(event)
        if (registration.event.events and EpollEvents.EXCLUSIVE != 0u ||
            updatedEvent.events and EpollEvents.EXCLUSIVE != 0u
        ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)

        registration.event = updatedEvent
        registration.disabled = false
        registration.edgeArmed = true
        registration.readyEvents = 0u
        registration.observedEvents = 0u
        registration.observedVersion = registration.key.file.backend.readinessVersion
        if (registration.queued) {
            registration.queued = false
            ready.remove(registration)
            version.fetchAndAdd(1)
        }
        return VfsResult.Ok(Unit)
    }

    private fun refresh(caller: VfsOperationContext) {
        var index = 0
        while (index < registrations.size) {
            val registration = registrations[index]
            val result = registration.key.file.poll(
                caller,
                ((registration.event.events and EpollEvents.IO_EVENTS) or
                    EpollEvents.ALWAYS_REPORTED).toInt(),
            )
            if (result < 0) {
                removeAt(index)
                continue
            }

            val current = result.toUInt() and
                ((registration.event.events and EpollEvents.IO_EVENTS) or
                    EpollEvents.ALWAYS_REPORTED)
            val targetVersion = registration.key.file.backend.readinessVersion
            val edgeTriggered = registration.event.events and
                EpollEvents.EDGE_TRIGGERED != 0u
            if (edgeTriggered &&
                (targetVersion != registration.observedVersion ||
                    current and registration.observedEvents.inv() != 0u)
            ) registration.edgeArmed = true
            registration.observedVersion = targetVersion
            registration.observedEvents = current
            if (current == 0u) {
                if (registration.queued) {
                    registration.queued = false
                    registration.readyEvents = 0u
                    ready.remove(registration)
                    version.fetchAndAdd(1)
                }
                if (edgeTriggered) {
                    registration.edgeArmed = true
                }
            } else if (!registration.disabled) {
                if (registration.queued) {
                    registration.readyEvents = if (edgeTriggered) {
                        registration.readyEvents or current
                    } else {
                        current
                    }
                } else if (!edgeTriggered || registration.edgeArmed) {
                    registration.readyEvents = current
                    registration.queued = true
                    registration.edgeArmed = false
                    ready.addLast(registration)
                    version.fetchAndAdd(1)
                }
            }
            index++
        }
    }

    private fun removeAt(index: Int) {
        val removed = registrations[index]
        indices.remove(removed.key)
        if (removed.queued) {
            removed.queued = false
            ready.remove(removed)
            version.fetchAndAdd(1)
        }

        val lastIndex = registrations.lastIndex
        if (index != lastIndex) {
            val replacement = registrations[lastIndex]
            registrations[index] = replacement
            indices[replacement.key] = index
        }
        registrations.removeAt(lastIndex)
    }

    private fun reaches(target: Epoll): Boolean {
        val pending = ArrayDeque<Epoll>()
        val visited = HashSet<Epoll>()
        pending.addLast(this)
        while (true) {
            val current = pending.removeFirstOrNull() ?: return false
            if (current === target) return true
            if (!visited.add(current)) continue
            current.lock.withLock {
                current.registrations.forEach { registration ->
                    (registration.key.file.backend as? Epoll)?.let(pending::addLast)
                }
            }
        }
    }
}
