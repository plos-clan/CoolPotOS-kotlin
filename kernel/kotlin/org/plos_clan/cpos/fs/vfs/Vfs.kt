package org.plos_clan.cpos.fs

class Vfs(maxSymlinkDepth: Int = 40) {
    private val paths = VfsPathResolver(maxSymlinkDepth)
    private val nodes = VfsNodeOperations(paths)
    private val mounts = VfsMountManager(paths)
    private val pipes = PipeFactory()

    fun snapshotFileSystems(): List<FileSystemType> =
        mounts.snapshotFileSystems()

    fun register(fileSystem: FileSystemType): VfsResult<Unit> =
        mounts.register(fileSystem)

    fun createContext(
        fileSystemName: String,
        options: RootMountOptions = RootMountOptions(),
    ): VfsResult<FileSystemContext> = mounts.createContext(fileSystemName, options)

    fun createPipe(
        context: FileSystemContext,
    ): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> = pipes.create(context)

    fun mount(
        context: FileSystemContext,
        target: VfsPathname,
        request: MountRequest,
    ): VfsResult<Unit> = mounts.mount(context, target, request)

    fun unmount(
        context: FileSystemContext,
        target: VfsPathname,
        mode: UnmountMode = UnmountMode.REGULAR,
        followFinalSymlink: Boolean = true,
    ): VfsResult<Unit> = mounts.unmount(context, target, mode, followFinalSymlink)

    fun resolve(
        context: FileSystemContext,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
    ): VfsResult<VfsPath> = paths.resolve(context, pathname, followFinalSymlink)

    fun resolveAt(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
        allowEmpty: Boolean = false,
    ): VfsResult<VfsPath> = paths.resolveAt(
        context,
        directory,
        pathname,
        followFinalSymlink,
        allowEmpty,
    )

    fun open(
        context: FileSystemContext,
        pathname: VfsPathname,
        options: OpenOptions = OpenOptions(),
    ): VfsResult<OpenFileDescription> = openAt(
        context = context,
        directory = context.workingDirectory,
        pathname = pathname,
        options = options,
    )

    fun openAt(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        options: OpenOptions = OpenOptions(),
    ): VfsResult<OpenFileDescription> {
        if (options.truncate && !options.access.canWrite) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val opened = when (options.create) {
            CreateDisposition.OPEN_EXISTING -> when (
                val result = resolveAt(
                    context,
                    directory,
                    pathname,
                    options.followFinalSymlink,
                )
            ) {
                is VfsResult.Ok -> OpenedPath(result.value, created = false)
                is VfsResult.Err -> return result
            }

            CreateDisposition.OPEN_OR_CREATE,
            CreateDisposition.CREATE_NEW,
            -> when (val result = nodes.openOrCreate(context, directory, pathname, options)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
        }

        val path = opened.path
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (options.noAtime && !options.privileged && options.createUid != inode.metadata().uid) {
            return VfsResult.Err(VfsError.NOT_PERMITTED)
        }
        if (inode.type == InodeType.SYMLINK) {
            return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
        }
        if (options.directoryOnly && inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        if (options.access.canWrite && inode.type == InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.IS_DIRECTORY)
        }
        if (options.access.canWrite && inode.type == InodeType.REGULAR &&
            MountFlag.READ_ONLY in path.mount.flags
        ) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        if (MountFlag.NO_DEVICE in path.mount.flags &&
            (inode.type == InodeType.CHARACTER_DEVICE || inode.type == InodeType.BLOCK_DEVICE)
        ) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        return OpenFileDescription.open(
            path,
            inode,
            options,
            truncate = options.truncate && !opened.created,
        )
    }

    fun resize(mount: Mount, inode: Inode, size: ULong): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val backend = inode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return backend.resize(inode, size)
    }

    fun allocate(
        mount: Mount,
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (length == 0uL || offset > ULong.MAX_VALUE - length) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val backend = inode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return backend.allocate(inode, offset, length, mode)
    }

    fun setMode(mount: Mount, inode: Inode, mode: FileMode): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.setMode(inode, mode)
    }

    fun setOwner(mount: Mount, inode: Inode, uid: UInt?, gid: UInt?): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.setOwner(inode, uid, gid)
    }

    fun updateTimestamps(
        mount: Mount,
        inode: Inode,
        update: InodeTimestampSet,
    ): VfsResult<Unit> {
        if (update.omitsBoth) return VfsResult.Ok(Unit)
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.superBlock.backend.updateTimestamps(inode, update)
    }

    fun getExtendedAttribute(
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = inode.backend.getExtendedAttribute(inode, name)

    fun listExtendedAttributes(inode: Inode): VfsResult<ByteArray> =
        inode.backend.listExtendedAttributes(inode)

    fun setExtendedAttribute(
        mount: Mount,
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.setExtendedAttribute(inode, name, value, mode)
    }

    fun removeExtendedAttribute(
        mount: Mount,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.removeExtendedAttribute(inode, name)
    }

    internal fun createFile(
        directory: VfsPath,
        name: VfsName,
        mode: FileMode,
        content: FileContent,
        contentOffset: Int,
        contentSize: Int,
    ): VfsResult<VfsPath> =
        nodes.createFile(directory, name, mode, content, contentOffset, contentSize)

    fun createNode(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> = nodes.createNode(context, directory, pathname, node)

    fun createNode(
        context: FileSystemContext,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> = nodes.createNode(context, pathname, node)

    fun remove(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        mode: RemoveMode,
    ): VfsResult<Unit> = nodes.remove(context, directory, pathname, mode)

    fun link(
        context: FileSystemContext,
        sourceMount: Mount,
        sourceInode: Inode,
        targetDirectory: VfsPath,
        target: VfsPathname,
    ): VfsResult<Unit> =
        nodes.link(context, sourceMount, sourceInode, targetDirectory, target)

    fun rename(
        context: FileSystemContext,
        sourceDirectory: VfsPath,
        source: VfsPathname,
        targetDirectory: VfsPath,
        target: VfsPathname,
        mode: RenameMode,
    ): VfsResult<Unit> =
        nodes.rename(context, sourceDirectory, source, targetDirectory, target, mode)

    fun chdir(context: FileSystemContext, pathname: VfsPathname): VfsResult<Unit> {
        val path = when (val result = resolve(context, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return chdir(context, path)
    }

    internal fun chdir(context: FileSystemContext, path: VfsPath): VfsResult<Unit> {
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return if (context.changeWorkingDirectory(path)) VfsResult.Ok(Unit)
        else VfsResult.Err(VfsError.NOT_FOUND)
    }

    fun absolutePath(
        context: FileSystemContext,
        initial: VfsPath,
    ): VfsResult<ByteArray> = paths.absolutePath(context, initial)
}
