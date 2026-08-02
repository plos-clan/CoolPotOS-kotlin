package org.plos_clan.cpos.mem

const val VMA_READ = 0x1uL
const val VMA_WRITE = 0x2uL
const val VMA_EXEC = 0x4uL

data class MemChunk(val start: ULong, var end: ULong, val flags: ULong, val name: String?)

class VMA internal constructor(
    val pageDirectory: PageDirectory,
) {
    val chunks = mutableListOf<MemChunk>()
}
