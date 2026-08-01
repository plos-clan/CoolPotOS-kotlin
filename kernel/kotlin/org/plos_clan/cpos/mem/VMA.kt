package org.plos_clan.cpos.mem

import org.plos_clan.cpos.tasks.Process

data class MemChunk(val start: ULong, var end: ULong, val flags: ULong, val name: String?)

class VMA(process: Process, clone: PageDirectory?) {
    var pageDirectory: PageDirectory =
        clone?.cloneDirectory() ?: KernelPageDirectory.getDirectory()
    val chunks = mutableListOf<MemChunk>()
}
