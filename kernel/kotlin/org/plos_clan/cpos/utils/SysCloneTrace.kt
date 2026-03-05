@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import bridge.get_last_sys_clone_entry
import bridge.get_last_sys_clone_stack
import bridge.get_sys_clone_call_count
import bridge.get_sys_clone_entry_at
import bridge.get_sys_clone_recorded_count
import bridge.get_sys_clone_stack_at
import kotlinx.cinterop.ExperimentalForeignApi

data class SysCloneContext(
    val stack: ULong,
    val entry: ULong,
)

object SysCloneTrace {
    fun callCount(): ULong = get_sys_clone_call_count()

    fun recordedCount(): ULong = get_sys_clone_recorded_count()

    fun lastOrNull(): SysCloneContext? {
        if (recordedCount() == 0uL) {
            return null
        }
        return SysCloneContext(
            stack = get_last_sys_clone_stack(),
            entry = get_last_sys_clone_entry(),
        )
    }

    fun getOrNull(index: ULong): SysCloneContext? {
        if (index >= recordedCount()) {
            return null
        }
        return SysCloneContext(
            stack = get_sys_clone_stack_at(index),
            entry = get_sys_clone_entry_at(index),
        )
    }
}
