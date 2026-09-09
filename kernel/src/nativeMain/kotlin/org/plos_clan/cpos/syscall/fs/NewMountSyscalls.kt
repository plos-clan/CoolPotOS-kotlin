@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.DetachedMountHandle
import org.plos_clan.cpos.fs.vfs.FileSystemCreation
import org.plos_clan.cpos.fs.vfs.FileSystemParameter
import org.plos_clan.cpos.fs.vfs.MountAttributeUpdate
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.MountPropagation
import org.plos_clan.cpos.fs.vfs.MountResources
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_EMPTY_PATH
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_FDCWD
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_NO_AUTOMOUNT
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_RECURSIVE
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_SYMLINK_NOFOLLOW
import org.plos_clan.cpos.syscall.fs.FsPathResolver.resolveAt
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters

internal object NewMountSyscalls {
    private const val FSOPEN_CLOEXEC = 0x1uL
    private const val FSMOUNT_CLOEXEC = 0x1uL
    private const val FSMOUNT_NAMESPACE = 0x2uL
    private const val MOVE_MOUNT_F_SYMLINKS = 0x000001uL
    private const val MOVE_MOUNT_F_AUTOMOUNTS = 0x000002uL
    private const val MOVE_MOUNT_F_EMPTY_PATH = 0x000004uL
    private const val MOVE_MOUNT_T_SYMLINKS = 0x000010uL
    private const val MOVE_MOUNT_T_AUTOMOUNTS = 0x000020uL
    private const val MOVE_MOUNT_T_EMPTY_PATH = 0x000040uL
    private val MOVE_MOUNT_SUPPORTED = MOVE_MOUNT_F_SYMLINKS or
        MOVE_MOUNT_F_AUTOMOUNTS or MOVE_MOUNT_F_EMPTY_PATH or MOVE_MOUNT_T_SYMLINKS or
        MOVE_MOUNT_T_AUTOMOUNTS or MOVE_MOUNT_T_EMPTY_PATH
    private const val MAX_PARAMETER_LENGTH = 256
    private const val MAX_PATH_LENGTH = 4096
    private const val MAX_BINARY_PARAMETER_SIZE = 1024 * 1024

    private enum class ConfigurationCommand {
        SET_FLAG,
        SET_STRING,
        SET_BINARY,
        SET_PATH,
        SET_PATH_EMPTY,
        SET_FD,
        CREATE,
        RECONFIGURE,
        CREATE_EXCLUSIVE;

        companion object {
            fun from(raw: ULong): ConfigurationCommand? =
                if (raw < entries.size.toULong()) entries[raw.toInt()] else null
        }
    }

    internal object MountAttributeCodec {
        const val IDMAP = 0x100000uL

        private const val READ_ONLY = 0x000001uL
        private const val NO_SUID = 0x000002uL
        private const val NO_DEVICE = 0x000004uL
        private const val NO_EXEC = 0x000008uL
        private const val ATIME_MASK = 0x000070uL
        private const val NO_ATIME = 0x000010uL
        private const val STRICT_ATIME = 0x000020uL
        private const val NO_DIRECTORY_ATIME = 0x000080uL
        private const val NO_SYMLINK_FOLLOW = 0x200000uL
        private val flagAttributes = READ_ONLY or NO_SUID or NO_DEVICE or NO_EXEC or
            ATIME_MASK or NO_DIRECTORY_ATIME or NO_SYMLINK_FOLLOW
        private val setattrAttributes = flagAttributes or IDMAP
        private val atimeFlags = MountFlags.of(
            MountFlag.RELATIVE_ATIME,
            MountFlag.NO_ATIME,
            MountFlag.STRICT_ATIME,
        )

        fun initial(bits: ULong): MountFlags? {
            if (bits and flagAttributes.inv() != 0uL) return null
            val atime = when (bits and ATIME_MASK) {
                0uL -> MountFlag.RELATIVE_ATIME
                NO_ATIME -> MountFlag.NO_ATIME
                STRICT_ATIME -> MountFlag.STRICT_ATIME
                else -> return null
            }
            return commonFlags(bits) + atime
        }

        fun update(
            set: ULong,
            clear: ULong,
            propagation: MountPropagation? = null,
        ): MountAttributeUpdate? {
            if ((set or clear) and setattrAttributes.inv() != 0uL) return null

            val atimeClear = clear and ATIME_MASK
            if (atimeClear != 0uL && atimeClear != ATIME_MASK ||
                atimeClear == 0uL && set and ATIME_MASK != 0uL
            ) {
                return null
            }

            var setFlags = commonFlags(set)
            var clearFlags = commonFlags(clear)
            if (atimeClear == ATIME_MASK) {
                clearFlags += atimeFlags
                setFlags += when (set and ATIME_MASK) {
                    0uL -> MountFlag.RELATIVE_ATIME
                    NO_ATIME -> MountFlag.NO_ATIME
                    STRICT_ATIME -> MountFlag.STRICT_ATIME
                    else -> return null
                }
            }
            return MountAttributeUpdate(setFlags, clearFlags, propagation)
        }

        private fun commonFlags(bits: ULong): MountFlags {
            var flags = MountFlags.NONE
            if (bits and READ_ONLY != 0uL) flags += MountFlag.READ_ONLY
            if (bits and NO_SUID != 0uL) flags += MountFlag.NO_SUID
            if (bits and NO_DEVICE != 0uL) flags += MountFlag.NO_DEVICE
            if (bits and NO_EXEC != 0uL) flags += MountFlag.NO_EXEC
            if (bits and NO_DIRECTORY_ATIME != 0uL) flags += MountFlag.NO_DIRECTORY_ATIME
            if (bits and NO_SYMLINK_FOLLOW != 0uL) flags += MountFlag.NO_SYMLINK_FOLLOW
            return flags
        }
    }

    private data class MountAttributeArguments(
        val set: ULong,
        val clear: ULong,
        val propagation: ULong,
        val userNamespaceFd: ULong,
    ) {
        val isNoOperation: Boolean
            get() = set == 0uL && clear == 0uL && propagation == 0uL

        val hasValidPropagation: Boolean
            get() = propagation and PROPAGATION_MASK.inv() == 0uL &&
                propagation.countOneBits() <= 1

        val decodedPropagation: MountPropagation?
            get() = when (propagation) {
                PRIVATE -> MountPropagation.PRIVATE
                SHARED -> MountPropagation.SHARED
                SLAVE -> MountPropagation.SLAVE
                UNBINDABLE -> MountPropagation.UNBINDABLE
                else -> null
            }

        companion object {
            const val SIZE = 32
            private const val UNBINDABLE = 0x020000uL
            private const val PRIVATE = 0x040000uL
            private const val SLAVE = 0x080000uL
            private const val SHARED = 0x100000uL
            private const val PROPAGATION_MASK = 0x1e0000uL

            fun decode(bytes: ByteArray): MountAttributeArguments {
                val input = LittleEndianBuffer(bytes)
                return MountAttributeArguments(
                    set = input.readU64(0),
                    clear = input.readU64(8),
                    propagation = input.readU64(16),
                    userNamespaceFd = input.readU64(24),
                )
            }
        }
    }

    fun fsopen(registers: PtraceRegisters, process: Process): Long {
        val flags = registers[PtraceRegisters.IDX_RSI]
        if (flags and FSOPEN_CLOEXEC.inv() != 0uL) return errno(Errno.EINVAL)
        val fileSystemName = when (val result = copyCString(
            process,
            registers[PtraceRegisters.IDX_RDI],
            MAX_PATH_LENGTH,
            VfsError.NAME_TOO_LONG,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val context = process.context ?: return errno(Errno.ENOENT)
        val opened = when (val result = FileSystemManager.vfs.openFileSystem(
            caller = process.vfsOperationContext,
            context = context,
            fileSystemName = fileSystemName.decodeToString(),
            resources = MountResources(process.fdTable::acquire),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val descriptorFlags = if (flags and FSOPEN_CLOEXEC != 0uL) {
            FileDescriptorFlags.FD_CLOEXEC
        } else {
            0uL
        }
        val reservation = process.fdTable.reserve(
            opened,
            descriptorFlags,
            process.resourceLimits.get(ProcessResource.OPEN_FILES).soft,
        ) ?: run {
            opened.release()
            return errno(Errno.EMFILE)
        }
        return reservation.use { it.install().toLong() }
    }

    fun fsconfig(registers: PtraceRegisters, process: Process): Long {
        val command = ConfigurationCommand.from(registers[PtraceRegisters.IDX_RSI])
            ?: return errno(Errno.EOPNOTSUPP)
        val descriptor = fileDescriptor(registers[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            val configuration = file.backend as? FileSystemCreation
                ?: return errno(Errno.EINVAL)
            val keyAddress = registers[PtraceRegisters.IDX_RDX]
            val valueAddress = registers[PtraceRegisters.IDX_R10]
            val auxiliary = registers[PtraceRegisters.IDX_R8].toUInt().toInt()
            when (command) {
                ConfigurationCommand.CREATE,
                ConfigurationCommand.CREATE_EXCLUSIVE,
                ConfigurationCommand.RECONFIGURE,
                -> {
                    if (keyAddress != 0uL || valueAddress != 0uL || auxiliary != 0) {
                        return errno(Errno.EINVAL)
                    }
                    val result = if (command == ConfigurationCommand.RECONFIGURE) {
                        configuration.reconfigure()
                    } else {
                        configuration.create(
                            exclusive = command == ConfigurationCommand.CREATE_EXCLUSIVE,
                        )
                    }
                    when (result) {
                        is VfsResult.Ok -> 0L
                        is VfsResult.Err -> errno(result.error.errno)
                    }
                }
                else -> {
                    val parameter = when (val result = copyParameter(
                        command,
                        keyAddress,
                        valueAddress,
                        auxiliary,
                        process,
                    )) {
                        is VfsResult.Ok -> result.value
                        is VfsResult.Err -> return errno(result.error.errno)
                    }
                    when (val result = configuration.configure(parameter)) {
                        is VfsResult.Ok -> 0L
                        is VfsResult.Err -> errno(result.error.errno)
                    }
                }
            }
        } finally {
            file.release()
        }
    }

    fun fsmount(registers: PtraceRegisters, process: Process): Long {
        val flags = registers[PtraceRegisters.IDX_RSI]
        if (flags and (FSMOUNT_CLOEXEC or FSMOUNT_NAMESPACE).inv() != 0uL) {
            return errno(Errno.EINVAL)
        }
        val attributes = MountAttributeCodec.initial(registers[PtraceRegisters.IDX_RDX])
            ?: return errno(Errno.EINVAL)
        if (ProcessManager.currentThread()?.capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) {
            return errno(Errno.EPERM)
        }
        val descriptor = fileDescriptor(registers[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val source = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            val configuration = source.backend as? FileSystemCreation
                ?: return errno(Errno.EINVAL)
            val descriptorFlags = if (flags and FSMOUNT_CLOEXEC != 0uL) {
                FileDescriptorFlags.FD_CLOEXEC
            } else {
                0uL
            }
            val reservation = process.fdTable.reserve(
                descriptorFlags,
                process.resourceLimits.get(ProcessResource.OPEN_FILES).soft,
            ) ?: return errno(Errno.EMFILE)
            reservation.use {
                val mountedFile = when (val result = configuration.mount(
                    caller = process.vfsOperationContext,
                    flags = attributes,
                    createNamespace = flags and FSMOUNT_NAMESPACE != 0uL,
                )) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return errno(result.error.errno)
                }
                it.install(mountedFile).toLong()
            }
        } finally {
            source.release()
        }
    }

    fun moveMount(registers: PtraceRegisters, process: Process): Long {
        val flags = registers[PtraceRegisters.IDX_R8]
        if (flags and MOVE_MOUNT_SUPPORTED.inv() != 0uL) return errno(Errno.EINVAL)
        if (ProcessManager.currentThread()?.capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) {
            return errno(Errno.EPERM)
        }
        val sourcePathname = when (val result = copyCString(
            process,
            registers[PtraceRegisters.IDX_RSI],
            MAX_PATH_LENGTH,
            VfsError.NAME_TOO_LONG,
        )) {
            is VfsResult.Ok -> VfsPathname.fromBytes(result.value)
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val targetPathname = when (val result = copyCString(
            process,
            registers[PtraceRegisters.IDX_R10],
            MAX_PATH_LENGTH,
            VfsError.NAME_TOO_LONG,
        )) {
            is VfsResult.Ok -> VfsPathname.fromBytes(result.value)
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val allowEmptySource = flags and MOVE_MOUNT_F_EMPTY_PATH != 0uL
        val allowEmptyTarget = flags and MOVE_MOUNT_T_EMPTY_PATH != 0uL
        if (sourcePathname.size == 0 && !allowEmptySource ||
            targetPathname.size == 0 && !allowEmptyTarget
        ) {
            return errno(Errno.ENOENT)
        }

        val caller = process.vfsOperationContext
        val context = process.context ?: return errno(Errno.ENOENT)
        val sourceDescriptor = registers[PtraceRegisters.IDX_RDI].toUInt().toInt()
        val targetDescriptor = registers[PtraceRegisters.IDX_RDX].toUInt().toInt()
        val sourceFile = if (sourcePathname.size == 0 && sourceDescriptor != AT_FDCWD) {
            process.fdTable.acquire(sourceDescriptor)
                ?: return errno(Errno.EBADF)
        } else {
            null
        }
        val targetFile = if (targetPathname.size == 0 && targetDescriptor != AT_FDCWD) {
            process.fdTable.acquire(targetDescriptor) ?: run {
                sourceFile?.release()
                return errno(Errno.EBADF)
            }
        } else {
            null
        }
        return try {
            val source = sourceFile?.path ?: when (val result = FsPathResolver.resolveAt(
                process = process,
                dirFd = sourceDescriptor,
                pathname = sourcePathname,
                followFinalSymlink = flags and MOVE_MOUNT_F_SYMLINKS != 0uL,
                allowEmpty = allowEmptySource,
                caller = caller,
            )) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return errno(result.error.errno)
            }
            val target = targetFile?.path ?: when (val result = FsPathResolver.resolveAt(
                process = process,
                dirFd = targetDescriptor,
                pathname = targetPathname,
                followFinalSymlink = flags and MOVE_MOUNT_T_SYMLINKS != 0uL,
                allowEmpty = allowEmptyTarget,
                followFinalMount = false,
                caller = caller,
            )) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return errno(result.error.errno)
            }
            val detached = sourceFile?.backend === DetachedMountHandle &&
                source.mount.attachment == null
            val result = FileSystemManager.vfs.moveMount(context, source, target, detached)
            when (result) {
                is VfsResult.Ok -> 0L
                is VfsResult.Err -> errno(result.error.errno)
            }
        } finally {
            targetFile?.release()
            sourceFile?.release()
        }
    }

    fun mountSetattr(registers: PtraceRegisters, process: Process): Long {
        val flags = registers[PtraceRegisters.IDX_RDX].toUInt()
        val supportedFlags = (AT_EMPTY_PATH or AT_RECURSIVE or AT_SYMLINK_NOFOLLOW or
            AT_NO_AUTOMOUNT).toUInt()
        if (flags and supportedFlags.inv() != 0u) return errno(Errno.EINVAL)

        val attributeSize = registers[PtraceRegisters.IDX_R8]
        if (attributeSize > PAGE_SIZE_BYTES) return errno(Errno.E2BIG)
        if (attributeSize < MountAttributeArguments.SIZE.toULong()) return errno(Errno.EINVAL)
        if (ProcessManager.currentThread()?.capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) {
            return errno(Errno.EPERM)
        }

        val attributeBytes = UserMemory(
            process.addressSpace,
            registers[PtraceRegisters.IDX_R10],
        ).copyFromUser(attributeSize.toInt()) ?: return errno(Errno.EFAULT)
        if ((MountAttributeArguments.SIZE until attributeBytes.size).any {
            attributeBytes[it] != 0.toByte()
        }) {
            return errno(Errno.E2BIG)
        }

        val arguments = MountAttributeArguments.decode(attributeBytes)
        if (arguments.isNoOperation) return 0L
        if (!arguments.hasValidPropagation) return errno(Errno.EINVAL)
        val attributes = MountAttributeCodec.update(
            arguments.set,
            arguments.clear,
            arguments.decodedPropagation,
        ) ?: return errno(Errno.EINVAL)

        if (arguments.clear and MountAttributeCodec.IDMAP != 0uL) {
            return errno(Errno.EINVAL)
        }
        if (arguments.set and MountAttributeCodec.IDMAP != 0uL) {
            if (arguments.userNamespaceFd > Int.MAX_VALUE.toULong()) {
                return errno(Errno.EINVAL)
            }
            val namespace = process.fdTable.acquire(arguments.userNamespaceFd.toInt())
                ?: return errno(Errno.EBADF)
            namespace.release()
            return errno(Errno.EINVAL)
        }
        val pathname = when (val result = copyCString(
            process,
            registers[PtraceRegisters.IDX_RSI],
            MAX_PATH_LENGTH,
            VfsError.NAME_TOO_LONG,
        )) {
            is VfsResult.Ok -> VfsPathname.fromBytes(result.value)
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val atPath = when (val result = FsPathResolver.atPath(
            process = process,
            dirFd = registers[PtraceRegisters.IDX_RDI].toUInt().toInt(),
            pathname = pathname,
            caller = process.vfsOperationContext,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val target = when (val result = atPath.resolve(
            followFinalSymlink = flags and AT_SYMLINK_NOFOLLOW.toUInt() == 0u,
            allowEmpty = flags and AT_EMPTY_PATH.toUInt() != 0u,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        return when (val result = FileSystemManager.vfs.setMountAttributes(
            context = atPath.context,
            target = target,
            attributes = attributes,
            recursive = flags and AT_RECURSIVE.toUInt() != 0u,
        )) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    private fun copyParameter(
        command: ConfigurationCommand,
        keyAddress: ULong,
        valueAddress: ULong,
        auxiliary: Int,
        process: Process,
    ): VfsResult<FileSystemParameter> {
        if (keyAddress == 0uL) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val key = when (val result = copyCString(
            process,
            keyAddress,
            MAX_PARAMETER_LENGTH,
            VfsError.fromErrno(Errno.E2BIG),
        )) {
            is VfsResult.Ok -> result.value.decodeToString()
            is VfsResult.Err -> return result
        }
        return when (command) {
            ConfigurationCommand.SET_FLAG -> {
                if (valueAddress != 0uL || auxiliary != 0) {
                    VfsResult.Err(VfsError.INVALID_ARGUMENT)
                } else {
                    VfsResult.Ok(FileSystemParameter.Flag(key))
                }
            }
            ConfigurationCommand.SET_STRING -> {
                if (valueAddress == 0uL || auxiliary != 0) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                val value = when (val result = copyCString(
                    process,
                    valueAddress,
                    MAX_PARAMETER_LENGTH,
                    VfsError.fromErrno(Errno.E2BIG),
                )) {
                    is VfsResult.Ok -> result.value.decodeToString()
                    is VfsResult.Err -> return result
                }
                VfsResult.Ok(FileSystemParameter.StringValue(key, value))
            }
            ConfigurationCommand.SET_BINARY -> {
                if (valueAddress == 0uL || auxiliary !in 1..MAX_BINARY_PARAMETER_SIZE) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                val value = UserMemory(process.addressSpace, valueAddress).copyFromUser(auxiliary)
                    ?: return VfsResult.Err(VfsError.FAULT)
                VfsResult.Ok(FileSystemParameter.BinaryValue(key, value))
            }
            ConfigurationCommand.SET_PATH,
            ConfigurationCommand.SET_PATH_EMPTY,
            -> {
                if (valueAddress == 0uL) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                val pathname = when (val result = copyCString(
                    process,
                    valueAddress,
                    MAX_PATH_LENGTH,
                    VfsError.NAME_TOO_LONG,
                )) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return result
                }
                val allowEmpty = command == ConfigurationCommand.SET_PATH_EMPTY
                if (pathname.isEmpty() && !allowEmpty) {
                    return VfsResult.Err(VfsError.NOT_FOUND)
                }
                val path = when (val result = resolveAt(
                    process = process,
                    dirFd = auxiliary,
                    pathname = VfsPathname.fromBytes(pathname),
                    followFinalSymlink = true,
                    allowEmpty = allowEmpty,
                    caller = process.vfsOperationContext,
                )) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return result
                }
                val inode = path.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
                when (val result = OpenFileDescription.open(
                    process.vfsOperationContext,
                    path,
                    inode,
                    OpenOptions(access = AccessMode.PATH),
                )) {
                    is VfsResult.Ok -> VfsResult.Ok(FileSystemParameter.PathValue(key, result.value))
                    is VfsResult.Err -> result
                }
            }
            ConfigurationCommand.SET_FD -> {
                if (valueAddress != 0uL || auxiliary < 0) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                val file = process.fdTable.acquire(auxiliary)
                    ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
                VfsResult.Ok(FileSystemParameter.FileValue(key, file))
            }
            else -> VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
    }

    private fun copyCString(
        process: Process,
        address: ULong,
        limit: Int,
        tooLong: VfsError,
    ): VfsResult<ByteArray> {
        val memory = UserMemory(process.addressSpace, address)
        memory.copyCStringFromUser(limit)?.let { return VfsResult.Ok(it) }
        return if (memory.copyFromUser(limit) == null) {
            VfsResult.Err(VfsError.FAULT)
        } else {
            VfsResult.Err(tooLong)
        }
    }
}
