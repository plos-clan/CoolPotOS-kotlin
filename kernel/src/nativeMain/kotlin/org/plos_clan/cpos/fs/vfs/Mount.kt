@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.plos_clan.cpos.utils.IrqSpinLock

class Mount internal constructor(
    val superBlock: SuperBlock,
    val fileSystemName: String,
    val source: String,
    val root: Dentry = superBlock.root,
    flags: MountFlags = MountFlags.NONE,
    attachment: VfsPath? = null,
) {
    private val references = AtomicInt(1)
    private val attachmentReference = AtomicReference(attachment)
    val flags = flags.withDefaultAtimePolicy()

    init {
        require(root.superBlock === superBlock)
        check(attachment?.mount?.retain() != false)
    }

    internal val attachment: VfsPath?
        get() = attachmentReference.load()

    internal fun retain(): Boolean {
        var observed = references.load()
        while (observed in 1 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) return true
            observed = references.load()
        }
        return false
    }

    internal fun release() {
        var observed = references.load()
        while (observed > 0) {
            if (!references.compareAndSet(observed, observed - 1)) {
                observed = references.load()
                continue
            }
            if (observed == 1) releaseResources()
            return
        }
    }

    internal fun tryBeginUnmount(): Boolean = references.compareAndSet(2, 0)

    internal fun completeUnmount() = releaseResources()

    internal fun detachFromParent() {
        attachmentReference.exchange(null)?.mount?.release()
    }

    internal fun moveTo(target: VfsPath): VfsPath? {
        if (!target.mount.retain()) return null
        return attachmentReference.exchange(target).also {
            if (it == null) target.mount.release()
        }
    }

    internal fun isDescendantOf(ancestor: Mount): Boolean {
        var current: Mount? = this
        while (current != null && current !== ancestor) current = current.attachment?.mount
        return current === ancestor
    }

    internal fun recordAccess(caller: VfsOperationContext, inode: Inode) {
        val update = when {
            MountFlag.READ_ONLY in flags || MountFlag.NO_ATIME in flags ->
                InodeTimestampEvent.NONE
            inode.type == InodeType.DIRECTORY && MountFlag.NO_DIRECTORY_ATIME in flags ->
                InodeTimestampEvent.NONE
            inode.type != InodeType.REGULAR && inode.type != InodeType.DIRECTORY &&
                inode.type != InodeType.SYMLINK -> InodeTimestampEvent.NONE
            MountFlag.STRICT_ATIME in flags -> InodeTimestampEvent.ACCESSED
            else -> InodeTimestampEvent.RELATIVE_ACCESS
        }
        if (update != InodeTimestampEvent.NONE) {
            superBlock.backend.updateTimestamps(caller, inode, update)
        }
    }

    private fun releaseResources() {
        superBlock.root.releaseCachedChildren()
        superBlock.backend.release()
        detachFromParent()
    }
}

data class VfsPath(val mount: Mount, val dentry: Dentry) {
    val inode: Inode?
        get() = dentry.inode()
}

class MountNamespace internal constructor(val root: Mount) {
    private val lock = IrqSpinLock()
    private val mounts = mutableMapOf<VfsPath, Mount>()
    private val references = AtomicInt(0)

    internal fun retain(): Boolean {
        var observed = references.load()
        while (observed in 0 until Int.MAX_VALUE) {
            if (references.compareAndSet(observed, observed + 1)) return true
            observed = references.load()
        }
        return false
    }

    internal fun release() {
        var observed = references.load()
        while (observed > 0) {
            val updated = if (observed == 1) CLOSED else observed - 1
            if (!references.compareAndSet(observed, updated)) {
                observed = references.load()
                continue
            }
            if (updated == CLOSED) releaseResources()
            return
        }
    }

    internal fun mountedAt(path: VfsPath): Mount? =
        lock.withLock { mounts[path] }

    internal fun attach(
        target: VfsPath,
        superBlock: SuperBlock,
        fileSystemName: String,
        source: String,
        flags: MountFlags,
    ): VfsResult<Unit> = lock.withLock {
        if (!contains(target.mount)) return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        if (mounts.containsKey(target)) return@withLock VfsResult.Err(VfsError.BUSY)

        mounts[target] = Mount(
            superBlock = superBlock,
            fileSystemName = fileSystemName,
            source = source,
            flags = flags,
            attachment = target,
        )
        VfsResult.Ok(Unit)
    }

    internal fun unmount(mount: Mount): VfsResult<Unit> {
        val detached = lock.withLock {
            val target = attachedAt(mount) ?: return@withLock false
            if (!mount.tryBeginUnmount()) return@withLock false
            mounts.remove(target)
            true
        }
        if (!detached) return VfsResult.Err(VfsError.BUSY)
        mount.completeUnmount()
        return VfsResult.Ok(Unit)
    }

    internal fun move(mount: Mount, target: VfsPath): VfsResult<Unit> {
        val previous = lock.withLock {
            val source = attachedAt(mount)
                ?: return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            if (!contains(target.mount)) {
                return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            }
            if (mounts.containsKey(target)) return@withLock VfsResult.Err(VfsError.BUSY)
            if (target.mount.isDescendantOf(mount)) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }

            val attachment = mount.moveTo(target)
                ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            mounts.remove(source)
            mounts[target] = mount
            VfsResult.Ok(attachment)
        }
        return when (previous) {
            is VfsResult.Ok -> {
                previous.value.mount.release()
                VfsResult.Ok(Unit)
            }
            is VfsResult.Err -> previous
        }
    }

    internal fun detach(mount: Mount): VfsResult<Unit> {
        val subtree = lock.withLock {
            attachedAt(mount) ?: return@withLock null
            buildList<Mount> {
                val iterator = mounts.entries.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next().value
                    if (!candidate.isDescendantOf(mount)) continue
                    add(candidate)
                    iterator.remove()
                }
            }
        } ?: return VfsResult.Err(VfsError.BUSY)
        mount.detachFromParent()
        mount.release()
        subtree.forEach(Mount::release)
        return VfsResult.Ok(Unit)
    }

    internal fun snapshotMounts(): Map<VfsPath, Mount> = lock.withLock {
        LinkedHashMap<VfsPath, Mount>(mounts.size + 1).apply {
            put(VfsPath(root, root.root), root)
            putAll(mounts)
        }
    }

    private fun contains(mount: Mount): Boolean = mount === root || attachedAt(mount) != null

    private fun attachedAt(mount: Mount): VfsPath? =
        mount.attachment?.takeIf { mounts[it] === mount }

    private fun releaseResources() {
        val detached = lock.withLock {
            mounts.values.toList().also { mounts.clear() }
        }
        detached.forEach(Mount::release)
        root.release()
    }

    private companion object {
        const val CLOSED = -1
    }
}

class FileSystemContext internal constructor(
    val namespace: MountNamespace,
    root: VfsPath = VfsPath(namespace.root, namespace.root.root),
    workingDirectory: VfsPath = root,
) {
    private val lock = IrqSpinLock()
    private var currentRoot: VfsPath? = root
    private var currentWorkingDirectory: VfsPath? = workingDirectory

    init {
        check(namespace.retain())
        check(root.mount.retain())
        check(workingDirectory.mount.retain())
    }

    val root: VfsPath
        get() = lock.withLock { checkNotNull(currentRoot) }

    val workingDirectory: VfsPath
        get() = lock.withLock { checkNotNull(currentWorkingDirectory) }

    internal fun changeRoot(path: VfsPath): Boolean {
        if (!path.mount.retain()) return false
        val previous = lock.withLock { currentRoot?.also { currentRoot = path } }
        if (previous == null) {
            path.mount.release()
            return false
        }
        previous.mount.release()
        return true
    }

    internal fun changeWorkingDirectory(path: VfsPath): Boolean {
        if (!path.mount.retain()) return false
        val previous = lock.withLock {
            currentWorkingDirectory?.also { currentWorkingDirectory = path }
        }
        if (previous == null) {
            path.mount.release()
            return false
        }
        previous.mount.release()
        return true
    }

    internal fun fork(): FileSystemContext = lock.withLock {
        FileSystemContext(
            namespace,
            checkNotNull(currentRoot),
            checkNotNull(currentWorkingDirectory),
        )
    }

    internal fun release() {
        val (root, workingDirectory) = lock.withLock {
            val root = currentRoot ?: return
            val workingDirectory = checkNotNull(currentWorkingDirectory)
            currentRoot = null
            currentWorkingDirectory = null
            root to workingDirectory
        }
        workingDirectory.mount.release()
        root.mount.release()
        namespace.release()
    }
}
