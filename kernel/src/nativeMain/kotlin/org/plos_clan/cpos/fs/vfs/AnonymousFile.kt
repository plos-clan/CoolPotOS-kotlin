package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.utils.IrqSpinLock

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
        mode: FileMode = FileMode(0x1FFu),
    ): VfsResult<OpenFileDescription> = OpenFileDescription.open(
        caller,
        context.root,
        createInode(
            context,
            backend,
            InodeMetadata(mode = mode, linkCount = 0u),
        ),
        options,
    )
}
