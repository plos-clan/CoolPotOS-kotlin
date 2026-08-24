package org.plos_clan.cpos.fs.overlay

import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.DirectoryBackend
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.DirectoryLookup
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeMode
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeName
import org.plos_clan.cpos.fs.vfs.FileAllocationMode
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeBackend
import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.MountResource
import org.plos_clan.cpos.fs.vfs.MountResourceProvider
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.RemoveMode
import org.plos_clan.cpos.fs.vfs.RenameMode
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory

internal class OverlayLocation(
    var lower: VfsPath?,
    var upper: VfsPath?,
    parent: OverlayLocation?,
    name: VfsName?,
) {
    private val children = mutableMapOf<VfsName, OverlayLocation>()
    var parent = parent
        private set
    var name = name
        private set
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

    fun relocate(parent: OverlayLocation, name: VfsName) {
        this.parent = parent
        this.name = name
    }
}

internal interface OverlayNodeBackend : InodeBackend {
    val instance: OverlayInstance
    val location: OverlayLocation

    override fun setMode(
        caller: VfsOperationContext,
        inode: Inode,
        mode: FileMode,
    ): VfsResult<Unit> = instance.setMode(caller, location, inode, mode)

    override fun setOwner(
        caller: VfsOperationContext,
        inode: Inode,
        uid: UInt?,
        gid: UInt?,
    ): VfsResult<Unit> = instance.setOwner(caller, location, inode, uid, gid)

    override fun sync(
        caller: VfsOperationContext,
        inode: Inode,
        dataOnly: Boolean,
    ): VfsResult<Unit> = instance.sync(caller, location, dataOnly)

    override fun loadAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<InodeAttributeSnapshot> = instance.loadAttributes(caller, location)

    override fun checkAccess(
        caller: VfsOperationContext,
        inode: Inode,
        requested: AccessPermissions,
    ): VfsResult<Unit> = instance.checkAccess(caller, location, requested)

    override fun pageCacheIdentity(inode: Inode): Any = instance.pageCacheIdentity(location)

    override fun getExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = instance.getExtendedAttribute(caller, location, name)

    override fun listExtendedAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<ByteArray> = instance.listExtendedAttributes(caller, location)

    override fun setExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> =
        instance.setExtendedAttribute(caller, location, inode, name, value, mode)

    override fun removeExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> = instance.removeExtendedAttribute(caller, location, inode, name)
}

internal class OverlayDirectoryBackend(
    override val instance: OverlayInstance,
    private val superBlock: SuperBlock,
    override val location: OverlayLocation,
) : DirectoryBackend, OverlayNodeBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override fun lookup(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
    ): VfsResult<DirectoryLookup> = VfsResult.Ok(
        DirectoryLookup(
            instance.child(caller, superBlock, location, name),
            CacheValidity.Volatile,
        ),
    )

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
        VfsResult.Ok(OverlayDirectoryHandle(instance.entries(caller, superBlock, location)))

    override fun create(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> = instance.mapResult(
        caller,
        instance.create(caller, location, name, node),
        superBlock,
        location,
        name,
    ).also { result ->
        if (result is VfsResult.Ok) instance.refreshMetadata(location, directory)
    }
    override fun link(
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        target: Inode,
    ): VfsResult<Unit> {
        val source = (target.backend as? OverlayNodeBackend)?.location
            ?: return VfsResult.Err(VfsError.CROSS_DEVICE)
        return instance.link(caller, location, name, source, target).also { result ->
            if (result is VfsResult.Ok) instance.refreshMetadata(location, directory)
        }
    }
    override fun rename(
        caller: VfsOperationContext,
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
            caller,
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
        caller: VfsOperationContext,
        directory: Inode,
        name: VfsName,
        target: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> = instance.remove(
        caller,
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
        caller: VfsOperationContext,
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
    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> = instance.open(caller, location, inode, options)

    override fun resize(
        caller: VfsOperationContext,
        inode: Inode,
        size: ULong,
    ): VfsResult<Unit> = instance.resize(caller, location, inode, size)

    override fun allocate(
        caller: VfsOperationContext,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> = instance.allocate(caller, location, inode, offset, length, mode)
}

internal class OverlayFileHandle(
    private val inode: Inode,
    private val target: Inode,
    private val delegate: OpenFileBackend,
) : OpenFileBackend, PageCacheProvider, MountResourceProvider {
    override val cacheSource
        get() = (delegate as? PageCacheProvider)?.cacheSource

    override val mountResource: MountResource?
        get() = delegate as? MountResource
            ?: (delegate as? MountResourceProvider)?.mountResource

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult =
        delegate.read(caller, target, destination, destinationOffset, count, position)

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult =
        delegate.write(caller, target, source, sourceOffset, count, position, append).also { result ->
            if (result.isSuccess) {
                val metadata = target.metadata()
                inode.updateMetadata(InodeTimestampEvent.NONE) { metadata }
            }
        }

    override fun flush(caller: VfsOperationContext, inode: Inode): VfsResult<Unit> =
        delegate.flush(caller, target)

    override fun ioctl(
        caller: VfsOperationContext,
        inode: Inode,
        command: Int,
        args: UserMemory,
    ): Long = delegate.ioctl(caller, target, command, args)

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long =
        delegate.poll(caller, target, events)

    override fun syncHandle(
        caller: VfsOperationContext,
        inode: Inode,
        dataOnly: Boolean,
    ): VfsResult<Unit> = delegate.syncHandle(caller, target, dataOnly)

    override fun release() = delegate.release()
}

internal class OverlaySymlinkBackend(
    override val instance: OverlayInstance,
    override val location: OverlayLocation,
) : SymlinkBackend, OverlayNodeBackend {
    override val type: InodeType = InodeType.SYMLINK
    override fun readLink(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<VfsPathname> =
        (location.upper ?: location.lower)?.inode?.let { source ->
            (source.backend as? SymlinkBackend)?.readLink(caller, source)
        } ?: VfsResult.Err(VfsError.NOT_FOUND)

    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
}

internal class OverlaySpecialBackend(
    override val instance: OverlayInstance,
    override val location: OverlayLocation,
    override val type: InodeType,
) : OverlayNodeBackend {
    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> = instance.open(caller, location, inode, options)
}
