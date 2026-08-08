package org.plos_clan.cpos.fs

import org.plos_clan.cpos.module.ModuleManager
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Cmdline

object FileSystemManager {
    val vfs = Vfs()

    var kernelContext: FileSystemContext? = null
        private set

    fun initialize(): Boolean {
        return register(Tmpfs) && register(Devfs) && register(Erofs) && register(Overlayfs)
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

        val devPath = VfsPathname.fromString("/dev")
        when (val result = vfs.mkdir(context, devPath)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> if (result.error != VfsError.ALREADY_EXISTS) {
                println("VFS: failed to create /dev in overlay: ${result.error}")
                return false
            }
        }
        val devfsFlags = MountFlags(MountFlags.NO_EXEC.bits or MountFlags.NO_SUID.bits)
        when (val result = vfs.mount(context, devPath, Devfs.name, MountOptions(flags = devfsFlags))) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> {
                println("VFS: failed to mount devfs at /dev: ${result.error}")
                return false
            }
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
}
