package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.UnixCredentials
import org.plos_clan.cpos.fs.sock.UnixSocket
import org.plos_clan.cpos.fs.sock.UnixSocketAddress
import org.plos_clan.cpos.fs.sock.UnixSocketSubsystem
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.mem.PageCache

class Vfs(maxSymlinkDepth: Int = 40) {
    private val paths = VfsPathResolver(maxSymlinkDepth)
    private val nodes = VfsNodeOperations(paths)
    private val mounts = VfsMountManager(paths)
    private val anonymousFiles = AnonymousFileFactory()
    private val pipes = PipeFactory(anonymousFiles)
    private val sockets = UnixSocketSubsystem(paths, nodes, anonymousFiles)

    fun snapshotFileSystems(): List<FileSystemType> =
        mounts.snapshotFileSystems()

    fun register(fileSystem: FileSystemType): VfsResult<Unit> =
        mounts.register(fileSystem)

    fun createContext(
        fileSystemName: String,
        options: RootMountOptions = RootMountOptions(),
    ): VfsResult<FileSystemContext> = mounts.createContext(fileSystemName, options)

    fun createPipe(
        caller: VfsOperationContext,
        context: FileSystemContext,
    ): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> = pipes.create(caller, context)

    internal fun createUnixSocket(
        caller: VfsOperationContext,
        context: FileSystemContext,
        type: SocketType,
        nonBlocking: Boolean,
        credentials: UnixCredentials,
    ): VfsResult<OpenFileDescription> = sockets.create(
        caller,
        context,
        type,
        nonBlocking,
        credentials,
    )

    internal fun openSocket(
        caller: VfsOperationContext,
        context: FileSystemContext,
        socket: AbstractSocket,
        nonBlocking: Boolean,
    ): VfsResult<OpenFileDescription> = anonymousFiles.open(
        caller,
        context,
        socket,
        OpenOptions(access = AccessMode.READ_WRITE, nonBlocking = nonBlocking),
    )

    internal fun createUnixSocketPair(
        caller: VfsOperationContext,
        context: FileSystemContext,
        type: SocketType,
        credentials: UnixCredentials,
        nonBlocking: Boolean,
    ): VfsResult<Pair<OpenFileDescription, OpenFileDescription>> =
        sockets.pair(caller, context, type, credentials, nonBlocking)

    internal fun bindUnixSocket(
        caller: VfsOperationContext,
        context: FileSystemContext,
        socket: UnixSocket,
        address: UnixSocketAddress,
        mode: FileMode,
        uid: UInt,
        gid: UInt,
    ): VfsResult<UnixSocketAddress> = sockets.bind(
        caller,
        context,
        socket,
        address,
        mode,
        uid,
        gid,
    )

    internal fun resolveUnixSocket(
        caller: VfsOperationContext,
        context: FileSystemContext,
        address: UnixSocketAddress,
    ): VfsResult<UnixSocket> = sockets.resolve(caller, context, address)

    fun mount(
        caller: VfsOperationContext,
        context: FileSystemContext,
        target: VfsPathname,
        request: MountRequest,
    ): VfsResult<Unit> = mounts.mount(caller, context, target, request)

    fun moveMount(
        caller: VfsOperationContext,
        context: FileSystemContext,
        source: VfsPathname,
        target: VfsPathname,
    ): VfsResult<Unit> = mounts.move(caller, context, source, target)

    fun unmount(
        caller: VfsOperationContext,
        context: FileSystemContext,
        target: VfsPathname,
        mode: UnmountMode = UnmountMode.REGULAR,
        followFinalSymlink: Boolean = true,
    ): VfsResult<Unit> = mounts.unmount(caller, context, target, mode, followFinalSymlink)

    fun resolve(
        caller: VfsOperationContext,
        context: FileSystemContext,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
    ): VfsResult<VfsPath> = paths.resolve(caller, context, pathname, followFinalSymlink)

    fun resolveAt(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
        allowEmpty: Boolean = false,
    ): VfsResult<VfsPath> = paths.resolveAt(
        caller,
        context,
        directory,
        pathname,
        followFinalSymlink,
        allowEmpty,
    )

    fun open(
        caller: VfsOperationContext,
        context: FileSystemContext,
        pathname: VfsPathname,
        options: OpenOptions = OpenOptions(),
    ): VfsResult<OpenFileDescription> = openAt(
        context = context,
        caller = caller,
        directory = context.workingDirectory,
        pathname = pathname,
        options = options,
    )

    fun openAt(
        caller: VfsOperationContext,
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
                    caller,
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
            -> when (
                val result = nodes.openOrCreate(caller, context, directory, pathname, options)
            ) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
        }

        val path = opened.path
        val inode = path.inode ?: return opened.reject(VfsError.NOT_FOUND)
        if (options.noAtime && !caller.privileged) {
            val owner = when (val result = inode.attributes(caller)) {
                is VfsResult.Ok -> result.value.metadata.uid
                is VfsResult.Err -> return opened.reject(result.error)
            }
            if (caller.uid != owner) return opened.reject(VfsError.NOT_PERMITTED)
        }
        if (inode.type == InodeType.SYMLINK && options.access != AccessMode.PATH) {
            return opened.reject(VfsError.TOO_MANY_SYMLINKS)
        }
        if (options.directoryOnly && inode.type != InodeType.DIRECTORY) {
            return opened.reject(VfsError.NOT_DIRECTORY)
        }
        if (options.access.canWrite && inode.type == InodeType.DIRECTORY) {
            return opened.reject(VfsError.IS_DIRECTORY)
        }
        if (options.access.canWrite && inode.type == InodeType.REGULAR &&
            MountFlag.READ_ONLY in path.mount.flags
        ) {
            return opened.reject(VfsError.READ_ONLY)
        }
        if (MountFlag.NO_DEVICE in path.mount.flags &&
            (inode.type == InodeType.CHARACTER_DEVICE || inode.type == InodeType.BLOCK_DEVICE)
        ) {
            return opened.reject(VfsError.PERMISSION_DENIED)
        }
        val requestedAccess = when (options.access) {
            AccessMode.READ -> AccessPermissions.READ
            AccessMode.WRITE -> AccessPermissions.WRITE
            AccessMode.READ_WRITE -> AccessPermissions.READ + AccessPermission.WRITE
            AccessMode.PATH -> AccessPermissions.NONE
        }
        if (!opened.created) {
            when (val result = inode.backend.checkAccess(caller, inode, requestedAccess)) {
                is VfsResult.Ok -> Unit
                is VfsResult.Err -> return opened.reject(result.error)
            }
        }
        return OpenFileDescription.open(
            caller,
            path,
            inode,
            options,
            truncate = options.truncate && !opened.created,
            openedBackend = opened.backend,
        )
    }

    fun resize(
        caller: VfsOperationContext,
        mount: Mount,
        inode: Inode,
        size: ULong,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        val backend = inode.backend as? RegularFileBackend
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val result = backend.resize(caller, inode, size)
        if (result is VfsResult.Ok) {
            PageCache.invalidate(inode)
            val identity = backend.pageCacheIdentity(inode)
            if (identity != inode) PageCache.invalidate(identity)
        }
        return result
    }

    fun checkAccess(
        caller: VfsOperationContext,
        inode: Inode,
        requested: AccessPermissions,
    ): VfsResult<Unit> = inode.backend.checkAccess(caller, inode, requested)

    fun access(
        caller: VfsOperationContext,
        inode: Inode,
        requested: AccessPermissions,
    ): VfsResult<Unit> = inode.backend.access(caller, inode, requested)

    fun allocate(
        caller: VfsOperationContext,
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
        val result = backend.allocate(caller, inode, offset, length, mode)
        if (result is VfsResult.Ok) {
            PageCache.invalidate(inode, offset, length)
            val identity = backend.pageCacheIdentity(inode)
            if (identity != inode) PageCache.invalidate(identity, offset, length)
        }
        return result
    }

    fun setMode(
        caller: VfsOperationContext,
        mount: Mount,
        inode: Inode,
        mode: FileMode,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.setMode(caller, inode, mode)
    }

    fun setOwner(
        caller: VfsOperationContext,
        mount: Mount,
        inode: Inode,
        uid: UInt?,
        gid: UInt?,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.setOwner(caller, inode, uid, gid)
    }

    fun updateTimestamps(
        caller: VfsOperationContext,
        mount: Mount,
        inode: Inode,
        update: InodeTimestampSet,
    ): VfsResult<Unit> {
        if (update.omitsBoth) return VfsResult.Ok(Unit)
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.superBlock.backend.updateTimestamps(caller, inode, update)
    }

    fun getExtendedAttribute(
        caller: VfsOperationContext,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<ByteArray> = inode.backend.getExtendedAttribute(caller, inode, name)

    fun listExtendedAttributes(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<ByteArray> = inode.backend.listExtendedAttributes(caller, inode)

    fun setExtendedAttribute(
        caller: VfsOperationContext,
        mount: Mount,
        inode: Inode,
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.setExtendedAttribute(caller, inode, name, value, mode)
    }

    fun removeExtendedAttribute(
        caller: VfsOperationContext,
        mount: Mount,
        inode: Inode,
        name: ExtendedAttributeName,
    ): VfsResult<Unit> {
        if (MountFlag.READ_ONLY in mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        return inode.backend.removeExtendedAttribute(caller, inode, name)
    }

    internal fun createFile(
        caller: VfsOperationContext,
        directory: VfsPath,
        name: VfsName,
        mode: FileMode,
        content: FileContent,
        contentOffset: Int,
        contentSize: Int,
    ): VfsResult<VfsPath> =
        nodes.createFile(caller, directory, name, mode, content, contentOffset, contentSize)

    fun createNode(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> = nodes.createNode(caller, context, directory, pathname, node)

    fun createNode(
        caller: VfsOperationContext,
        context: FileSystemContext,
        pathname: VfsPathname,
        node: NodeCreation,
    ): VfsResult<VfsPath> = nodes.createNode(caller, context, pathname, node)

    fun remove(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        mode: RemoveMode,
    ): VfsResult<Unit> = nodes.remove(caller, context, directory, pathname, mode)

    fun link(
        caller: VfsOperationContext,
        context: FileSystemContext,
        sourceMount: Mount,
        sourceInode: Inode,
        targetDirectory: VfsPath,
        target: VfsPathname,
    ): VfsResult<Unit> =
        nodes.link(caller, context, sourceMount, sourceInode, targetDirectory, target)

    fun rename(
        caller: VfsOperationContext,
        context: FileSystemContext,
        sourceDirectory: VfsPath,
        source: VfsPathname,
        targetDirectory: VfsPath,
        target: VfsPathname,
        mode: RenameMode,
    ): VfsResult<Unit> =
        nodes.rename(caller, context, sourceDirectory, source, targetDirectory, target, mode)

    fun chdir(
        caller: VfsOperationContext,
        context: FileSystemContext,
        pathname: VfsPathname,
    ): VfsResult<Unit> {
        val path = when (val result = resolve(caller, context, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return chdir(caller, context, path)
    }

    fun chroot(
        caller: VfsOperationContext,
        context: FileSystemContext,
        pathname: VfsPathname,
    ): VfsResult<Unit> {
        val path = when (val result = resolve(caller, context, pathname)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (inode.type != InodeType.DIRECTORY) return VfsResult.Err(VfsError.NOT_DIRECTORY)
        when (val access = inode.backend.checkAccess(caller, inode, AccessPermissions.EXECUTE)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        return if (context.changeRoot(path)) VfsResult.Ok(Unit)
        else VfsResult.Err(VfsError.NOT_FOUND)
    }

    internal fun chdir(
        caller: VfsOperationContext,
        context: FileSystemContext,
        path: VfsPath,
    ): VfsResult<Unit> {
        val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        when (val access = inode.backend.checkAccess(caller, inode, AccessPermissions.EXECUTE)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        return if (context.changeWorkingDirectory(path)) VfsResult.Ok(Unit)
        else VfsResult.Err(VfsError.NOT_FOUND)
    }

    fun absolutePath(
        context: FileSystemContext,
        initial: VfsPath,
    ): VfsResult<ByteArray> = paths.absolutePath(context, initial)
}
