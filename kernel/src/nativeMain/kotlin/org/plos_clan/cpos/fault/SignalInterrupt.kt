@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import org.plos_clan.cpos.syscall.SignalGateway
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.SignalPreemption
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.InterruptFrame

internal object SignalInterrupt : SignalPreemption {
    fun initialize() {
        bridge.fast_handoff_set_user_interrupt_handler(staticCFunction(::signalInterrupt))
        SignalRouter.installPreemption(this)
    }

    override fun request(thread: Thread) {
        bridge.fast_handoff_request_user_interrupt(thread.nativeContext)
    }

    fun handle(frame: InterruptFrame) {
        val thread = ProcessManager.currentThread() ?: return
        if (thread.hasPendingSignal() || thread.cgroup?.freezing == true) {
            SignalGateway.redirectPending(frame, thread)
        }
    }
}

private fun signalInterrupt(frame: COpaquePointer?) {
    SignalInterrupt.handle(InterruptFrame(requireNotNull(frame).reinterpret()))
}
