package org.plos_clan.cpos.fs

internal class VfsPathResolver(
    private val maxSymlinkDepth: Int,
) {
    init {
        require(maxSymlinkDepth > 0)
    }

    fun resolve(
        context: FileSystemContext,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
    ): VfsResult<VfsPath> = resolveAt(
        context = context,
        directory = context.workingDirectory,
        pathname = pathname,
        followFinalSymlink = followFinalSymlink,
    )

    fun resolveAt(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
        allowEmpty: Boolean = false,
    ): VfsResult<VfsPath> {
        if (pathname.size == 0) {
            return if (allowEmpty) VfsResult.Ok(directory)
            else VfsResult.Err(VfsError.NOT_FOUND)
        }
        val start = when {
            pathname.isAbsolute -> context.root
            directory.inode?.type == InodeType.DIRECTORY -> directory
            else -> return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val components = when (val result = pathname.components()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val result = walk(
            context,
            start,
            components,
            followFinalSymlink || pathname.requiresDirectory,
        )
        if (result is VfsResult.Ok && pathname.requiresDirectory &&
            result.value.inode?.type != InodeType.DIRECTORY
        ) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return result
    }

    fun absolutePath(
        context: FileSystemContext,
        initial: VfsPath,
    ): VfsResult<ByteArray> {
        val components = mutableListOf<ByteArray>()
        var current = initial
        while (current != context.root) {
            if (current.dentry === current.mount.root) {
                current = current.mount.attachment
                    ?: return VfsResult.Err(VfsError.NOT_FOUND)
                if (current == context.root) {
                    break
                }
            }

            components += current.dentry.name.copyBytes()
            val parent = current.dentry.parent
                ?: return VfsResult.Err(VfsError.NOT_FOUND)
            current = VfsPath(current.mount, parent)
        }

        if (components.isEmpty()) {
            return VfsResult.Ok(byteArrayOf('/'.code.toByte()))
        }

        val pathSize = components.fold(components.size - 1L) { size, component ->
            size + component.size
        } + 1L
        if (pathSize > Int.MAX_VALUE) {
            return VfsResult.Err(VfsError.FILE_TOO_LARGE)
        }

        val result = ByteArray(pathSize.toInt())
        var offset = 0
        result[offset++] = '/'.code.toByte()
        components.asReversed().forEachIndexed { index, component ->
            if (index != 0) {
                result[offset++] = '/'.code.toByte()
            }
            component.copyInto(result, destinationOffset = offset)
            offset += component.size
        }
        return VfsResult.Ok(result)
    }

    data class ParentPath(val path: VfsPath, val name: VfsName)

    fun resolveParent(
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
    ): VfsResult<ParentPath> {
        if (pathname.size == 0) {
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        val components = when (val result = pathname.components()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (components.isEmpty()) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val name = components.last()
        val start = if (pathname.isAbsolute) context.root else directory
        val parent = when (val result = walk(context, start, components.dropLast(1), true)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return VfsResult.Ok(ParentPath(parent, name))
    }

    private fun walk(
        context: FileSystemContext,
        start: VfsPath,
        components: List<VfsName>,
        followFinalSymlink: Boolean,
    ): VfsResult<VfsPath> {
        var current = followMounts(context.namespace, start)
        var symlinkDepth = 0
        val remaining = ArrayDeque(components)

        while (remaining.isNotEmpty()) {
            val name = remaining.removeFirst()
            when {
                name.isDot -> continue
                name.isDotDot -> {
                    current = walkUp(context, current)
                    continue
                }
            }

            val parent = current
            val next = when (val result = lookupChild(context, parent, name)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            val inode = next.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
            val shouldFollow = inode.type == InodeType.SYMLINK &&
                (remaining.isNotEmpty() || followFinalSymlink)
            if (!shouldFollow) {
                current = next
                continue
            }
            if (MountFlag.NO_SYMLINK_FOLLOW in next.mount.flags) {
                return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
            }

            if (++symlinkDepth > maxSymlinkDepth) {
                return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
            }
            val symlink = inode.backend as? SymlinkBackend
                ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
            val target = when (val result = symlink.readLink(inode)) {
                is VfsResult.Ok -> result.value.also { next.mount.recordAccess(inode) }
                is VfsResult.Err -> return result
            }
            val targetComponents = when (val result = target.components()) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            current = if (target.isAbsolute) context.root else parent
            for (index in targetComponents.indices.reversed()) {
                remaining.addFirst(targetComponents[index])
            }
        }
        return VfsResult.Ok(current)
    }

    fun lookupChild(
        context: FileSystemContext,
        parent: VfsPath,
        name: VfsName,
        followMount: Boolean = true,
    ): VfsResult<VfsPath> {
        val directory = parent.inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        parent.dentry.cachedChild(name)?.let { cached ->
            val inode = cached.inode()
            if (backend.isLookupStable(name, inode)) {
                if (inode == null) return VfsResult.Err(VfsError.NOT_FOUND)
                val path = VfsPath(parent.mount, cached)
                return VfsResult.Ok(
                    if (followMount) followMounts(context.namespace, path) else path,
                )
            }
        }

        val inode = when (val result = backend.lookup(directory, name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (inode == null) {
            if (backend.isLookupStable(name, null)) {
                parent.dentry.cacheChild(name, null)
            }
            return VfsResult.Err(VfsError.NOT_FOUND)
        }
        val dentry = parent.dentry.cacheChild(name, inode)
        val path = VfsPath(parent.mount, dentry)
        return VfsResult.Ok(if (followMount) followMounts(context.namespace, path) else path)
    }

    private fun followMounts(namespace: MountNamespace, initial: VfsPath): VfsPath {
        var current = initial
        while (true) {
            val mounted = namespace.mountedAt(current) ?: return current
            current = VfsPath(mounted, mounted.root)
        }
    }

    fun isDescendant(candidate: Dentry, ancestor: Dentry): Boolean {
        var current: Dentry? = candidate
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun walkUp(context: FileSystemContext, initial: VfsPath): VfsPath {
        var current = initial
        if (current == context.root) {
            return current
        }

        while (current.dentry === current.mount.root) {
            current = current.mount.attachment ?: return current
            if (current == context.root) {
                return current
            }
        }

        val parent = current.dentry.parent ?: return current
        return VfsPath(current.mount, parent)
    }
}
