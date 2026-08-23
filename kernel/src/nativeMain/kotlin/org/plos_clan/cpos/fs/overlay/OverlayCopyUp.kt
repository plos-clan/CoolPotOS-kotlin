package org.plos_clan.cpos.fs.overlay

import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.DirectoryBackend
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeMode
import org.plos_clan.cpos.fs.vfs.ExtendedAttributeName
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RemoveMode
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer

internal object OverlayCopyUp {
    fun ensureWritable(location: OverlayLocation): Boolean {
        if (location.upper != null) return true
        val lower = location.lower ?: return false
        val parent = location.parent ?: return false
        val name = location.name ?: return false
        val upperParent = ensureUpper(parent) ?: return false
        val parentInode = upperParent.inode ?: return false
        val parentBackend = parentInode.backend as? DirectoryBackend ?: return false
        val lowerInode = lower.inode ?: return false
        val metadata = lowerInode.metadata()
        val kind = when (lowerInode.type) {
            InodeType.REGULAR -> NodeKind.Regular
            InodeType.DIRECTORY -> NodeKind.Directory
            InodeType.SYMLINK -> {
                val target = (lowerInode.backend as? SymlinkBackend)?.readLink(lowerInode)
                    ?: return false
                when (target) {
                    is VfsResult.Ok -> NodeKind.SymbolicLink(target.value)
                    is VfsResult.Err -> return false
                }
            }
            InodeType.PIPE -> NodeKind.Fifo
            InodeType.SOCKET -> NodeKind.Socket
            InodeType.CHARACTER_DEVICE,
            InodeType.BLOCK_DEVICE,
            -> NodeKind.Device(lowerInode.type, metadata.deviceNumber)
        }
        val upperInode = when (val created = parentBackend.create(
            parentInode,
            name,
            NodeCreation(kind, metadata.mode, metadata.uid, metadata.gid),
        )) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> return false
        }
        val upperPath = VfsPath(upperParent.mount, upperParent.dentry.cacheChild(name, upperInode))
        location.upper = upperPath
        if (lowerInode.type == InodeType.REGULAR && !copyFile(lowerInode, upperInode)) {
            parentBackend.remove(parentInode, name, upperInode, RemoveMode.FILE)
            upperParent.dentry.markChildNegative(name, upperPath.dentry)
            location.upper = null
            return false
        }
        if (!copyExtendedAttributes(lowerInode, upperInode)) {
            parentBackend.remove(
                parentInode,
                name,
                upperInode,
                if (lowerInode.type == InodeType.DIRECTORY) RemoveMode.DIRECTORY else RemoveMode.FILE,
            )
            upperParent.dentry.markChildNegative(name, upperPath.dentry)
            location.upper = null
            return false
        }
        upperInode.updateMetadata(InodeTimestampEvent.NONE) { current ->
            current.copy(
                timestamps = location.overlayInode?.metadata()?.timestamps ?: metadata.timestamps,
            )
        }
        return true
    }

    private fun copyExtendedAttributes(source: Inode, destination: Inode): Boolean {
        val names = when (val result = source.backend.listExtendedAttributes(source)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result.error == VfsError.NOT_SUPPORTED
        }
        var offset = 0
        while (offset < names.size) {
            var end = offset
            while (end < names.size && names[end] != 0.toByte()) end++
            val name = when (val result = ExtendedAttributeName.fromBytes(names.copyOfRange(offset, end))) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return false
            }
            val value = when (val result = source.backend.getExtendedAttribute(source, name)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return false
            }
            if (destination.backend.setExtendedAttribute(
                destination,
                name,
                value,
                ExtendedAttributeMode.CREATE,
            ) is VfsResult.Err) return false
            offset = end + 1
        }
        return true
    }

    private fun copyFile(source: Inode, destination: Inode): Boolean {
        val sourceHandle = when (val result = source.backend.open(source, OpenOptions())) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return false
        }
        val destinationHandle = when (val result = destination.backend.open(
            destination, OpenOptions(access = AccessMode.WRITE)
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                sourceHandle.release()
                return false
            }
        }
        return try {
            val size = source.metadata().size
            if (size > Long.MAX_VALUE.toULong()) return false
            val buffer = ByteArray(8192)
            val transferBuffer = ByteArrayBuffer(buffer)
            val destinationBuffer = checkNotNull(transferBuffer.prepareWrite(0, buffer.size))
            val sourceBuffer = checkNotNull(transferBuffer.prepareRead(0, buffer.size))
            val position = FilePosition()
            var copied = 0uL
            while (copied < size) {
                val count = minOf(buffer.size.toULong(), size - copied).toInt()
                val read = sourceHandle.read(source, destinationBuffer, 0, count, position)
                if (!read.isSuccess || read.bytesTransferred == 0) break
                val write = destinationHandle.write(
                    destination,
                    sourceBuffer,
                    0,
                    read.bytesTransferred,
                    FilePosition(copied.toLong()),
                    false,
                )
                if (!write.isSuccess || write.bytesTransferred != read.bytesTransferred) break
                copied += read.bytesTransferred.toULong()
            }
            copied == size
        } finally {
            destinationHandle.release()
            sourceHandle.release()
        }
    }

    fun ensureUpper(location: OverlayLocation): VfsPath? {
        location.upper?.let { return it }
        val parent = location.parent ?: return null
        val upperParent = ensureUpper(parent) ?: return null
        val parentInode = upperParent.inode ?: return null
        val backend = parentInode.backend as? DirectoryBackend ?: return null
        val lowerInode = location.lower?.inode ?: return null
        val name = location.name ?: return null
        val metadata = lowerInode.metadata()
        val created = backend.create(
            parentInode,
            name,
            NodeCreation(NodeKind.Directory, metadata.mode, metadata.uid, metadata.gid),
        )
        val inode = when (created) {
            is VfsResult.Ok -> created.value
            is VfsResult.Err -> return null
        }
        if (!copyExtendedAttributes(lowerInode, inode)) {
            backend.remove(parentInode, name, inode, RemoveMode.DIRECTORY)
            return null
        }
        inode.updateMetadata(InodeTimestampEvent.NONE) { current ->
            current.copy(
                timestamps = location.overlayInode?.metadata()?.timestamps ?: metadata.timestamps,
            )
        }
        return VfsPath(upperParent.mount, upperParent.dentry.cacheChild(name, inode)).also {
            location.upper = it
        }
    }
}
