package org.plos_clan.cpos.fs.fuse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.utils.LittleEndianBuffer

class FuseAbiTest {
    @Test
    fun encodesFuse745RequestHeader() {
        val request = FuseRequest(FuseOpcode.LOOKUP, 17uL, UInt.SIZE_BYTES).apply {
            writeU32(0, 0x12345678u)
            prepare(23uL, VfsOperationContext(1000u, 1001u, 42u))
        }
        val fields = LittleEndianBuffer(request.bytes)

        assertEquals(44u, fields.readU32(0))
        assertEquals(FuseOpcode.LOOKUP.value, fields.readU32(4))
        assertEquals(23uL, fields.readU64(8))
        assertEquals(17uL, fields.readU64(16))
        assertEquals(1000u, fields.readU32(24))
        assertEquals(1001u, fields.readU32(28))
        assertEquals(42u, fields.readU32(32))
        assertEquals(0x12345678u, fields.readU32(FuseAbi.IN_HEADER_SIZE))
    }

    @Test
    fun advertisesOnlyImplementedCapabilities() {
        assertTrue(FuseFeature.ASYNC_READ.mask and FuseFeature.supportedMask != 0uL)
        assertTrue(FuseFeature.BIG_WRITES.mask and FuseFeature.supportedMask != 0uL)
        assertTrue(FuseFeature.WRITEBACK_CACHE.mask and FuseFeature.supportedMask == 0uL)
        assertTrue(FuseFeature.PASSTHROUGH.mask and FuseFeature.supportedMask == 0uL)
    }
}
