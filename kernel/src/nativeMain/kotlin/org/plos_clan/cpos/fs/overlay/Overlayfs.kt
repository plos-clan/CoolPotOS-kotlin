package org.plos_clan.cpos.fs

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
