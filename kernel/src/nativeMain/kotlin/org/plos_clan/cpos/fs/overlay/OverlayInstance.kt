package org.plos_clan.cpos.fs.overlay

import org.plos_clan.cpos.fs.vfs.DirectoryBackend
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeMode
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeName
import org.plos_clan.cpos.fs.vfs.FileAllocationMode
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeId
import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.InodeTimestampSet
import org.plos_clan.cpos.fs.vfs.InodeTimestampUpdate
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.RemoveMode
import org.plos_clan.cpos.fs.vfs.RenameMode
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.SuperBlockBackend
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult

internal class OverlayInstance private constructor(options: OverlayfsOptions) : SuperBlockBackend {
    companion object {
        fun open(options: OverlayfsOptions): VfsResult<OverlayInstance> {
            val lower = options.lower.mount
            if (!lower.retain()) return VfsResult.Err(VfsError.NOT_FOUND)

            val upper = options.upper.mount
            if (!upper.retain()) {
                lower.release()
                return VfsResult.Err(VfsError.NOT_FOUND)
            }
            if (options.lower.inode?.type != InodeType.DIRECTORY ||
                options.upper.inode?.type != InodeType.DIRECTORY
            ) {
                upper.release()
                lower.release()
                return VfsResult.Err(VfsError.NOT_DIRECTORY)
            }
            if (MountFlag.READ_ONLY in upper.flags) {
                upper.release()
                lower.release()
                return VfsResult.Err(VfsError.READ_ONLY)
            }
            return VfsResult.Ok(OverlayInstance(options))
        }
    }

    private val whiteouts = mutableSetOf<Whiteout>()
    private val root = OverlayLocation(options.lower, options.upper, null, null)

    private fun inode(superBlock: SuperBlock, location: OverlayLocation): Inode {
        location.overlayInode?.let { return it }
        val source = location.upper ?: location.lower ?: error("overlay inode has no layer")
        val metadata = source.inode?.metadata() ?: error("overlay layer inode is missing")
        val backend = when (location.type) {
            InodeType.DIRECTORY -> OverlayDirectoryBackend(this, superBlock, location)
            InodeType.REGULAR -> OverlayFileBackend(this, location)
            InodeType.SYMLINK -> OverlaySymlinkBackend(this, location)
            else -> OverlaySpecialBackend(this, location, location.type)
        }
        return Inode(InodeId(nextInodeId++), superBlock, backend, metadata).also {
            location.overlayInode = it
        }
    }

    private var nextInodeId = 1uL

    override fun createRoot(superBlock: SuperBlock): Inode = inode(superBlock, root)

    override fun updateTimestamps(
        inode: Inode,
        update: InodeTimestampUpdate,
    ): VfsResult<Unit> {
        val location = (inode.backend as? OverlayNodeBackend)?.location
        if (location == null) {
            inode.updateMetadata(update)
            return VfsResult.Ok(Unit)
        }
        val access = update == InodeTimestampEvent.ACCESSED ||
            update == InodeTimestampEvent.RELATIVE_ACCESS
        if (access && location.upper == null) {
            inode.updateMetadata(update)
            return VfsResult.Ok(Unit)
        }
        if (update is InodeTimestampSet && !OverlayCopyUp.ensureWritable(location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: location.lower
            ?: return VfsResult.Err(VfsError.READ_ONLY)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = target.mount.superBlock.backend.updateTimestamps(targetInode, update)
        if (result is VfsResult.Err && result.error == VfsError.READ_ONLY && access) {
            inode.updateMetadata(update)
            return VfsResult.Ok(Unit)
        }
        if (result is VfsResult.Ok) {
            val timestamps = targetInode.metadata().timestamps
            inode.updateMetadata(InodeTimestampEvent.NONE) { it.copy(timestamps = timestamps) }
        }
        return result
    }

    override fun sync(): VfsResult<Unit> =
        checkNotNull(root.upper).mount.superBlock.backend.sync()

    override fun release() {
        checkNotNull(root.upper).mount.release()
        checkNotNull(root.lower).mount.release()
    }

    internal fun child(superBlock: SuperBlock, directory: OverlayLocation, name: VfsName): Inode? {
        val location = childLocation(directory, name) ?: return null
        return inode(superBlock, location)
    }

    internal fun entries(superBlock: SuperBlock, directory: OverlayLocation): List<DirectoryEntry> {
        val names = linkedSetOf<VfsName>()
        layerEntries(directory.upper).forEach { names += it.name }
        layerEntries(directory.lower).forEach { names += it.name }
        return names.mapNotNull { name ->
            val child = childLocation(directory, name) ?: return@mapNotNull null
            val inode = inode(superBlock, child)
            DirectoryEntry(name, inode.id, inode.type)
        }
    }

    internal fun create(
        directory: OverlayLocation,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> {
        if (childLocation(directory, name) != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val upper = OverlayCopyUp.ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.create(parent, name, node)
        if (result is VfsResult.Ok) reveal(directory, name)
        return result
    }

    internal fun remove(
        superBlock: SuperBlock,
        directory: OverlayLocation,
        name: VfsName,
        expectedTarget: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> {
        val child = childLocation(directory, name) ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (child.overlayInode !== expectedTarget) return VfsResult.Err(VfsError.NOT_FOUND)
        val upper = OverlayCopyUp.ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        if (mode == RemoveMode.DIRECTORY && entries(superBlock, child).isNotEmpty()) {
            return VfsResult.Err(VfsError.NOT_EMPTY)
        }
        val upperTarget = child.upper?.inode
        val result = if (upperTarget != null) {
            backend.remove(parent, name, upperTarget, mode)
        } else VfsResult.Ok(Unit)
        if (result is VfsResult.Err) return result
        if (upperTarget == null) {
            val timestampResult = parent.superBlock.backend.updateTimestamps(
                parent,
                InodeTimestampEvent.CONTENT_CHANGED,
            )
            if (timestampResult is VfsResult.Err) return timestampResult
        }
        val metadata = child.upper?.inode?.metadata()
        child.overlayInode?.updateMetadata(
            if (metadata == null) InodeTimestampEvent.STATUS_CHANGED else InodeTimestampEvent.NONE,
        ) { current -> metadata ?: current.copy(linkCount = 0u) }
        if (child.lower != null) whiteouts += Whiteout(directory, name)
        directory.invalidate(name)
        return VfsResult.Ok(Unit)
    }

    internal fun link(
        directory: OverlayLocation,
        name: VfsName,
        target: OverlayLocation,
        overlayInode: Inode,
    ): VfsResult<Unit> {
        if (childLocation(directory, name) != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (!OverlayCopyUp.ensureWritable(target)) return VfsResult.Err(VfsError.READ_ONLY)
        val source = target.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val upper = OverlayCopyUp.ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.link(parent, name, source)
        if (result is VfsResult.Ok) {
            val metadata = source.metadata()
            overlayInode.updateMetadata(InodeTimestampEvent.NONE) {
                it.copy(linkCount = metadata.linkCount, timestamps = metadata.timestamps)
            }
            reveal(directory, name)
            childLocation(directory, name)?.overlayInode = overlayInode
        }
        return result
    }

    internal fun rename(
        superBlock: SuperBlock,
        sourceDirectory: OverlayLocation,
        sourceName: VfsName,
        expectedSource: Inode,
        targetDirectory: OverlayLocation,
        targetName: VfsName,
        expectedTarget: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> {
        val source = childLocation(sourceDirectory, sourceName)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val target = childLocation(targetDirectory, targetName)
        if (source.overlayInode !== expectedSource || target?.overlayInode !== expectedTarget) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        if (mode == RenameMode.NO_REPLACE && target != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val exchanged = if (mode == RenameMode.EXCHANGE) {
            target ?: return VfsResult.Err(VfsError.NOT_FOUND)
        } else null
        if (exchanged == null && target != null) {
            if (source.type != target.type &&
                (source.type == InodeType.DIRECTORY || target.type == InodeType.DIRECTORY)
            ) {
                return VfsResult.Err(
                    if (source.type == InodeType.DIRECTORY) VfsError.NOT_DIRECTORY
                    else VfsError.IS_DIRECTORY,
                )
            }
            if (target.type == InodeType.DIRECTORY && entries(superBlock, target).isNotEmpty()) {
                return VfsResult.Err(VfsError.NOT_EMPTY)
            }
        }
        if (!OverlayCopyUp.ensureWritable(source)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        if (exchanged != null && !OverlayCopyUp.ensureWritable(exchanged)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val sourceUpper = source.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetUpper = target?.upper
        val sourceParentPath = OverlayCopyUp.ensureUpper(sourceDirectory)
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val targetParentPath = OverlayCopyUp.ensureUpper(targetDirectory)
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val sourceParent = sourceParentPath.inode
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val targetParent = targetParentPath.inode
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = sourceParent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val sourceLayerLower = layerChild(sourceDirectory.lower, sourceName)
        val targetLayerLower = layerChild(targetDirectory.lower, targetName)
        val result = backend.rename(
            sourceParent,
            sourceName,
            sourceUpper.inode ?: return VfsResult.Err(VfsError.NOT_FOUND),
            targetParent,
            targetName,
            targetUpper?.inode,
            mode,
        )
        if (result is VfsResult.Err) return result
        if (exchanged == null) {
            val metadata = targetUpper?.inode?.metadata()
            expectedTarget?.updateMetadata(
                if (metadata == null) InodeTimestampEvent.STATUS_CHANGED
                else InodeTimestampEvent.NONE,
            ) { current -> metadata ?: current.copy(linkCount = 0u) }
        }
        if (exchanged == null && source.lower != null) {
            whiteouts += Whiteout(sourceDirectory, sourceName)
        }
        if (exchanged != null) {
            val sourceWhiteout = Whiteout(sourceDirectory, sourceName)
            val targetWhiteout = Whiteout(targetDirectory, targetName)
            if (sourceLayerLower != null && sourceLayerLower != exchanged.lower) {
                whiteouts += sourceWhiteout
            } else {
                whiteouts.remove(sourceWhiteout)
            }
            if (targetLayerLower != null && targetLayerLower != source.lower) {
                whiteouts += targetWhiteout
            } else {
                whiteouts.remove(targetWhiteout)
            }
        } else if (targetLayerLower != null) {
            val targetWhiteout = Whiteout(targetDirectory, targetName)
            if (targetLayerLower != source.lower) whiteouts += targetWhiteout
            else whiteouts.remove(targetWhiteout)
        }
        sourceParentPath.dentry.renameChild(
            sourceUpper.dentry,
            targetParentPath.dentry,
            targetName,
            targetUpper?.dentry.takeIf { exchanged != null },
        )
        sourceDirectory.invalidate(sourceName)
        targetDirectory.invalidate(targetName)
        source.relocate(targetDirectory, targetName)
        targetDirectory.cache(targetName, source)
        refreshMetadata(source, expectedSource)
        if (exchanged != null) {
            exchanged.relocate(sourceDirectory, sourceName)
            sourceDirectory.cache(sourceName, exchanged)
            refreshMetadata(exchanged, checkNotNull(expectedTarget))
        }
        return VfsResult.Ok(Unit)
    }

    private fun reveal(directory: OverlayLocation, name: VfsName) {
        directory.invalidate(name)
    }

    internal fun open(location: OverlayLocation, inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> {
        if (options.access.canWrite && !OverlayCopyUp.ensureWritable(location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: location.lower ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val delegate = when (val result = targetInode.backend.open(targetInode, options)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return VfsResult.Ok(OverlayFileHandle(inode, targetInode, delegate))
    }

    internal fun resize(location: OverlayLocation, inode: Inode, size: ULong): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = targetInode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val result = backend.resize(targetInode, size)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun allocate(
        location: OverlayLocation,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = targetInode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val result = backend.allocate(targetInode, offset, length, mode)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun setMode(location: OverlayLocation, inode: Inode, mode: FileMode): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = targetInode.backend.setMode(targetInode, mode)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun setOwner(
        location: OverlayLocation,
        inode: Inode,
        uid: UInt?,
        gid: UInt?,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val targetInode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = targetInode.backend.setOwner(targetInode, uid, gid)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun sync(location: OverlayLocation, dataOnly: Boolean): VfsResult<Unit> {
        val targetInode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return targetInode.backend.sync(targetInode, dataOnly)
    }

    internal fun allocatedBlocks(location: OverlayLocation): ULong {
        val targetInode = (location.upper ?: location.lower)?.inode ?: return 0uL
        return targetInode.backend.allocatedBlocks(targetInode)
    }

    internal fun refreshMetadata(location: OverlayLocation, inode: Inode) {
        val metadata = (location.upper ?: location.lower)?.inode?.metadata() ?: return
        inode.updateMetadata(InodeTimestampEvent.NONE) { metadata }
    }

    internal fun getExtendedAttribute(
        location: OverlayLocation,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> {
        val inode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.getExtendedAttribute(inode, name)
    }

    internal fun listExtendedAttributes(location: OverlayLocation): VfsResult<ByteArray> {
        val inode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.listExtendedAttributes(inode)
    }

    internal fun setExtendedAttribute(
        location: OverlayLocation,
        overlayInode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val inode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = inode.backend.setExtendedAttribute(inode, name, value, mode)
        if (result is VfsResult.Ok) refreshMetadata(location, overlayInode)
        return result
    }

    internal fun removeExtendedAttribute(
        location: OverlayLocation,
        overlayInode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val inode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = inode.backend.removeExtendedAttribute(inode, name)
        if (result is VfsResult.Ok) refreshMetadata(location, overlayInode)
        return result
    }

    internal fun link(location: OverlayLocation): VfsResult<VfsPathname> {
        val target = location.upper ?: location.lower ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val inode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return (inode.backend as? SymlinkBackend)?.readLink(inode)
            ?: VfsResult.Err(VfsError.NOT_SUPPORTED)
    }

    private fun childLocation(directory: OverlayLocation, name: VfsName): OverlayLocation? {
        directory.cached(name)?.let { return it }
        val lowerHidden = whiteouts.contains(Whiteout(directory, name))
        val upper = layerChild(directory.upper, name)
        if (upper == null && lowerHidden) return null
        val lower = if (lowerHidden) null else layerChild(directory.lower, name)
        if (upper == null && lower == null) return null
        return OverlayLocation(lower, upper, directory, name).also {
            directory.cache(name, it)
        }
    }

    private fun layerChild(parent: VfsPath?, name: VfsName): VfsPath? {
        val inode = parent?.inode ?: return null
        val backend = inode.backend as? DirectoryBackend ?: return null
        val child = when (val result = backend.lookup(inode, name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> null
        } ?: return null
        return VfsPath(parent.mount, parent.dentry.cacheChild(name, child))
    }

    private fun layerEntries(path: VfsPath?): List<DirectoryEntry> {
        val inode = path?.inode ?: return emptyList()
        val backend = inode.backend as? DirectoryBackend ?: return emptyList()
        val handle = when (val result = backend.open(inode, OpenOptions())) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return emptyList()
        }
        val result = mutableListOf<DirectoryEntry>()
        try {
            handle.iterate(inode, FilePosition()) { entry, _ ->
                result += entry
                true
            }
        } finally {
            handle.release()
        }
        return result
    }

    private data class Whiteout(val directory: OverlayLocation, val name: VfsName)

    internal fun mapResult(
        result: VfsResult<Inode>,
        superBlock: SuperBlock,
        directory: OverlayLocation,
        name: VfsName,
    ): VfsResult<Inode> = when (result) {
        is VfsResult.Ok -> child(superBlock, directory, name)?.let { VfsResult.Ok(it) }
            ?: VfsResult.Err(VfsError.IO)
        is VfsResult.Err -> result
    }
}
