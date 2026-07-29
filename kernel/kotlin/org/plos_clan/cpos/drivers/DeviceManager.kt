package org.plos_clan.cpos.drivers

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno
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
    fun ioctl(device: Device, args: UserMemory): ULong
    fun poll(device: Device, events: Int): ULong
    fun read(device: Device, buf: ByteArray, offset: ULong, size: ULong): ULong
    fun write(device: Device, buf: ByteArray, offset: ULong, size: ULong): ULong
}

class Device(
    val name: String, val type: Int, val subType: Int, val dev: ULong, val parent: ULong,
    val handle: Any, val backend: DeviceBackend
)

object DeviceManager {
    val devices = mutableListOf<Device>()

    val deviceIdx = ULongArray(227)

    val deviceLock = IrqSpinLock()

    fun deviceMinorInUse(subtype: Int, minor: ULong): Boolean {
        for (dev in devices) {
            if (dev.type == DEV_NULL || dev.subType != subtype) continue
            if ((dev.dev and 255u) == minor) {
                return true
            }
        }
        return false
    }

    private fun installDeviceInternal(
        type: Int,
        subtype: Int,
        handle: Any,
        name: String,
        parent: ULong,
        fixedMinor: ULong?,
        backend: DeviceBackend
    ): ULong {
        deviceLock.withLock({
            var devMinor: ULong

            if (fixedMinor != null) {
                if (deviceMinorInUse(subtype, fixedMinor)) {
                    return 0u
                }
                devMinor = fixedMinor

                if (deviceIdx[subtype] <= devMinor) {
                    deviceIdx[subtype] = devMinor + 1u
                }
            } else {
                devMinor = deviceIdx[subtype]++
            }

            val device = Device(
                name,
                type,
                subtype,
                ((subtype.toULong() shl 8) or devMinor),
                parent,
                handle,
                backend
            )
            devices += device

            println("DEV: install ${device.name}")
            return device.dev
        })
    }

    fun installDevice(
        type: Int,
        subtype: Int,
        handle: Any,
        name: String,
        parent: ULong,
        backend: DeviceBackend
    ): ULong {
        return installDeviceInternal(type, subtype, handle, name, parent, null, backend)
    }

    fun installDeviceMinor(
        type: Int,
        subtype: Int,
        handle: Any,
        name: String,
        parent: ULong,
        backend: DeviceBackend,
        fixedMinor: ULong
    ): ULong {
        return installDeviceInternal(type,subtype,handle,name,parent,fixedMinor,backend)
    }

    fun findDevice(subType: Int,index: ULong) : Device? {
        var nr = 0UL
        for (device in devices) {
            if(device.subType != subType) continue
            if(index == nr) return device
            nr++
        }
        return null
    }

    fun getDevice(dev: ULong) : Device? {
        for (device in devices) {
            if (device.dev == dev) return device
        }
        return null
    }

    fun write(dev: ULong, buf: ByteArray, offset: ULong, count: ULong) : ULong {
        val device = getDevice(dev) ?: return (-Errno.ENODEV).toULong()
        return device.backend.write(device, buf, offset, count)
    }

    fun read(dev: ULong, buf: ByteArray, offset: ULong, count: ULong) : ULong {
        val device = getDevice(dev) ?: return (-Errno.ENODEV).toULong()
        return device.backend.read(device, buf, offset, count)
    }
}