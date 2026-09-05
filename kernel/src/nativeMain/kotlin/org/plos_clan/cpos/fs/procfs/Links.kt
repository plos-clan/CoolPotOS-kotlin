package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.AnonymousFileBackend
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.MagicLinkBackend
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.TaskState

internal class ProcFileSymlink(
    private val target: PidHandle,
    private val acquire: () -> OpenFileDescription?,
) : MagicLinkBackend {
    override fun readLink(caller: VfsOperationContext, inode: Inode): VfsResult<VfsPathname> =
        withFile(caller) { file ->
            val fileInode = file.inode
            val inodeId = fileInode.id.value
            val anonymousName = (file.backend as? AnonymousFileBackend)?.anonymousName
            val target = when {
                fileInode.type == InodeType.PIPE && file.path.dentry === file.path.mount.root ->
                    "pipe:[$inodeId]"
                fileInode.type == InodeType.SOCKET -> "socket:[$inodeId]"
                anonymousName != null -> "anon_inode:[$anonymousName]"
                else -> null
            }
            if (target != null) return@withFile VfsResult.Ok(VfsPathname.fromString(target))

            val context = ProcessManager.currentProcess()?.context ?: FileSystemManager.kernelContext
                ?: return@withFile VfsResult.Err(VfsError.NOT_FOUND)
            val path = when (val result = FileSystemManager.vfs.absolutePath(
                context,
                file.path,
                allowUnreachable = true,
            )) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return@withFile result
            }
            val targetPath = if (file.path.dentry.isUnlinked) path + DELETED_SUFFIX else path
            VfsResult.Ok(VfsPathname.fromBytes(targetPath))
        }

    override fun resolveLink(caller: VfsOperationContext, inode: Inode): VfsResult<VfsPath> =
        withFile(caller) { file ->
            if (file.path.inode?.sameIdentity(file.inode) == true) VfsResult.Ok(file.path)
            else VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
        }

    private inline fun <T> withFile(
        caller: VfsOperationContext,
        action: (OpenFileDescription) -> VfsResult<T>,
    ): VfsResult<T> {
        val leader = target.thread
        val process = leader.process
        if (!process.state.canReceiveSignals || leader.state == TaskState.ZOMBIE) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        val inspector = ProcessManager.currentThread()
        val privileged = inspector?.capabilities?.hasEffective(CapEnum.SYS_PTRACE) ?: caller.privileged
        val userIds = process.credentials.userIds
        val groupIds = process.credentials.groupIds
        val permitted = leader.capabilities.permitted
        val effective = inspector?.capabilities?.effective ?: 0uL
        if (caller.processId != process.id.toUInt() && !privileged &&
            (!process.dumpable ||
                caller.uid != userIds.real.toUInt() || caller.uid != userIds.effective.toUInt() ||
                caller.uid != userIds.saved.toUInt() || caller.gid != groupIds.real.toUInt() ||
                caller.gid != groupIds.effective.toUInt() || caller.gid != groupIds.saved.toUInt() ||
                permitted and effective != permitted)
        ) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        val file = acquire() ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return try {
            action(file)
        } finally {
            file.release()
        }
    }
}

private val DELETED_SUFFIX = " (deleted)".encodeToByteArray()
