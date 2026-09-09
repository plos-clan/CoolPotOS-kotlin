package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.PathResolution
import org.plos_clan.cpos.fs.vfs.PathResolutionBoundary
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
            followFinalMount: Boolean = true,
            resolution: PathResolution = PathResolution.DEFAULT,
        ): VfsResult<VfsPath> = FileSystemManager.vfs.resolveAt(
            caller,
            context,
            directory,
            pathname,
            followFinalSymlink,
            allowEmpty,
            followFinalMount,
            resolution,
        )
    }

    fun atPath(
        process: Process,
        dirFd: Int,
        pathname: VfsPathname,
        caller: VfsOperationContext,
        resolution: PathResolution = PathResolution.DEFAULT,
    ): VfsResult<AtPath> {
        val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (dirFd == AT_FDCWD ||
            pathname.isAbsolute && resolution.boundary != PathResolutionBoundary.IN_ROOT
        ) {
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
        followFinalMount: Boolean = true,
        caller: VfsOperationContext,
        resolution: PathResolution = PathResolution.DEFAULT,
    ): VfsResult<VfsPath> = when (
        val result = atPath(process, dirFd, pathname, caller, resolution)
    ) {
        is VfsResult.Ok -> result.value.resolve(
            followFinalSymlink,
            allowEmpty,
            followFinalMount,
            resolution,
        )
        is VfsResult.Err -> result
    }

}
