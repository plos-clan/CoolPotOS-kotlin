package org.plos_clan.cpos.mem.dmabuf

import org.plos_clan.cpos.fs.vfs.PinnedFilePages

internal class FileDmaBuffer(private val pins: List<PinnedFilePages>) :
    CpuDmaBuffer(pins.flatMap { it.pages }) {
    override fun close() = pins.forEach(PinnedFilePages::close)
}
