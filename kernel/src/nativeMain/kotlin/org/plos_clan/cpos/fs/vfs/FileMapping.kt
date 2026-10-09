package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.addressspace.FileRegionBacking

internal interface FileMappingProvider {
    val mapping: MappableFile?
}

internal interface MappableFile : FileMappingProvider {
    override val mapping: MappableFile get() = this

    fun map(
        file: OpenFileDescription,
        shared: Boolean,
        access: ULong,
        maximumAccess: ULong,
        offset: ULong,
        length: ULong,
    ): VfsResult<MappedFile>
}

internal open class MappedFile(
    file: OpenFileDescription,
    val maximumAccess: ULong,
    val offset: ULong = 0uL,
) : FileRegionBacking(file) {
    override val cacheSource
        get() = file.cacheSource ?: this

    override val identity: Any
        get() = file.inode

    override val sharedMemoryIdentity: Any
        get() = file.inode

    override fun read(offset: ULong, destination: ByteArray): Int = readFile(offset, destination)
}
