package org.plos_clan.cpos.fs

import org.plos_clan.cpos.utils.IrqSpinLock

internal class TmpfsDirectory(
    private val fileSystem: TmpfsInstance,
    private val automatic: Boolean,
    private var parent: Inode?,
) : DirectoryBackend, MutableInodeBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override fun isLookupStable(name: VfsName, inode: Inode?): Boolean =
        fileSystem.cacheDirectoryLookups

    private val lock = IrqSpinLock()
    private val children = linkedMapOf<VfsName, Inode>()

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        lock.withLock { VfsResult.Ok(children[name]) }

    override fun create(
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> = fileSystem.mutate {
        lock.withLock {
            if (children.containsKey(name)) {
                return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
            }
            if (node.kind == NodeKind.Directory &&
                directory.metadata().linkCount == UInt.MAX_VALUE
            ) {
                return@withLock VfsResult.Err(VfsError.TOO_MANY_LINKS)
            }
            val inode = fileSystem.newNode(directory.superBlock, node, directory)
            children[name] = inode
            if (node.kind == NodeKind.Directory) {
                directory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
            }
            VfsResult.Ok(inode)
        }
    }

    override fun link(directory: Inode, name: VfsName, target: Inode): VfsResult<Unit> =
        fileSystem.mutate {
            lock.withLock {
                if (children.containsKey(name)) {
                    return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
                }
                val links = target.metadata().linkCount
                if (links == UInt.MAX_VALUE) {
                    return@withLock VfsResult.Err(VfsError.TOO_MANY_LINKS)
                }
                children[name] = target
                target.updateMetadata { it.copy(linkCount = links + 1u) }
                VfsResult.Ok(Unit)
            }
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
        val targetBackend = targetDirectory.backend as? TmpfsDirectory
            ?: return VfsResult.Err(VfsError.CROSS_DEVICE)
        if (targetBackend.fileSystem !== fileSystem) return VfsResult.Err(VfsError.CROSS_DEVICE)
        return fileSystem.mutate {
            withLocks(sourceDirectory, targetDirectory, targetBackend) {
                renameLocked(
                    sourceDirectory,
                    sourceName,
                    source,
                    targetDirectory,
                    targetName,
                    targetBackend,
                    target,
                    mode,
                )
            }
        }
    }

    override fun remove(
        directory: Inode,
        name: VfsName,
        target: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> = fileSystem.mutate {
        lock.withLock {
            val inode = children[name] ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            if (inode !== target) return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            if (mode == RemoveMode.DIRECTORY) {
                val child = inode.backend as? TmpfsDirectory
                    ?: return@withLock VfsResult.Err(VfsError.NOT_DIRECTORY)
                if (!child.isEmpty()) return@withLock VfsResult.Err(VfsError.NOT_EMPTY)
                directory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                child.parent = null
                inode.updateMetadata { it.copy(linkCount = 0u) }
            } else {
                if (inode.type == InodeType.DIRECTORY) {
                    return@withLock VfsResult.Err(VfsError.IS_DIRECTORY)
                }
                inode.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            }
            children.remove(name)
            VfsResult.Ok(Unit)
        }
    }

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(TmpfsDirectoryHandle(this))

    fun snapshot(): List<DirectoryEntry> = lock.withLock {
        children.map { (name, inode) -> DirectoryEntry(name, inode.id, inode.type) }
    }

    fun installSpecialNode(
        directory: Inode,
        path: List<VfsName>,
        index: Int,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Boolean = lock.withLock {
        val name = path[index]
        if (index == path.lastIndex) {
            if (children.containsKey(name)) return@withLock false
            children[name] = fileSystem.newSpecialNode(directory.superBlock, backend, metadata)
            return@withLock true
        }

        var child = children[name]
        var created = false
        if (child == null) {
            child = fileSystem.newDirectory(
                directory.superBlock,
                FileMode(0x1EDu),
                automatic = true,
                parent = directory,
            )
            children[name] = child
            directory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
            created = true
        }
        val childDirectory = child.backend as? TmpfsDirectory ?: return@withLock false
        val installed = childDirectory.installSpecialNode(
            child,
            path,
            index + 1,
            backend,
            metadata,
        )
        if (!installed && created && childDirectory.isEmpty()) {
            children.remove(name)
            directory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            childDirectory.parent = null
            child.updateMetadata { it.copy(linkCount = 0u) }
        }
        installed
    }

    fun removeSpecialNode(
        directory: Inode,
        path: List<VfsName>,
        index: Int,
        matches: (InodeBackend) -> Boolean,
    ): Boolean = lock.withLock {
        val name = path[index]
        val child = children[name] ?: return@withLock false
        if (index == path.lastIndex) {
            if (!matches(child.backend)) return@withLock false
            children.remove(name)
            child.updateMetadata { it.copy(linkCount = 0u) }
            return@withLock true
        }

        val childDirectory = child.backend as? TmpfsDirectory ?: return@withLock false
        val removed = childDirectory.removeSpecialNode(
            child,
            path,
            index + 1,
            matches,
        )
        if (removed && childDirectory.automatic && childDirectory.isEmpty()) {
            children.remove(name)
            directory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            childDirectory.parent = null
            child.updateMetadata { it.copy(linkCount = 0u) }
        }
        removed
    }

    private fun isEmpty(): Boolean = lock.withLock { children.isEmpty() }

    private fun renameLocked(
        sourceDirectory: Inode,
        sourceName: VfsName,
        expectedSource: Inode,
        targetDirectory: Inode,
        targetName: VfsName,
        target: TmpfsDirectory,
        expectedTarget: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> {
        val source = children[sourceName] ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (source !== expectedSource) return VfsResult.Err(VfsError.NOT_FOUND)
        val replaced = target.children[targetName]
        if (replaced !== expectedTarget) return VfsResult.Err(VfsError.NOT_FOUND)
        if (this === target && sourceName == targetName) return VfsResult.Ok(Unit)
        if (mode == RenameMode.NO_REPLACE && replaced != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (source === replaced) return VfsResult.Ok(Unit)
        if (source.type == InodeType.DIRECTORY && isWithin(targetDirectory, source)) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (mode == RenameMode.EXCHANGE) {
            val exchanged = replaced ?: return VfsResult.Err(VfsError.NOT_FOUND)
            if (exchanged.type == InodeType.DIRECTORY && isWithin(sourceDirectory, exchanged)) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (this !== target) {
                val gainsDirectory = if (source.type == InodeType.DIRECTORY) {
                    targetDirectory.takeIf { exchanged.type != InodeType.DIRECTORY }
                } else {
                    sourceDirectory.takeIf { exchanged.type == InodeType.DIRECTORY }
                }
                if (gainsDirectory?.metadata()?.linkCount == UInt.MAX_VALUE) {
                    return VfsResult.Err(VfsError.TOO_MANY_LINKS)
                }
            }
            children[sourceName] = exchanged
            target.children[targetName] = source
            if (this !== target) {
                val sourceIsDirectory = source.type == InodeType.DIRECTORY
                val targetIsDirectory = exchanged.type == InodeType.DIRECTORY
                if (sourceIsDirectory) {
                    (source.backend as TmpfsDirectory).parent = targetDirectory
                }
                if (targetIsDirectory) {
                    (exchanged.backend as TmpfsDirectory).parent = sourceDirectory
                }
                if (sourceIsDirectory != targetIsDirectory) {
                    if (sourceIsDirectory) {
                        sourceDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                        targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
                    } else {
                        sourceDirectory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
                        targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                    }
                }
            }
            return VfsResult.Ok(Unit)
        }

        if (replaced != null) {
            val sourceIsDirectory = source.type == InodeType.DIRECTORY
            if (sourceIsDirectory != (replaced.type == InodeType.DIRECTORY)) {
                return VfsResult.Err(
                    if (sourceIsDirectory) VfsError.NOT_DIRECTORY else VfsError.IS_DIRECTORY,
                )
            }
            val replacedDirectory = replaced.backend as? TmpfsDirectory
            if (replacedDirectory != null && !replacedDirectory.isEmpty()) {
                return VfsResult.Err(VfsError.NOT_EMPTY)
            }
        }
        if (source.type == InodeType.DIRECTORY && this !== target && replaced == null &&
            targetDirectory.metadata().linkCount == UInt.MAX_VALUE
        ) {
            return VfsResult.Err(VfsError.TOO_MANY_LINKS)
        }

        children.remove(sourceName)
        target.children[targetName] = source
        if (source.type == InodeType.DIRECTORY && this !== target) {
            (source.backend as TmpfsDirectory).parent = targetDirectory
            sourceDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
        }
        if (replaced != null) {
            if (replaced.type == InodeType.DIRECTORY) {
                targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                (replaced.backend as TmpfsDirectory).parent = null
                replaced.updateMetadata { it.copy(linkCount = 0u) }
            } else {
                replaced.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            }
        }
        return VfsResult.Ok(Unit)
    }

    private fun isWithin(directory: Inode, ancestor: Inode): Boolean {
        var current: Inode? = directory
        while (current != null) {
            if (current === ancestor) return true
            current = (current.backend as? TmpfsDirectory)?.parent
        }
        return false
    }

    private fun <T> withLocks(
        source: Inode,
        target: Inode,
        targetBackend: TmpfsDirectory,
        operation: () -> T,
    ): T {
        if (this === targetBackend) return lock.withLock(operation)
        return if (source.id.value < target.id.value) {
            lock.withLock { targetBackend.lock.withLock(operation) }
        } else {
            targetBackend.lock.withLock { lock.withLock(operation) }
        }
    }
}

private class TmpfsDirectoryHandle(private val directory: TmpfsDirectory) : OpenFileBackend {
    override fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        val entries = directory.snapshot()
        if (position.value > Int.MAX_VALUE) {
            return VfsResult.Ok(Unit)
        }
        var index = position.value.coerceAtLeast(0).toInt()
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
