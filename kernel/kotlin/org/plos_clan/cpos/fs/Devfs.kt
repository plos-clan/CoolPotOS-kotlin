package org.plos_clan.cpos.fs

import org.plos_clan.cpos.drivers.DEV_BLOCK
import org.plos_clan.cpos.drivers.DEV_CHAR
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock

object Devfs : FileSystemType {
    override val name: String = "devfs"
    override val magic: ULong = 0x1373uL

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

    fun createRoot(superBlock: SuperBlock): Inode =
        Inode(
            id = InodeId(0uL),
            superBlock = superBlock,
            backend = DevfsDirectory(this),
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
}

private class DevfsDirectory(
    private val fileSystem: DevfsInstance,
) : DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cacheNegativeLookups: Boolean = false

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> {
        val device = DeviceManager.findDeviceByName(name.toString())
            ?.takeIf(::isDeviceNode)
            ?: return VfsResult.Ok(null)
        return VfsResult.Ok(fileSystem.inodeFor(directory.superBlock, device))
    }

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(DevfsDirectoryHandle(this))

    fun snapshot(superBlock: SuperBlock): List<DirectoryEntry> =
        DeviceManager.snapshotDevices()
            .asSequence()
            .filter(::isDeviceNode)
            .sortedBy(Device::name)
            .map { device ->
                val bytes = device.name.encodeToByteArray()
                val name = VfsName.fromPath(bytes, 0, bytes.size)
                val inode = fileSystem.inodeFor(superBlock, device)
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

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(DevfsDeviceHandle(device))
}

private class DevfsDeviceHandle(
    private val device: Device,
) : OpenFileBackend {
    override fun read(
        inode: Inode,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (count == 0) {
            return IoResult.success(0)
        }
        val deviceOffset = position.deviceOffset() ?: return IoResult.failure(VfsError.FILE_TOO_LARGE)
        val buffer =
            if (destinationOffset == 0 && count == destination.size) destination else ByteArray(count)
        val result = device.backend.read(device, buffer, deviceOffset, count.toULong())
            .toIoResult(count)
        if (!result.isSuccess) {
            return result
        }

        val transferred = result.bytesTransferred
        if (buffer !== destination) {
            buffer.copyInto(destination, destinationOffset, 0, transferred)
        }
        position.value += transferred
        return result
    }

    override fun write(
        inode: Inode,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult {
        if (count == 0) {
            return IoResult.success(0)
        }
        val deviceOffset = position.deviceOffset() ?: return IoResult.failure(VfsError.FILE_TOO_LARGE)
        val buffer =
            if (sourceOffset == 0 && count == source.size) source
            else source.copyOfRange(sourceOffset, sourceOffset + count)
        val result = device.backend.write(device, buffer, deviceOffset, count.toULong())
            .toIoResult(count)
        if (result.isSuccess) {
            position.value += result.bytesTransferred
        }
        return result
    }

    override fun ioctl(inode: Inode, command: Int, args: UserMemory): Long =
        device.backend.ioctl(device, command, args)

    override fun poll(inode: Inode, events: Int): Long =
        device.backend.poll(device, events)

    private fun FilePosition.deviceOffset(): ULong? =
        value.takeIf { it >= 0 && it <= Long.MAX_VALUE - Int.MAX_VALUE }?.toULong()

    private fun Long.toIoResult(requested: Int): IoResult {
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
}
