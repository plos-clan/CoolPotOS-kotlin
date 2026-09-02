package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.utils.IrqSpinLock

internal abstract class AnonymousFileBackend(
    final override val type: InodeType,
    val anonymousName: String,
    private val access: AccessMode = AccessMode.READ_WRITE,
) : InodeBackend, OpenFileBackend {
    final override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> =
        if (options.access == access) VfsResult.Ok(this)
        else VfsResult.Err(VfsError.BAD_DESCRIPTOR)

    final override fun syncHandle(
        caller: VfsOperationContext,
        inode: Inode,
        dataOnly: Boolean,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.INVALID_ARGUMENT)
}

internal class AnonymousFileFactory {
    private val lock = IrqSpinLock()
    private var nextInodeId = ULong.MAX_VALUE

    fun createInode(
        context: FileSystemContext,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Inode = Inode(
        id = lock.withLock { InodeId(nextInodeId--) },
        superBlock = context.root.mount.superBlock,
        backend = backend,
        initialAttributes = InodeAttributeSnapshot(
            InodeAttributes(metadata),
            CacheValidity.Persistent,
        ),
    )

    fun open(
        caller: VfsOperationContext,
        context: FileSystemContext,
        backend: InodeBackend,
        options: OpenOptions,
        metadata: InodeMetadata = InodeMetadata(mode = FileMode(0x1FFu), linkCount = 0u),
        initialStatusFlags: Int = 0,
    ): VfsResult<OpenFileDescription> = OpenFileDescription.open(
        caller,
        context.root,
        createInode(context, backend, metadata),
        options,
        initialStatusFlags = initialStatusFlags,
    )
}
