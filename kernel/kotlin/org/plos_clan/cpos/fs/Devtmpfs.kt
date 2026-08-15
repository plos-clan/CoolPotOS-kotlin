package org.plos_clan.cpos.fs

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceIoEvent
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceNumber
import org.plos_clan.cpos.drivers.DeviceRegistryObserver
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.DiscardingDeviceBackend
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.drivers.WaitablePositionlessDeviceBackend
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory

object Devtmpfs : FileSystemType {
    override val name: String = "devtmpfs"
    override val magic: ULong = 0x0102_1994uL
    override val requiresDevice: Boolean = false

    override fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock> {
        if (options != EmptyFileSystemOptions) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val instance = DevtmpfsInstance()
        return VfsResult.Ok(
            SuperBlock(this, instance) { superBlock ->
                instance.createRoot(superBlock)
            }
        )
    }
}

private class DevtmpfsInstance : SuperBlockBackend, DeviceRegistryObserver {
    private val storage = TmpfsInstance(
        options = TmpfsOptions(),
        cacheDirectoryLookups = false,
    )
    private var root: Inode? = null

    fun createRoot(superBlock: SuperBlock): Inode {
        val inode = storage.newDirectory(superBlock, FileMode(0x1EDu), parent = null)
        root = inode
        DeviceManager.observe(this)
        return inode
    }

    override fun deviceRegistered(device: Device) {
        val root = root ?: return
        storage.installSpecialNode(
            root = root,
            path = devicePath(device),
            backend = DeviceNode(
                if (device.type == DeviceType.BLOCK) InodeType.BLOCK_DEVICE
                else InodeType.CHARACTER_DEVICE,
                device.number.value,
            ),
            metadata = InodeMetadata(
                mode = FileMode(0x180u),
                linkCount = 1u,
                deviceNumber = device.number.value,
            ),
        )
    }

    override fun deviceUnregistered(device: Device) {
        val root = root ?: return
        storage.removeSpecialNode(root, devicePath(device)) { backend ->
            backend is DeviceNode && backend.matches(device)
        }
    }

    override fun sync(): VfsResult<Unit> = storage.sync()

    override fun release() {
        DeviceManager.stopObserving(this)
        root = null
        storage.release()
    }

    private fun devicePath(device: Device): List<VfsName> =
        device.name.split('/').map { component ->
            val bytes = component.encodeToByteArray()
            VfsName.fromPath(bytes, 0, bytes.size)
        }
}

internal class DeviceNode(
    override val type: InodeType,
    encodedNumber: ULong,
) : MutableInodeBackend {
    private val number = requireNotNull(DeviceNumber.fromEncoded(encodedNumber))

    init {
        require(type == InodeType.CHARACTER_DEVICE || type == InodeType.BLOCK_DEVICE)
    }

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> {
        val deviceType = if (type == InodeType.BLOCK_DEVICE) DeviceType.BLOCK else DeviceType.CHARACTER
        val device = DeviceManager.find(deviceType, number)
            ?: return VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
        val backend = device.backend.open(device)
            ?: return VfsResult.Err(VfsError.NO_DEVICE)
        return VfsResult.Ok(DeviceOpenFile.open(device, backend))
    }

    fun matches(device: Device): Boolean = device.number == number &&
        type == if (device.type == DeviceType.BLOCK) InodeType.BLOCK_DEVICE
        else InodeType.CHARACTER_DEVICE
}

internal sealed class DeviceOpenFile(
    protected val device: Device,
    protected val backend: DeviceBackend,
) : OpenFileBackend {
    companion object {
        fun open(device: Device, backend: DeviceBackend): DeviceOpenFile = when (backend) {
            is DiscardingDeviceBackend -> Discarding(device, backend)
            is WaitablePositionlessDeviceBackend -> Waitable(device, backend)
            is PositionlessDeviceBackend -> Positionless(device, backend)
            else -> Positioned(device, backend)
        }
    }

    override fun ioctl(inode: Inode, command: Int, args: UserMemory): Long =
        backend.ioctl(device, command, args)

    override fun poll(inode: Inode, events: Int): Long =
        backend.poll(device, events)

    override fun release() = backend.close(device)

    protected fun Long.toIoResult(requested: Int): IoResult {
        if (this < 0) {
            if (this == Long.MIN_VALUE || -this > Int.MAX_VALUE) {
                return IoResult.failure(VfsError.IO)
            }
            return IoResult.failure(VfsError.fromErrno((-this).toInt()))
        }
        if (this > requested) {
            return IoResult.failure(VfsError.IO)
        }
        return IoResult.success(toInt())
    }

    private class Positioned(
        device: Device,
        backend: DeviceBackend,
    ) : DeviceOpenFile(device, backend) {
        override fun read(
            inode: Inode,
            destination: PreparedBufferDestination,
            destinationOffset: Int,
            count: Int,
            position: FilePosition,
        ): IoResult {
            if (count == 0) return IoResult.success(0)
            val deviceOffset = position.deviceOffset(count)
                ?: return IoResult.failure(VfsError.FILE_TOO_LARGE)
            val result = backend.read(
                device,
                destination,
                destinationOffset,
                deviceOffset,
                count.toULong(),
            ).toIoResult(count)
            if (result.isSuccess) position.value += result.bytesTransferred
            return result
        }

        override fun write(
            inode: Inode,
            source: PreparedBufferSource,
            sourceOffset: Int,
            count: Int,
            position: FilePosition,
            append: Boolean,
        ): IoResult {
            if (count == 0) return IoResult.success(0)
            val deviceOffset = position.deviceOffset(count)
                ?: return IoResult.failure(VfsError.FILE_TOO_LARGE)
            val result = backend.write(
                device,
                source,
                sourceOffset,
                deviceOffset,
                count.toULong(),
            ).toIoResult(count)
            if (result.isSuccess) position.value += result.bytesTransferred
            return result
        }

        private fun FilePosition.deviceOffset(count: Int): ULong? =
            value.takeIf { it >= 0 && count.toLong() <= Long.MAX_VALUE - it }?.toULong()
    }

    private open class Positionless(
        device: Device,
        protected val positionlessBackend: PositionlessDeviceBackend,
    ) : DeviceOpenFile(device, positionlessBackend), PositionlessOpenFileBackend {
        override fun read(
            inode: Inode,
            destination: PreparedBufferDestination,
            destinationOffset: Int,
            count: Int,
        ): IoResult = if (count == 0) {
            IoResult.success(0)
        } else {
            positionlessBackend.read(
                device,
                destination,
                destinationOffset,
                count.toULong(),
            ).toIoResult(count)
        }

        override fun write(
            inode: Inode,
            source: PreparedBufferSource,
            sourceOffset: Int,
            count: Int,
        ): IoResult = if (count == 0) {
            IoResult.success(0)
        } else {
            positionlessBackend.write(
                device,
                source,
                sourceOffset,
                count.toULong(),
            ).toIoResult(count)
        }
    }

    private class Waitable(
        device: Device,
        private val waitableBackend: WaitablePositionlessDeviceBackend,
    ) : Positionless(device, waitableBackend), WaitableOpenFileBackend {
        override fun write(
            inode: Inode,
            source: PreparedBufferSource,
            sourceOffset: Int,
            count: Int,
        ): IoResult = super<Positionless>.write(inode, source, sourceOffset, count)

        override fun write(
            inode: Inode,
            source: PreparedBufferSource,
            sourceOffset: Int,
            count: Int,
            mode: IoMode,
        ): IoResult = super<Positionless>.write(inode, source, sourceOffset, count)

        override fun await(event: IoEvent, count: Int) = waitableBackend.await(
            device,
            when (event) {
                IoEvent.READABLE -> DeviceIoEvent.READABLE
                IoEvent.WRITABLE -> DeviceIoEvent.WRITABLE
            },
            count,
        )
    }

    private class Discarding(
        device: Device,
        private val discardingBackend: DiscardingDeviceBackend,
    ) : Positionless(device, discardingBackend), DiscardingOpenFileBackend {
        override fun write(
            inode: Inode,
            source: PreparedBufferSource,
            sourceOffset: Int,
            count: Int,
        ): IoResult = discard(inode, count)

        override fun discard(inode: Inode, count: Int): IoResult =
            if (count == 0) IoResult.success(0)
            else discardingBackend.discard(device, count.toULong()).toIoResult(count)
    }
}
