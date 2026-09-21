@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.block

import kotlin.concurrent.atomics.AtomicInt
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

class BlockDeviceBackend(val volume: BlockVolume) : DeviceBackend {
    override val ueventEnvironment: List<Pair<String, String>>
        get() = buildList {
            val partition = volume.partition
            add("DEVTYPE" to if (partition == null) "disk" else "partition")
            if (partition != null) add("PARTN" to partition.number.toString())
        }

    override val byteSize: ULong
        get() = volume.size

    override fun open(device: Device): VfsResult<DeviceBackend> =
        if (volume.cache.connected) VfsResult.Ok(this) else VfsResult.Err(VfsError.NO_DEVICE)

    override fun sync(device: Device): Long = volume.cache.flush().error

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long {
        if (!volume.cache.connected) return -Errno.ENODEV.toLong()
        val geometry = volume.geometry
        val (width, value) =
            when (command) {
                0x125e -> 4 to if (volume.readOnly) 1uL else 0uL
                0x1260 -> 8 to geometry.byteSize / 512uL
                0x1268 -> 4 to geometry.blockSize.toULong()
                0x80081272.toInt() -> 8 to geometry.byteSize
                else -> return -Errno.ENOTTY.toLong()
            }
        val bytes = ByteArray(width)
        val fields = LittleEndianBuffer(bytes)
        if (width == 4) fields.writeU32(0, value.toUInt()) else fields.writeU64(0, value)
        return if (args.copyToUser(bytes)) 0 else -Errno.EFAULT.toLong()
    }

    override fun poll(device: Device, events: Int): Long =
        if (volume.cache.connected) (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()
        else (PollEvents.POLLERR or PollEvents.POLLHUP).toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long {
        if (
            size > Int.MAX_VALUE.toULong() ||
                bufferOffset < 0 ||
                bufferOffset > Int.MAX_VALUE - size.toInt()
        ) {
            return -Errno.EINVAL.toLong()
        }
        return volume.read(offset, buffer, bufferOffset, size.toInt()).value
    }

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long {
        if (
            size > Int.MAX_VALUE.toULong() ||
                bufferOffset < 0 ||
                bufferOffset > Int.MAX_VALUE - size.toInt()
        ) {
            return -Errno.EINVAL.toLong()
        }
        if (size != 0uL && offset >= byteSize) return -Errno.ENOSPC.toLong()
        return volume.write(offset, buffer, bufferOffset, size.toInt()).value
    }

    private val BlockResult.value: Long
        get() = if (bytes > 0 || successful) bytes.toLong() else status.error

    private val BlockStatus.error: Long
        get() =
            when (this) {
                BlockStatus.SUCCESS -> 0
                BlockStatus.IO_ERROR -> -Errno.EIO.toLong()
                BlockStatus.NO_DEVICE -> -Errno.ENODEV.toLong()
                BlockStatus.READ_ONLY -> -Errno.EROFS.toLong()
                BlockStatus.INVALID -> -Errno.EINVAL.toLong()
                BlockStatus.NO_MEMORY -> -Errno.ENOMEM.toLong()
            }
}

object BlockDevices {
    private val sequence = AtomicInt(0)
    private val lock = IrqSpinLock()
    private val devices = mutableListOf<Device>()
    private val changes = MutableStateFlow(0uL)

    suspend fun register(block: BlockDevice): Device? {
        val partitions = Gpt(block).read().orEmpty()
        if (!block.connected) return null
        var index = sequence.fetchAndAdd(1)
        val suffix = StringBuilder()
        do {
            suffix.append('a' + index % 26)
            index = index / 26 - 1
        } while (index >= 0)
        val name = "sd${suffix.reverse()}"
        val cache = BufferCache(block)
        val disk = publish(name, BlockVolume(cache)) ?: return null
        for (partition in partitions) {
            if (publish("$name${partition.number}", BlockVolume(cache, partition), disk) == null) {
                unregister(disk)
                return null
            }
        }
        return disk
    }

    fun find(source: String): Device? {
        val id = if (source.startsWith("PARTUUID=", true)) {
            Uuid.parseOrNull(source.substringAfter('=')) ?: return null
        } else null
        val name = source.removePrefix("/dev/")
        return lock.withLock {
            devices.singleOrNull { device ->
                val volume = (device.backend as BlockDeviceBackend).volume
                val matches = if (id != null) volume.partition?.id == id else device.name == name
                volume.cache.connected && matches
            }
        }
    }

    suspend fun await(source: String, timeoutMillis: Long = 30_000): Device? =
        withTimeoutOrNull(timeoutMillis) {
            changes.mapNotNull { find(source) }.first()
        }

    fun flush(): BlockStatus {
        val caches =
            lock.withLock {
                devices.map { (it.backend as BlockDeviceBackend).volume.cache }.distinct()
            }
        var status = BlockStatus.SUCCESS
        for (cache in caches) {
            val result = cache.flush()
            if (result != BlockStatus.SUCCESS) status = result
        }
        return status
    }

    fun unregister(device: Device) {
        val backend = device.backend as? BlockDeviceBackend ?: return
        backend.volume.cache.detach()
        val removed =
            lock.withLock {
                val matches = devices.filter {
                    (it.backend as BlockDeviceBackend).volume.cache === backend.volume.cache
                }
                devices.removeAll(matches.toSet())
                changes.value++
                matches
            }
        removed.asReversed().forEach(DeviceManager::unregister)
    }

    private fun publish(name: String, volume: BlockVolume, parent: Device? = null): Device? {
        val partition = volume.partition
        val size = SysfsTextAttribute.constant("size", "${volume.size / 512uL}\n")
        val readOnly = SysfsTextAttribute.constant("ro", if (volume.readOnly) "1\n" else "0\n")
        val attributes = mutableListOf(size, readOnly)
        if (partition != null) {
            val number = SysfsTextAttribute.constant("partition", "${partition.number}\n")
            val start = SysfsTextAttribute.constant("start", "${volume.offset / 512uL}\n")
            attributes += number
            attributes += start
        }
        val binding = SysfsIndexBinding("block")
        val bindings = SysfsBindings(deviceClass = binding, block = parent == null)
        val location = parent?.let { SysfsParent.DeviceObject(it) } ?: SysfsParent.Virtual("block")
        val specification = SysfsObjectSpec(name, location, attributes = attributes, bindings = bindings)
        val publication = SysfsDevicePublication.NewObject(specification)
        val backend = BlockDeviceBackend(volume)
        val registration = DeviceRegistration(
            name, DeviceType.BLOCK, 259u, backend = backend, sysfs = publication,
        )
        val device = DeviceManager.register(registration) ?: return null
        lock.withLock {
            devices.add(device)
            changes.value++
        }
        volume.partition?.let { println("Block: /dev/$name PARTUUID=${it.id}") }
        return device
    }
}
