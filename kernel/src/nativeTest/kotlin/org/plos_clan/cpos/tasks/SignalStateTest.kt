package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalStateTest {
    @Test
    fun nonRealtimeSignalsCoalesceAndUseLowestNumberFirst() {
        val signals = PendingSignalStorage(quota(uid = 1, limit = 32uL))
        val user1 = checkNotNull(Signal.from(10))
        val user2 = checkNotNull(Signal.from(12))

        assertEquals(
            SignalEnqueueResult.ADDED,
            signals.enqueue(SignalInfo(user2, SignalInfo.USER)),
        )
        assertEquals(
            SignalEnqueueResult.ADDED,
            signals.enqueue(SignalInfo(user1, SignalInfo.USER, error = 1)),
        )
        assertEquals(
            SignalEnqueueResult.COALESCED,
            signals.enqueue(SignalInfo(user1, SignalInfo.USER, error = 2)),
        )

        assertEquals(1, signals.take(ULong.MAX_VALUE)?.error)
        assertEquals(user2, signals.take(ULong.MAX_VALUE)?.signal)
        assertNull(signals.take(ULong.MAX_VALUE))
    }

    @Test
    fun realtimeSignalsQueueInFifoOrderAndRespectQuota() {
        val signals = PendingSignalStorage(quota(uid = 2, limit = 2uL))
        val realtime = checkNotNull(Signal.from(35))

        assertEquals(
            SignalEnqueueResult.ADDED,
            signals.enqueue(queued(realtime, 1uL)),
        )
        assertEquals(
            SignalEnqueueResult.ADDED,
            signals.enqueue(queued(realtime, 2uL)),
        )
        assertEquals(
            SignalEnqueueResult.LIMIT_REACHED,
            signals.enqueue(queued(realtime, 3uL)),
        )

        assertEquals(1uL, (signals.take(realtime.bit)?.payload as SignalPayload.Sender).value)
        assertEquals(2uL, (signals.take(realtime.bit)?.payload as SignalPayload.Sender).value)
        assertEquals(SignalEnqueueResult.ADDED, signals.enqueue(queued(realtime, 3uL)))
        assertEquals(3uL, (signals.take(realtime.bit)?.payload as SignalPayload.Sender).value)
    }

    @Test
    fun realtimeSignalsUseNumberPriorityAndPerSignalFifo() {
        val signals = PendingSignalStorage(quota(uid = 6, limit = 4uL))
        val lower = checkNotNull(Signal.from(35))
        val higher = checkNotNull(Signal.from(36))

        signals.enqueue(queued(higher, 1uL))
        signals.enqueue(queued(lower, 2uL))
        signals.enqueue(queued(lower, 3uL))

        assertEquals(2uL, (signals.take(ULong.MAX_VALUE)?.payload as SignalPayload.Sender).value)
        assertEquals(3uL, (signals.take(ULong.MAX_VALUE)?.payload as SignalPayload.Sender).value)
        assertEquals(1uL, (signals.take(ULong.MAX_VALUE)?.payload as SignalPayload.Sender).value)
    }

    @Test
    fun queuedStandardSignalsFallBackToUserInfoWhenTheQuotaIsFull() {
        val signals = PendingSignalStorage(quota(uid = 3, limit = 0uL))
        val standard = checkNotNull(Signal.from(10))

        assertEquals(
            SignalEnqueueResult.ADDED,
            signals.enqueue(queued(standard, 1uL)),
        )
        assertEquals(SignalInfo(standard, SignalInfo.USER), signals.take(standard.bit))
        assertEquals(0uL, signals.mask)
        assertEquals(
            SignalEnqueueResult.ADDED,
            signals.enqueue(SignalInfo(standard, SignalInfo.USER)),
        )
    }

    @Test
    fun pendingLimitIsSharedByProcessesWithTheSameRealUid() {
        val first = PendingSignalStorage(quota(uid = 4, limit = 1uL))
        val second = PendingSignalStorage(quota(uid = 4, limit = 1uL))
        val realtime = checkNotNull(Signal.from(35))

        assertEquals(SignalEnqueueResult.ADDED, first.enqueue(queued(realtime, 1uL)))
        assertEquals(
            SignalEnqueueResult.LIMIT_REACHED,
            second.enqueue(queued(realtime, 2uL)),
        )
        assertEquals(1uL, (first.take(realtime.bit)?.payload as SignalPayload.Sender).value)
        assertEquals(SignalEnqueueResult.ADDED, second.enqueue(queued(realtime, 2uL)))
        assertEquals(2uL, (second.take(realtime.bit)?.payload as SignalPayload.Sender).value)
    }

    @Test
    fun realtimeUserSignalsCoalesceWithoutAllocatingBeyondTheQuota() {
        val signals = PendingSignalStorage(quota(uid = 7, limit = 0uL))
        val realtime = checkNotNull(Signal.from(35))
        val info = SignalInfo(realtime, SignalInfo.USER)

        assertEquals(SignalEnqueueResult.ADDED, signals.enqueue(info))
        assertEquals(SignalEnqueueResult.COALESCED, signals.enqueue(info))
        assertEquals(SignalEnqueueResult.LIMIT_REACHED, signals.enqueue(queued(realtime, 1uL)))
        assertEquals(info, signals.take(realtime.bit))
        assertEquals(0uL, signals.mask)
        assertNull(signals.take(realtime.bit))

        signals.enqueue(info)
        signals.discard(realtime.bit)
        assertEquals(0uL, signals.mask)
        assertNull(signals.take(realtime.bit))
    }

    @Test
    fun realtimeFallbackDoesNotReplaceQueuedInfoOrReleaseItsCharge() {
        val signals = PendingSignalStorage(quota(uid = 8, limit = 1uL))
        val realtime = checkNotNull(Signal.from(35))
        val queued = queued(realtime, 1uL)

        assertEquals(SignalEnqueueResult.ADDED, signals.enqueue(queued))
        assertEquals(
            SignalEnqueueResult.COALESCED,
            signals.enqueue(SignalInfo(realtime, SignalInfo.USER)),
        )
        assertEquals(queued, signals.take(realtime.bit))
        assertEquals(0uL, signals.mask)
        assertEquals(SignalEnqueueResult.ADDED, signals.enqueue(queued))
        signals.discard(realtime.bit)
        assertEquals(SignalEnqueueResult.ADDED, signals.enqueue(queued))
        assertEquals(queued, signals.take(realtime.bit))
    }

    @Test
    fun realtimeThreadAndKernelCodesRequireQueueSpace() {
        val signals = PendingSignalStorage(quota(uid = 9, limit = 0uL))
        val realtime = checkNotNull(Signal.from(35))

        for (code in listOf(SignalInfo.THREAD, SignalInfo.QUEUED, SignalInfo.KERNEL)) {
            assertEquals(
                SignalEnqueueResult.LIMIT_REACHED,
                signals.enqueue(SignalInfo(realtime, code)),
            )
        }
        assertEquals(0uL, signals.mask)
    }

    @Test
    fun killAndNonnegativeStandardCodesBypassTheQuota() {
        val signals = PendingSignalStorage(quota(uid = 10, limit = 0uL))
        val standard = checkNotNull(Signal.from(10))
        val infos = listOf(
            queued(Signal.KILL, 1uL),
            SignalInfo(standard, SignalInfo.USER, payload = SignalPayload.Sender(42, 1000)),
            SignalInfo(standard, SignalInfo.KERNEL, payload = SignalPayload.Fault(0x1234uL)),
        )
        for (info in infos) {
            assertEquals(SignalEnqueueResult.ADDED, signals.enqueue(info))
            assertEquals(info, signals.take(info.signal.bit))
        }
    }

    @Test
    fun masksNeverBlockKillOrStop() {
        val signals = ThreadSignalState(
            quota = quota(uid = 5, limit = 32uL),
            mask = ULong.MAX_VALUE,
            stack = SignalStack.DISABLED,
        )

        assertEquals(Signal.BLOCKABLE_MASK, signals.mask)
        assertFalse(signals.mask and Signal.KILL.bit != 0uL)
        assertFalse(signals.mask and Signal.STOP.bit != 0uL)

        val previous = signals.updateMask(SignalMaskOperation.SET, ULong.MAX_VALUE)
        assertEquals(Signal.BLOCKABLE_MASK, previous)
        assertEquals(Signal.BLOCKABLE_MASK, signals.mask)
    }

    @Test
    fun actionFlagsAreStronglyTyped() {
        val action = SignalAction(
            handler = 0x1234uL,
            flags = SignalActionFlag.RESTART.mask or SignalActionFlag.ON_STACK.mask,
        )

        assertTrue(action.has(SignalActionFlag.RESTART))
        assertTrue(action.has(SignalActionFlag.ON_STACK))
        assertFalse(action.has(SignalActionFlag.NODEFER))
        assertTrue(SignalAction.DEFAULT.isDefault)
        assertTrue(SignalAction.IGNORED.isIgnored)
    }

    @Test
    fun actionabilityFollowsDispositionAndDefaultAction() {
        val user = checkNotNull(Signal.from(10))
        val caught = SignalAction(handler = 0x1234uL)

        assertTrue(SignalAction.DEFAULT.isActionable(user))
        assertFalse(SignalAction.IGNORED.isActionable(user))
        assertFalse(SignalAction.DEFAULT.isActionable(Signal.CHILD))
        assertFalse(SignalAction.DEFAULT.isActionable(Signal.CONTINUE))
        assertTrue(caught.isActionable(Signal.CHILD))
    }

    @Test
    fun alternateStackUsesHalfOpenBounds() {
        val stack = SignalStack(base = 0x1000uL, size = 0x2000uL)

        assertTrue(stack.contains(0x1000uL))
        assertTrue(stack.contains(0x2fffuL))
        assertFalse(stack.contains(0x3000uL))
        assertFalse(SignalStack.DISABLED.contains(0uL))
    }

    private fun queued(signal: Signal, value: ULong) = SignalInfo(
        signal = signal,
        code = SignalInfo.QUEUED,
        payload = SignalPayload.Sender(1, 0, value),
    )

    private fun quota(uid: Int, limit: ULong) = PendingSignalQuota(
        uid = { uid },
        limit = { limit },
    )
}
