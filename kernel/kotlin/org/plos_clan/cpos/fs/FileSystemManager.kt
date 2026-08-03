package org.plos_clan.cpos.fs

object FileSystemManager {
    val vfs = Vfs()

    var kernelContext: FileSystemContext? = null
        private set

    fun initialize(): Boolean {
        if (kernelContext != null) {
            return true
        }

        if (!register(Tmpfs) || !register(Devfs)) {
            return false
        }

        val context = when (val result = vfs.createContext(Tmpfs.name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                println("VFS: failed to mount root tmpfs: ${result.error}")
                return false
            }
        }

        val devPath = VfsPathname.fromString("/dev")
        when (val result = vfs.mkdir(context, devPath)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> if (result.error != VfsError.ALREADY_EXISTS) {
                println("VFS: failed to create /dev: ${result.error}")
                return false
            }
        }

        val devfsFlags = MountFlags(MountFlags.NO_EXEC.bits or MountFlags.NO_SUID.bits)
        when (
            val result = vfs.mount(
                context,
                devPath,
                Devfs.name,
                MountOptions(flags = devfsFlags),
            )
        ) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> {
                println("VFS: failed to mount devfs at /dev: ${result.error}")
                return false
            }
        }

        kernelContext = context
        println("VFS: mounted tmpfs at '/'")
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
