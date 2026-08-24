package org.plos_clan.cpos.fs.overlay

import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.DirectoryBackend
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeMode
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeName
import org.plos_clan.cpos.fs.vfs.FileAllocationMode
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
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
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
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
        val attributes = InodeAttributeSnapshot(InodeAttributes(metadata), CacheValidity.Volatile)
        return Inode(InodeId(nextInodeId++), superBlock, backend, attributes).also {
            location.overlayInode = it
        }
    }

    private var nextInodeId = 1uL

    override fun createRoot(superBlock: SuperBlock): Inode = inode(superBlock, root)

    override fun updateTimestamps(
        caller: VfsOperationContext,
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
        if (update is InodeTimestampSet && !OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: location.lower
            ?: return VfsResult.Err(VfsError.READ_ONLY)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = target.mount.superBlock.backend.updateTimestamps(caller, targetInode, update)
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

    override fun sync(caller: VfsOperationContext): VfsResult<Unit> =
        checkNotNull(root.upper).mount.superBlock.backend.sync(caller)

    override fun release() {
        checkNotNull(root.upper).mount.release()
        checkNotNull(root.lower).mount.release()
    }

    internal fun child(
        caller: VfsOperationContext,
        superBlock: SuperBlock,
        directory: OverlayLocation,
        name: VfsName,
    ): Inode? {
        val location = childLocation(caller, directory, name) ?: return null
        return inode(superBlock, location)
    }

    internal fun entries(
        caller: VfsOperationContext,
        superBlock: SuperBlock,
        directory: OverlayLocation,
    ): List<DirectoryEntry> {
        val names = linkedSetOf<VfsName>()
        layerEntries(caller, directory.upper).forEach { names += it.name }
        layerEntries(caller, directory.lower).forEach { names += it.name }
        return names.mapNotNull { name ->
            val child = childLocation(caller, directory, name) ?: return@mapNotNull null
            val inode = inode(superBlock, child)
            DirectoryEntry(name, inode.id, inode.type)
        }
    }

    internal fun create(
        caller: VfsOperationContext,
        directory: OverlayLocation,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> {
        if (childLocation(caller, directory, name) != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val upper = OverlayCopyUp.ensureUpper(caller, directory)
            ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.create(caller, parent, name, node)
        if (result is VfsResult.Ok) reveal(directory, name)
        return result
    }

    internal fun remove(
        caller: VfsOperationContext,
        superBlock: SuperBlock,
        directory: OverlayLocation,
        name: VfsName,
        expectedTarget: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> {
        val child = childLocation(caller, directory, name)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (child.overlayInode !== expectedTarget) return VfsResult.Err(VfsError.NOT_FOUND)
        val upper = OverlayCopyUp.ensureUpper(caller, directory)
            ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        if (mode == RemoveMode.DIRECTORY && entries(caller, superBlock, child).isNotEmpty()) {
            return VfsResult.Err(VfsError.NOT_EMPTY)
        }
        val upperTarget = child.upper?.inode
        val result = if (upperTarget != null) {
            backend.remove(caller, parent, name, upperTarget, mode)
        } else VfsResult.Ok(Unit)
        if (result is VfsResult.Err) return result
        if (upperTarget == null) {
            val timestampResult = parent.superBlock.backend.updateTimestamps(
                caller,
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
        caller: VfsOperationContext,
        directory: OverlayLocation,
        name: VfsName,
        target: OverlayLocation,
        overlayInode: Inode,
    ): VfsResult<Unit> {
        if (childLocation(caller, directory, name) != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (!OverlayCopyUp.ensureWritable(caller, target)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val source = target.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val upper = OverlayCopyUp.ensureUpper(caller, directory)
            ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.link(caller, parent, name, source)
        if (result is VfsResult.Ok) {
            val metadata = source.metadata()
            overlayInode.updateMetadata(InodeTimestampEvent.NONE) {
                it.copy(linkCount = metadata.linkCount, timestamps = metadata.timestamps)
            }
            reveal(directory, name)
            childLocation(caller, directory, name)?.overlayInode = overlayInode
        }
        return result
    }

    internal fun rename(
        caller: VfsOperationContext,
        superBlock: SuperBlock,
        sourceDirectory: OverlayLocation,
        sourceName: VfsName,
        expectedSource: Inode,
        targetDirectory: OverlayLocation,
        targetName: VfsName,
        expectedTarget: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> {
        val source = childLocation(caller, sourceDirectory, sourceName)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val target = childLocation(caller, targetDirectory, targetName)
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
            if (target.type == InodeType.DIRECTORY &&
                entries(caller, superBlock, target).isNotEmpty()
            ) {
                return VfsResult.Err(VfsError.NOT_EMPTY)
            }
        }
        if (!OverlayCopyUp.ensureWritable(caller, source)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        if (exchanged != null && !OverlayCopyUp.ensureWritable(caller, exchanged)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val sourceUpper = source.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetUpper = target?.upper
        val sourceParentPath = OverlayCopyUp.ensureUpper(caller, sourceDirectory)
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val targetParentPath = OverlayCopyUp.ensureUpper(caller, targetDirectory)
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val sourceParent = sourceParentPath.inode
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val targetParent = targetParentPath.inode
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = sourceParent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val sourceLayerLower = layerChild(caller, sourceDirectory.lower, sourceName)
        val targetLayerLower = layerChild(caller, targetDirectory.lower, targetName)
        val result = backend.rename(
            caller,
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

    internal fun open(
        caller: VfsOperationContext,
        location: OverlayLocation,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> {
        if (options.access.canWrite && !OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: location.lower ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val delegate = when (val result = targetInode.backend.open(caller, targetInode, options)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return VfsResult.Ok(OverlayFileHandle(inode, targetInode, delegate))
    }

    internal fun resize(
        caller: VfsOperationContext,
        location: OverlayLocation,
        inode: Inode,
        size: ULong,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = targetInode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val result = backend.resize(caller, targetInode, size)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun allocate(
        caller: VfsOperationContext,
        location: OverlayLocation,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = targetInode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val result = backend.allocate(caller, targetInode, offset, length, mode)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun setMode(
        caller: VfsOperationContext,
        location: OverlayLocation,
        inode: Inode,
        mode: FileMode,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = targetInode.backend.setMode(caller, targetInode, mode)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun setOwner(
        caller: VfsOperationContext,
        location: OverlayLocation,
        inode: Inode,
        uid: UInt?,
        gid: UInt?,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val targetInode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = targetInode.backend.setOwner(caller, targetInode, uid, gid)
        if (result is VfsResult.Ok) refreshMetadata(location, inode)
        return result
    }

    internal fun sync(
        caller: VfsOperationContext,
        location: OverlayLocation,
        dataOnly: Boolean,
    ): VfsResult<Unit> {
        val targetInode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return targetInode.backend.sync(caller, targetInode, dataOnly)
    }

    internal fun pageCacheIdentity(location: OverlayLocation): Any {
        val inode = (location.upper ?: location.lower)?.inode ?: return location
        return inode.backend.pageCacheIdentity(inode)
    }

    internal fun loadAttributes(
        caller: VfsOperationContext,
        location: OverlayLocation,
    ): VfsResult<InodeAttributeSnapshot> {
        val target = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return target.attributeSnapshot(caller)
    }

    internal fun checkAccess(
        caller: VfsOperationContext,
        location: OverlayLocation,
        requested: AccessPermissions,
    ): VfsResult<Unit> {
        val target = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return target.backend.checkAccess(caller, target, requested)
    }

    internal fun refreshMetadata(location: OverlayLocation, inode: Inode) {
        val metadata = (location.upper ?: location.lower)?.inode?.metadata() ?: return
        inode.updateMetadata(InodeTimestampEvent.NONE) { metadata }
    }

    internal fun getExtendedAttribute(
        caller: VfsOperationContext,
        location: OverlayLocation,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> {
        val inode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.getExtendedAttribute(caller, inode, name)
    }

    internal fun listExtendedAttributes(
        caller: VfsOperationContext,
        location: OverlayLocation,
    ): VfsResult<ByteArray> {
        val inode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.listExtendedAttributes(caller, inode)
    }

    internal fun setExtendedAttribute(
        caller: VfsOperationContext,
        location: OverlayLocation,
        overlayInode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val inode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = inode.backend.setExtendedAttribute(caller, inode, name, value, mode)
        if (result is VfsResult.Ok) refreshMetadata(location, overlayInode)
        return result
    }

    internal fun removeExtendedAttribute(
        caller: VfsOperationContext,
        location: OverlayLocation,
        overlayInode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> {
        if (!OverlayCopyUp.ensureWritable(caller, location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val inode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = inode.backend.removeExtendedAttribute(caller, inode, name)
        if (result is VfsResult.Ok) refreshMetadata(location, overlayInode)
        return result
    }

    internal fun link(
        caller: VfsOperationContext,
        location: OverlayLocation,
    ): VfsResult<VfsPathname> {
        val target = location.upper ?: location.lower ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val inode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return (inode.backend as? SymlinkBackend)?.readLink(caller, inode)
            ?: VfsResult.Err(VfsError.NOT_SUPPORTED)
    }

    private fun childLocation(
        caller: VfsOperationContext,
        directory: OverlayLocation,
        name: VfsName,
    ): OverlayLocation? {
        val lowerHidden = whiteouts.contains(Whiteout(directory, name))
        val upper = layerChild(caller, directory.upper, name)
        val lower = if (lowerHidden) null else layerChild(caller, directory.lower, name)
        if (upper == null && lower == null) {
            directory.invalidate(name)
            return null
        }
        directory.cached(name)?.let { cached ->
            cached.upper = upper
            cached.lower = lower
            return cached
        }
        return OverlayLocation(lower, upper, directory, name).also {
            directory.cache(name, it)
        }
    }

    private fun layerChild(
        caller: VfsOperationContext,
        parent: VfsPath?,
        name: VfsName,
    ): VfsPath? {
        val inode = parent?.inode ?: return null
        val backend = inode.backend as? DirectoryBackend ?: return null
        parent.dentry.cachedChild(name)?.let { cached ->
            return cached.inode()?.let { VfsPath(parent.mount, cached) }
        }
        val lookup = when (val result = backend.lookup(caller, inode, name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> null
        } ?: return null
        val dentry = parent.dentry.cacheChild(name, lookup)
        return lookup.inode?.let { VfsPath(parent.mount, dentry) }
    }

    private fun layerEntries(
        caller: VfsOperationContext,
        path: VfsPath?,
    ): List<DirectoryEntry> {
        val inode = path?.inode ?: return emptyList()
        val backend = inode.backend as? DirectoryBackend ?: return emptyList()
        val handle = when (val result = backend.open(caller, inode, OpenOptions())) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return emptyList()
        }
        val result = mutableListOf<DirectoryEntry>()
        try {
            handle.iterate(caller, inode, FilePosition()) { entry, _ ->
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
        caller: VfsOperationContext,
        result: VfsResult<Inode>,
        superBlock: SuperBlock,
        directory: OverlayLocation,
        name: VfsName,
    ): VfsResult<Inode> = when (result) {
        is VfsResult.Ok -> child(caller, superBlock, directory, name)?.let { VfsResult.Ok(it) }
            ?: VfsResult.Err(VfsError.IO)
        is VfsResult.Err -> result
    }
}
