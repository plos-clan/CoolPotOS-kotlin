package org.plos_clan.cpos.fs.sysfs

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.DirectoryBackend
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.DirectoryLookup
import org.plos_clan.cpos.fs.vfs.EmptyFileSystemOptions
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.FileSystemOptions
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeId
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.InodeTimestamps
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.SuperBlockBackend
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

object Sysfs : FileSystemType("sysfs", 0x62656572uL) {
    private val registry = SysfsRegistry()

    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> =
        if (options === EmptyFileSystemOptions) VfsResult.Ok(SysfsInstance(registry))
        else VfsResult.Err(VfsError.INVALID_ARGUMENT)

    fun registerObject(spec: SysfsObjectSpec): VfsResult<SysfsObjectHandle> =
        registry.registerObject(spec)

    fun unregisterObject(handle: SysfsObjectHandle): VfsResult<Unit> =
        registry.unregisterObject(handle)

    fun registerClass(name: String): VfsResult<SysfsClassHandle> = registry.registerClass(name)

    fun unregisterClass(handle: SysfsClassHandle): VfsResult<Unit> =
        registry.unregisterClass(handle)

    fun registerBus(name: String): VfsResult<SysfsBusHandle> = registry.registerBus(name)

    fun unregisterBus(handle: SysfsBusHandle): VfsResult<Unit> = registry.unregisterBus(handle)

    fun registerDevice(
        device: Device,
        publication: SysfsDevicePublication,
    ): VfsResult<SysfsObjectHandle> = when (publication) {
        is SysfsDevicePublication.NewObject -> registry.registerDevice(device, publication.spec)
        is SysfsDevicePublication.ExistingObject -> registry.registerDevice(
            device,
            publication.objectHandle,
            publication.bindings,
        )
    }

    fun registerDevice(
        device: Device,
        objectHandle: SysfsObjectHandle,
        bindings: SysfsBindings = SysfsBindings(),
    ): VfsResult<SysfsObjectHandle> = registry.registerDevice(device, objectHandle, bindings)

    fun unregisterDevice(device: Device): VfsResult<Unit> = registry.unregisterDevice(device)
}

internal class SysfsInstance(
    private val registry: SysfsRegistry,
) : SuperBlockBackend {
    override fun createRoot(superBlock: SuperBlock): Inode = inode(superBlock, registry.root)

    fun inode(superBlock: SuperBlock, node: SysfsNode): Inode = Inode(
        id = InodeId(node.id),
        superBlock = superBlock,
        backend = when (node) {
            is SysfsNode.Directory -> SysfsDirectoryBackend(this, registry, node)
            is SysfsNode.Attribute -> when (val attribute = node.attribute) {
                is SysfsTextAttribute -> SysfsTextFile(registry, node, attribute)
                is SysfsBinaryAttribute -> SysfsBinaryFile(registry, node, attribute)
            }
            is SysfsNode.Link -> SysfsSymlink(registry, node)
        },
        initialAttributes = InodeAttributeSnapshot(
            InodeAttributes(
                InodeMetadata(
                    mode = FileMode(node.mode),
                    size = if (node is SysfsNode.Attribute) node.attribute.size else 0uL,
                    linkCount = if (node is SysfsNode.Directory) 2u else 1u,
                    uid = node.uid,
                    gid = node.gid,
                    timestamps = InodeTimestamps.fromModificationTime(node.createdAt),
                ),
            ),
            CacheValidity.Persistent,
        ),
    )

    fun directoryEntries(entries: List<SysfsDirectoryEntry>): List<DirectoryEntry> =
        entries.map { entry ->
            DirectoryEntry(
                entry.name,
                InodeId(entry.id),
                entry.type,
            )
        }
}

private class SysfsDirectoryBackend(
    private val fileSystem: SysfsInstance,
    private val registry: SysfsRegistry,
    private val directory: SysfsNode.Directory,
) : DirectoryBackend {
    override val type = InodeType.DIRECTORY

    override fun lookup(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
    ): VfsResult<DirectoryLookup> = when (val result = registry.lookup(this.directory.id, name)) {
        is VfsResult.Ok -> VfsResult.Ok(
            DirectoryLookup(
                result.value.node?.let { fileSystem.inode(directory.superBlock, it) },
                result.value.validity,
                result.value.reference,
            ),
        )
        is VfsResult.Err -> result
    }

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> = when (val result = registry.snapshot(directory.id)) {
        is VfsResult.Ok -> VfsResult.Ok(
            SysfsDirectoryHandle(fileSystem.directoryEntries(result.value)),
        )
        is VfsResult.Err -> result
    }
}

private class SysfsDirectoryHandle(
    private val entries: List<DirectoryEntry>,
) : OpenFileBackend {
    override fun iterate(
        caller: VfsOperationContext,
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        if (position.value !in 0..Int.MAX_VALUE.toLong()) return VfsResult.Ok(Unit)
        var index = position.value.toInt()
        while (index < entries.size) {
            val next = index.toLong() + 1L
            if (!emit(entries[index], next)) break
            position.value = next
            index++
        }
        return VfsResult.Ok(Unit)
    }
}

private class SysfsTextFile(
    private val registry: SysfsRegistry,
    private val node: SysfsNode.Attribute,
    private val attribute: SysfsTextAttribute,
) : RegularFileBackend() {
    override fun resize(
        caller: VfsOperationContext,
        inode: Inode,
        size: ULong,
    ): VfsResult<Unit> =
        if (attribute.writable && size == 0uL) VfsResult.Ok(Unit)
        else VfsResult.Err(VfsError.NOT_SUPPORTED)

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> {
        if (options.access.canWrite && !attribute.writable) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        if (!registry.retain(node)) return VfsResult.Err(VfsError.NO_DEVICE)
        return VfsResult.Ok(SysfsTextHandle(registry, node, attribute))
    }
}

private class SysfsTextHandle(
    private val registry: SysfsRegistry,
    private val node: SysfsNode.Attribute,
    private val attribute: SysfsTextAttribute,
) : OpenFileBackend {
    private var content: ByteArray? = null

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (count == 0 || position.value < 0) return IoResult.success(0)
        if (content == null || position.value == 0L) {
            content = when (val result = show()) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return IoResult.failure(result.error)
            }
        }
        val snapshot = checkNotNull(content)
        if (position.value >= snapshot.size) return IoResult.success(0)
        val start = position.value.toInt()
        val requested = minOf(count, snapshot.size - start)
        val copied = destination.copyFrom(destinationOffset, snapshot, start, requested)
        if (copied == 0) return IoResult.failure(VfsError.FAULT)
        position.value += copied
        return IoResult.success(copied)
    }

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult {
        if (count == 0) return IoResult.success(0)
        if (append || position.value != 0L || count > PAGE_SIZE_BYTES.toInt()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        if (!registry.isLive(node)) return IoResult.failure(VfsError.NO_DEVICE)
        val input = ByteArray(count)
        if (source.copyTo(sourceOffset, input, 0, count) != count) {
            return IoResult.failure(VfsError.FAULT)
        }
        return when (val result = attribute.store(input)) {
            is VfsResult.Ok -> {
                position.value = count.toLong()
                content = null
                inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED)
                IoResult.success(count)
            }
            is VfsResult.Err -> IoResult.failure(result.error)
        }
    }

    override fun release() = registry.releaseOpenReference(node)

    private fun show(): VfsResult<ByteArray> {
        if (!registry.isLive(node)) return VfsResult.Err(VfsError.NO_DEVICE)
        return when (val result = attribute.show()) {
            is VfsResult.Ok -> if (result.value.size <= PAGE_SIZE_BYTES.toInt()) result
                else VfsResult.Err(VfsError.FILE_TOO_LARGE)
            is VfsResult.Err -> result
        }
    }
}

private class SysfsBinaryFile(
    private val registry: SysfsRegistry,
    private val node: SysfsNode.Attribute,
    private val attribute: SysfsBinaryAttribute,
) : RegularFileBackend() {
    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> {
        if (options.access.canWrite && !attribute.writable) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        if (!registry.retain(node)) return VfsResult.Err(VfsError.NO_DEVICE)
        return VfsResult.Ok(SysfsBinaryHandle(registry, node, attribute))
    }
}

private class SysfsBinaryHandle(
    private val registry: SysfsRegistry,
    private val node: SysfsNode.Attribute,
    private val attribute: SysfsBinaryAttribute,
) : OpenFileBackend {
    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (count == 0) return IoResult.success(0)
        if (position.value < 0) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        val offset = position.value.toULong()
        if (offset >= attribute.size) return IoResult.success(0)
        if (!registry.isLive(node)) return IoResult.failure(VfsError.NO_DEVICE)
        val available = minOf(count.toULong(), attribute.size - offset).toInt()
        val result = attribute.read(offset, destination, destinationOffset, available)
        if (!result.isSuccess) return result
        if (result.bytesTransferred > available) return IoResult.failure(VfsError.IO)
        position.value += result.bytesTransferred
        return result
    }

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult {
        if (count == 0) return IoResult.success(0)
        if (append || position.value < 0) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        val offset = position.value.toULong()
        if (offset >= attribute.size) return IoResult.failure(VfsError.FILE_TOO_LARGE)
        if (!registry.isLive(node)) return IoResult.failure(VfsError.NO_DEVICE)
        val available = minOf(count.toULong(), attribute.size - offset).toInt()
        val result = attribute.write(offset, source, sourceOffset, available)
        if (!result.isSuccess) return result
        if (result.bytesTransferred > available) return IoResult.failure(VfsError.IO)
        if (result.bytesTransferred != 0) {
            position.value += result.bytesTransferred
            inode.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED)
        }
        return result
    }

    override fun release() = registry.releaseOpenReference(node)
}

private class SysfsSymlink(
    private val registry: SysfsRegistry,
    private val node: SysfsNode.Link,
) : SymlinkBackend {
    override fun readLink(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<VfsPathname> = registry.readLink(node)
}
