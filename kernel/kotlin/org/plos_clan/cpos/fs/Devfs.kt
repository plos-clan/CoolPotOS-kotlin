package org.plos_clan.cpos.fs

import org.plos_clan.cpos.drivers.DEV_BLOCK
import org.plos_clan.cpos.drivers.DEV_CHAR
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceIoEvent
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DiscardingDeviceBackend
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.drivers.WaitablePositionlessDeviceBackend
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock

object Devfs : FileSystemType {
    override val name: String = "devfs"
    override val magic: ULong = 0x1373uL
    override val requiresDevice: Boolean = false

    override fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock> {
        if (options != EmptyFileSystemOptions) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val instance = DevfsInstance()
        return VfsResult.Ok(
            SuperBlock(this, instance) { superBlock ->
                instance.createRoot(superBlock)
            }
        )
    }
}

private class DevfsInstance : SuperBlockBackend {
    private val lock = IrqSpinLock()
    private val inodes = mutableMapOf<ULong, Inode>()
    private val directories = mutableMapOf<String, Inode>()
    private var nextDirectoryInode = ULong.MAX_VALUE

    fun createRoot(superBlock: SuperBlock): Inode =
        Inode(
            id = InodeId(0uL),
            superBlock = superBlock,
            backend = DevfsDirectory(this, ""),
            metadata = InodeMetadata(
                mode = FileMode(0x1EDu),
                linkCount = 2u,
            ),
        )

    fun inodeFor(superBlock: SuperBlock, device: Device): Inode = lock.withLock {
        inodes.getOrPut(device.dev) {
            Inode(
                id = InodeId(device.dev + 1uL),
                superBlock = superBlock,
                backend = DevfsDeviceNode(device),
                metadata = InodeMetadata(
                    mode = FileMode(0x1B6u),
                    linkCount = 1u,
                    deviceNumber = device.dev,
                ),
            )
        }
    }

    fun directoryFor(superBlock: SuperBlock, path: String): Inode = lock.withLock {
        directories.getOrPut(path) {
            Inode(
                id = InodeId(nextDirectoryInode--),
                superBlock = superBlock,
                backend = DevfsDirectory(this, path),
                metadata = InodeMetadata(
                    mode = FileMode(0x1EDu),
                    linkCount = 2u,
                ),
            )
        }
    }
}

private class DevfsDirectory(
    private val fileSystem: DevfsInstance,
    private val path: String,
) : DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cacheNegativeLookups: Boolean = false

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> {
        val child = if (path.isEmpty()) name.toString() else "$path/$name"
        val devices = DeviceManager.snapshotDevices().filter(::isDeviceNode)
        devices.firstOrNull { it.name == child }?.let { device ->
            return VfsResult.Ok(fileSystem.inodeFor(directory.superBlock, device))
        }
        return if (devices.any { it.name.startsWith("$child/") }) {
            VfsResult.Ok(fileSystem.directoryFor(directory.superBlock, child))
        } else {
            VfsResult.Ok(null)
        }
    }

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(DevfsDirectoryHandle(this))

    fun snapshot(superBlock: SuperBlock): List<DirectoryEntry> =
        DeviceManager.snapshotDevices()
            .asSequence()
            .filter(::isDeviceNode)
            .mapNotNull { device ->
                val relative = when {
                    path.isEmpty() -> device.name
                    device.name.startsWith("$path/") -> device.name.substring(path.length + 1)
                    else -> return@mapNotNull null
                }
                val childName = relative.substringBefore('/')
                childName to device.takeIf { '/' !in relative }
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { (childName, device) ->
                val bytes = childName.encodeToByteArray()
                val name = VfsName.fromPath(bytes, 0, bytes.size)
                val inode = device?.let { fileSystem.inodeFor(superBlock, it) }
                    ?: fileSystem.directoryFor(
                        superBlock,
                        if (path.isEmpty()) childName else "$path/$childName",
                    )
                DirectoryEntry(name, inode.id, inode.type)
            }
            .toList()

    private fun isDeviceNode(device: Device): Boolean =
        device.type == DEV_CHAR || device.type == DEV_BLOCK
}

private class DevfsDirectoryHandle(
    private val directory: DevfsDirectory,
) : OpenFileBackend {
    override fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        if (position.value < 0 || position.value > Int.MAX_VALUE) {
            return VfsResult.Ok(Unit)
        }

        val entries = directory.snapshot(inode.superBlock)
        var index = position.value.toInt()
        while (index < entries.size) {
            val nextOffset = index.toLong() + 1L
            if (!emit(entries[index], nextOffset)) {
                break
            }
            index++
            position.value = nextOffset
        }
        return VfsResult.Ok(Unit)
    }
}

private class DevfsDeviceNode(
    private val device: Device,
) : InodeBackend {
    override val type: InodeType =
        if (device.type == DEV_BLOCK) InodeType.BLOCK_DEVICE else InodeType.CHARACTER_DEVICE

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> {
        val backend = device.backend.open(device)
            ?: return VfsResult.Err(VfsError.NO_DEVICE)
        return VfsResult.Ok(DevfsDeviceHandle.open(device, backend))
    }
}

private sealed class DevfsDeviceHandle(
    protected val device: Device,
    protected val backend: DeviceBackend,
) : OpenFileBackend {
    companion object {
        fun open(device: Device, backend: DeviceBackend): DevfsDeviceHandle = when (backend) {
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
    ) : DevfsDeviceHandle(device, backend) {
        override fun read(
            inode: Inode,
            destination: PreparedBufferDestination,
            destinationOffset: Int,
            count: Int,
            position: FilePosition,
        ): IoResult {
            if (count == 0) return IoResult.success(0)
            val deviceOffset = position.deviceOffset()
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
            val deviceOffset = position.deviceOffset()
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

        private fun FilePosition.deviceOffset(): ULong? =
            value.takeIf { it >= 0 && it <= Long.MAX_VALUE - Int.MAX_VALUE }?.toULong()
    }

    private open class Positionless(
        device: Device,
        protected val positionlessBackend: PositionlessDeviceBackend,
    ) : DevfsDeviceHandle(device, positionlessBackend), PositionlessOpenFileBackend {
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
