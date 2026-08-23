package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
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

    fun render(process: Process): ByteArray {
        val context = process.context ?: return ByteArray(0)
        val mounts = context.namespace.snapshotMounts()

        return buildString {
            for ((path, mount) in mounts) {
                val displayPath = when (val result = FileSystemManager.vfs.absolutePath(
                    context = context,
                    initial = path,
                )) {
                    is VfsResult.Ok -> result.value.decodeToString()
                    is VfsResult.Err -> continue
                }

                appendField(mount.source)
                append(' ')
                appendField(displayPath)
                append(' ')
                append(mount.superBlock.type.name)
                append(' ')
                appendOptions(mount.flags)
                append(" 0 0\n")
            }
        }.encodeToByteArray()
    }
}
