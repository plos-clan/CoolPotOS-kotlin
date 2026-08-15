package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

data class OverlayfsOptions(
    val lower: VfsPath,
    val upper: VfsPath,
) : FileSystemOptions

object Overlayfs : FileSystemType {
    override val name: String = "overlay"
    override val magic: ULong = 0x794c_7630uL
    override val requiresDevice: Boolean = false

    override fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock> {
        val configuration = options as? OverlayfsOptions
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (configuration.lower.inode?.type != InodeType.DIRECTORY ||
            configuration.upper.inode?.type != InodeType.DIRECTORY
        ) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val instance = OverlayInstance(configuration)
        return VfsResult.Ok(SuperBlock(this, instance) { superBlock ->
            instance.rootInode(superBlock)
        })
    }
}

private class OverlayInstance(options: OverlayfsOptions) : SuperBlockBackend {
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
            else -> UnsupportedBackend(this, location, location.type)
        }
        return Inode(InodeId(nextInodeId++), superBlock, backend, metadata).also {
            location.overlayInode = it
        }
    }

    private var nextInodeId = 1uL

    internal fun rootInode(superBlock: SuperBlock): Inode = inode(superBlock, root)

    override fun sync(): VfsResult<Unit> =
        root.upper?.mount?.superBlock?.backend?.sync() ?: VfsResult.Ok(Unit)

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

    private fun create(directory: Location, name: VfsName, mode: FileMode): VfsResult<Inode> {
        val upper = ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.create(parent, name, mode)
        if (result is VfsResult.Ok) reveal(directory, name)
        return result
    }

    private fun mkdir(directory: Location, name: VfsName, mode: FileMode): VfsResult<Inode> {
        val upper = ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.mkdir(parent, name, mode)
        if (result is VfsResult.Ok) reveal(directory, name)
        return result
    }

    private fun symlink(directory: Location, name: VfsName, target: VfsPathname): VfsResult<Inode> {
        val upper = ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val result = backend.symlink(parent, name, target)
        if (result is VfsResult.Ok) reveal(directory, name)
        return result
    }

    private fun remove(
        superBlock: SuperBlock,
        directory: Location,
        name: VfsName,
        isDirectory: Boolean,
    ): VfsResult<Unit> {
        val child = childLocation(directory, name) ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val upper = ensureUpper(directory) ?: return VfsResult.Err(VfsError.READ_ONLY)
        val parent = upper.inode ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        val backend = parent.backend as? org.plos_clan.cpos.fs.DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        if (isDirectory && entries(superBlock, child).isNotEmpty()) {
            return VfsResult.Err(VfsError.NOT_EMPTY)
        }
        val result = if (child.upper != null) {
            if (isDirectory) backend.rmdir(parent, name) else backend.unlink(parent, name)
        } else VfsResult.Ok(Unit)
        if (result is VfsResult.Err) return result
        if (child.lower != null) whiteouts += Whiteout(directory, name)
        directory.invalidate(name)
        return VfsResult.Ok(Unit)
    }

    private fun reveal(directory: Location, name: VfsName) {
        whiteouts.remove(Whiteout(directory, name))
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
        val created = when (lowerInode.type) {
            InodeType.REGULAR -> parentBackend.create(parentInode, name, lowerInode.metadata().mode)
            InodeType.DIRECTORY -> parentBackend.mkdir(parentInode, name, lowerInode.metadata().mode)
            InodeType.SYMLINK -> {
                val target = (lowerInode.backend as? org.plos_clan.cpos.fs.SymlinkBackend)?.readLink(lowerInode)
                    ?: return false
                when (target) {
                    is VfsResult.Ok -> parentBackend.symlink(parentInode, name, target.value)
                    is VfsResult.Err -> return false
                }
            }
            else -> return false
        }
        val upperInode = when (created) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> return false
        }
        location.upper = VfsPath(upperParent.mount, upperParent.dentry.cacheChild(name, upperInode))
        if (lowerInode.type == InodeType.REGULAR && !copyFile(lowerInode, upperInode)) {
            parentBackend.unlink(parentInode, name)
            upperParent.dentry.markChildNegative(name)
            location.upper = null
            return false
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
        val created = backend.mkdir(parentInode, name, lowerInode.metadata().mode)
        val inode = when (created) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> return null
        }
        return VfsPath(upperParent.mount, upperParent.dentry.cacheChild(name, inode)).also {
            location.upper = it
        }
    }

    private fun childLocation(directory: Location, name: VfsName): Location? {
        if (whiteouts.contains(Whiteout(directory, name))) return null
        directory.cached(name)?.let { return it }
        val upper = layerChild(directory.upper, name)
        val lower = layerChild(directory.lower, name)
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
        override fun create(directory: Inode, name: VfsName, mode: FileMode): VfsResult<Inode> =
            instance.mapResult(instance.create(location, name, mode), superBlock, location, name)
        override fun mkdir(directory: Inode, name: VfsName, mode: FileMode): VfsResult<Inode> =
            instance.mapResult(instance.mkdir(location, name, mode), superBlock, location, name)
        override fun symlink(directory: Inode, name: VfsName, target: VfsPathname): VfsResult<Inode> =
            instance.mapResult(instance.symlink(location, name, target), superBlock, location, name)
        override fun unlink(directory: Inode, name: VfsName): VfsResult<Unit> =
            instance.remove(superBlock, location, name, false)
        override fun rmdir(directory: Inode, name: VfsName): VfsResult<Unit> =
            instance.remove(superBlock, location, name, true)
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

    private class UnsupportedBackend(
        override val instance: OverlayInstance,
        override val location: Location,
        override val type: InodeType,
    ) : Backend {
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Err(VfsError.NOT_SUPPORTED)
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
