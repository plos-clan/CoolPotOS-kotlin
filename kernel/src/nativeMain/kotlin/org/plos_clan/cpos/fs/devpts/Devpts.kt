package org.plos_clan.cpos.fs.devpts

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.char.tty.Pty
import org.plos_clan.cpos.fs.DeviceOpenFile
import org.plos_clan.cpos.fs.vfs.*
import org.plos_clan.cpos.utils.KernelMutex

internal data class DevptsOptions(
    val uid: UInt? = null,
    val gid: UInt? = null,
    val mode: UInt = 0x180u,
    val ptmxMode: UInt = 0u,
    val maximum: Int = DeviceNumber.MAX_MINOR.toInt() + 1,
) : FileSystemOptions {
    companion object {
        fun parse(parameters: FileSystemParameters): VfsResult<DevptsOptions> {
            var options = DevptsOptions()
            for (parameter in parameters) {
                if (parameter.key == "newinstance" && parameter is FileSystemParameter.Flag) continue
                val value = (parameter as? FileSystemParameter.StringValue)?.value
                    ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                val number = value.toUIntOrNull(if (parameter.key == "mode" || parameter.key == "ptmxmode") 8 else 10)
                    ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                options = when (parameter.key) {
                    "uid" -> if (number != UInt.MAX_VALUE) options.copy(uid = number) else null
                    "gid" -> if (number != UInt.MAX_VALUE) options.copy(gid = number) else null
                    "mode" -> options.copy(mode = number and 0x1FFu)
                    "ptmxmode" -> options.copy(ptmxMode = number and 0x1FFu)
                    "max" -> if (number <= DeviceNumber.MAX_MINOR + 1u) options.copy(maximum = number.toInt()) else null
                    else -> null
                } ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            return VfsResult.Ok(options)
        }
    }
}

object Devpts : FileSystemType("devpts", 0x1CD1uL) {
    override fun configure(source: String?, parameters: FileSystemParameters): VfsResult<FileSystemOptions> =
        DevptsOptions.parse(parameters)

    override fun validateParameter(existing: List<FileSystemParameter>, parameter: FileSystemParameter): VfsResult<Unit> =
        when (val result = DevptsOptions.parse(FileSystemParameters.copyOf(listOf(parameter)))) {
            is VfsResult.Err -> result
            is VfsResult.Ok -> VfsResult.Ok(Unit)
        }

    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> = when (options) {
        EmptyFileSystemOptions -> VfsResult.Ok(DevptsInstance(DevptsOptions()))
        is DevptsOptions -> VfsResult.Ok(DevptsInstance(options))
        else -> VfsResult.Err(VfsError.INVALID_ARGUMENT)
    }
}

private class DevptsInstance(private val options: DevptsOptions) :
    SuperBlockBackend, DirectoryBackend, MutableInodeBackend, OpenFileBackend {
    override fun evict(inode: Inode) { if (inode.backend !== this) inode.backend.evict(inode) }
    override fun release() {}

    override val type = InodeType.DIRECTORY
    private val lock = KernelMutex()
    private val slaves = mutableMapOf<Int, Inode>()
    private var nextNumber = 0
    private lateinit var root: Inode
    private lateinit var ptmx: Inode

    override val mountOptions: List<String>
        get() = listOfNotNull(options.uid?.let { "uid=$it" }, options.gid?.let { "gid=$it" },
            "mode=${options.mode.toString(8)}", "ptmxmode=${options.ptmxMode.toString(8)}", "max=${options.maximum}")

    override fun createRoot(superBlock: SuperBlock): Inode {
        root = inode(superBlock, 1uL, this, InodeMetadata(mode = FileMode(0x1EDu), linkCount = 2u))
        ptmx = inode(superBlock, 2uL, Multiplexer(), InodeMetadata(
            mode = FileMode(options.ptmxMode), deviceNumber = checkNotNull(DeviceNumber.create(5u, 2u)).value,
        ))
        return root
    }

    override fun open(caller: VfsOperationContext, inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> = VfsResult.Ok(this)

    override fun lookup(caller: VfsOperationContext, directory: Inode, name: VfsName): VfsResult<DirectoryLookup> = lock.withLock {
        val text = name.toString()
        val inode = if (text == "ptmx") ptmx else text.toIntOrNull()?.takeIf { it.toString() == text }?.let(slaves::get)
        VfsResult.Ok(DirectoryLookup(inode?.takeIf { it.metadata().linkCount != 0u }, CacheValidity.Volatile))
    }

    override fun iterate(caller: VfsOperationContext, inode: Inode, position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean): VfsResult<Unit> {
        val entries = lock.withLock {
            listOf("." to root, ".." to root, "ptmx" to ptmx) + slaves.entries.sortedBy { it.key }
                .filter { it.value.metadata().linkCount != 0u }.map { it.key.toString() to it.value }
        }
        for ((index, entry) in entries.withIndex()) {
            val cookie = if (index < 3) index.toLong() else entry.first.toLong() + 3
            if (cookie < position.value) continue
            val name = (VfsName.fromBytes(entry.first.encodeToByteArray()) as VfsResult.Ok).value
            if (!emit(DirectoryEntry(name, entry.second.id, entry.second.type), cookie + 1)) break
            position.value = cookie + 1
        }
        return VfsResult.Ok(Unit)
    }

    private inner class Multiplexer : MutableInodeBackend {
        override val type = InodeType.CHARACTER_DEVICE

        override fun open(caller: VfsOperationContext, inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> = lock.withLock {
            if (nextNumber >= this@DevptsInstance.options.maximum) return@withLock VfsResult.Err(VfsError.NO_SPACE)
            val index = nextNumber
            val number = checkNotNull(DeviceNumber.create(136u, index.toUInt()))
            val pty = Pty(index, number.value,
                unlink = {
                    lock.withLock {
                        slaves[index]?.updateMetadata(InodeTimestampEvent.STATUS_CHANGED) { it.copy(linkCount = 0u) }
                    }
                },
                release = { lock.withLock { slaves.remove(index); nextNumber = minOf(nextNumber, index) } },
            )
            val device = Device(index.toString(), DeviceType.CHARACTER, number, pty.session)
            slaves[index] = inode(root.superBlock, index.toULong() + 3uL, Slave(device), InodeMetadata(
                mode = FileMode(this@DevptsInstance.options.mode),
                uid = this@DevptsInstance.options.uid ?: caller.uid,
                gid = this@DevptsInstance.options.gid ?: caller.gid,
                deviceNumber = number.value,
            ))
            while (slaves.containsKey(nextNumber)) nextNumber++
            val peer = root.superBlock.root.cacheChild(
                (VfsName.fromBytes(index.toString().encodeToByteArray()) as VfsResult.Ok).value,
                DirectoryLookup(slaves[index], CacheValidity.Volatile),
            )
            VfsResult.Ok(DeviceOpenFile.open(device, pty.Master(), peer))
        }
    }

    private class Slave(private val device: Device) : MutableInodeBackend {
        override val type = InodeType.CHARACTER_DEVICE

        override fun open(caller: VfsOperationContext, inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            when (val result = device.backend.open(device, caller, options)) {
                is VfsResult.Err -> result
                is VfsResult.Ok -> VfsResult.Ok(DeviceOpenFile.open(device, result.value))
            }
    }

    private fun inode(superBlock: SuperBlock, number: ULong, backend: InodeBackend, metadata: InodeMetadata): Inode =
        Inode(InodeId(number), superBlock, backend, InodeAttributeSnapshot(InodeAttributes(metadata), CacheValidity.Persistent))
}
