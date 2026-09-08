package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.utils.IrqSpinLock

internal class FileSystemCreation(
    private val fileSystem: FileSystemType,
    private val fileSystemName: String,
    private val resources: MountResources,
) : AnonymousFileBackend(InodeType.REGULAR, "fscontext") {
    private enum class State {
        CONFIGURING,
        CREATING,
        CREATED,
        MOUNTING,
        MOUNTED,
        CLOSED,
    }

    private val lock = IrqSpinLock()
    private val parameters = mutableListOf<FileSystemParameter>()
    private var state = State.CONFIGURING
    private var source: String? = null
    private var superBlock: SuperBlock? = null

    override val seekable = false

    fun configure(parameter: FileSystemParameter): VfsResult<Unit> {
        val result = lock.withLock {
            if (state != State.CONFIGURING) return@withLock VfsResult.Err(VfsError.BUSY)
            if (parameter.key == SOURCE_PARAMETER) {
                val value = parameter as? FileSystemParameter.StringValue
                    ?: return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                if (source != null) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                source = value.value
            } else {
                when (val validated = fileSystem.validateParameter(parameters, parameter)) {
                    is VfsResult.Ok -> parameters += parameter
                    is VfsResult.Err -> return@withLock validated
                }
            }
            VfsResult.Ok(Unit)
        }
        if (result is VfsResult.Err) parameter.release()
        return result
    }

    fun create(exclusive: Boolean): VfsResult<Unit> {
        val configuration = lock.withLock {
            if (state != State.CONFIGURING) return@withLock null
            state = State.CREATING
            FileSystemConfiguration(
                fileSystemName = fileSystemName,
                source = source,
                parameters = FileSystemParameters.copyOf(parameters),
                resources = resources,
                exclusive = exclusive,
            )
        } ?: return VfsResult.Err(VfsError.BUSY)

        val result = fileSystem.createSuperBlock(configuration)
        lock.withLock {
            check(state == State.CREATING)
            when (result) {
                is VfsResult.Ok -> {
                    superBlock = result.value
                    state = State.CREATED
                }
                is VfsResult.Err -> state = State.CONFIGURING
            }
        }
        return when (result) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> result
        }
    }

    fun mount(
        caller: VfsOperationContext,
        flags: MountFlags,
        createNamespace: Boolean,
    ): VfsResult<OpenFileDescription> {
        val mount = lock.withLock {
            if (state != State.CREATED) return@withLock null
            val mountedSuperBlock = checkNotNull(superBlock)
            if (!mountedSuperBlock.retain()) return@withLock null
            state = State.MOUNTING
            Mount(
                superBlock = mountedSuperBlock,
                fileSystemName = fileSystemName,
                source = source ?: fileSystemName,
                flags = flags,
            )
        } ?: return VfsResult.Err(VfsError.BUSY)

        val path = VfsPath(mount, mount.root)
        val namespaceHandle = if (createNamespace) {
            val namespace = MountNamespace(mount)
            check(namespace.retain())
            MountNamespaceHandle(namespace)
        } else {
            null
        }
        val result = OpenFileDescription.open(
            caller,
            path,
            checkNotNull(path.inode),
            OpenOptions(access = AccessMode.PATH),
            openedBackend = namespaceHandle,
        )
        if (!createNamespace) mount.release()

        val createdSuperBlock = lock.withLock {
            check(state == State.MOUNTING)
            if (result is VfsResult.Err) {
                state = State.CREATED
                null
            } else {
                state = State.MOUNTED
                checkNotNull(superBlock).also { superBlock = null }
            }
        }
        createdSuperBlock?.release()
        return result
    }

    override fun release() {
        val released = lock.withLock {
            if (state == State.CLOSED) return
            state = State.CLOSED
            val values = parameters.toList()
            parameters.clear()
            val mountedSuperBlock = superBlock
            superBlock = null
            values to mountedSuperBlock
        }
        released.first.forEach(FileSystemParameter::release)
        released.second?.release()
    }

    private companion object {
        const val SOURCE_PARAMETER = "source"
    }
}
