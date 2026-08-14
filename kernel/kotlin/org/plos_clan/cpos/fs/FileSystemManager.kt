package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.procfs.Procfs
import org.plos_clan.cpos.module.ModuleManager
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Cmdline

object FileSystemManager {
    val vfs = Vfs()

    var kernelContext: FileSystemContext? = null
        private set

    fun initialize(): Boolean {
        return register(Tmpfs) && register(Devtmpfs) && register(Procfs) &&
            register(Erofs) && register(Overlayfs)
    }

    fun mountRootfs(): Boolean {
        if (kernelContext != null) return true
        val moduleName = Cmdline["rootfs"] ?: "cachyos-rootfs-x86_64.erofs"
        val module = ModuleManager[moduleName] ?: run {
            println("VFS: rootfs module '$moduleName' is unavailable")
            return false
        }
        val lower = when (val result = vfs.createContext(
            Erofs.name,
            MountOptions(
                source = moduleName,
                flags = MountFlags.READ_ONLY,
                fileSystem = ErofsOptions(module.data),
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
                println("VFS: failed to create overlay upper tmpfs: ${result.error}")
                return false
            }
        }
        val context = when (val result = vfs.createContext(
            Overlayfs.name,
            MountOptions(fileSystem = OverlayfsOptions(lower.root, upper.root)),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                println("VFS: failed to create OverlayFS: ${result.error}")
                return false
            }
        }

        val devFlags = MountFlags(MountFlags.NO_EXEC.bits or MountFlags.NO_SUID.bits)
        if (!mount(context, "/dev", Devtmpfs, devFlags)) return false

        val procFlags = MountFlags(
            MountFlags.NO_EXEC.bits or MountFlags.NO_DEVICE.bits or MountFlags.NO_SUID.bits,
        )
        if (!mount(context, "/proc", Procfs, procFlags)) return false
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
        val created = vfs.mkdir(context, target)
        if (created is VfsResult.Err && created.error != VfsError.ALREADY_EXISTS) {
            println("VFS: failed to create $path in overlay: ${created.error}")
            return false
        }
        return when (val result = vfs.mount(
            context,
            target,
            fileSystem.name,
            MountOptions(flags = flags),
        )) {
            is VfsResult.Ok -> true
            is VfsResult.Err -> {
                println("VFS: failed to mount ${fileSystem.name} at $path: ${result.error}")
                false
            }
        }
    }
}
