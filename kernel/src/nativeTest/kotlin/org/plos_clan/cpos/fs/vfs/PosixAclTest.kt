package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosixAclTest {
    @Test
    fun namedUsersAndGroupsRespectMaskAndDoNotFallThroughToOther() {
        val acl = assertNotNull(PosixAcl.parse(encoded(
            Triple(1, 6, -1), Triple(2, 2, 100), Triple(4, 4, -1),
            Triple(8, 2, 200), Triple(16, 4, -1), Triple(32, 7, -1),
        )))
        val metadata = InodeMetadata(mode = FileMode(0x180u), uid = 10u, gid = 20u)
        val owner = VfsOperationContext(10u, 30u, 1u)
        val user = VfsOperationContext(100u, 30u, 1u)
        val group = VfsOperationContext(101u, 200u, 1u)
        val member = VfsOperationContext(101u, 30u, 1u, supplementaryGroups = listOf(20, 200))
        val other = VfsOperationContext(102u, 30u, 1u)
        assertTrue(acl.permits(owner, metadata, AccessPermissions.WRITE))
        assertFalse(acl.permits(user, metadata, AccessPermissions.READ))
        assertFalse(acl.permits(user, metadata, AccessPermissions.WRITE))
        assertFalse(acl.permits(group, metadata, AccessPermissions.READ))
        assertTrue(acl.permits(member, metadata, AccessPermissions.READ))
        assertFalse(acl.permits(member, metadata, AccessPermissions.WRITE))
        assertTrue(acl.permits(other, metadata, AccessPermissions.WRITE))
        assertEquals(0x1a7u, acl.mode(metadata.mode).bits)
        val changed = acl.chmod(FileMode(0x1b0u))
        assertTrue(changed.permits(user, metadata, AccessPermissions.WRITE))
        assertFalse(changed.permits(other, metadata, AccessPermissions.READ))
        assertNotNull(PosixAcl.parse(changed.bytes()))
    }

    @Test
    fun rejectsMalformedEntriesAndUnmaskedNamedEntries() {
        assertNull(PosixAcl.parse(ByteArray(4)))
        assertNull(PosixAcl.parse(encoded(Triple(1, 8, -1), Triple(4, 0, -1), Triple(32, 0, -1))))
        assertNull(PosixAcl.parse(encoded(Triple(1, 6, 0), Triple(4, 0, -1), Triple(32, 0, -1))))
        assertNull(PosixAcl.parse(encoded(
            Triple(1, 6, -1), Triple(2, 4, 100), Triple(4, 0, -1), Triple(32, 0, -1),
        )))
        assertNull(PosixAcl.parse(encoded(
            Triple(1, 6, -1), Triple(2, 4, 100), Triple(2, 4, 100),
            Triple(4, 0, -1), Triple(16, 4, -1), Triple(32, 0, -1),
        )))
    }

    private fun encoded(vararg entries: Triple<Int, Int, Int>): ByteArray {
        val bytes = ByteArray(4 + entries.size * 8)
        val data = LittleEndianBuffer(bytes)
        data.writeU32(0, 2u)
        entries.forEachIndexed { index, (tag, permissions, id) ->
            val offset = 4 + index * 8
            data.writeU16(offset, tag.toUShort())
            data.writeU16(offset + 2, permissions.toUShort())
            data.writeU32(offset + 4, id.toUInt())
        }
        return bytes
    }
}
