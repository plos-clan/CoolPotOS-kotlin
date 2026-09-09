package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlagUpdate
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.syscall.fs.NewMountSyscalls.MountAttributeCodec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountAttributeCodecTest {
    @Test
    fun decodesInitialMountAttributes() {
        val flags = assertNotNull(MountAttributeCodec.initial(0x200087uL))

        assertTrue(MountFlag.READ_ONLY in flags)
        assertTrue(MountFlag.NO_SUID in flags)
        assertTrue(MountFlag.NO_DEVICE in flags)
        assertTrue(MountFlag.NO_DIRECTORY_ATIME in flags)
        assertTrue(MountFlag.NO_SYMLINK_FOLLOW in flags)
        assertTrue(MountFlag.RELATIVE_ATIME in flags)
        assertNull(MountAttributeCodec.initial(0x40uL))
        assertNull(MountAttributeCodec.initial(MountAttributeCodec.IDMAP))
    }

    @Test
    fun appliesSetClearAndAtimeTransitions() {
        val initial = MountFlags.of(
            MountFlag.NO_SUID,
            MountFlag.NO_ATIME,
            MountFlag.NO_DIRECTORY_ATIME,
        )
        val update = assertNotNull(MountAttributeCodec.update(
            set = 0x21uL,
            clear = 0x72uL,
        ))
        val flags = update.applyTo(initial)

        assertTrue(MountFlag.READ_ONLY in flags)
        assertTrue(MountFlag.STRICT_ATIME in flags)
        assertTrue(MountFlag.NO_DIRECTORY_ATIME in flags)
        assertFalse(MountFlag.NO_SUID in flags)
        assertFalse(MountFlag.NO_ATIME in flags)
        assertFalse(MountFlag.RELATIVE_ATIME in flags)
    }

    @Test
    fun enforcesAtimeEnumProtocolAndSetPrecedence() {
        assertNull(MountAttributeCodec.update(set = 0x10uL, clear = 0uL))
        assertNull(MountAttributeCodec.update(set = 0uL, clear = 0x10uL))
        assertNull(MountAttributeCodec.update(set = 0x30uL, clear = 0x70uL))

        val overlap = assertNotNull(MountAttributeCodec.update(set = 1uL, clear = 1uL))
        val flags = overlap.applyTo(MountFlags.NONE)
        assertTrue(MountFlag.READ_ONLY in flags)
        assertTrue(MountFlag.RELATIVE_ATIME in flags)
    }

    @Test
    fun composesSuperBlockFlagTransitions() {
        val update = MountFlagUpdate.NONE
            .with(MountFlag.READ_ONLY, true)
            .with(MountFlag.SYNCHRONOUS, true)
            .with(MountFlag.READ_ONLY, false)
        val flags = update.applyTo(MountFlags.of(MountFlag.LAZY_TIME))

        assertFalse(MountFlag.READ_ONLY in flags)
        assertTrue(MountFlag.SYNCHRONOUS in flags)
        assertTrue(MountFlag.LAZY_TIME in flags)
    }
}
