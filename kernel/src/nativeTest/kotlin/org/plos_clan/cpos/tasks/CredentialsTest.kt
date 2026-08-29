package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialsTest {
    @Test
    fun privilegedIdentityChangeUpdatesAllIds() {
        val credentials = Credentials()
        val change = assertNotNull(credentials.setResUserIds(81, 81, 81, privileged = true))

        assertEquals(Credentials.Identity.ROOT, change.previous)
        assertEquals(Credentials.Identity(81, 81, 81), change.current)
        assertEquals(change.current, credentials.userIds)
    }

    @Test
    fun unprivilegedIdentityChangeCannotIntroduceIds() {
        val credentials = Credentials(
            userIds = Credentials.Identity(1000, 1001, 1002),
            groupIds = Credentials.Identity(2000, 2001, 2002),
        )

        assertNull(credentials.setResUserIds(null, 1003, null, privileged = false))
        assertFalse(credentials.setResGroupIds(null, 2003, null, privileged = false))
        assertNotNull(credentials.setResUserIds(null, 1002, null, privileged = false))
        assertTrue(credentials.setResGroupIds(null, 2002, null, privileged = false))
    }

    @Test
    fun supplementaryGroupsAreInheritedByValue() {
        val parent = Credentials().also { it.replaceSupplementaryGroups(listOf(10, 20)) }
        val child = Credentials().also { it.inherit(parent) }

        parent.replaceSupplementaryGroups(listOf(30))
        assertEquals(listOf(10, 20), child.supplementaryGroups)
    }
}

class CapabilityStateTest {
    @Test
    fun permittedCapabilitiesCannotBeRegained() {
        val audit = bit(CapEnum.AUDIT_WRITE)
        val state = CapabilityState(effective = audit, permitted = audit)

        assertFalse(state.apply(audit, audit or bit(CapEnum.SETUID), 0uL))
        assertEquals(audit, state.permitted)
    }

    @Test
    fun ambientCapabilityRequiresPermittedAndInheritableMembership() {
        val audit = bit(CapEnum.AUDIT_WRITE)
        val state = CapabilityState(effective = audit, permitted = audit)

        assertFalse(state.raiseAmbient(CapEnum.AUDIT_WRITE.id))
        assertTrue(state.apply(audit, audit, audit))
        assertTrue(state.raiseAmbient(CapEnum.AUDIT_WRITE.id))
        assertTrue(state.containsAmbient(CapEnum.AUDIT_WRITE.id))
    }

    @Test
    fun keepCapsPreservesPermittedSetAcrossRootDrop() {
        val transition = Credentials.UserIdChange(
            Credentials.Identity.ROOT,
            Credentials.Identity(81, 81, 81),
        )
        val state = CapabilityState(keepAcrossUserIdChange = true)

        state.applyUserIdChange(transition)
        assertEquals(TASK_CAP_FULL_MASK, state.permitted)
        assertEquals(0uL, state.effective)
        assertEquals(0uL, state.ambient)
    }

    @Test
    fun boundingCapabilitiesCanOnlyBeDropped() {
        val state = CapabilityState()
        val capability = CapEnum.SETPCAP.id

        assertTrue(state.containsBounding(capability))
        state.dropBounding(capability)
        assertFalse(state.containsBounding(capability))
    }

    private fun bit(capability: CapEnum): ULong = 1uL shl capability.id
}
