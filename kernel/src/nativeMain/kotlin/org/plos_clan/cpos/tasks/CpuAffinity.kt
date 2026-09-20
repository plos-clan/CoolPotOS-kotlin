package org.plos_clan.cpos.tasks

internal object CpuAffinity {
    fun online(): ByteArray {
        val highest = SMProcessor.locals.values.maxOf { it.cpuid }.toInt()
        val bytes = ByteArray((highest / Long.SIZE_BITS + 1) * Long.SIZE_BYTES)
        for (cpu in SMProcessor.locals.values) {
            val id = cpu.cpuid.toInt()
            bytes[id / 8] = (bytes[id / 8].toInt() or (1 shl (id % 8))).toByte()
        }
        return bytes
    }

    fun contains(mask: ByteArray, cpu: Int): Boolean =
        cpu >= 0 && cpu / 8 < mask.size && mask[cpu / 8].toInt() and (1 shl (cpu % 8)) != 0
}
