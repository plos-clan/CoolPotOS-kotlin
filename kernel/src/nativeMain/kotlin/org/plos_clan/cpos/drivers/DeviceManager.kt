@file:OptIn(
    ExperimentalAtomicApi::class,
    ExperimentalForeignApi::class,
)

package org.plos_clan.cpos.drivers

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class DeviceType {
    CHARACTER,
    BLOCK,
}

enum class LinuxDeviceMajor(val number: UInt) {
    MEMORY(1u),
    TTY(4u),
    TTY_AUXILIARY(5u),
    MISC(10u),
    INPUT(13u),
}

interface DeviceBackend {
    fun open(device: Device): DeviceBackend? = this

    fun close(device: Device) {}

    fun ioctl(device: Device, command: Int, args: UserMemory): Long
    fun poll(device: Device, events: Int): Long
    fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long

    fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long
}

enum class DeviceIoEvent {
    READABLE,
    WRITABLE,
}

interface PositionlessDeviceBackend : DeviceBackend {
    fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long

    fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long = read(device, buffer, bufferOffset, size)

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long = write(device, buffer, bufferOffset, size)
}

interface WaitablePositionlessDeviceBackend : PositionlessDeviceBackend {
    fun await(device: Device, event: DeviceIoEvent, count: Int): Boolean
}

interface DiscardingDeviceBackend : PositionlessDeviceBackend {
    fun discard(device: Device, size: ULong): Long

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long = discard(device, size)
}

data class DeviceRegistration(
    val name: String,
    val type: DeviceType,
    val major: UInt,
    val minor: UInt? = null,
    val backend: DeviceBackend,
    val sysfs: SysfsDevicePublication? = null,
)

class Device internal constructor(
    val name: String,
    val type: DeviceType,
    val number: DeviceNumber,
    val backend: DeviceBackend,
)

interface DeviceRegistryObserver {
    fun deviceRegistered(device: Device)
    fun deviceUnregistered(device: Device)
}

object DeviceManager {
    private data class DeviceKey(val type: DeviceType, val number: DeviceNumber)
    private data class MajorKey(val type: DeviceType, val major: UInt)

    private sealed class Publication {
        val completed = AtomicBoolean(false)

        abstract fun dispatch()

        class Registered(
            val device: Device,
            val sysfs: SysfsDevicePublication?,
            val observers: List<DeviceRegistryObserver>,
        ) : Publication() {
            override fun dispatch() {
                sysfs?.let { publication ->
                    val result = Sysfs.registerDevice(device, publication)
                    if (result is VfsResult.Err) {
                        println("sysfs: failed to publish ${device.name}: ${result.error}")
                    }
                }
                observers.forEach { it.deviceRegistered(device) }
            }
        }

        class Unregistered(
            val devices: List<Device>,
            val observers: List<DeviceRegistryObserver>,
        ) : Publication() {
            override fun dispatch() = devices.forEach { device ->
                Sysfs.unregisterDevice(device)
                observers.forEach { it.deviceUnregistered(device) }
            }
        }

        class Replay(
            val observer: DeviceRegistryObserver,
            val devices: List<Device>,
        ) : Publication() {
            override fun dispatch() = devices.forEach(observer::deviceRegistered)
        }

        class Barrier : Publication() {
            override fun dispatch() = Unit
        }
    }

    private data class PendingPublication(
        val event: Publication,
        val drainsQueue: Boolean,
    )

    private data class RegisteredDevice(
        val device: Device,
        val publication: PendingPublication,
    )

    private val devicesByName = mutableMapOf<String, Device>()
    private val devicesByNumber = mutableMapOf<DeviceKey, Device>()
    private val nextMinor = mutableMapOf<MajorKey, UInt>()
    private val observers = mutableSetOf<DeviceRegistryObserver>()
    private val publications = ArrayDeque<Publication>()
    private var publishing = false
    private val lock = IrqSpinLock()

    fun register(registration: DeviceRegistration): Device? {
        if (registration.major > DeviceNumber.MAX_MAJOR || !validDevicePath(registration.name)) {
            return null
        }
        val registered = lock.withLock {
            if (devicesByName.containsKey(registration.name)) return@withLock null

            val majorKey = MajorKey(registration.type, registration.major)
            val minor = registration.minor ?: allocateMinor(majorKey) ?: return@withLock null
            val number = DeviceNumber.create(registration.major, minor) ?: return@withLock null
            val key = DeviceKey(registration.type, number)
            if (devicesByNumber.containsKey(key)) return@withLock null

            if (registration.minor == null) nextMinor[majorKey] = minor + 1u
            val device = Device(
                name = registration.name,
                type = registration.type,
                number = number,
                backend = registration.backend,
            )
            devicesByName[device.name] = device
            devicesByNumber[key] = device
            RegisteredDevice(
                device,
                enqueueLocked(
                    Publication.Registered(device, registration.sysfs, observers.toList()),
                ),
            )
        } ?: return null
        publish(registered.publication)
        return registered.device
    }

    fun unregister(device: Device): Boolean {
        val publication = lock.withLock {
            if (devicesByName[device.name] !== device) return@withLock null
            removeLocked(device)
            enqueueLocked(Publication.Unregistered(listOf(device), observers.toList()))
        } ?: return false
        publish(publication)
        return true
    }

    fun unregisterAll(backend: DeviceBackend): Int {
        val removed = lock.withLock {
            val devices = devicesByName.values.filter { it.backend === backend }
            if (devices.isEmpty()) return@withLock null
            devices.forEach(::removeLocked)
            devices to enqueueLocked(Publication.Unregistered(devices, observers.toList()))
        } ?: return 0
        publish(removed.second)
        return removed.first.size
    }

    fun findByBackend(backend: DeviceBackend): Device? = lock.withLock {
        var match: Device? = null
        for (device in devicesByName.values) {
            if (device.backend === backend &&
                (match == null || device.number.value < match.number.value)
            ) {
                match = device
            }
        }
        match
    }

    fun find(type: DeviceType, number: DeviceNumber): Device? =
        lock.withLock { devicesByNumber[DeviceKey(type, number)] }

    fun observe(observer: DeviceRegistryObserver) {
        val publication = lock.withLock {
            if (!observers.add(observer)) return@withLock null
            enqueueLocked(Publication.Replay(observer, devicesByName.values.toList()))
        } ?: return
        publish(publication)
    }

    fun stopObserving(observer: DeviceRegistryObserver) {
        val publication = lock.withLock {
            if (!observers.remove(observer)) return@withLock null
            enqueueLocked(Publication.Barrier())
        } ?: return
        publish(publication)
    }

    private fun allocateMinor(key: MajorKey): UInt? {
        var candidate = nextMinor[key] ?: 0u
        while (candidate <= DeviceNumber.MAX_MINOR) {
            val number = DeviceNumber.create(key.major, candidate) ?: return null
            if (!devicesByNumber.containsKey(DeviceKey(key.type, number))) return candidate
            if (candidate == DeviceNumber.MAX_MINOR) return null
            candidate++
        }
        return null
    }

    private fun removeLocked(device: Device) {
        devicesByName.remove(device.name)
        devicesByNumber.remove(DeviceKey(device.type, device.number))
        val major = MajorKey(device.type, device.number.major)
        nextMinor[major] = minOf(nextMinor[major] ?: DeviceNumber.MAX_MINOR, device.number.minor)
    }

    private fun enqueueLocked(event: Publication): PendingPublication {
        publications.addLast(event)
        val drainsQueue = !publishing
        if (drainsQueue) publishing = true
        return PendingPublication(event, drainsQueue)
    }

    private fun publish(pending: PendingPublication) {
        if (pending.drainsQueue) {
            while (true) {
                val event = lock.withLock {
                    publications.removeFirstOrNull() ?: run {
                        publishing = false
                        null
                    }
                } ?: return
                try {
                    event.dispatch()
                } finally {
                    event.completed.store(true)
                }
            }
        }
        while (!pending.event.completed.load()) bridge.asm_pause()
    }

    private fun validDevicePath(path: String): Boolean {
        if (path.isEmpty() || path.first() == '/' || path.last() == '/' || '\u0000' in path) {
            return false
        }
        return path.split('/').all { component ->
            component.isNotEmpty() && component != "." && component != ".."
        }
    }
}
