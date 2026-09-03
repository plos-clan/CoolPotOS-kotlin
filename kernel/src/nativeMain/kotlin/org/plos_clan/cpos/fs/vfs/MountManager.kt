package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.utils.IrqSpinLock

internal class VfsMountManager(
    private val paths: VfsPathResolver,
) {
    private val lock = IrqSpinLock()
    private val fileSystems = mutableMapOf<String, FileSystemType>()

    fun snapshotFileSystems(): List<FileSystemType> =
        lock.withLock { fileSystems.values.toList() }

    fun register(fileSystem: FileSystemType): VfsResult<Unit> = lock.withLock {
        if (fileSystems.containsKey(fileSystem.name)) {
            return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        fileSystems[fileSystem.name] = fileSystem
        VfsResult.Ok(Unit)
    }

    fun createContext(
        fileSystemName: String,
        options: RootMountOptions = RootMountOptions(),
    ): VfsResult<FileSystemContext> {
        val fileSystem = findFileSystem(fileSystemName)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val superBlock = when (
            val result = fileSystem.createSuperBlock(options.source, options.fileSystemOptions)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val rootMount = Mount(
            superBlock = superBlock,
            fileSystemName = fileSystemName,
            source = options.source ?: fileSystemName,
            flags = options.flags,
        )
        return VfsResult.Ok(FileSystemContext(MountNamespace(rootMount)))
    }

    fun mount(
        caller: VfsOperationContext,
        context: FileSystemContext,
        target: VfsPathname,
        request: MountRequest,
    ): VfsResult<Unit> {
        val fileSystem = findFileSystem(request.fileSystemName)
            ?: return VfsResult.Err(VfsError.NO_DEVICE)
        val path = when (val result = paths.resolve(caller, context, target)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (path.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }

        val superBlock = when (
            val result = fileSystem.createSuperBlock(request)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return when (val attached = context.namespace.attach(
            target = path,
            superBlock = superBlock,
            fileSystemName = request.fileSystemName,
            source = request.source ?: request.fileSystemName,
            flags = request.flags,
        )) {
            is VfsResult.Ok -> attached
            is VfsResult.Err -> {
                superBlock.release()
                attached
            }
        }
    }

    fun move(
        caller: VfsOperationContext,
        context: FileSystemContext,
        source: VfsPathname,
        target: VfsPathname,
    ): VfsResult<Unit> {
        val mounted = when (val result = paths.resolve(caller, context, source)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (mounted.mount === context.namespace.root || mounted.dentry !== mounted.mount.root) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val mountpoint = when (val result = paths.resolve(
            caller,
            context,
            target,
            followFinalMount = false,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (mounted.inode?.type != mountpoint.inode?.type) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return context.namespace.move(mounted.mount, mountpoint)
    }

    fun bind(
        caller: VfsOperationContext,
        context: FileSystemContext,
        source: VfsPathname,
        target: VfsPathname,
    ): VfsResult<Unit> {
        val sourcePath = when (val result = paths.resolve(caller, context, source)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val targetPath = when (val result = paths.resolve(
            caller,
            context,
            target,
            followFinalMount = false,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val sourceType = sourcePath.inode?.type ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val targetType = targetPath.inode?.type ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if ((sourceType == InodeType.DIRECTORY) != (targetType == InodeType.DIRECTORY)) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return context.namespace.bind(sourcePath, targetPath)
    }

    private fun findFileSystem(name: String): FileSystemType? {
        lock.withLock { fileSystems[name] }?.let { return it }
        val candidates = lock.withLock { fileSystems.values.toList() }
        return candidates.singleOrNull { it.accepts(name) }
    }

    fun unmount(
        caller: VfsOperationContext,
        context: FileSystemContext,
        target: VfsPathname,
        mode: UnmountMode = UnmountMode.REGULAR,
        followFinalSymlink: Boolean = true,
    ): VfsResult<Unit> {
        val path = when (
            val result = paths.resolve(caller, context, target, followFinalSymlink)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val mount = path.mount
        if (path.dentry !== mount.root) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (mount === context.namespace.root) return VfsResult.Err(VfsError.BUSY)
        if (!mount.retain()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)

        val result = mode.unmount(caller, context.namespace, mount)
        if (result is VfsResult.Err) mount.release()
        return result
    }
}
