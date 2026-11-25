package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessLimitsTest {
    @Test
    fun inheritedLimitsAreIsolatedInBothDirections() {
        val parent = ProcessLimits()
        val child = ProcessLimits()
        val resource = ProcessResource.OPEN_FILES
        val initial = ResourceLimit(24u, 48u)
        assertEquals(resource.initial, parent.replace(resource, initial))
        child.inherit(parent)
        val larger = ResourceLimit(40u, 48u)
        assertEquals(initial, parent.replace(resource, larger))
        assertEquals(initial, child.replace(resource, resource.initial))
        assertEquals(larger, parent.get(resource))
    }
}
