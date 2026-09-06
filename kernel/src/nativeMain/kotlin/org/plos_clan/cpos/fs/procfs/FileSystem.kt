package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.Dentry
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process

object FilesystemsFile : ProcFSRender {
    override fun render(): ByteArray =
        buildString {
            FileSystemManager.vfs.snapshotFileSystems().forEach { fileSystem ->
                if (!fileSystem.requiresDevice) {
                    append("nodev")
                }
                append('\t')
                append(fileSystem.name)
                append('\n')
            }
        }.encodeToByteArray()
}

object MountsFile {
    private fun StringBuilder.appendField(value: String) {
        value.forEach { character ->
            when (character) {
                ' ' -> append("\\040")
                '\t' -> append("\\011")
                '\n' -> append("\\012")
                '\\' -> append("\\134")
                else -> append(character)
            }
        }
    }

    private fun StringBuilder.appendOptions(flags: MountFlags) {
        append(if (MountFlag.READ_ONLY in flags) "ro" else "rw")
        for (flag in MountFlag.entries) {
            val option = flag.optionName ?: continue
            if (flag in flags) append(',').append(option)
        }
    }

    fun render(process: Process, mountInfo: Boolean = false): ByteArray {
        val context = process.context ?: return ByteArray(0)
        val mounts = context.namespace.snapshotMounts()
        val root = context.root

        return buildString {
            for ((path, mount) in mounts) {
                val displayPath = when (val result = FileSystemManager.vfs.absolutePath(
                    context = context,
                    initial = if (mount === root.mount) root else path,
                )) {
                    is VfsResult.Ok -> result.value.decodeToString()
                    is VfsResult.Err -> continue
                }

                if (mountInfo) {
                    append(mount.id).append(' ')
                    append(mount.attachment?.mount?.id ?: mount.id).append(' ')
                    val device = mount.superBlock.deviceNumber
                    append(device.major).append(':').append(device.minor).append(' ')
                    val names = ArrayList<String>()
                    var dentry: Dentry? = if (mount === root.mount) root.dentry else mount.root
                    while (dentry?.parent != null) {
                        names += dentry.name.toString()
                        dentry = dentry.parent
                    }
                    appendField(names.asReversed().joinToString("/", prefix = "/"))
                    append(' ')
                    appendField(displayPath)
                    append(' ')
                    appendOptions(mount.flags)
                    append(" - ").append(mount.fileSystemName).append(' ')
                    appendField(mount.source)
                    append(' ').append(if (MountFlag.READ_ONLY in mount.flags) "ro" else "rw")
                } else {
                    appendField(mount.source)
                    append(' ')
                    appendField(displayPath)
                    append(' ').append(mount.fileSystemName).append(' ')
                    appendOptions(mount.flags)
                }
                for (option in mount.superBlock.backend.mountOptions) append(',').append(option)
                append(if (mountInfo) "\n" else " 0 0\n")
            }
        }.encodeToByteArray()
    }
}
