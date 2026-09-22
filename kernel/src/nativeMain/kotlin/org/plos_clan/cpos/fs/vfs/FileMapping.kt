package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.addressspace.FileRegionBacking

internal interface MappableFile {
    fun map(file: OpenFileDescription, shared: Boolean, access: ULong, maximumAccess: ULong):
        VfsResult<MappedFile>
}

internal open class MappedFile(
    file: OpenFileDescription,
    val maximumAccess: ULong,
) : FileRegionBacking(file) {
    override val cacheSource
        get() = file.cacheSource ?: this

    override val identity
        get() = file.inode

    override val sharedMemoryIdentity: Any
        get() = file.inode

    override fun read(offset: ULong, destination: ByteArray): Int = readFile(offset, destination)
}
