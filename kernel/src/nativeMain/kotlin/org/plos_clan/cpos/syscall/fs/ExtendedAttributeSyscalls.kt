@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.EXTENDED_ATTRIBUTE_VALUE_MAX
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeMode
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeName
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_FDCWD
import org.plos_clan.cpos.syscall.fs.FsConstants.XATTR_CREATE
import org.plos_clan.cpos.syscall.fs.FsConstants.XATTR_REPLACE
import org.plos_clan.cpos.syscall.fs.FsPathResolver.resolveAt
import org.plos_clan.cpos.syscall.fs.FsPermissions.mayWrite
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun setxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.set(regs, process, ExtendedAttributes.Target.PATH)

internal fun lsetxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.set(regs, process, ExtendedAttributes.Target.LINK)

internal fun fsetxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.set(regs, process, ExtendedAttributes.Target.DESCRIPTOR)

internal fun getxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.get(regs, process, ExtendedAttributes.Target.PATH)

internal fun lgetxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.get(regs, process, ExtendedAttributes.Target.LINK)

internal fun fgetxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.get(regs, process, ExtendedAttributes.Target.DESCRIPTOR)

internal fun listxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.list(regs, process, ExtendedAttributes.Target.PATH)

internal fun llistxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.list(regs, process, ExtendedAttributes.Target.LINK)

internal fun flistxattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.list(regs, process, ExtendedAttributes.Target.DESCRIPTOR)

internal fun removexattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.remove(regs, process, ExtendedAttributes.Target.PATH)

internal fun lremovexattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.remove(regs, process, ExtendedAttributes.Target.LINK)

internal fun fremovexattr(regs: PtraceRegisters, process: Process): Long =
    ExtendedAttributes.remove(regs, process, ExtendedAttributes.Target.DESCRIPTOR)

private object ExtendedAttributes {
    enum class Target {
        PATH,
        LINK,
        DESCRIPTOR,
    }

    fun set(regs: PtraceRegisters, process: Process, target: Target): Long {
        val size = regs[PtraceRegisters.IDX_R10]
        if (size > EXTENDED_ATTRIBUTE_VALUE_MAX.toULong()) return errno(Errno.E2BIG)
        val mode = when (regs[PtraceRegisters.IDX_R8]) {
            0uL -> ExtendedAttributeMode.CREATE_OR_REPLACE
            XATTR_CREATE.toULong() -> ExtendedAttributeMode.CREATE
            XATTR_REPLACE.toULong() -> ExtendedAttributeMode.REPLACE
            else -> return errno(Errno.EINVAL)
        }
        val name = when (val result = name(process, regs[PtraceRegisters.IDX_RSI])) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val value = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDX])
            .copyFromUser(size.toInt()) ?: return errno(Errno.EFAULT)
        return withNode(regs, process, target) { path, inode ->
            if (!process.mayWrite(inode.metadata())) return@withNode errno(Errno.EACCES)
            when (val result = FileSystemManager.vfs.setExtendedAttribute(
                path.mount,
                inode,
                name,
                value,
                mode,
            )) {
                is VfsResult.Ok -> 0L
                is VfsResult.Err -> errno(result.error.errno)
            }
        }
    }

    fun get(regs: PtraceRegisters, process: Process, target: Target): Long {
        val name = when (val result = name(process, regs[PtraceRegisters.IDX_RSI])) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        return withNode(regs, process, target) { _, inode ->
            when (val result = FileSystemManager.vfs.getExtendedAttribute(inode, name)) {
                is VfsResult.Ok -> copyResult(
                    process,
                    regs[PtraceRegisters.IDX_RDX],
                    regs[PtraceRegisters.IDX_R10],
                    result.value,
                )
                is VfsResult.Err -> errno(result.error.errno)
            }
        }
    }

    fun list(regs: PtraceRegisters, process: Process, target: Target): Long =
        withNode(regs, process, target) { _, inode ->
            when (val result = FileSystemManager.vfs.listExtendedAttributes(inode)) {
                is VfsResult.Ok -> copyResult(
                    process,
                    regs[PtraceRegisters.IDX_RSI],
                    regs[PtraceRegisters.IDX_RDX],
                    result.value,
                )
                is VfsResult.Err -> errno(result.error.errno)
            }
        }

    fun remove(regs: PtraceRegisters, process: Process, target: Target): Long {
        val name = when (val result = name(process, regs[PtraceRegisters.IDX_RSI])) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        return withNode(regs, process, target) { path, inode ->
            if (!process.mayWrite(inode.metadata())) return@withNode errno(Errno.EACCES)
            when (val result = FileSystemManager.vfs.removeExtendedAttribute(
                path.mount,
                inode,
                name,
            )) {
                is VfsResult.Ok -> 0L
                is VfsResult.Err -> errno(result.error.errno)
            }
        }
    }

    private fun name(process: Process, address: ULong): VfsResult<ExtendedAttributeName> {
        val user = UserMemory(process.addressSpace, address)
        val bytes = user.copyCStringFromUser(ExtendedAttributeName.MAX_LENGTH + 1)
        if (bytes == null) {
            val prefix = user.copyFromUser(ExtendedAttributeName.MAX_LENGTH + 1)
                ?: return VfsResult.Err(VfsError.FAULT)
            return VfsResult.Err(
                if (prefix.none { it == 0.toByte() }) VfsError.RANGE else VfsError.FAULT,
            )
        }
        return ExtendedAttributeName.fromBytes(bytes)
    }

    private fun copyResult(
        process: Process,
        address: ULong,
        capacity: ULong,
        value: ByteArray,
    ): Long {
        if (capacity == 0uL) return value.size.toLong()
        if (capacity < value.size.toULong()) return errno(Errno.ERANGE)
        return if (UserMemory(process.addressSpace, address).copyToUser(value)) {
            value.size.toLong()
        } else {
            errno(Errno.EFAULT)
        }
    }

    private inline fun withNode(
        regs: PtraceRegisters,
        process: Process,
        target: Target,
        operation: (VfsPath, Inode) -> Long,
    ): Long {
        if (target == Target.DESCRIPTOR) {
            val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EBADF)
            val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
            return try {
                if (file.access == AccessMode.PATH) errno(Errno.EBADF)
                else operation(file.path, file.inode)
            } finally {
                file.release()
            }
        }
        val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EFAULT)
        val path = when (val result = resolveAt(
            process,
            AT_FDCWD,
            VfsPathname.fromBytes(pathname),
            followFinalSymlink = target == Target.PATH,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val inode = path.inode ?: return errno(Errno.ENOENT)
        return operation(path, inode)
    }
}
