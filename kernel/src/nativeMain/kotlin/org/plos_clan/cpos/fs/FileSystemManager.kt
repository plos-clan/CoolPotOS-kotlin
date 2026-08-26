package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.erofs.Erofs
import org.plos_clan.cpos.fs.erofs.ErofsOptions
import org.plos_clan.cpos.fs.fuse.Fuse
import org.plos_clan.cpos.fs.procfs.Procfs
import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.MountRequest
import org.plos_clan.cpos.fs.vfs.RootMountOptions
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.module.ModuleManager
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Cmdline

object FileSystemManager {
    val vfs = Vfs()

    private val builtInFileSystems = listOf(
        Tmpfs,
        Devtmpfs,
        Procfs,
        Erofs,
        Fuse,
        Sysfs,
    )

    var kernelContext: FileSystemContext? = null
        private set

    fun initialize(): Boolean = builtInFileSystems.all(::register)

    fun mountRootfs(): Boolean {
        if (kernelContext != null) return true
        val moduleName = Cmdline["rootfs"] ?: "rootfs-x86_64.erofs"
        val module = ModuleManager[moduleName] ?: run {
            println("VFS: rootfs module '$moduleName' is unavailable")
            return false
        }
        val context = when (val result = vfs.createContext(
            Erofs.name,
            RootMountOptions(
                source = moduleName,
                flags = MountFlags.of(MountFlag.READ_ONLY),
                fileSystemOptions = ErofsOptions(module.data),
            ),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                println("VFS: failed to mount EROFS: ${result.error}")
                return false
            }
        }

        val devFlags = MountFlags.of(MountFlag.NO_EXEC, MountFlag.NO_SUID)
        when (val result = vfs.mount(
            VfsOperationContext.KERNEL,
            context,
            VfsPathname.fromString("/dev"),
            MountRequest(Devtmpfs.name, flags = devFlags),
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> {
                context.release()
                println("VFS: failed to mount ${Devtmpfs.name} at /dev: ${result.error}")
                return false
            }
        }

        kernelContext = context
        ProcessManager.getKernelProcess()!!.context = context
        println("VFS: mounted zstd EROFS at '/'")
        return true
    }

    private fun register(fileSystem: FileSystemType): Boolean =
        when (val result = vfs.register(fileSystem)) {
            is VfsResult.Ok -> true
            is VfsResult.Err -> if (result.error == VfsError.ALREADY_EXISTS) {
                true
            } else {
                println("VFS: failed to register ${fileSystem.name}: ${result.error}")
                false
            }
        }
}
