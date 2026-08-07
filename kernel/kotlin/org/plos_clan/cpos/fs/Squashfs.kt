@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.fs

import bridge.cp_zstd_decompress
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.module.ModuleData
import org.plos_clan.cpos.utils.IrqSpinLock

data class SquashfsOptions(val data: ModuleData) : FileSystemOptions

object Squashfs : FileSystemType {
    override val name: String = "squashfs"

    override fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock> {
        val data = (options as? SquashfsOptions)?.data
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val instance = SquashfsInstance(data)
        if (!instance.valid) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return VfsResult.Ok(SuperBlock(this, instance) { superBlock ->
            instance.rootInode(superBlock)
                ?: error("SquashFS root inode is invalid")
        })
    }
}

private class SquashfsInstance(private val archive: ModuleData) : SuperBlockBackend {
    private companion object {
        const val MAGIC = 0x7371_7368uL
        const val VERSION_MAJOR = 4
        const val VERSION_MINOR = 0
        const val COMPRESSOR_ZSTD = 6
        const val METADATA_UNCOMPRESSED = 0x8000
        const val METADATA_SIZE_MASK = 0x7fff
        const val BLOCK_UNCOMPRESSED = 1 shl 24
        const val BLOCK_SIZE_MASK = 0x00ff_ffff
        const val MAX_METADATA_BLOCK = 8192
        const val BASIC_DIRECTORY = 1
        const val BASIC_FILE = 2
        const val BASIC_SYMLINK = 3
        const val EXTENDED_DIRECTORY = 8
        const val EXTENDED_FILE = 9
        const val EXTENDED_SYMLINK = 10
    }

    private val blockSize: Int
    private val inodeTableStart: ULong
    private val directoryTableStart: ULong
    private val rootReference: ULong
    val valid: Boolean
    private val inodeCache = mutableMapOf<ULong, Inode>()
    private val inodeLock = IrqSpinLock()

    internal fun rootInode(superBlock: SuperBlock): Inode? = inode(superBlock, rootReference)

    init {
        val header = archive.copyOfRange(0, minOf(96, archive.size))
        valid = header.size == 96 &&
            u32(header, 0) == MAGIC &&
            u16(header, 28) == VERSION_MAJOR &&
            u16(header, 30) == VERSION_MINOR &&
            u16(header, 20) == COMPRESSOR_ZSTD &&
            u32(header, 12) in 4096u..1_048_576u &&
            u64(header, 40) <= archive.size.toULong() &&
            u64(header, 64) < archive.size.toULong() &&
            u64(header, 72) < archive.size.toULong()
        blockSize = if (valid) u32(header, 12).toInt() else 4096
        inodeTableStart = if (valid) u64(header, 64) else 0uL
        directoryTableStart = if (valid) u64(header, 72) else 0uL
        rootReference = if (valid) u64(header, 32) else 0uL
    }

    private fun inode(superBlock: SuperBlock, reference: ULong): Inode? = inodeLock.withLock {
        inodeCache[reference] ?: readNode(reference)?.let { node ->
            Inode(
                id = InodeId(reference),
                superBlock = superBlock,
                backend = node.backend(this, superBlock),
                metadata = node.metadata,
            ).also { inodeCache[reference] = it }
        }
    }

    private fun lookup(superBlock: SuperBlock, directory: DirectoryNode, name: VfsName): Inode? {
        val entry = directory.entries(this).firstOrNull { it.name == name } ?: return null
        return inode(superBlock, entry.reference)
    }

    private fun entries(superBlock: SuperBlock, directory: DirectoryNode): List<DirectoryEntry> =
        directory.entries(this).mapNotNull { entry ->
            val inode = inode(superBlock, entry.reference) ?: return@mapNotNull null
            DirectoryEntry(entry.name, inode.id, inode.type)
        }

    private fun readFile(file: FileNode, destination: ByteArray, destinationOffset: Int, count: Int,
                 position: FilePosition): IoResult = file.read(this, destination, destinationOffset, count, position)

    private fun readNode(reference: ULong): Node? {
        val reader = try {
            MetadataReader(inodeTableStart, reference)
        } catch (_: InvalidMetadata) {
            return null
        }
        val type = reader.u16()
        val mode = reader.u16()
        val uid = reader.u16()
        val gid = reader.u16()
        reader.u32()
        reader.u32()
        val metadata = InodeMetadata(
            mode = FileMode(mode.toUInt()),
            uid = uid.toUInt(),
            gid = gid.toUInt(),
        )
        return when (type) {
            BASIC_DIRECTORY -> {
                val start = reader.u32()
                val links = reader.u32()
                val size = reader.u16()
                val offset = reader.u16()
                reader.u32()
                DirectoryNode(
                    metadata.copy(linkCount = links.toUInt()),
                    start,
                    offset,
                    size + 3,
                    extended = false,
                )
            }
            BASIC_FILE -> {
                val start = reader.u32()
                val fragment = reader.u32()
                reader.u32()
                val size = reader.u32()
                if (fragment != 0xffff_ffffuL) return null
                val count = ((size + blockSize.toULong() - 1uL) / blockSize.toULong()).toInt()
                val blocks = IntArray(count) { reader.u32().toInt() }
                FileNode(metadata.copy(size = size), start, blocks)
            }
            BASIC_SYMLINK -> {
                val links = reader.u32()
                val size = reader.u32()
                val target = reader.bytes(size.toInt())
                SymlinkNode(metadata.copy(size = size, linkCount = links.toUInt()),
                    VfsPathname.fromBytes(target))
            }
            else -> null
        }
    }

    private abstract class Node(val metadata: InodeMetadata) {
        abstract fun backend(instance: SquashfsInstance, superBlock: SuperBlock): InodeBackend
    }

    private class DirectoryNode(
        metadata: InodeMetadata,
        private val start: ULong,
        private val offset: Int,
        private val size: Int,
        private val extended: Boolean,
    ) : Node(metadata) {
        private var cached: List<Entry>? = null

        override fun backend(instance: SquashfsInstance, superBlock: SuperBlock): InodeBackend =
            DirectoryBackend(instance, superBlock, this)

        fun entries(instance: SquashfsInstance): List<Entry> {
            cached?.let { return it }
            val result = instance.readDirectory(start, offset, size, extended)
            cached = result
            return result
        }
    }

    private class FileNode(metadata: InodeMetadata, val start: ULong, val blocks: IntArray) : Node(metadata) {
        override fun backend(instance: SquashfsInstance, superBlock: SuperBlock): InodeBackend =
            RegularBackend(instance, this)

        fun read(instance: SquashfsInstance, destination: ByteArray, destinationOffset: Int,
                 count: Int, position: FilePosition): IoResult {
            val size = metadata.size
            if (count == 0 || position.value < 0 || position.value.toULong() >= size) {
                return IoResult.success(0)
            }
            val available = minOf(count.toULong(), size - position.value.toULong()).toInt()
            var copied = 0
            while (copied < available) {
                val absolute = position.value.toULong() + copied.toULong()
                val index = (absolute / instance.blockSize.toULong()).toInt()
                val offset = (absolute % instance.blockSize.toULong()).toInt()
                val logical = minOf(instance.blockSize, (size - index.toULong() * instance.blockSize.toULong()).toInt())
                val block = instance.dataBlock(start, blocks, index, logical) ?: return IoResult.failure(VfsError.IO)
                val chunk = minOf(available - copied, logical - offset)
                block.copyInto(destination, destinationOffset + copied, offset, offset + chunk)
                copied += chunk
            }
            position.value += copied
            return IoResult.success(copied)
        }
    }

    private class SymlinkNode(metadata: InodeMetadata, val target: VfsPathname) : Node(metadata) {
        override fun backend(instance: SquashfsInstance, superBlock: SuperBlock): InodeBackend =
            SymlinkBackend(target)
    }

    private data class Entry(val name: VfsName, val reference: ULong)

    private fun readDirectory(start: ULong, offset: Int, size: Int, extended: Boolean): List<Entry> {
        if (size < 3) return emptyList()
        val reader = try {
            MetadataReader(directoryTableStart, (start shl 16) or offset.toULong())
        } catch (_: InvalidMetadata) {
            return emptyList()
        }
        val result = mutableListOf<Entry>()
        var consumed = 0
        while (consumed + 12 <= size - 3) {
            val count = reader.u32()
            val inodeBlock = reader.u32()
            reader.u32()
            consumed += 12
            repeat((count + 1uL).toInt()) {
                val inodeOffset = reader.u16()
                reader.s16()
                val type = reader.u16()
                val nameSize = reader.u16()
                val nameBytes = reader.bytes(nameSize + 1)
                consumed += 8 + nameBytes.size
                if (type in 1..3) {
                    val name = VfsName.fromPath(nameBytes, 0, nameBytes.size)
                    result += Entry(name, (inodeBlock shl 16) or inodeOffset.toULong())
                }
            }
        }
        return result
    }

    private fun dataBlock(start: ULong, sizes: IntArray, index: Int, logicalSize: Int): ByteArray? {
        if (index !in sizes.indices) return null
        var physical = start
        for (i in 0 until index) physical += (sizes[i] and BLOCK_SIZE_MASK).toULong()
        val encoded = sizes[index]
        val stored = encoded and BLOCK_SIZE_MASK
        if (stored == 0) return ByteArray(logicalSize)
        if (physical > Int.MAX_VALUE.toULong() || stored > archive.size - physical.toInt()) return null
        val source = archive.copyOfRange(physical.toInt(), physical.toInt() + stored)
        if (encoded and BLOCK_UNCOMPRESSED != 0) {
            return source.takeIf { it.size == logicalSize }
        }
        val destination = ByteArray(logicalSize)
        val result = source.usePinned { input ->
            destination.usePinned { output ->
                cp_zstd_decompress(output.addressOf(0), destination.size.toULong(),
                    input.addressOf(0), source.size.toULong())
            }
        }
        return destination.takeIf { result == logicalSize }
    }

    private inner class MetadataReader(private val tableStart: ULong, reference: ULong) {
        private var relative = reference shr 16
        private var position = (reference and 0xffffuL).toInt()
        private var block = ByteArray(0)
        private var blockSize = 0
        private var nextRelative = 0uL

        init {
            block = load(relative) ?: throw InvalidMetadata()
            if (position > blockSize) throw InvalidMetadata()
        }

        private fun load(relative: ULong): ByteArray? {
            if (tableStart + relative > Int.MAX_VALUE.toULong()) return null
            val physical = (tableStart + relative).toInt()
            if (physical > archive.size - 2) return null
            val header = archive.copyOfRange(physical, physical + 2)
            val encoded = u16(header, 0)
            val stored = encoded and METADATA_SIZE_MASK
            if (stored > archive.size - physical - 2) return null
            val source = archive.copyOfRange(physical + 2, physical + 2 + stored)
            nextRelative = relative + 2uL + stored.toULong()
            return if (encoded and METADATA_UNCOMPRESSED != 0) {
                blockSize = source.size
                source
            } else ByteArray(MAX_METADATA_BLOCK).also { destination ->
                val result = source.usePinned { input ->
                    destination.usePinned { output ->
                        cp_zstd_decompress(output.addressOf(0), destination.size.toULong(),
                            input.addressOf(0), source.size.toULong())
                    }
                }
                if (result < 0) throw InvalidMetadata()
                blockSize = result
            }.copyOf(blockSize)
        }

        private fun byte(): Int {
            if (position == blockSize) {
                relative = nextRelative
                position = 0
                block = load(relative) ?: throw InvalidMetadata()
                blockSize = block.size
            }
            if (position !in 0 until blockSize) throw InvalidMetadata()
            return block[position++].toInt() and 0xff
        }

        fun u16(): Int = byte() or (byte() shl 8)
        fun s16(): Int = u16().toShort().toInt()
        fun u32(): ULong = byte().toULong() or (byte().toULong() shl 8) or
            (byte().toULong() shl 16) or (byte().toULong() shl 24)
        fun u64(): ULong = u32() or (u32() shl 32)
        fun bytes(count: Int): ByteArray = ByteArray(count) { byte().toByte() }
    }

    private class InvalidMetadata : Throwable()

    private class DirectoryBackend(
        private val instance: SquashfsInstance,
        private val superBlock: SuperBlock,
        private val node: DirectoryNode,
    ) : org.plos_clan.cpos.fs.DirectoryBackend {
        override val type: InodeType = InodeType.DIRECTORY
        override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
            VfsResult.Ok(instance.lookup(superBlock, node, name))
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Ok(Handle(this))
        fun snapshot(): List<DirectoryEntry> = instance.entries(superBlock, node)
        private class Handle(private val backend: DirectoryBackend) : OpenFileBackend {
            override fun iterate(inode: Inode, position: FilePosition,
                emit: (DirectoryEntry, Long) -> Boolean): VfsResult<Unit> {
                val entries = backend.snapshot()
                var index = position.value.coerceAtLeast(0).toInt()
                while (index < entries.size) {
                    val next = index.toLong() + 1
                    if (!emit(entries[index], next)) break
                    index++
                    position.value = next
                }
                return VfsResult.Ok(Unit)
            }
        }
    }

    private class RegularBackend(private val instance: SquashfsInstance, private val node: FileNode) : InodeBackend {
        override val type: InodeType = InodeType.REGULAR
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Ok(object : OpenFileBackend {
                override fun read(inode: Inode, destination: ByteArray, destinationOffset: Int,
                    count: Int, position: FilePosition): IoResult =
                    instance.readFile(node, destination, destinationOffset, count, position)
            })
    }

    private class SymlinkBackend(private val target: VfsPathname) : org.plos_clan.cpos.fs.SymlinkBackend {
        override val type: InodeType = InodeType.SYMLINK
        override fun readLink(inode: Inode): VfsResult<VfsPathname> = VfsResult.Ok(target)
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, offset: Int): ULong =
        u16(data, offset).toULong() or (u16(data, offset + 2).toULong() shl 16)

    private fun u64(data: ByteArray, offset: Int): ULong =
        u32(data, offset) or (u32(data, offset + 4) shl 32)
}
