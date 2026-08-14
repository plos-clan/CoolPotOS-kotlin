package org.plos_clan.cpos.drivers

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.utils.IrqSpinLock

const val DEV_NULL = 0  // 空设备
const val DEV_CHAR = 1  // 字符设备
const val DEV_BLOCK = 2 // 块设备
const val DEV_NET = 3   // 网络设备

const val DEV_TTY = 4     // TTY 设备
const val DEV_PART = 8    // 磁盘分区
const val DEV_SOUND = 116 // 声卡 / ALSA
const val DEV_INPUT = 13  // 输入设备
const val DEV_FB = 29    // 帧缓冲
const val DEV_DISK = 30     // 磁盘
const val DEV_NETIF = 31     // 网卡
const val DEV_SYSDEV = 32     // 系统设备
const val DEV_USB = 33      // USB userspace node
const val DEV_GPU = 226   // 显卡

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

class Device(
    val name: String,
    val type: Int,
    val subType: Int,
    val dev: ULong,
    val parent: ULong,
    val handle: Any,
    val backend: DeviceBackend,
) {
    fun write(buffer: PreparedBufferSource, bufferOffset: Int, offset: ULong, count: ULong): Long =
        backend.write(this, buffer, bufferOffset, offset, count)

    fun read(
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        offset: ULong,
        count: ULong,
    ): Long =
        backend.read(this, buffer, bufferOffset, offset, count)
}

object DeviceManager {
    private const val MAX_MINOR = 0xffuL

    private val devices = mutableListOf<Device>()
    private val deviceIdx = ULongArray(227)
    private val deviceLock = IrqSpinLock()

    private fun deviceMinorInUseLocked(subtype: Int, minor: ULong): Boolean =
        devices.any { device ->
            device.type != DEV_NULL &&
                device.subType == subtype &&
                (device.dev and MAX_MINOR) == minor
        }

    private fun installDeviceInternal(
        type: Int,
        subtype: Int,
        handle: Any,
        name: String,
        parent: ULong,
        fixedMinor: ULong?,
        backend: DeviceBackend,
    ): ULong = deviceLock.withLock {
        if (subtype !in deviceIdx.indices ||
            !validDevicePath(name) ||
            devices.any { device ->
                device.name == name ||
                    device.name.startsWith("$name/") ||
                    name.startsWith("${device.name}/")
            }
        ) {
            return@withLock 0uL
        }

        val minor = fixedMinor ?: deviceIdx[subtype]
        if (minor > MAX_MINOR || deviceMinorInUseLocked(subtype, minor)) {
            return@withLock 0uL
        }
        deviceIdx[subtype] = maxOf(deviceIdx[subtype], minor + 1uL)

        Device(
            name = name,
            type = type,
            subType = subtype,
            dev = (subtype.toULong() shl 8) or minor,
            parent = parent,
            handle = handle,
            backend = backend,
        ).also(devices::add).dev
    }

    fun installDevice(
        type: Int,
        subtype: Int,
        handle: Any,
        name: String,
        parent: ULong,
        backend: DeviceBackend,
    ): ULong = installDeviceInternal(type, subtype, handle, name, parent, null, backend)

    fun installDeviceMinor(
        type: Int,
        subtype: Int,
        handle: Any,
        name: String,
        parent: ULong,
        backend: DeviceBackend,
        fixedMinor: ULong,
    ): ULong = installDeviceInternal(type, subtype, handle, name, parent, fixedMinor, backend)

    fun findDevice(subType: Int, index: ULong): Device? = deviceLock.withLock {
        var current = 0uL
        devices.firstOrNull { device ->
            if (device.subType != subType) {
                false
            } else {
                current++ == index
            }
        }
    }

    fun getDevice(dev: ULong): Device? =
        deviceLock.withLock { devices.firstOrNull { it.dev == dev } }

    fun findDeviceByName(name: String): Device? =
        deviceLock.withLock { devices.firstOrNull { it.name == name } }

    fun snapshotDevices(): List<Device> =
        deviceLock.withLock { devices.toList() }

    private fun validDevicePath(path: String): Boolean {
        if (path.isEmpty() || path.first() == '/' || path.last() == '/' || '\u0000' in path) {
            return false
        }
        return path.split('/').all { component ->
            component.isNotEmpty() && component != "." && component != ".."
        }
    }
}
