@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.FileSystemCreation
import org.plos_clan.cpos.fs.vfs.FileSystemParameter
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.MountResources
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsPathResolver.resolveAt
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object NewMountSyscalls {
    private const val FSOPEN_CLOEXEC = 0x1uL
    private const val FSMOUNT_CLOEXEC = 0x1uL
    private const val FSMOUNT_NAMESPACE = 0x2uL
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

    private value class MountAttributes private constructor(val flags: MountFlags) {
        companion object {
            private const val READ_ONLY = 0x000001uL
            private const val NO_SUID = 0x000002uL
            private const val NO_DEVICE = 0x000004uL
            private const val NO_EXEC = 0x000008uL
            private const val ATIME_MASK = 0x000070uL
            private const val NO_ATIME = 0x000010uL
            private const val STRICT_ATIME = 0x000020uL
            private const val NO_DIRECTORY_ATIME = 0x000080uL
            private const val NO_SYMLINK_FOLLOW = 0x200000uL
            private val SUPPORTED = READ_ONLY or NO_SUID or NO_DEVICE or NO_EXEC or
                ATIME_MASK or NO_DIRECTORY_ATIME or NO_SYMLINK_FOLLOW

            fun from(bits: ULong): MountAttributes? {
                if (bits and SUPPORTED.inv() != 0uL) return null
                val atime = when (bits and ATIME_MASK) {
                    0uL -> null
                    NO_ATIME -> MountFlag.NO_ATIME
                    STRICT_ATIME -> MountFlag.STRICT_ATIME
                    else -> return null
                }
                var flags = MountFlags.NONE
                if (bits and READ_ONLY != 0uL) flags += MountFlag.READ_ONLY
                if (bits and NO_SUID != 0uL) flags += MountFlag.NO_SUID
                if (bits and NO_DEVICE != 0uL) flags += MountFlag.NO_DEVICE
                if (bits and NO_EXEC != 0uL) flags += MountFlag.NO_EXEC
                if (bits and NO_DIRECTORY_ATIME != 0uL) flags += MountFlag.NO_DIRECTORY_ATIME
                if (bits and NO_SYMLINK_FOLLOW != 0uL) flags += MountFlag.NO_SYMLINK_FOLLOW
                if (atime != null) flags += atime
                return MountAttributes(flags)
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
                    if (command == ConfigurationCommand.RECONFIGURE) {
                        return errno(Errno.EINVAL)
                    }
                    when (val result = configuration.create(
                        exclusive = command == ConfigurationCommand.CREATE_EXCLUSIVE,
                    )) {
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
        val attributes = MountAttributes.from(registers[PtraceRegisters.IDX_RDX])
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
                    flags = attributes.flags,
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
