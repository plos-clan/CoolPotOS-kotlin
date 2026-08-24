package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_FDCWD
import org.plos_clan.cpos.tasks.Process

internal object FsPathResolver {
    data class AtPath(
        val caller: VfsOperationContext,
        val context: FileSystemContext,
        val directory: VfsPath,
        val pathname: VfsPathname,
    ) {
        fun resolve(
            followFinalSymlink: Boolean = true,
            allowEmpty: Boolean = false,
        ): VfsResult<VfsPath> = FileSystemManager.vfs.resolveAt(
            caller,
            context,
            directory,
            pathname,
            followFinalSymlink,
            allowEmpty,
        )
    }

    fun atPath(
        process: Process,
        dirFd: Int,
        pathname: VfsPathname,
        caller: VfsOperationContext,
    ): VfsResult<AtPath> {
        val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (pathname.isAbsolute || dirFd == AT_FDCWD) {
            return VfsResult.Ok(
                AtPath(caller, context, context.workingDirectory, pathname),
            )
        }
        if (dirFd < 0) return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        val directory = process.fdTable.acquire(dirFd)
            ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        return try {
            VfsResult.Ok(AtPath(caller, context, directory.path, pathname))
        } finally {
            directory.release()
        }
    }

    fun resolveAt(
        process: Process,
        dirFd: Int,
        pathname: VfsPathname,
        followFinalSymlink: Boolean,
        allowEmpty: Boolean = false,
        caller: VfsOperationContext,
    ): VfsResult<VfsPath> = when (val result = atPath(process, dirFd, pathname, caller)) {
        is VfsResult.Ok -> result.value.resolve(followFinalSymlink, allowEmpty)
        is VfsResult.Err -> result
    }

}
