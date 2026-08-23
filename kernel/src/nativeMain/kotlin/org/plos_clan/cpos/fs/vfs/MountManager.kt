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
        val fileSystem = lock.withLock { fileSystems[fileSystemName] }
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val superBlock = when (
            val result = fileSystem.createSuperBlock(options.source, options.fileSystemOptions)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val rootMount = Mount(
            superBlock = superBlock,
            source = options.source ?: fileSystem.name,
            flags = options.flags,
        )
        return VfsResult.Ok(FileSystemContext(MountNamespace(rootMount)))
    }

    fun mount(
        context: FileSystemContext,
        target: VfsPathname,
        request: MountRequest,
    ): VfsResult<Unit> {
        val fileSystem = lock.withLock { fileSystems[request.fileSystemName] }
            ?: return VfsResult.Err(VfsError.NO_DEVICE)
        val fileSystemOptions = when (
            val result = fileSystem.parseOptions(request.source, request.data)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val path = when (val result = paths.resolve(context, target)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (path.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }

        val superBlock = when (
            val result = fileSystem.createSuperBlock(request.source, fileSystemOptions)
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return when (val attached = context.namespace.attach(
            target = path,
            superBlock = superBlock,
            source = request.source ?: fileSystem.name,
            flags = request.flags,
        )) {
            is VfsResult.Ok -> attached
            is VfsResult.Err -> {
                superBlock.backend.release()
                attached
            }
        }
    }

    fun unmount(
        context: FileSystemContext,
        target: VfsPathname,
        mode: UnmountMode = UnmountMode.REGULAR,
        followFinalSymlink: Boolean = true,
    ): VfsResult<Unit> {
        val path = when (val result = paths.resolve(context, target, followFinalSymlink)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val mount = path.mount
        if (path.dentry !== mount.root) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (mount === context.namespace.root) return VfsResult.Err(VfsError.BUSY)
        if (!mount.retain()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)

        val result = mode.unmount(context.namespace, mount)
        if (result is VfsResult.Err) mount.release()
        return result
    }
}
