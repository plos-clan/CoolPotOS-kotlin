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
        RECONFIGURING,
        CLOSED,
    }

    private enum class SuperBlockOption(
        val parameter: String,
        val flag: MountFlag,
        val enabled: Boolean,
    ) {
        READ_ONLY("ro", MountFlag.READ_ONLY, true),
        READ_WRITE("rw", MountFlag.READ_ONLY, false),
        SYNCHRONOUS("sync", MountFlag.SYNCHRONOUS, true),
        ASYNCHRONOUS("async", MountFlag.SYNCHRONOUS, false),
        DIRECTORY_SYNC("dirsync", MountFlag.DIRECTORY_SYNC, true),
        LAZY_TIME("lazytime", MountFlag.LAZY_TIME, true),
        NO_LAZY_TIME("nolazytime", MountFlag.LAZY_TIME, false);

        companion object {
            private val byParameter = entries.associateBy(SuperBlockOption::parameter)

            fun from(parameter: String): SuperBlockOption? = byParameter[parameter]
        }
    }

    private val lock = IrqSpinLock()
    private val parameters = mutableListOf<FileSystemParameter>()
    private var state = State.CONFIGURING
    private var source: String? = null
    private var superBlock: SuperBlock? = null
    private var attributes = MountFlagUpdate.NONE

    override val seekable = false

    fun configure(parameter: FileSystemParameter): VfsResult<Unit> {
        val result = lock.withLock {
            val option = SuperBlockOption.from(parameter.key)
            when (state) {
                State.CONFIGURING -> when {
                    option != null -> {
                        if (parameter !is FileSystemParameter.Flag) {
                            return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                        }
                        attributes = attributes.with(option.flag, option.enabled)
                    }
                    parameter.key == SOURCE_PARAMETER -> {
                        val value = parameter as? FileSystemParameter.StringValue
                            ?: return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                        if (source != null) {
                            return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                        }
                        source = value.value
                    }
                    else -> when (
                        val validated = fileSystem.validateParameter(parameters, parameter)
                    ) {
                        is VfsResult.Ok -> parameters += parameter
                        is VfsResult.Err -> return@withLock validated
                    }
                }
                State.MOUNTED,
                State.RECONFIGURING,
                -> {
                    if (option == null || parameter !is FileSystemParameter.Flag) {
                        return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    }
                    attributes = attributes.with(option.flag, option.enabled)
                    state = State.RECONFIGURING
                }
                else -> return@withLock VfsResult.Err(VfsError.BUSY)
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
                    result.value.setAttributes(attributes)
                    attributes = MountFlagUpdate.NONE
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
        val mountHandle = if (createNamespace) {
            val namespace = MountNamespace(mount)
            check(namespace.retain())
            MountNamespaceHandle(namespace)
        } else {
            DetachedMountHandle
        }
        val result = OpenFileDescription.open(
            caller,
            path,
            checkNotNull(path.inode),
            OpenOptions(access = AccessMode.PATH),
            openedBackend = mountHandle,
        )
        if (!createNamespace) mount.release()

        lock.withLock {
            check(state == State.MOUNTING)
            if (result is VfsResult.Err) {
                state = State.CREATED
            } else {
                state = State.MOUNTED
            }
        }
        return result
    }

    fun reconfigure(): VfsResult<Unit> = lock.withLock {
        if (state != State.RECONFIGURING) return@withLock VfsResult.Err(VfsError.BUSY)
        checkNotNull(superBlock).setAttributes(attributes)
        attributes = MountFlagUpdate.NONE
        state = State.MOUNTED
        VfsResult.Ok(Unit)
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
