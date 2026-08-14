package org.plos_clan.cpos.drivers

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock

enum class DeviceType {
    CHARACTER,
    BLOCK,
}

enum class LinuxDeviceMajor(val number: UInt) {
    MEMORY(1u),
    VIRTUAL_TERMINAL(4u),
    TTY_AUXILIARY(5u),
    INPUT(13u),
}

value class DeviceNumber private constructor(val value: ULong) {
    val major: UInt
        get() = (value shr 8 and 0xfffuL).toUInt()

    val minor: UInt
        get() = ((value and 0xffuL) or (value shr 12 and 0xfffff00uL)).toUInt()

    companion object {
        const val MAX_MAJOR = 0xfffu
        const val MAX_MINOR = 0xfffffu

        fun create(major: UInt, minor: UInt): DeviceNumber? {
            if (major > MAX_MAJOR || minor > MAX_MINOR) return null
            val encoded = (major.toULong() shl 8) or
                (minor.toULong() and 0xffuL) or
                ((minor.toULong() and 0xfffff00uL) shl 12)
            return DeviceNumber(encoded)
        }
    }
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
    fun await(device: Device, event: DeviceIoEvent, count: Int)
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

    private val devicesByName = mutableMapOf<String, Device>()
    private val devicesByNumber = mutableMapOf<DeviceKey, Device>()
    private val nextMinor = mutableMapOf<MajorKey, UInt>()
    private val observers = mutableSetOf<DeviceRegistryObserver>()
    private val lock = IrqSpinLock()

    fun register(registration: DeviceRegistration): Device? = lock.withLock {
        if (registration.major > DeviceNumber.MAX_MAJOR ||
            !validDevicePath(registration.name) ||
            devicesByName.containsKey(registration.name)
        ) {
            return@withLock null
        }

        val majorKey = MajorKey(registration.type, registration.major)
        val minor = registration.minor ?: allocateMinor(majorKey) ?: return@withLock null
        val number = DeviceNumber.create(registration.major, minor) ?: return@withLock null
        val key = DeviceKey(registration.type, number)
        if (devicesByNumber.containsKey(key)) return@withLock null

        if (registration.minor == null) {
            nextMinor[majorKey] = minor + 1u
        }
        val device = Device(
            name = registration.name,
            type = registration.type,
            number = number,
            backend = registration.backend,
        )
        devicesByName[device.name] = device
        devicesByNumber[key] = device
        observers.forEach { it.deviceRegistered(device) }
        device
    }

    fun unregister(device: Device): Boolean = lock.withLock {
        if (devicesByName[device.name] !== device) return@withLock false
        unregisterLocked(device)
        true
    }

    fun unregisterAll(backend: DeviceBackend): Int = lock.withLock {
        val removed = devicesByName.values.filter { it.backend === backend }
        removed.forEach(::unregisterLocked)
        removed.size
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

    fun observe(observer: DeviceRegistryObserver) {
        lock.withLock {
            if (observers.add(observer)) {
                devicesByName.values.forEach(observer::deviceRegistered)
            }
        }
    }

    fun stopObserving(observer: DeviceRegistryObserver) {
        lock.withLock { observers -= observer }
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

    private fun unregisterLocked(device: Device) {
        devicesByName.remove(device.name)
        devicesByNumber.remove(DeviceKey(device.type, device.number))
        val major = MajorKey(device.type, device.number.major)
        nextMinor[major] = minOf(nextMinor[major] ?: DeviceNumber.MAX_MINOR, device.number.minor)
        observers.forEach { it.deviceUnregistered(device) }
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
