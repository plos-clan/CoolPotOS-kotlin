package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.Dentry
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.PollEvents

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

internal class MountsFile(
    private val process: Process,
    private val mountInfo: Boolean,
) : RegularFileBackend() {
    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> {
        if (options.access.canWrite) return VfsResult.Err(VfsError.PERMISSION_DENIED)
        val context = process.context?.forkAtRoot() ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return try {
            val handle = object : ProcTextHandle(render(context), { render(context) }, null, true) {
                override fun release() = context.release()
            }
            VfsResult.Ok(ProcPollHandle({ context.namespace.version }, handle, PollEvents.NORMAL_INPUT))
        } catch (failure: Throwable) {
            context.release()
            throw failure
        }
    }

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

    private fun render(context: FileSystemContext): ByteArray {
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
