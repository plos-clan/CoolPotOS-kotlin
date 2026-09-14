package org.plos_clan.cpos.fs.vfs

internal interface SealableFile {
    fun getSeals(): Int
    fun addSeals(inode: Inode, seals: Int): VfsResult<Unit>
}
