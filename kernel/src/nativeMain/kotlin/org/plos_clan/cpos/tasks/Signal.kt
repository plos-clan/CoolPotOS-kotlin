@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal value class Signal private constructor(val number: Int) {
    val bit: ULong
        get() = 1uL shl (number - 1)

    val isRealtime: Boolean
        get() = number >= REALTIME_MIN

    val defaultAction: DefaultSignalAction
        get() = when (number) {
            17, 23, 28 -> DefaultSignalAction.IGNORE
            18 -> DefaultSignalAction.CONTINUE
            19, 20, 21, 22 -> DefaultSignalAction.STOP
            3, 4, 5, 6, 7, 8, 11, 24, 25, 31 -> DefaultSignalAction.CORE_DUMP
            else -> DefaultSignalAction.TERMINATE
        }

    companion object {
        const val MAX = 64
        const val REALTIME_MIN = 32

        val INTERRUPT = Signal(2)
        val QUIT = Signal(3)
        val ILLEGAL_INSTRUCTION = Signal(4)
        val TRAP = Signal(5)
        val ABORT = Signal(6)
        val BUS = Signal(7)
        val FLOATING_POINT_EXCEPTION = Signal(8)
        val KILL = Signal(9)
        val SEGV = Signal(11)
        val PIPE = Signal(13)
        val CHILD = Signal(17)
        val CONTINUE = Signal(18)
        val STOP = Signal(19)
        val TERMINAL_STOP = Signal(20)
        val TERMINAL_INPUT_STOP = Signal(21)
        val TERMINAL_OUTPUT_STOP = Signal(22)
        val SYS = Signal(31)

        val BLOCKABLE_MASK = ULong.MAX_VALUE and KILL.bit.inv() and STOP.bit.inv()
        val STOP_MASK = STOP.bit or TERMINAL_STOP.bit or
            TERMINAL_INPUT_STOP.bit or TERMINAL_OUTPUT_STOP.bit

        fun from(number: Int): Signal? = number.takeIf { it in 1..MAX }?.let(::Signal)

        fun from(number: ULong): Signal? =
            number.takeIf { it in 1uL..MAX.toULong() }?.toInt()?.let(::Signal)
    }
}

internal enum class DefaultSignalAction {
    IGNORE,
    TERMINATE,
    CORE_DUMP,
    STOP,
    CONTINUE,
}

internal enum class SignalActionFlag(val mask: ULong) {
    NO_CHILD_STOP(0x0000_0001uL),
    NO_CHILD_WAIT(0x0000_0002uL),
    SIGNAL_INFO(0x0000_0004uL),
    RESTORER(0x0400_0000uL),
    ON_STACK(0x0800_0000uL),
    RESTART(0x1000_0000uL),
    NODEFER(0x4000_0000uL),
    RESET_HANDLER(0x8000_0000uL),
}

internal data class SignalAction(
    val handler: ULong = DEFAULT_HANDLER,
    val flags: ULong = 0uL,
    val restorer: ULong = 0uL,
    val mask: ULong = 0uL,
) {
    val isDefault: Boolean
        get() = handler == DEFAULT_HANDLER

    val isIgnored: Boolean
        get() = handler == IGNORE_HANDLER

    val isCaught: Boolean
        get() = !isDefault && !isIgnored

    fun has(flag: SignalActionFlag): Boolean = flags and flag.mask != 0uL

    fun isActionable(signal: Signal): Boolean = !isIgnored &&
        (!isDefault || signal.defaultAction != DefaultSignalAction.IGNORE &&
            signal.defaultAction != DefaultSignalAction.CONTINUE)

    companion object {
        const val DEFAULT_HANDLER = 0uL
        const val IGNORE_HANDLER = 1uL

        val DEFAULT = SignalAction()
        val IGNORED = SignalAction(handler = IGNORE_HANDLER)
    }
}

internal sealed interface SignalPayload {
    data class Sender(val pid: Int, val uid: Int, val value: ULong = 0uL) : SignalPayload
    data class Child(
        val pid: Int,
        val uid: Int,
        val status: Int,
        val userTime: Long = 0,
        val systemTime: Long = 0,
    ) : SignalPayload

    data class Fault(val address: ULong) : SignalPayload
    data class Raw(val bytes: ByteArray) : SignalPayload
    data object None : SignalPayload
}

internal data class SignalInfo(
    val signal: Signal,
    val code: Int,
    val error: Int = 0,
    val payload: SignalPayload = SignalPayload.None,
) {
    companion object {
        const val USER = 0
        const val QUEUED = -1
        const val THREAD = -6
        const val KERNEL = 128

        const val CHILD_EXITED = 1
        const val CHILD_KILLED = 2
        const val CHILD_DUMPED = 3
        const val CHILD_STOPPED = 5
        const val CHILD_CONTINUED = 6

        const val ILLEGAL_OPERAND = 2
        const val INTEGER_DIVIDE_BY_ZERO = 1
        const val FLOATING_INVALID_OPERATION = 7
        const val TRAP_BREAKPOINT = 1
        const val TRAP_TRACE = 2
        const val SEGMENT_MAPPING_ERROR = 1
        const val SEGMENT_ACCESS_ERROR = 2
        const val BUS_ALIGNMENT_ERROR = 1
        const val BUS_OBJECT_ERROR = 3

        fun fromSender(
            signal: Signal,
            process: Process,
            code: Int = USER,
            value: ULong = 0uL,
            pid: Int = process.id,
        ) = SignalInfo(
            signal = signal,
            code = code,
            payload = SignalPayload.Sender(pid, process.credentials.userIds.real, value),
        )
    }
}

internal sealed interface SignalGatewayContext {
    val instructionPointer: ULong
    val stackPointer: ULong
    val flags: ULong
    val captureAddress: ULong?

    data class Pending(
        override val instructionPointer: ULong,
        override val stackPointer: ULong,
        override val flags: ULong,
        override val captureAddress: ULong?,
    ) : SignalGatewayContext

    data class Synchronous(
        val info: SignalInfo,
        override val instructionPointer: ULong,
        override val stackPointer: ULong,
        override val flags: ULong,
        val errorCode: ULong,
        val trapNumber: ULong,
        override val captureAddress: ULong?,
    ) : SignalGatewayContext
}

internal enum class SignalMaskOperation {
    BLOCK,
    UNBLOCK,
    SET,
}

internal data class SignalStack(
    val base: ULong = 0uL,
    val size: ULong = 0uL,
    val autoDisable: Boolean = false,
) {
    val enabled: Boolean
        get() = size != 0uL

    val top: ULong
        get() = base + size

    fun contains(stackPointer: ULong): Boolean =
        enabled && stackPointer >= base && stackPointer - base < size

    companion object {
        val DISABLED = SignalStack()
    }
}

internal enum class SignalEnqueueResult {
    ADDED,
    COALESCED,
    LIMIT_REACHED,
}

internal class PendingSignalAccount(
    val uid: Int,
    val next: PendingSignalAccount?,
) {
    private val queued = AtomicInt(0)

    fun reserve(limit: ULong): Boolean {
        while (true) {
            val current = queued.load()
            if (current == Int.MAX_VALUE || current.toULong() >= limit) {
                return false
            }
            if (queued.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        queued.fetchAndAdd(-1)
    }
}

private object PendingSignalAccounts {
    private val head = AtomicReference<PendingSignalAccount?>(null)

    fun account(uid: Int): PendingSignalAccount {
        while (true) {
            val observed = head.load()
            var account = observed
            while (account != null) {
                if (account.uid == uid) return account
                account = account.next
            }
            val created = PendingSignalAccount(uid, observed)
            if (head.compareAndSet(observed, created)) return created
        }
    }
}

internal class PendingSignalQuota(
    private val uid: () -> Int,
    private val limit: () -> ULong,
) {
    private val cached = AtomicReference<PendingSignalAccount?>(null)

    fun reserve(enforceLimit: Boolean): PendingSignalAccount? {
        val currentUid = uid()
        val account = cached.load().takeIf { it?.uid == currentUid }
            ?: PendingSignalAccounts.account(currentUid).also(cached::store)
        val effectiveLimit = if (enforceLimit) limit() else ULong.MAX_VALUE
        return account.takeIf { it.reserve(effectiveLimit) }
    }
}

private data class QueuedSignalEntry(
    val info: SignalInfo,
    val charge: PendingSignalAccount,
)

internal class PendingSignalStorage(private val quota: PendingSignalQuota) {
    private val standard = arrayOfNulls<SignalInfo>(Signal.REALTIME_MIN - 1)
    private val standardCharges = arrayOfNulls<PendingSignalAccount>(standard.size)
    private val realtime = arrayOfNulls<ArrayDeque<QueuedSignalEntry>>(
        Signal.MAX - Signal.REALTIME_MIN + 1,
    )
    private val pending = AtomicLong(0)

    val mask: ULong
        get() = pending.load().toULong()

    fun enqueue(info: SignalInfo, enforceLimit: Boolean = false): SignalEnqueueResult {
        val signal = info.signal
        val current = pending.load().toULong()
        if (!signal.isRealtime && current and signal.bit != 0uL) {
            return SignalEnqueueResult.COALESCED
        }

        if (!signal.isRealtime) {
            val index = signal.number - 1
            val charge = if (signal == Signal.KILL) null else quota.reserve(enforceLimit)
            if (signal != Signal.KILL && charge == null) {
                return SignalEnqueueResult.LIMIT_REACHED
            }
            standard[index] = info
            standardCharges[index] = charge
        } else {
            val charge = quota.reserve(enforceLimit)
                ?: return SignalEnqueueResult.LIMIT_REACHED
            val index = signal.number - Signal.REALTIME_MIN
            (realtime[index] ?: ArrayDeque<QueuedSignalEntry>().also {
                realtime[index] = it
            }).addLast(QueuedSignalEntry(info, charge))
        }
        pending.store((current or signal.bit).toLong())
        return SignalEnqueueResult.ADDED
    }

    fun take(accepted: ULong): SignalInfo? {
        val current = pending.load().toULong()
        val available = current and accepted
        if (available == 0uL) return null

        val signal = Signal.from(available.countTrailingZeroBits() + 1)!!
        val info = if (!signal.isRealtime) {
            val index = signal.number - 1
            standard[index].also {
                standard[index] = null
                standardCharges[index]?.release()
                standardCharges[index] = null
            }!!
        } else {
            val index = signal.number - Signal.REALTIME_MIN
            val queue = realtime[index]!!
            queue.removeFirst().also {
                it.charge.release()
                if (queue.isEmpty()) realtime[index] = null
            }.info
        }
        if (!signal.isRealtime || realtime[signal.number - Signal.REALTIME_MIN] == null) {
            pending.store((current and signal.bit.inv()).toLong())
        }
        return info
    }

    fun discard(discarded: ULong) {
        val current = pending.load().toULong()
        var remaining = current and discarded
        while (remaining != 0uL) {
            val signal = Signal.from(remaining.countTrailingZeroBits() + 1)!!
            if (signal.isRealtime) {
                val index = signal.number - Signal.REALTIME_MIN
                realtime[index]?.forEach { it.charge.release() }
                realtime[index] = null
            } else {
                val index = signal.number - 1
                standardCharges[index]?.release()
                standard[index] = null
                standardCharges[index] = null
            }
            remaining = remaining and signal.bit.inv()
        }
        pending.store((current and discarded.inv()).toLong())
    }
}

internal class PendingSignals(quota: PendingSignalQuota) {
    private val lock = IrqSpinLock()
    private val storage = PendingSignalStorage(quota)
    private val sequence = AtomicInt(0)

    val mask: ULong
        get() = storage.mask

    val version: Int
        get() = sequence.load()

    fun enqueue(info: SignalInfo, enforceLimit: Boolean = false): SignalEnqueueResult =
        lock.withLock {
            storage.enqueue(info, enforceLimit).also { result ->
                if (result == SignalEnqueueResult.ADDED) sequence.store(sequence.load() + 1)
            }
        }

    fun take(accepted: ULong): SignalInfo? {
        if (mask and accepted == 0uL) return null
        return lock.withLock {
            storage.take(accepted).also {
                if (it != null) sequence.store(sequence.load() + 1)
            }
        }
    }

    fun discard(discarded: ULong) {
        if (mask and discarded == 0uL) return
        lock.withLock {
            if (storage.mask and discarded == 0uL) return@withLock
            storage.discard(discarded)
            sequence.store(sequence.load() + 1)
        }
    }
}

internal class ProcessSignalState(uid: () -> Int, limit: () -> ULong) {
    private val dispositionLock = IrqSpinLock()
    private val actions = Array(Signal.MAX) { SignalAction.DEFAULT }
    private val actionable = AtomicLong(DEFAULT_ACTIONABLE_MASK.toLong())
    private val quota = PendingSignalQuota(uid, limit)
    private val signalFdWaiters = AtomicReference<List<Thread>>(emptyList())
    private val stopLock = IrqSpinLock()
    private val stoppedThreads = mutableListOf<Thread>()

    val pending = PendingSignals(quota)

    fun action(signal: Signal): SignalAction = dispositionLock.withLock {
        actions[signal.number - 1]
    }

    fun actionForDelivery(signal: Signal): SignalAction = dispositionLock.withLock {
        val index = signal.number - 1
        val action = actions[index]
        if (action.has(SignalActionFlag.RESET_HANDLER)) {
            actions[index] = SignalAction.DEFAULT
            updateActionable(signal, SignalAction.DEFAULT)
        }
        action
    }

    fun replaceAction(
        signal: Signal,
        action: SignalAction,
        discardPending: () -> Unit = {},
    ): SignalAction =
        dispositionLock.withLock {
            val index = signal.number - 1
            val previous = actions[index]
            actions[index] = action
            updateActionable(signal, action)
            if (action.isIgnored ||
                action.isDefault && signal.defaultAction == DefaultSignalAction.IGNORE
            ) {
                discardPending()
            }
            previous
        }

    fun <T> withAction(signal: Signal, operation: (SignalAction) -> T): T =
        dispositionLock.withLock { operation(actions[signal.number - 1]) }

    fun actionMasks(): Pair<ULong, ULong> = dispositionLock.withLock {
        var ignored = 0uL
        var caught = 0uL
        for (index in actions.indices) {
            val action = actions[index]
            val signal = Signal.from(index + 1)!!
            when {
                action.isIgnored -> ignored = ignored or signal.bit
                action.isCaught -> caught = caught or signal.bit
            }
        }
        ignored to caught
    }

    fun hasActionable(pending: ULong): Boolean =
        pending and actionable.load().toULong() != 0uL

    fun registerSignalFdWaiter(thread: Thread) {
        while (true) {
            val observed = signalFdWaiters.load()
            if (thread in observed ||
                signalFdWaiters.compareAndSet(observed, observed + thread)
            ) {
                return
            }
        }
    }

    fun unregisterSignalFdWaiter(thread: Thread) {
        while (true) {
            val observed = signalFdWaiters.load()
            if (thread !in observed ||
                signalFdWaiters.compareAndSet(observed, observed - thread)
            ) {
                return
            }
        }
    }

    fun notifySignalFdWaiters() {
        for (waiter in signalFdWaiters.load()) Scheduler.wake(waiter)
    }

    fun inherit(source: ProcessSignalState) {
        val inherited = source.dispositionLock.withLock { source.actions.copyOf() }
        dispositionLock.withLock {
            inherited.copyInto(actions)
            rebuildActionable()
        }
    }

    fun resetForExec() = dispositionLock.withLock {
        for (index in actions.indices) {
            val action = actions[index]
            actions[index] = if (action.isIgnored) SignalAction.IGNORED else SignalAction.DEFAULT
        }
        rebuildActionable()
    }

    fun resetAll() = dispositionLock.withLock {
        actions.fill(SignalAction.DEFAULT)
        actionable.store(DEFAULT_ACTIONABLE_MASK.toLong())
    }

    fun newThread(mask: ULong = 0uL, stack: SignalStack = SignalStack.DISABLED) =
        ThreadSignalState(quota, mask, stack)

    fun stop(process: Process, current: Thread, signal: Signal) {
        val notifyParent = stopLock.withLock {
            if (!process.state.canReceiveSignals) {
                return
            }
            val stopped = process.state == ProcessState.STOPPED
            process.state = ProcessState.STOPPED
            for (thread in process.threads) {
                if ((thread.state == TaskState.READY || thread.state == TaskState.RUNNING) &&
                    thread !in stoppedThreads
                ) {
                    stoppedThreads += thread
                    if (thread !== current) thread.state = TaskState.BLOCKED
                }
            }
            !stopped
        }
        if (notifyParent) ProcessManager.markStopped(process, signal)
        while (process.state == ProcessState.STOPPED) {
            if (!Scheduler.parkCurrent()) Scheduler.yieldCurrent()
        }
    }

    fun resume(process: Process, notifyParent: Boolean = false) {
        var continued = false
        val threads = stopLock.withLock {
            continued = process.state == ProcessState.STOPPED
            if (continued) process.state = ProcessState.READY
            stoppedThreads.toList().also { stoppedThreads.clear() }
        }
        if (continued && notifyParent) ProcessManager.markContinued(process)
        for (thread in threads) Scheduler.wake(thread)
    }

    fun deferWake(process: Process, thread: Thread): Boolean = stopLock.withLock {
        if (process.state != ProcessState.STOPPED || thread.state == TaskState.ZOMBIE) {
            false
        } else {
            if (thread !in stoppedThreads) stoppedThreads += thread
            true
        }
    }

    private fun updateActionable(signal: Signal, action: SignalAction) {
        val current = actionable.load().toULong()
        val replacement = if (action.isActionable(signal)) {
            current or signal.bit
        } else {
            current and signal.bit.inv()
        }
        actionable.store(replacement.toLong())
    }

    private fun rebuildActionable() {
        var mask = 0uL
        for (index in actions.indices) {
            val action = actions[index]
            val signal = Signal.from(index + 1)!!
            if (action.isActionable(signal)) mask = mask or signal.bit
        }
        actionable.store(mask.toLong())
    }

    private companion object {
        val DEFAULT_ACTIONABLE_MASK = (1..Signal.MAX).fold(0uL) { mask, number ->
            val signal = Signal.from(number)!!
            if (SignalAction.DEFAULT.isActionable(signal)) mask or signal.bit else mask
        }
    }
}

internal class ThreadSignalState(
    private val quota: PendingSignalQuota,
    mask: ULong,
    stack: SignalStack,
) {
    private val blocked = AtomicLong((mask and Signal.BLOCKABLE_MASK).toLong())
    private val waited = AtomicLong(0)
    private val alternativeStack = AtomicReference(stack)
    private val gateway = AtomicReference<SignalGatewayContext?>(null)

    val pending = PendingSignals(quota)

    var mask: ULong
        get() = blocked.load().toULong()
        set(value) = blocked.store((value and Signal.BLOCKABLE_MASK).toLong())

    val stack: SignalStack
        get() = alternativeStack.load()

    fun updateMask(operation: SignalMaskOperation, requested: ULong): ULong {
        val filtered = requested and Signal.BLOCKABLE_MASK
        while (true) {
            val previous = blocked.load()
            val replacement = when (operation) {
                SignalMaskOperation.BLOCK -> previous.toULong() or filtered
                SignalMaskOperation.UNBLOCK -> previous.toULong() and filtered.inv()
                SignalMaskOperation.SET -> filtered
            }.toLong()
            if (blocked.compareAndSet(previous, replacement)) return previous.toULong()
        }
    }

    fun replaceMask(replacement: ULong): ULong =
        blocked.exchange((replacement and Signal.BLOCKABLE_MASK).toLong()).toULong()

    fun replaceStack(replacement: SignalStack): SignalStack =
        alternativeStack.exchange(replacement)

    fun waitsFor(signal: Signal): Boolean = waited.load().toULong() and signal.bit != 0uL

    fun beginWait(mask: ULong) = waited.store(mask.toLong())

    fun endWait() = waited.store(0)

    fun installGateway(context: SignalGatewayContext): Boolean =
        gateway.compareAndSet(null, context)

    fun replaceGateway(context: SignalGatewayContext) = gateway.store(context)

    fun takeGateway(): SignalGatewayContext? = gateway.exchange(null)

    fun fork(inheritStack: Boolean): ThreadSignalState = ThreadSignalState(
        quota = quota,
        mask = mask,
        stack = stack.takeIf { inheritStack } ?: SignalStack.DISABLED,
    )
}

internal enum class SignalSendResult {
    SUCCESS,
    NO_SUCH_PROCESS,
    NOT_PERMITTED,
    LIMIT_REACHED,
}

internal fun interface SignalPreemption {
    fun request(thread: Thread)
}

internal object SignalRouter {
    private val preemption = AtomicReference<SignalPreemption?>(null)

    fun installPreemption(controller: SignalPreemption) {
        check(preemption.compareAndSet(null, controller)) {
            "signal preemption is already installed"
        }
    }

    private fun permitted(sender: Process, target: Process, signal: Signal?): Boolean =
        sender === target || sender.credentials.userIds.effective == 0 ||
            sender.credentials.userIds.real == target.credentials.userIds.real ||
            sender.credentials.userIds.real == target.credentials.userIds.saved ||
            sender.credentials.userIds.effective == target.credentials.userIds.real ||
            sender.credentials.userIds.effective == target.credentials.userIds.saved ||
            signal == Signal.CONTINUE && sender.sessionId == target.sessionId

    fun sendProcess(
        sender: Process?,
        target: Process,
        info: SignalInfo?,
        enforceLimit: Boolean = false,
    ): SignalSendResult {
        if (sender != null && !permitted(sender, target, info?.signal)) {
            return SignalSendResult.NOT_PERMITTED
        }
        if (info == null || !target.state.canReceiveSignals) {
            return SignalSendResult.SUCCESS
        }

        val result = generate(target, null, info, enforceLimit)
            ?: return SignalSendResult.SUCCESS
        if (result == SignalEnqueueResult.LIMIT_REACHED) {
            return SignalSendResult.LIMIT_REACHED
        }
        val threads = target.threads
        if (info.signal == Signal.KILL) {
            target.signals.resume(target)
            val current = ProcessManager.currentThread()
            val controller = preemption.load()
            for (thread in threads) {
                if (thread === current || thread.state == TaskState.ZOMBIE) continue
                Scheduler.wake(thread)
                controller?.request(thread)
            }
            return SignalSendResult.SUCCESS
        }

        var waiting: Thread? = null
        var unblocked: Thread? = null
        for (thread in threads) {
            if (thread.state == TaskState.ZOMBIE) continue
            if (thread.signals.waitsFor(info.signal)) {
                waiting = thread
                break
            }
            if (unblocked == null && thread.signals.mask and info.signal.bit == 0uL) {
                unblocked = thread
            }
        }
        val eligible = waiting ?: unblocked
        eligible?.let { wakeForDelivery(it, info.signal) }
        return SignalSendResult.SUCCESS
    }

    fun sendThread(
        sender: Process?,
        target: Thread,
        info: SignalInfo?,
        enforceLimit: Boolean = false,
    ): SignalSendResult {
        if (target.state == TaskState.ZOMBIE) return SignalSendResult.NO_SUCH_PROCESS
        if (sender != null && !permitted(sender, target.process, info?.signal)) {
            return SignalSendResult.NOT_PERMITTED
        }
        if (info == null) return SignalSendResult.SUCCESS

        val result = generate(target.process, target, info, enforceLimit)
            ?: return SignalSendResult.SUCCESS
        if (result == SignalEnqueueResult.LIMIT_REACHED) {
            return SignalSendResult.LIMIT_REACHED
        }
        if (info.signal == Signal.KILL) target.process.signals.resume(target.process)
        if (target.signals.mask and info.signal.bit == 0uL || target.signals.waitsFor(info.signal)) {
            wakeForDelivery(target, info.signal)
        }
        return SignalSendResult.SUCCESS
    }

    fun installAction(process: Process, signal: Signal, action: SignalAction): SignalAction {
        val previous = process.signals.replaceAction(signal, action) {
            discard(process, signal.bit)
        }
        requestDelivery(process)
        return previous
    }

    private fun discard(process: Process, mask: ULong) {
        process.signals.pending.discard(mask)
        for (thread in process.threads) thread.signals.pending.discard(mask)
    }

    fun requestDelivery(thread: Thread) {
        if (thread.hasPendingSignal() && thread !== ProcessManager.currentThread()) {
            preemption.load()?.request(thread)
        }
    }

    fun requestDelivery(process: Process) {
        for (thread in process.threads) {
            if (thread.hasPendingSignal()) wakeForDelivery(thread)
        }
    }

    private fun prepare(process: Process, signal: Signal) {
        when (signal) {
            Signal.CONTINUE -> {
                discard(process, Signal.STOP_MASK)
                process.signals.resume(process, notifyParent = true)
            }
            else -> if (signal.bit and Signal.STOP_MASK != 0uL) {
                discard(process, Signal.CONTINUE.bit)
            }
        }
    }

    private fun generate(
        process: Process,
        targetThread: Thread?,
        info: SignalInfo,
        enforceLimit: Boolean,
    ): SignalEnqueueResult? {
        val pending = targetThread?.signals?.pending ?: process.signals.pending
        val result = process.signals.withAction(info.signal) { action ->
            prepare(process, info.signal)
            if (discardedAtGeneration(process, info.signal, targetThread, action)) null
            else pending.enqueue(info, enforceLimit)
        }
        if (result == SignalEnqueueResult.ADDED) process.signals.notifySignalFdWaiters()
        return result
    }

    private fun wakeForDelivery(thread: Thread, waitedSignal: Signal? = null) {
        if (thread.state == TaskState.ZOMBIE) return
        if (thread === ProcessManager.currentThread()) {
            if (waitedSignal != null && thread.signals.waitsFor(waitedSignal)) {
                Scheduler.wake(thread)
            }
            return
        }
        Scheduler.wake(thread)
        if (waitedSignal == null || !thread.signals.waitsFor(waitedSignal)) {
            preemption.load()?.request(thread)
        }
    }

    private fun discardedAtGeneration(
        process: Process,
        signal: Signal,
        targetThread: Thread?,
        action: SignalAction,
    ): Boolean {
        if (action.isActionable(signal)) return false
        val routingThread = targetThread ?: process.threads.firstOrNull() ?: return true
        return routingThread.signals.mask and signal.bit == 0uL
    }
}
