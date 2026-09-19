package org.plos_clan.cpos.mem

interface DmaBuffer : IoBuffer {
    val physicalAddress: ULong
    val virtualAddress: ULong
    val size: Int

    fun close()
}
