package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

internal class OverlayLocation(
    val lower: VfsPath?,
    var upper: VfsPath?,
    val parent: OverlayLocation?,
    val name: VfsName?,
) {
    private val children = mutableMapOf<VfsName, OverlayLocation>()
    var overlayInode: Inode? = null

    val type: InodeType
        get() = (upper ?: lower)?.inode?.type ?: InodeType.REGULAR

    fun cached(name: VfsName): OverlayLocation? = children[name]

    fun cache(name: VfsName, location: OverlayLocation) {
        children[name] = location
    }

    fun invalidate(name: VfsName) {
        children.remove(name)
    }
}

internal interface OverlayNodeBackend : InodeBackend {
    val instance: OverlayInstance
    val location: OverlayLocation

    override fun setMode(inode: Inode, mode: FileMode): VfsResult<Unit> =
        instance.setMode(location, inode, mode)

    override fun sync(inode: Inode, dataOnly: Boolean): VfsResult<Unit> =
        instance.sync(location, dataOnly)

    override fun allocatedBlocks(inode: Inode): ULong = instance.allocatedBlocks(location)

    override fun getExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = instance.getExtendedAttribute(location, name)

    override fun listExtendedAttributes(inode: Inode): VfsResult<ByteArray> =
        instance.listExtendedAttributes(location)

    override fun setExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = instance.setExtendedAttribute(location, name, value, mode)

    override fun removeExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> = instance.removeExtendedAttribute(location, name)
}

internal class OverlayDirectoryBackend(
    override val instance: OverlayInstance,
    private val superBlock: SuperBlock,
    override val location: OverlayLocation,
) : org.plos_clan.cpos.fs.DirectoryBackend, OverlayNodeBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        VfsResult.Ok(instance.child(superBlock, location, name))
    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(OverlayDirectoryHandle(instance.entries(superBlock, location)))
    override fun create(
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> = instance.mapResult(
        instance.create(location, name, node),
        superBlock,
        location,
        name,
    ).also { result ->
        if (result is VfsResult.Ok) instance.refreshMetadata(location, directory)
    }
    override fun link(directory: Inode, name: VfsName, target: Inode): VfsResult<Unit> {
        val source = (target.backend as? OverlayNodeBackend)?.location
            ?: return VfsResult.Err(VfsError.CROSS_DEVICE)
        return instance.link(location, name, source, target)
    }
    override fun rename(
        sourceDirectory: Inode,
        sourceName: VfsName,
        source: Inode,
        targetDirectory: Inode,
        targetName: VfsName,
        target: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> {
        val destination = (targetDirectory.backend as? OverlayDirectoryBackend)?.location
            ?: return VfsResult.Err(VfsError.CROSS_DEVICE)
        return instance.rename(
            superBlock,
            location,
            sourceName,
            source,
            destination,
            targetName,
            target,
            mode,
        ).also { result ->
            if (result is VfsResult.Ok) {
                instance.refreshMetadata(location, sourceDirectory)
                instance.refreshMetadata(destination, targetDirectory)
            }
        }
    }
    override fun remove(
        directory: Inode,
        name: VfsName,
        target: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> = instance.remove(
        superBlock,
        location,
        name,
        target,
        mode,
    ).also { result ->
        if (result is VfsResult.Ok) instance.refreshMetadata(location, directory)
    }
}

private class OverlayDirectoryHandle(
    private val entries: List<DirectoryEntry>,
) : OpenFileBackend {
    override fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (DirectoryEntry, Long) -> Boolean,
    ): VfsResult<Unit> {
        var index = position.value.coerceAtLeast(0).toInt()
        while (index < entries.size) {
            val next = index.toLong() + 1
            if (!emit(entries[index], next)) break
            index++
            position.value = next
        }
        return VfsResult.Ok(Unit)
    }
}

internal class OverlayFileBackend(
    override val instance: OverlayInstance,
    override val location: OverlayLocation,
) : RegularFileBackend(), OverlayNodeBackend {
    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        instance.open(location, inode, options)
    override fun resize(inode: Inode, size: ULong): VfsResult<Unit> =
        instance.resize(location, inode, size)
    override fun allocate(
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> = instance.allocate(location, inode, offset, length, mode)
}

internal class OverlayFileHandle(
    private val inode: Inode,
    private val target: Inode,
    private val delegate: OpenFileBackend,
) : OpenFileBackend, PageCacheProvider {
    override val cacheSource
        get() = (delegate as? PageCacheProvider)?.cacheSource

    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult =
        delegate.read(target, destination, destinationOffset, count, position)

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult =
        delegate.write(target, source, sourceOffset, count, position, append).also { result ->
            if (result.isSuccess) inode.updateMetadata { it.copy(size = target.metadata().size) }
        }

    override fun release() = delegate.release()
}

internal class OverlaySymlinkBackend(
    override val instance: OverlayInstance,
    override val location: OverlayLocation,
) : org.plos_clan.cpos.fs.SymlinkBackend, OverlayNodeBackend {
    override val type: InodeType = InodeType.SYMLINK
    override fun readLink(inode: Inode): VfsResult<VfsPathname> =
        (location.upper ?: location.lower)?.inode?.let { source ->
            (source.backend as? org.plos_clan.cpos.fs.SymlinkBackend)?.readLink(source)
        } ?: VfsResult.Err(VfsError.NOT_FOUND)
    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
}

internal class OverlaySpecialBackend(
    override val instance: OverlayInstance,
    override val location: OverlayLocation,
    override val type: InodeType,
) : OverlayNodeBackend {
    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        instance.open(location, inode, options)
}
