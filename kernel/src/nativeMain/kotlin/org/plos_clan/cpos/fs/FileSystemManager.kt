package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.erofs.Erofs
import org.plos_clan.cpos.fs.erofs.ErofsOptions
import org.plos_clan.cpos.fs.overlay.Overlayfs
import org.plos_clan.cpos.fs.overlay.OverlayfsOptions
import org.plos_clan.cpos.fs.procfs.Procfs
import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.MountRequest
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.RootMountOptions
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
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
        Overlayfs,
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
        val lower = when (val result = vfs.createContext(
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
        val upper = when (val result = vfs.createContext(Tmpfs.name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                lower.release()
                println("VFS: failed to create overlay upper tmpfs: ${result.error}")
                return false
            }
        }
        val overlay = vfs.createContext(
            Overlayfs.name,
            RootMountOptions(fileSystemOptions = OverlayfsOptions(lower.root, upper.root)),
        )
        lower.release()
        upper.release()
        val context = when (overlay) {
            is VfsResult.Ok -> overlay.value
            is VfsResult.Err -> {
                println("VFS: failed to create OverlayFS: ${overlay.error}")
                return false
            }
        }

        val devFlags = MountFlags.of(MountFlag.NO_EXEC, MountFlag.NO_SUID)
        if (!mount(context, "/dev", Devtmpfs, devFlags)) {
            context.release()
            return false
        }

        kernelContext = context
        ProcessManager.getKernelProcess()!!.context = context
        println("VFS: mounted zstd EROFS with tmpfs overlay at '/'")
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

    private fun mount(
        context: FileSystemContext,
        path: String,
        fileSystem: FileSystemType,
        flags: MountFlags,
    ): Boolean {
        val target = VfsPathname.fromString(path)
        val created = vfs.createNode(
            VfsOperationContext.KERNEL,
            context,
            target,
            NodeCreation(NodeKind.Directory, FileMode(0x1EDu)),
        )
        if (created is VfsResult.Err && created.error != VfsError.ALREADY_EXISTS) {
            println("VFS: failed to create $path in overlay: ${created.error}")
            return false
        }
        return when (val result = vfs.mount(
            VfsOperationContext.KERNEL,
            context,
            target,
            MountRequest(fileSystem.name, flags = flags),
        )) {
            is VfsResult.Ok -> true
            is VfsResult.Err -> {
                println("VFS: failed to mount ${fileSystem.name} at $path: ${result.error}")
                false
            }
        }
    }
}
