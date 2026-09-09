package org.plos_clan.cpos.fs.vfs

internal class VfsPathResolver(
    private val maxSymlinkDepth: Int,
) {
    init {
        require(maxSymlinkDepth > 0)
    }

    fun resolve(
        caller: VfsOperationContext,
        context: FileSystemContext,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
        followFinalMount: Boolean = true,
        resolution: PathResolution = PathResolution.DEFAULT,
    ): VfsResult<VfsPath> = resolveAt(
        caller = caller,
        context = context,
        directory = context.workingDirectory,
        pathname = pathname,
        followFinalSymlink = followFinalSymlink,
        followFinalMount = followFinalMount,
        resolution = resolution,
    )

    fun resolveAt(
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        followFinalSymlink: Boolean = true,
        allowEmpty: Boolean = false,
        followFinalMount: Boolean = true,
        resolution: PathResolution = PathResolution.DEFAULT,
    ): VfsResult<VfsPath> {
        if (pathname.size == 0) {
            return if (allowEmpty) VfsResult.Ok(directory)
            else VfsResult.Err(VfsError.NOT_FOUND)
        }
        if (pathname.isAbsolute &&
            resolution.boundary == PathResolutionBoundary.BENEATH
        ) {
            return VfsResult.Err(VfsError.CROSS_DEVICE)
        }
        val boundary = directory.takeUnless {
            resolution.boundary == PathResolutionBoundary.NONE
        }
        val start = when {
            resolution.boundary == PathResolutionBoundary.IN_ROOT -> directory
            pathname.isAbsolute -> context.root
            else -> directory
        }
        if (start.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val components = when (val result = pathname.components()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val result = walk(
            caller,
            context,
            start,
            components,
            followFinalSymlink || pathname.requiresDirectory,
            followFinalMount,
            resolution,
            boundary,
            followStartMount = pathname.isAbsolute && boundary == null &&
                (components.isNotEmpty() || followFinalMount),
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
        allowUnreachable: Boolean = false,
    ): VfsResult<ByteArray> {
        val components = mutableListOf<ByteArray>()
        var current = initial
        while (current != context.root) {
            if (current.dentry === current.mount.root) {
                val attachment = current.mount.attachment
                if (attachment == null) {
                    if (allowUnreachable) break
                    return VfsResult.Err(VfsError.NOT_FOUND)
                }
                current = attachment
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
        caller: VfsOperationContext,
        context: FileSystemContext,
        directory: VfsPath,
        pathname: VfsPathname,
        resolution: PathResolution = PathResolution.DEFAULT,
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
        if (pathname.isAbsolute &&
            resolution.boundary == PathResolutionBoundary.BENEATH
        ) {
            return VfsResult.Err(VfsError.CROSS_DEVICE)
        }
        val name = components.last()
        val boundary = directory.takeUnless {
            resolution.boundary == PathResolutionBoundary.NONE
        }
        val start = when {
            resolution.boundary == PathResolutionBoundary.IN_ROOT -> directory
            pathname.isAbsolute -> context.root
            else -> directory
        }
        if (start.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val parent = when (
            val result = walk(
                caller,
                context,
                start,
                components.dropLast(1),
                followFinalSymlink = true,
                resolution = resolution,
                boundary = boundary,
                followStartMount = pathname.isAbsolute && boundary == null,
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (parent.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return VfsResult.Ok(ParentPath(parent, name))
    }

    private fun walk(
        caller: VfsOperationContext,
        context: FileSystemContext,
        start: VfsPath,
        components: List<VfsName>,
        followFinalSymlink: Boolean,
        followFinalMount: Boolean = true,
        resolution: PathResolution = PathResolution.DEFAULT,
        boundary: VfsPath? = null,
        followStartMount: Boolean = false,
    ): VfsResult<VfsPath> {
        var current = if (followStartMount) {
            when (val result = followMounts(
                context.namespace,
                start,
                resolution.allowMountCrossing,
            )) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
        } else {
            start
        }
        var symlinkDepth = 0
        var requireDirectory = false
        val remaining = ArrayDeque(components)

        while (remaining.isNotEmpty()) {
            val name = remaining.removeFirst()
            when {
                name.isDot -> {
                    when (val access = current.requireSearch(caller, resolution.cachedOnly)) {
                        is VfsResult.Ok -> continue
                        is VfsResult.Err -> return access
                    }
                }
                name.isDotDot -> {
                    when (val access = current.requireSearch(caller, resolution.cachedOnly)) {
                        is VfsResult.Ok -> Unit
                        is VfsResult.Err -> return access
                    }
                    current = when (val result = walkUp(
                        context,
                        current,
                        boundary,
                        resolution,
                    )) {
                        is VfsResult.Ok -> result.value
                        is VfsResult.Err -> return result
                    }
                    continue
                }
            }

            val parent = current
            val next = when (val result = lookupChild(
                caller,
                context,
                parent,
                name,
                followMount = remaining.isNotEmpty() || followFinalMount,
                resolution = resolution,
            )) {
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
            if (resolution.symlinks == SymlinkResolution.NO_SYMLINKS) {
                return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
            }
            if (MountFlag.NO_SYMLINK_FOLLOW in next.mount.flags) {
                return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
            }

            if (++symlinkDepth > maxSymlinkDepth) {
                return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
            }
            val symlink = inode.backend as? SymlinkBackend
                ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
            if (symlink is MagicLinkBackend) {
                if (resolution.symlinks != SymlinkResolution.FOLLOW) {
                    return VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
                }
                if (boundary != null) return VfsResult.Err(VfsError.CROSS_DEVICE)
                current = when (val result = symlink.resolveLink(
                    caller,
                    inode,
                    resolution.cachedOnly,
                )) {
                    is VfsResult.Ok -> result.value.also { next.mount.recordAccess(caller, inode) }
                    is VfsResult.Err -> return result
                }
                if (!resolution.allowMountCrossing && current.mount !== parent.mount) {
                    return VfsResult.Err(VfsError.CROSS_DEVICE)
                }
                continue
            }
            val target = when (val result = symlink.readLink(
                caller,
                inode,
                resolution.cachedOnly,
            )) {
                is VfsResult.Ok -> result.value.also { next.mount.recordAccess(caller, inode) }
                is VfsResult.Err -> return result
            }
            if (target.size == 0) return VfsResult.Err(VfsError.NOT_FOUND)
            if (target.requiresDirectory && remaining.isEmpty()) requireDirectory = true
            val targetComponents = when (val result = target.components()) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            current = if (!target.isAbsolute) {
                parent
            } else when (resolution.boundary) {
                PathResolutionBoundary.NONE -> context.root
                PathResolutionBoundary.BENEATH -> return VfsResult.Err(VfsError.CROSS_DEVICE)
                PathResolutionBoundary.IN_ROOT -> checkNotNull(boundary)
            }
            if (!resolution.allowMountCrossing && current.mount !== parent.mount) {
                return VfsResult.Err(VfsError.CROSS_DEVICE)
            }
            if (target.isAbsolute && boundary == null &&
                (targetComponents.isNotEmpty() || remaining.isNotEmpty() || followFinalMount)
            ) {
                current = when (val result = followMounts(
                    context.namespace,
                    current,
                    resolution.allowMountCrossing,
                )) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return result
                }
            }
            for (index in targetComponents.indices.reversed()) {
                remaining.addFirst(targetComponents[index])
            }
        }
        if (requireDirectory && current.inode?.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return VfsResult.Ok(current)
    }

    fun lookupChild(
        caller: VfsOperationContext,
        context: FileSystemContext,
        parent: VfsPath,
        name: VfsName,
        followMount: Boolean = true,
        resolution: PathResolution = PathResolution.DEFAULT,
    ): VfsResult<VfsPath> {
        val dentry = when (val result = parent.dentry.lookupChild(
            caller,
            name,
            cachedOnly = resolution.cachedOnly,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val path = VfsPath(parent.mount, dentry)
        if (!followMount) return VfsResult.Ok(path)
        return followMounts(context.namespace, path, resolution.allowMountCrossing)
    }

    private fun followMounts(
        namespace: MountNamespace,
        initial: VfsPath,
        allowMountCrossing: Boolean,
    ): VfsResult<VfsPath> {
        var current = initial
        while (true) {
            val mounted = namespace.mountedAt(current) ?: return VfsResult.Ok(current)
            if (!allowMountCrossing) return VfsResult.Err(VfsError.CROSS_DEVICE)
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

    private fun VfsPath.requireSearch(
        caller: VfsOperationContext,
        cachedOnly: Boolean,
    ): VfsResult<Unit> {
        val inode = inode ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = inode.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        return backend.checkAccess(caller, inode, AccessPermissions.EXECUTE, cachedOnly)
    }

    private fun walkUp(
        context: FileSystemContext,
        initial: VfsPath,
        boundary: VfsPath?,
        resolution: PathResolution,
    ): VfsResult<VfsPath> {
        var current = initial
        if (current == boundary) {
            return when (resolution.boundary) {
                PathResolutionBoundary.BENEATH -> VfsResult.Err(VfsError.CROSS_DEVICE)
                PathResolutionBoundary.IN_ROOT -> VfsResult.Ok(current)
                PathResolutionBoundary.NONE -> error("unbounded resolution has a boundary")
            }
        }
        if (current == context.root) {
            return VfsResult.Ok(current)
        }

        while (current.dentry === current.mount.root) {
            val attachment = current.mount.attachment ?: return VfsResult.Ok(current)
            if (!resolution.allowMountCrossing) {
                return VfsResult.Err(VfsError.CROSS_DEVICE)
            }
            current = attachment
            if (current == context.root) {
                return VfsResult.Ok(current)
            }
        }

        val parent = current.dentry.parent ?: return VfsResult.Ok(current)
        return VfsResult.Ok(VfsPath(current.mount, parent))
    }
}
