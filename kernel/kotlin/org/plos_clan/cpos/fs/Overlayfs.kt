package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

data class OverlayfsOptions(
    val lower: VfsPath,
    val upper: VfsPath,
) : FileSystemOptions

object Overlayfs : FileSystemType("overlay", 0x794c_7630uL) {
    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> {
        val configuration = options as? OverlayfsOptions
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return OverlayInstance.open(configuration)
    }
}

private class OverlayInstance private constructor(options: OverlayfsOptions) : SuperBlockBackend {
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
    private val root = Location(options.lower, options.upper, null, null)

    private fun inode(superBlock: SuperBlock, location: Location): Inode {
        location.overlayInode?.let { return it }
        val source = location.upper ?: location.lower ?: error("overlay inode has no layer")
        val metadata = source.inode?.metadata() ?: error("overlay layer inode is missing")
        val backend = when (location.type) {
            InodeType.DIRECTORY -> DirectoryBackend(this, superBlock, location)
            InodeType.REGULAR -> FileBackend(this, location)
            InodeType.SYMLINK -> SymlinkBackend(this, location)
            else -> SpecialBackend(this, location, location.type)
        }
        return Inode(InodeId(nextInodeId++), superBlock, backend, metadata).also {
            location.overlayInode = it
        }
    }

    private var nextInodeId = 1uL

    override fun createRoot(superBlock: SuperBlock): Inode = inode(superBlock, root)

    override fun sync(): VfsResult<Unit> =
        checkNotNull(root.upper).mount.superBlock.backend.sync()

    override fun release() {
        checkNotNull(root.upper).mount.release()
        checkNotNull(root.lower).mount.release()
    }

    private fun child(superBlock: SuperBlock, directory: Location, name: VfsName): Inode? {
        val location = childLocation(directory, name) ?: return null
        return inode(superBlock, location)
    }

    private fun entries(superBlock: SuperBlock, directory: Location): List<DirectoryEntry> {
        val names = linkedSetOf<VfsName>()
        layerEntries(directory.upper).forEach { names += it.name }
        layerEntries(directory.lower).forEach { names += it.name }
        return names.mapNotNull { name ->
            val child = childLocation(directory, name) ?: return@mapNotNull null
            val inode = inode(superBlock, child)
            DirectoryEntry(name, inode.id, inode.type)
        }
    }

    private fun create(
        directory: Location,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> {
        if (childLocation(directory, name) != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val upper = ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.create(parent, name, node)
        if (result is VfsResult.Ok) reveal(directory, name)
        return result
    }

    private fun remove(
        superBlock: SuperBlock,
        directory: Location,
        name: VfsName,
        expectedTarget: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> {
        val child = childLocation(directory, name) ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (child.overlayInode !== expectedTarget) return VfsResult.Err(VfsError.NOT_FOUND)
        val upper = ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        if (mode == RemoveMode.DIRECTORY && entries(superBlock, child).isNotEmpty()) {
            return VfsResult.Err(VfsError.NOT_EMPTY)
        }
        val upperTarget = child.upper?.inode
        val result = if (upperTarget != null) {
            backend.remove(parent, name, upperTarget, mode)
        } else VfsResult.Ok(Unit)
        if (result is VfsResult.Err) return result
        val remainingLinks = child.upper?.inode?.metadata()?.linkCount ?: 0u
        child.overlayInode?.updateMetadata { it.copy(linkCount = remainingLinks) }
        if (child.lower != null) whiteouts += Whiteout(directory, name)
        directory.invalidate(name)
        return VfsResult.Ok(Unit)
    }

    private fun link(
        directory: Location,
        name: VfsName,
        target: Location,
        overlayInode: Inode,
    ): VfsResult<Unit> {
        if (childLocation(directory, name) != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (!ensureWritable(target)) return VfsResult.Err(VfsError.READ_ONLY)
        val source = target.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val upper = ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.link(parent, name, source)
        if (result is VfsResult.Ok) {
            overlayInode.updateMetadata { it.copy(linkCount = source.metadata().linkCount) }
            reveal(directory, name)
            childLocation(directory, name)?.overlayInode = overlayInode
        }
        return result
    }

    private fun rename(
        superBlock: SuperBlock,
        sourceDirectory: Location,
        sourceName: VfsName,
        expectedSource: Inode,
        targetDirectory: Location,
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
        if (mode == RenameMode.EXCHANGE && target == null) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        if (mode != RenameMode.EXCHANGE && target != null) {
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
        if (!ensureWritable(source) ||
            (mode == RenameMode.EXCHANGE && !ensureWritable(checkNotNull(target)))
        ) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val sourceParent = ensureUpper(sourceDirectory)?.inode
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val targetParent = ensureUpper(targetDirectory)?.inode
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = sourceParent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val sourceLayerLower = layerChild(sourceDirectory.lower, sourceName)
        val targetLayerLower = layerChild(targetDirectory.lower, targetName)
        val result = backend.rename(
            sourceParent,
            sourceName,
            source.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND),
            targetParent,
            targetName,
            target?.upper?.inode,
            mode,
        )
        if (result is VfsResult.Err) return result
        val sourceOverlay = source.overlayInode
        val targetOverlay = target?.overlayInode
        if (mode != RenameMode.EXCHANGE) {
            val links = target?.upper?.inode?.metadata()?.linkCount ?: 0u
            targetOverlay?.updateMetadata { it.copy(linkCount = links) }
        }
        if (mode != RenameMode.EXCHANGE && source.lower != null) {
            whiteouts += Whiteout(sourceDirectory, sourceName)
        }
        if (mode == RenameMode.EXCHANGE) {
            val exchanged = checkNotNull(target)
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
        sourceDirectory.invalidate(sourceName)
        targetDirectory.invalidate(targetName)
        val movedSource = Location(
            source.lower,
            layerChild(targetDirectory.upper, targetName),
            targetDirectory,
            targetName,
        ).also { it.overlayInode = sourceOverlay }
        targetDirectory.cache(targetName, movedSource)
        if (mode == RenameMode.EXCHANGE) {
            val exchanged = checkNotNull(target)
            val movedTarget = Location(
                exchanged.lower,
                layerChild(sourceDirectory.upper, sourceName),
                sourceDirectory,
                sourceName,
            ).also { it.overlayInode = targetOverlay }
            sourceDirectory.cache(sourceName, movedTarget)
        }
        return VfsResult.Ok(Unit)
    }

    private fun reveal(directory: Location, name: VfsName) {
        directory.invalidate(name)
    }

    private fun open(location: Location, inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> {
        if (options.access.canWrite && !ensureWritable(location)) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = location.upper ?: location.lower ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val delegate = when (val result = targetInode.backend.open(targetInode, options)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return VfsResult.Ok(FileHandle(inode, targetInode, delegate))
    }

    private fun resize(location: Location, inode: Inode, size: ULong): VfsResult<Unit> {
        if (!ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = targetInode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val result = backend.resize(targetInode, size)
        if (result is VfsResult.Ok) inode.updateMetadata { it.copy(size = size) }
        return result
    }

    private fun allocate(
        location: Location,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (!ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = targetInode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val result = backend.allocate(targetInode, offset, length, mode)
        if (result is VfsResult.Ok) {
            inode.updateMetadata { it.copy(size = targetInode.metadata().size) }
        }
        return result
    }

    private fun setMode(location: Location, inode: Inode, mode: FileMode): VfsResult<Unit> {
        if (!ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val target = location.upper ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetInode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val result = targetInode.backend.setMode(targetInode, mode)
        if (result is VfsResult.Ok) inode.updateMetadata { it.copy(mode = mode) }
        return result
    }

    private fun sync(location: Location, dataOnly: Boolean): VfsResult<Unit> {
        val targetInode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return targetInode.backend.sync(targetInode, dataOnly)
    }

    private fun allocatedBlocks(location: Location): ULong {
        val targetInode = (location.upper ?: location.lower)?.inode ?: return 0uL
        return targetInode.backend.allocatedBlocks(targetInode)
    }

    private fun refreshMetadata(location: Location, inode: Inode) {
        val metadata = (location.upper ?: location.lower)?.inode?.metadata() ?: return
        inode.updateMetadata { metadata }
    }

    private fun getExtendedAttribute(
        location: Location,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> {
        val inode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.getExtendedAttribute(inode, name)
    }

    private fun listExtendedAttributes(location: Location): VfsResult<ByteArray> {
        val inode = (location.upper ?: location.lower)?.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.listExtendedAttributes(inode)
    }

    private fun setExtendedAttribute(
        location: Location,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> {
        if (!ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val inode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.setExtendedAttribute(inode, name, value, mode)
    }

    private fun removeExtendedAttribute(
        location: Location,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> {
        if (!ensureWritable(location)) return VfsResult.Err(VfsError.READ_ONLY)
        val inode = location.upper?.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return inode.backend.removeExtendedAttribute(inode, name)
    }

    private fun link(location: Location): VfsResult<VfsPathname> {
        val target = location.upper ?: location.lower ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val inode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return (inode.backend as? org.plos_clan.cpos.fs.SymlinkBackend)?.readLink(inode)
            ?: VfsResult.Err(VfsError.NOT_SUPPORTED)
    }

    private fun ensureWritable(location: Location): Boolean {
        if (location.upper != null) return true
        val lower = location.lower ?: return false
        val parent = location.parent ?: return false
        val name = location.name ?: return false
        val upperParent = ensureUpper(parent) ?: return false
        val parentInode = upperParent.inode ?: return false
        val parentBackend = parentInode.backend as? org.plos_clan.cpos.fs.DirectoryBackend ?: return false
        val lowerInode = lower.inode ?: return false
        val metadata = lowerInode.metadata()
        val kind = when (lowerInode.type) {
            InodeType.REGULAR -> NodeKind.Regular
            InodeType.DIRECTORY -> NodeKind.Directory
            InodeType.SYMLINK -> {
                val target = (lowerInode.backend as? org.plos_clan.cpos.fs.SymlinkBackend)?.readLink(lowerInode)
                    ?: return false
                when (target) {
                    is VfsResult.Ok -> NodeKind.SymbolicLink(target.value)
                    is VfsResult.Err -> return false
                }
            }
            InodeType.PIPE -> NodeKind.Fifo
            InodeType.SOCKET -> NodeKind.Socket
            InodeType.CHARACTER_DEVICE,
            InodeType.BLOCK_DEVICE,
            -> NodeKind.Device(lowerInode.type, metadata.deviceNumber)
        }
        val upperInode = when (val created = parentBackend.create(
            parentInode,
            name,
            NodeCreation(kind, metadata.mode, metadata.uid, metadata.gid),
        )) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> return false
        }
        val upperPath = VfsPath(upperParent.mount, upperParent.dentry.cacheChild(name, upperInode))
        location.upper = upperPath
        if (lowerInode.type == InodeType.REGULAR && !copyFile(lowerInode, upperInode)) {
            parentBackend.remove(parentInode, name, upperInode, RemoveMode.FILE)
            upperParent.dentry.markChildNegative(name, upperPath.dentry)
            location.upper = null
            return false
        }
        if (!copyExtendedAttributes(lowerInode, upperInode)) {
            parentBackend.remove(
                parentInode,
                name,
                upperInode,
                if (lowerInode.type == InodeType.DIRECTORY) RemoveMode.DIRECTORY else RemoveMode.FILE,
            )
            upperParent.dentry.markChildNegative(name, upperPath.dentry)
            location.upper = null
            return false
        }
        return true
    }

    private fun copyExtendedAttributes(source: Inode, destination: Inode): Boolean {
        val names = when (val result = source.backend.listExtendedAttributes(source)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result.error == VfsError.NOT_SUPPORTED
        }
        var offset = 0
        while (offset < names.size) {
            var end = offset
            while (end < names.size && names[end] != 0.toByte()) end++
            val name = when (val result = ExtendedAttributeName.fromBytes(names.copyOfRange(offset, end))) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return false
            }
            val value = when (val result = source.backend.getExtendedAttribute(source, name)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return false
            }
            if (destination.backend.setExtendedAttribute(
                destination,
                name,
                value,
                ExtendedAttributeMode.CREATE,
            ) is VfsResult.Err) return false
            offset = end + 1
        }
        return true
    }

    private fun copyFile(source: Inode, destination: Inode): Boolean {
        val sourceHandle = when (val result = source.backend.open(source, OpenOptions())) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return false
        }
        val destinationHandle = when (val result = destination.backend.open(
            destination, OpenOptions(access = AccessMode.WRITE)
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                sourceHandle.release()
                return false
            }
        }
        return try {
            val size = source.metadata().size
            if (size > Long.MAX_VALUE.toULong()) return false
            val buffer = ByteArray(8192)
            val transferBuffer = ByteArrayBuffer(buffer)
            val destinationBuffer = checkNotNull(transferBuffer.prepareWrite(0, buffer.size))
            val sourceBuffer = checkNotNull(transferBuffer.prepareRead(0, buffer.size))
            var position = FilePosition()
            var copied = 0uL
            while (copied < size) {
                val count = minOf(buffer.size.toULong(), size - copied).toInt()
                val read = sourceHandle.read(source, destinationBuffer, 0, count, position)
                if (!read.isSuccess || read.bytesTransferred == 0) break
                val write = destinationHandle.write(
                    destination,
                    sourceBuffer,
                    0,
                    read.bytesTransferred,
                    FilePosition(copied.toLong()),
                    false,
                )
                if (!write.isSuccess || write.bytesTransferred != read.bytesTransferred) break
                copied += read.bytesTransferred.toULong()
            }
            copied == size
        } finally {
            destinationHandle.release()
            sourceHandle.release()
        }
    }

    private fun ensureUpper(location: Location): VfsPath? {
        location.upper?.let { return it }
        val parent = location.parent ?: return null
        val upperParent = ensureUpper(parent) ?: return null
        val parentInode = upperParent.inode ?: return null
        val backend = parentInode.backend as? org.plos_clan.cpos.fs.DirectoryBackend ?: return null
        val lowerInode = location.lower?.inode ?: return null
        val name = location.name ?: return null
        val metadata = lowerInode.metadata()
        val created = backend.create(
            parentInode,
            name,
            NodeCreation(NodeKind.Directory, metadata.mode, metadata.uid, metadata.gid),
        )
        val inode = when (created) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> return null
        }
        if (!copyExtendedAttributes(lowerInode, inode)) {
            backend.remove(parentInode, name, inode, RemoveMode.DIRECTORY)
            return null
        }
        return VfsPath(upperParent.mount, upperParent.dentry.cacheChild(name, inode)).also {
            location.upper = it
        }
    }

    private fun childLocation(directory: Location, name: VfsName): Location? {
        directory.cached(name)?.let { return it }
        val lowerHidden = whiteouts.contains(Whiteout(directory, name))
        val upper = layerChild(directory.upper, name)
        if (upper == null && lowerHidden) return null
        val lower = if (lowerHidden) null else layerChild(directory.lower, name)
        if (upper == null && lower == null) return null
        return Location(lower, upper, directory, name).also {
            directory.cache(name, it)
        }
    }

    private fun layerChild(parent: VfsPath?, name: VfsName): VfsPath? {
        val inode = parent?.inode ?: return null
        val backend = inode.backend as? org.plos_clan.cpos.fs.DirectoryBackend ?: return null
        val child = when (val result = backend.lookup(inode, name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> null
        } ?: return null
        return VfsPath(parent.mount, parent.dentry.cacheChild(name, child))
    }

    private fun layerEntries(path: VfsPath?): List<DirectoryEntry> {
        val inode = path?.inode ?: return emptyList()
        val backend = inode.backend as? org.plos_clan.cpos.fs.DirectoryBackend ?: return emptyList()
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

    private data class Whiteout(val directory: Location, val name: VfsName)

    private class Location(
        val lower: VfsPath?,
        var upper: VfsPath?,
        val parent: Location?,
        val name: VfsName?,
    ) {
        private val children = mutableMapOf<VfsName, Location>()
        var overlayInode: Inode? = null

        val type: InodeType
            get() = (upper ?: lower)?.inode?.type ?: InodeType.REGULAR

        fun cached(name: VfsName): Location? = children[name]

        fun cache(name: VfsName, location: Location) {
            children[name] = location
        }

        fun invalidate(name: VfsName) {
            children.remove(name)
        }
    }

    private interface Backend : InodeBackend {
        val instance: OverlayInstance
        val location: Location

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

    private class DirectoryBackend(
        override val instance: OverlayInstance,
        private val superBlock: SuperBlock,
        override val location: Location,
    ) : org.plos_clan.cpos.fs.DirectoryBackend, Backend {
        override val type: InodeType = InodeType.DIRECTORY
        override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
            VfsResult.Ok(instance.child(superBlock, location, name))
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Ok(Handle(instance.entries(superBlock, location)))
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
            val source = (target.backend as? Backend)?.location
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
            val destination = (targetDirectory.backend as? DirectoryBackend)?.location
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

    private class Handle(
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

    private class FileBackend(
        override val instance: OverlayInstance,
        override val location: Location,
    ) : RegularFileBackend(), Backend {
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

    private class FileHandle(
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

    private class SymlinkBackend(
        override val instance: OverlayInstance,
        override val location: Location,
    ) : org.plos_clan.cpos.fs.SymlinkBackend, Backend {
        override val type: InodeType = InodeType.SYMLINK
        override fun readLink(inode: Inode): VfsResult<VfsPathname> =
            (location.upper ?: location.lower)?.inode?.let { source ->
                (source.backend as? org.plos_clan.cpos.fs.SymlinkBackend)?.readLink(source)
            } ?: VfsResult.Err(VfsError.NOT_FOUND)
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
    }

    private class SpecialBackend(
        override val instance: OverlayInstance,
        override val location: Location,
        override val type: InodeType,
    ) : Backend {
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            instance.open(location, inode, options)
    }

    private fun mapResult(
        result: VfsResult<Inode>,
        superBlock: SuperBlock,
        directory: Location,
        name: VfsName,
    ): VfsResult<Inode> = when (result) {
        is VfsResult.Ok -> child(superBlock, directory, name)?.let { VfsResult.Ok(it) }
            ?: VfsResult.Err(VfsError.IO)
        is VfsResult.Err -> result
    }
}
