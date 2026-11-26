@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import bridge.vm_space_t
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.concurrent.atomicGet
import kotlin.concurrent.atomicSet

internal class TranslationCache(directory: ULong) : AutoCloseable {
    private val storage = LongArray(headerWords + maskWords).pin()
    val handle: CPointer<vm_space_t> = storage.addressOf(0).reinterpret()

    init {
        handle.pointed.root = directory
    }

    fun invalidate(start: ULong, end: ULong) {
        val pages = (end - start) / PAGE_SIZE_BYTES
        val individual = start >= USER_VIRTUAL_ADDRESS_LIMIT || pages <= INDIVIDUAL_PAGE_LIMIT
        bridge.tlb_invalidate(handle, start, if (individual) pages else 0uL, ids, cpuCount.toULong())
    }

    override fun close() {
        val words = storage.get()
        for (index in headerWords until words.size) check(words.atomicGet(index) == 0L)
        storage.unpin()
    }

    companion object {
        private const val INDIVIDUAL_PAGE_LIMIT = 32uL
        private val headerWords = (sizeOf<vm_space_t>() / Long.SIZE_BYTES).toInt()
        private val cpuCount = bridge.mp_request.response?.pointed?.cpu_count?.toInt() ?: 1
        private val maskWords = (cpuCount - 1) / Long.SIZE_BITS + 1
        private val apics = LongArray(cpuCount) { -1L }.pin()
        private val ids = apics.addressOf(0)

        fun join(cpu: Int, apicId: UInt) {
            apics.get().atomicSet(cpu, apicId.toLong())
            bridge.write_cr3(bridge.read_cr3())
        }
    }
}
