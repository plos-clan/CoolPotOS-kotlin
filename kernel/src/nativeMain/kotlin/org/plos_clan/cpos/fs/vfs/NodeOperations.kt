package org.plos_clan.cpos.fs.vfs

internal data class OpenedPath(
    val path: VfsPath,
    val created: Boolean,
    val backend: OpenFileBackend? = null,
) {
    fun reject(error: VfsError): VfsResult<Nothing> {
        backend?.release()
        return VfsResult.Err(error)
    }
}

internal class VfsNodeOperations(
    private val paths: VfsPathResolver,
) {
    fun createFile(
        caller: VfsOperationContext,
        directory: VfsPath,
        name: VfsName,
        mode: FileMode,
        content: FileContent,
        contentOffset: Int,
        contentSize: Int,
    ): VfsResult<VfsPath> {
        val path = when (val result = createChild(
            caller,
            directory,
            name,
            NodeCreation(
                NodeKind.Regular,
                mode
            ),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backed = inode.backend as? ContentBackedFile
        if (backed?.attachContent(inode, content, contentOffset, contentSize) == true) {
            return VfsResult.Ok(path)
        }

        val parent = directory.inode ?: return VfsResult.Err(VfsError.IO)
        val backend = parent.backend as? DirectoryBackend ?: return VfsResult.Err(VfsError.IO)
        backend.remove(caller, parent, name, inode, RemoveMode.FILE)
        directory.dentry.markChildNegative(name, path.dentry)
        return VfsResult.Err(VfsError.IO)
    }

    fun createNode(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> {
        if (pathname.isRoot) return VfsResult.Err(VfsError.ALREADY_EXISTS)
        val parent = when (
            val result = paths.resolveParent(caller, context, directory, pathname)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (pathname.requiresDirectory && node.kind != NodeKind.Directory &&
            !parent.name.isDot && !parent.name.isDotDot
        ) {
            return when (
                val existing = paths.lookupChild(caller, context, parent.path, parent.name)
            ) {
                is VfsResult.Ok -> VfsResult.Err(VfsError.ALREADY_EXISTS)
                is VfsResult.Err -> existing
            }
        }
        return createChild(caller, parent.path, parent.name, node)
    }

    fun createNode(
        caller: VfsOperationContext,
        context: FileSystemContext,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> = createNode(
        caller,
        context,
        context.workingDirectory,
        pathname,
        node,
    )

    fun remove(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        mode: RemoveMode,
    ): VfsResult<Unit> {
        if (pathname.isRoot) return VfsResult.Err(VfsError.BUSY)
        val parent = when (
            val result = paths.resolveParent(caller, context, directory, pathname)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.name.isDot || parent.name.isDotDot) {
            return VfsResult.Err(
                when (mode) {
                    RemoveMode.FILE -> VfsError.IS_DIRECTORY
                    RemoveMode.DIRECTORY -> if (parent.name.isDot) {
                        VfsError.INVALID_ARGUMENT
                    } else {
                        VfsError.NOT_EMPTY
                    }
                },
            )
        }
        if (MountFlag.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val target = when (
            val result = paths.lookupChild(
                caller,
                context,
                parent.path,
                parent.name,
                followMount = false,
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (context.namespace.mountedAt(target) != null) {
            return VfsResult.Err(VfsError.BUSY)
        }
        val inode = target.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (pathname.requiresDirectory && inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val removesDirectory = mode == RemoveMode.DIRECTORY
        if (removesDirectory != (inode.type == InodeType.DIRECTORY)) {
            return VfsResult.Err(
                if (removesDirectory) VfsError.NOT_DIRECTORY else VfsError.IS_DIRECTORY,
            )
        }
        val parentInode = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parentInode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        when (val access = backend.checkAccess(
            caller,
            parentInode,
            AccessPermissions.WRITE_AND_EXECUTE,
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        val result = backend.remove(caller, parentInode, parent.name, inode, mode)
        if (result is VfsResult.Ok) {
            parent.path.dentry.markChildNegative(parent.name, target.dentry)
        }
        return result
    }

    fun link(
        caller: VfsOperationContext,
        context: FileSystemContext,
        sourceMount: Mount,
        sourceInode: Inode,
        targetDirectory: VfsPath,
        target: VfsPathname,
    ): VfsResult<Unit> {
        if (target.isRoot) return VfsResult.Err(VfsError.ALREADY_EXISTS)
        if (sourceInode.type == InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        val parent = when (
            val result = paths.resolveParent(caller, context, targetDirectory, target)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.name.isDot || parent.name.isDotDot) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (target.requiresDirectory) {
            return when (
                val existing = paths.lookupChild(caller, context, parent.path, parent.name)
            ) {
                is VfsResult.Ok -> VfsResult.Err(VfsError.ALREADY_EXISTS)
                is VfsResult.Err -> existing
            }
        }
        if (sourceMount !== parent.path.mount) {
            return VfsResult.Err(VfsError.CROSS_DEVICE)
        }
        if (MountFlag.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val parentInode = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parentInode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        when (val access = backend.checkAccess(
            caller,
            parentInode,
            AccessPermissions.WRITE_AND_EXECUTE,
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        val result = backend.linkEntry(caller, parentInode, parent.name, sourceInode)
        if (result is VfsResult.Ok) {
            parent.path.dentry.cacheChild(parent.name, result.value)
        }
        return when (result) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> result
        }
    }

    fun rename(
        caller: VfsOperationContext,
        context: FileSystemContext,
        sourceDirectory: VfsPath,
        source: VfsPathname,
        targetDirectory: VfsPath,
        target: VfsPathname,
        mode: RenameMode,
    ): VfsResult<Unit> {
        if (source.isRoot || target.isRoot) return VfsResult.Err(VfsError.BUSY)
        val sourceParent = when (
            val result = paths.resolveParent(caller, context, sourceDirectory, source)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val targetParent = when (
            val result = paths.resolveParent(caller, context, targetDirectory, target)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (sourceParent.name.isDot || sourceParent.name.isDotDot ||
            targetParent.name.isDot || targetParent.name.isDotDot
        ) {
            return VfsResult.Err(VfsError.BUSY)
        }
        if (sourceParent.path.mount !== targetParent.path.mount) {
            return VfsResult.Err(VfsError.CROSS_DEVICE)
        }
        if (MountFlag.READ_ONLY in sourceParent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val sourcePath = when (
            val result = paths.lookupChild(
                caller,
                context,
                sourceParent.path,
                sourceParent.name,
                followMount = false,
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (context.namespace.mountedAt(sourcePath) != null) {
            return VfsResult.Err(VfsError.BUSY)
        }
        val sourceInode = sourcePath.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (source.requiresDirectory && sourceInode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        if (sourceInode.type == InodeType.DIRECTORY &&
            paths.isDescendant(targetParent.path.dentry, sourcePath.dentry)
        ) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val targetPath = when (
            val result = paths.lookupChild(
                caller,
                context,
                targetParent.path,
                targetParent.name,
                followMount = false,
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> if (result.error == VfsError.NOT_FOUND) null else return result
        }
        if (target.requiresDirectory && targetPath?.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(
                if (targetPath == null) VfsError.NOT_FOUND else VfsError.NOT_DIRECTORY,
            )
        }
        if (targetPath != null && context.namespace.mountedAt(targetPath) != null) {
            return VfsResult.Err(VfsError.BUSY)
        }
        if (mode == RenameMode.EXCHANGE && targetPath == null) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        if (mode == RenameMode.NO_REPLACE && targetPath != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (mode == RenameMode.EXCHANGE && targetPath?.inode?.type == InodeType.DIRECTORY &&
            paths.isDescendant(sourceParent.path.dentry, targetPath.dentry)
        ) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (targetPath?.inode === sourceInode) return VfsResult.Ok(Unit)
        val sourceParentInode = sourceParent.path.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetParentInode = targetParent.path.inode
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = sourceParentInode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        when (val access = backend.checkAccess(
            caller,
            sourceParentInode,
            AccessPermissions.WRITE_AND_EXECUTE,
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        if (targetParentInode !== sourceParentInode) {
            when (val access = targetParentInode.backend.checkAccess(
                caller,
                targetParentInode,
                AccessPermissions.WRITE_AND_EXECUTE,
            )) {
                is VfsResult.Ok -> Unit
                is VfsResult.Err -> return access
            }
        }
        val result = backend.rename(
            caller,
            sourceParentInode,
            sourceParent.name,
            sourceInode,
            targetParentInode,
            targetParent.name,
            targetPath?.inode,
            mode,
        )
        if (result is VfsResult.Ok) {
            sourceParent.path.dentry.renameChild(
                sourcePath.dentry,
                targetParent.path.dentry,
                targetParent.name,
                targetPath?.dentry.takeIf { mode == RenameMode.EXCHANGE },
            )
        }
        return result
    }

    fun openOrCreate(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        options: OpenOptions,
    ): VfsResult<OpenedPath> {
        if (pathname.isRoot) {
            return if (options.create == CreateDisposition.CREATE_NEW) {
                VfsResult.Err(VfsError.ALREADY_EXISTS)
            } else {
                resolveExisting(caller, context, directory, pathname, options.followFinalSymlink)
            }
        }
        val parent = when (
            val result = paths.resolveParent(caller, context, directory, pathname)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.name.isDot || parent.name.isDotDot) {
            return if (options.create == CreateDisposition.CREATE_NEW) {
                VfsResult.Err(VfsError.ALREADY_EXISTS)
            } else {
                resolveExisting(caller, context, directory, pathname, options.followFinalSymlink)
            }
        }

        val parentInode = parent.path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parentInode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)

        when (val existing = paths.lookupChild(caller, context, parent.path, parent.name)) {
            is VfsResult.Ok -> {
                if (options.create == CreateDisposition.CREATE_NEW) {
                    return VfsResult.Err(VfsError.ALREADY_EXISTS)
                }
                if (options.followFinalSymlink && existing.value.inode?.type == InodeType.SYMLINK) {
                    return resolveExisting(
                        caller,
                        context,
                        directory,
                        pathname,
                        followSymlink = true,
                    )
                }
                return VfsResult.Ok(OpenedPath(existing.value, created = false))
            }
            is VfsResult.Err -> if (existing.error != VfsError.NOT_FOUND) return existing
        }

        if (pathname.requiresDirectory) return VfsResult.Err(VfsError.NOT_FOUND)

        if (MountFlag.READ_ONLY in parent.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        when (val access = backend.checkAccess(
            caller,
            parentInode,
            AccessPermissions.WRITE_AND_EXECUTE,
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        val node = NodeCreation(
            kind = NodeKind.Regular,
            mode = options.createMode,
            uid = caller.uid,
            gid = caller.gid,
            requestedMode = options.requestedCreateMode,
            creationMask = options.creationMask,
        )
        val atomic = (backend as? AtomicCreateDirectoryBackend)?.createAndOpen(
            caller,
            parentInode,
            parent.name,
            node,
            options,
        )
        if (atomic != null) {
            return when (atomic) {
                is VfsResult.Ok -> {
                    val dentry = parent.path.dentry.cacheChild(parent.name, atomic.value.entry)
                    VfsResult.Ok(
                        OpenedPath(
                            VfsPath(parent.path.mount, dentry),
                            created = true,
                            backend = atomic.value.backend,
                        ),
                    )
                }
                is VfsResult.Err -> atomic
            }
        }

        val lookup = when (val created = backend.createEntry(caller, parentInode, parent.name, node)) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> {
                if (created.error == VfsError.ALREADY_EXISTS &&
                    options.create == CreateDisposition.OPEN_OR_CREATE
                ) {
                    parent.path.dentry.invalidateNegativeChild(parent.name)
                    return resolveExisting(
                        caller,
                        context,
                        directory,
                        pathname,
                        options.followFinalSymlink,
                    )
                }
                return created
            }
        }
        val dentry = parent.path.dentry.cacheChild(parent.name, lookup)
        return VfsResult.Ok(OpenedPath(VfsPath(parent.path.mount, dentry), created = true))
    }

    private fun resolveExisting(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        followSymlink: Boolean,
    ): VfsResult<OpenedPath> = when (
        val result = paths.resolveAt(caller, context, directory, pathname, followSymlink)
    ) {
        is VfsResult.Ok -> VfsResult.Ok(OpenedPath(result.value, created = false))
        is VfsResult.Err -> result
    }

    private fun createChild(
        caller: VfsOperationContext,
        directory: VfsPath,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<VfsPath> {
        if (name.isDot || name.isDotDot) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (MountFlag.READ_ONLY in directory.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val parent = directory.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = parent.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        when (val access = backend.checkAccess(
            caller,
            parent,
            AccessPermissions.WRITE_AND_EXECUTE,
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        val lookup = when (val result = backend.createEntry(caller, parent, name, node)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return VfsResult.Ok(
            VfsPath(directory.mount, directory.dentry.cacheChild(name, lookup)),
        )
    }
}
