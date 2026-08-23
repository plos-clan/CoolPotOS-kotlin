package org.plos_clan.cpos.fs.overlay

import org.plos_clan.cpos.fs.vfs.FileSystemOptions
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.SuperBlockBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsResult

data class OverlayfsOptions(
    val lower: VfsPath,
    val upper: VfsPath,
) : FileSystemOptions

object Overlayfs : FileSystemType("overlay", 0x794c_7630uL) {
    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> {
        val configuration = options as? OverlayfsOptions
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return OverlayInstance.open(configuration)
    }
}
