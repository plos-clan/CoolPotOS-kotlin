package org.plos_clan.cpos.fs.fuse

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceIoEvent
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.drivers.WaitablePositionlessDeviceBackend
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.vfs.MountResource
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

internal object FuseDevice : PositionlessDeviceBackend {
    private const val MINOR = 229u

    fun initialize(): Boolean = DeviceManager.register(
        DeviceRegistration(
            name = "fuse",
            type = DeviceType.CHARACTER,
            major = LinuxDeviceMajor.MISC.number,
            minor = MINOR,
            backend = this,
            sysfs = SysfsDevicePublication.virtual("misc", "fuse"),
        ),
    ) != null

    override fun open(device: Device): DeviceBackend = FuseSession()

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
        -Errno.ENOTTY.toLong()

    override fun poll(device: Device, events: Int): Long = PollEvents.POLLERR.toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EPERM.toLong()

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EPERM.toLong()
}

internal interface FuseNotificationSink {
    fun invalidateInode(nodeId: ULong, offset: Long, length: Long)
    fun invalidateEntry(parentId: ULong, name: VfsName, childId: ULong? = null)
}

internal class FuseSession : WaitablePositionlessDeviceBackend, MountResource {
    private companion object {
        const val REQUESTS_PER_FORGET_BATCH = 8
    }

    private enum class State {
        NEW,
        INITIALIZING,
        ACTIVE,
        DESTROYING,
        DISCONNECTED,
    }

    private enum class PendingKind {
        INITIALIZATION,
        SYNCHRONOUS,
        BACKGROUND,
    }

    private enum class PendingState {
        QUEUED,
        CLAIMED,
        SENT,
        FINISHED,
    }

    private data class Negotiation(
        val minorVersion: UInt,
        val features: ULong,
        val maxRead: Int,
        val maxReadAhead: Int,
        val maxWrite: Int,
    )

    private class PendingRequest(
        val kind: PendingKind,
        val thread: Thread?,
    ) {
        var unique = 0uL
        var state = PendingState.QUEUED
        var interrupted = false
        var interruptQueued = false
        var result: VfsResult<FuseReply>? = null
    }

    private data class Forget(val nodeId: ULong, val count: ULong)

    private data class OutboundRequest(
        val request: FuseRequest,
        val pending: PendingRequest?,
        val disconnectAfterRead: Boolean = false,
        val interruptTarget: ULong? = null,
        val forgets: List<Forget>? = null,
    )

    private val lock = IrqSpinLock()
    private val outbound = ArrayDeque<OutboundRequest>()
    private val pending = mutableMapOf<ULong, PendingRequest>()
    private val forgotten = linkedMapOf<ULong, ULong>()
    private val readWaiters = IoWaitQueue()
    private val stateWaiters = IoWaitQueue()
    private val unsupported = mutableSetOf<FuseOpcode>()
    private var state = State.NEW
    private var nextUnique = 2uL
    private var maximumRead = FuseAbi.MAX_TRANSFER_SIZE
    private var negotiation: Negotiation? = null
    private var notificationSink: FuseNotificationSink? = null
    private var disconnectionError = VfsError.NOT_CONNECTED
    private var deviceDisconnectionErrno = Errno.ENODEV
    private var requestsSinceForget = 0

    fun attach(maxRead: Int, sink: FuseNotificationSink): VfsResult<Unit> {
        require(maxRead in 1..FuseAbi.MAX_TRANSFER_SIZE)
        val request = FuseRequest(FuseOpcode.INIT, FuseAbi.ROOT_ID, 64).apply {
            writeU32(0, FuseAbi.VERSION)
            writeU32(4, FuseAbi.MINOR_VERSION)
            writeU32(8, maxRead.toUInt())
            writeU32(12, FuseFeature.supportedMask.toUInt())
            writeU32(16, (FuseFeature.supportedMask shr 32).toUInt())
        }
        return lock.withLock {
            if (state != State.NEW) return@withLock VfsResult.Err(VfsError.BUSY)
            state = State.INITIALIZING
            maximumRead = maxRead
            notificationSink = sink
            enqueueLocked(
                request,
                PendingKind.INITIALIZATION,
                null,
                VfsOperationContext.KERNEL,
            )
            VfsResult.Ok(Unit)
        }
    }

    fun request(
        caller: VfsOperationContext,
        request: FuseRequest,
    ): VfsResult<FuseReply> {
        when (val initialized = awaitActive()) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return initialized
        }
        val thread = ProcessManager.currentThread()
            ?: return VfsResult.Err(VfsError.INTERRUPTED)
        val queued = lock.withLock {
            if (state != State.ACTIVE) return@withLock null
            enqueueLocked(request, PendingKind.SYNCHRONOUS, thread, caller)
        } ?: return VfsResult.Err(lock.withLock { disconnectionError })

        while (true) {
            val result = lock.withLock {
                queued.result?.let { return@withLock it }
                if (thread.hasPendingSignal() && !queued.interrupted) {
                    if (queued.state == PendingState.QUEUED) {
                        outbound.removeAll { it.pending === queued }
                        pending.remove(queued.unique)
                        queued.state = PendingState.FINISHED
                        return@withLock VfsResult.Err(VfsError.INTERRUPTED)
                    }
                    queued.interrupted = true
                    if (queued.state == PendingState.SENT) queueInterruptLocked(queued)
                }
                null
            }
            if (result != null) return result
            if (!Scheduler.parkCurrent()) Scheduler.yieldCurrent()
        }
    }

    fun submit(caller: VfsOperationContext, request: FuseRequest) = lock.withLock {
        if (state == State.ACTIVE || state == State.DESTROYING) {
            enqueueLocked(request, PendingKind.BACKGROUND, null, caller)
        }
    }

    fun forget(nodeId: ULong, count: ULong) {
        if (nodeId == FuseAbi.ROOT_ID || count == 0uL) return
        lock.withLock {
            if (state == State.ACTIVE || state == State.DESTROYING) {
                forgotten[nodeId] = (forgotten[nodeId] ?: 0uL) + count
                readWaiters.wakeOne()
            }
        }
    }

    fun destroy() = lock.withLock {
        if (state != State.ACTIVE) {
            if (state != State.DISCONNECTED) disconnectLocked(VfsError.NOT_CONNECTED)
            return@withLock
        }
        state = State.DESTROYING
        val request = FuseRequest(FuseOpcode.DESTROY, FuseAbi.ROOT_ID)
        request.prepare(0uL, VfsOperationContext.KERNEL)
        outbound.addLast(OutboundRequest(request, null, disconnectAfterRead = true))
        readWaiters.wakeOne()
        stateWaiters.wakeAll()
    }

    fun supports(feature: FuseFeature): Boolean = lock.withLock {
        negotiation?.features?.and(feature.mask) != 0uL
    }

    fun maximumReadSize(): Int = lock.withLock {
        negotiation?.maxRead ?: maximumRead
    }

    fun maximumReadAheadSize(): Int = lock.withLock {
        negotiation?.maxReadAhead ?: 4096
    }

    fun maximumWriteSize(): Int = lock.withLock {
        negotiation?.maxWrite ?: 4096
    }

    fun protocolMinorVersion(): UInt = lock.withLock {
        negotiation?.minorVersion ?: 0u
    }

    fun isUnsupported(opcode: FuseOpcode): Boolean = lock.withLock { opcode in unsupported }

    fun markUnsupported(opcode: FuseOpcode) = lock.withLock { unsupported += opcode }

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long {
        if (size > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
        val capacity = size.toInt()
        var error = 0L
        val message = lock.withLock {
            when (state) {
                State.NEW -> {
                    error = -Errno.EPERM.toLong()
                    return@withLock null
                }
                State.DISCONNECTED -> {
                    error = -deviceDisconnectionErrno.toLong()
                    return@withLock null
                }
                else -> Unit
            }
            if (capacity < requiredReadBufferLocked()) {
                error = -Errno.EINVAL.toLong()
                return@withLock null
            }
            val next = nextOutboundLocked(capacity) ?: run {
                error = -Errno.EAGAIN.toLong()
                return@withLock null
            }
            if (next.request.bytes.size > capacity) {
                restoreOutboundLocked(next)
                error = -Errno.EINVAL.toLong()
                return@withLock null
            }
            next.also { it.pending?.state = PendingState.CLAIMED }
        }
        if (message == null) return error

        val bytes = message.request.bytes
        if (buffer.copyFrom(bufferOffset, bytes, 0, bytes.size) != bytes.size) {
            var wake: Thread? = null
            lock.withLock {
                if (state != State.DISCONNECTED) {
                    val request = message.pending
                    if (request?.interrupted == true) {
                        pending.remove(request.unique)
                        request.state = PendingState.FINISHED
                        request.result = VfsResult.Err(VfsError.INTERRUPTED)
                        wake = request.thread
                    } else {
                        request?.state = PendingState.QUEUED
                        restoreOutboundLocked(message)
                        readWaiters.wakeOne()
                    }
                }
            }
            wake?.let(Scheduler::wake)
            return -Errno.EFAULT.toLong()
        }

        var wake: Thread? = null
        lock.withLock {
            message.pending?.also {
                it.state = PendingState.SENT
                if (it.interrupted) queueInterruptLocked(it)
                wake = it.thread
            }
            if (message.disconnectAfterRead) {
                disconnectLocked(VfsError.NOT_CONNECTED)
            } else if (hasOutboundLocked()) {
                readWaiters.wakeOne()
            }
        }
        wake?.let(Scheduler::wake)
        return bytes.size.toLong()
    }

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long {
        if (size !in FuseAbi.OUT_HEADER_SIZE.toULong()..FuseAbi.MAX_PACKET_SIZE.toULong()) {
            return -Errno.EINVAL.toLong()
        }
        val count = size.toInt()
        val bytes = ByteArray(count)
        if (buffer.copyTo(bufferOffset, bytes, 0, count) != count) return -Errno.EFAULT.toLong()
        val accepted = acceptResponse(bytes)
        return if (accepted == 0) count.toLong() else -accepted.toLong()
    }

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
        -Errno.ENOTTY.toLong()

    override fun poll(device: Device, events: Int): Long = lock.withLock {
        val available = when {
            state == State.NEW || state == State.DISCONNECTED -> PollEvents.POLLERR
            hasOutboundLocked() -> PollEvents.NORMAL_INPUT or PollEvents.NORMAL_OUTPUT
            else -> PollEvents.NORMAL_OUTPUT
        }
        (available and (events or PollEvents.UNCONDITIONALLY_REPORTED)).toLong()
    }

    override fun await(device: Device, event: DeviceIoEvent, count: Int): Boolean {
        if (event == DeviceIoEvent.WRITABLE) return lock.withLock { state != State.DISCONNECTED }
        val thread = checkNotNull(ProcessManager.currentThread())
        val waiter = lock.withLock {
            if (hasOutboundLocked() || state == State.DISCONNECTED || state == State.NEW) {
                return@withLock null
            }
            readWaiters.add(thread)
        } ?: return true
        return readWaiters.await(lock, waiter)
    }

    override fun close(device: Device) = lock.withLock {
        disconnectLocked(VfsError.NOT_CONNECTED, aborted = true)
    }

    private fun awaitActive(): VfsResult<Unit> {
        val thread = ProcessManager.currentThread()
            ?: return VfsResult.Err(VfsError.INTERRUPTED)
        while (true) {
            val waiter = lock.withLock {
                when (state) {
                    State.ACTIVE -> return VfsResult.Ok(Unit)
                    State.INITIALIZING -> stateWaiters.add(thread)
                    else -> return VfsResult.Err(disconnectionError)
                }
            }
            if (!stateWaiters.await(lock, waiter)) return VfsResult.Err(VfsError.INTERRUPTED)
        }
    }

    private fun enqueueLocked(
        request: FuseRequest,
        kind: PendingKind,
        thread: Thread?,
        caller: VfsOperationContext,
    ): PendingRequest {
        val unique = allocateUniqueLocked()
        request.prepare(unique, caller)
        val requestState = PendingRequest(kind, thread)
        requestState.unique = unique
        pending[unique] = requestState
        outbound.addLast(OutboundRequest(request, requestState))
        readWaiters.wakeOne()
        return requestState
    }

    private fun allocateUniqueLocked(): ULong {
        while (pending.containsKey(nextUnique)) {
            nextUnique += 2uL
            if (nextUnique == 0uL || nextUnique and FuseAbi.RESEND_UNIQUE_MASK != 0uL) {
                nextUnique = 2uL
            }
        }
        val unique = nextUnique
        nextUnique += 2uL
        if (nextUnique == 0uL || nextUnique and FuseAbi.RESEND_UNIQUE_MASK != 0uL) {
            nextUnique = 2uL
        }
        return unique
    }

    private fun acceptResponse(bytes: ByteArray): Int {
        val header = LittleEndianBuffer(bytes)
        if (header.readU32(0).toInt() != bytes.size) return Errno.EINVAL
        val error = header.readU32(4).toInt()
        val unique = header.readU64(8)
        if (unique == 0uL) {
            if (error <= 0) return Errno.EINVAL
            return acceptNotification(error, FuseReply(bytes))
        }
        if (error > 0 || error <= -512 || error != 0 && bytes.size != FuseAbi.OUT_HEADER_SIZE) {
            return Errno.EINVAL
        }
        if (unique and FuseAbi.INTERRUPT_UNIQUE_MASK != 0uL) {
            if (bytes.size != FuseAbi.OUT_HEADER_SIZE) return Errno.EINVAL
            return acceptInterruptResponse(unique xor FuseAbi.INTERRUPT_UNIQUE_MASK, error)
        }

        val request = lock.withLock {
            val found = pending.remove(unique) ?: return@withLock null
            if (found.state != PendingState.SENT) {
                pending[unique] = found
                return@withLock null
            }
            outbound.removeAll { it.interruptTarget == unique }
            found.interruptQueued = false
            found.state = PendingState.FINISHED
            found.result = if (error == 0) {
                VfsResult.Ok(FuseReply(bytes))
            } else {
                VfsResult.Err(VfsError.fromErrno(-error))
            }
            found
        } ?: return Errno.ENOENT

        when (request.kind) {
            PendingKind.INITIALIZATION -> finishInitialization(checkNotNull(request.result))
            PendingKind.SYNCHRONOUS -> request.thread?.let(Scheduler::wake)
            PendingKind.BACKGROUND -> Unit
        }
        return 0
    }

    private fun finishInitialization(result: VfsResult<FuseReply>) {
        val initialized = when (result) {
            is VfsResult.Err -> null
            is VfsResult.Ok -> {
                val reply = result.value
                if (reply.bodySize < 64 || reply.readU32(0) != FuseAbi.VERSION) {
                    null
                } else {
                    val minor = minOf(reply.readU32(4), FuseAbi.MINOR_VERSION)
                    if (minor < 31u) {
                        null
                    } else {
                        val offeredLow = reply.readU32(12).toULong()
                        val offered = offeredLow or if (
                            offeredLow and FuseFeature.INIT_EXT.mask != 0uL
                        ) {
                            reply.readU32(32).toULong() shl 32
                        } else {
                            0uL
                        }
                        val features = offered and FuseFeature.supportedMask
                        val pageLimit = if (features and FuseFeature.MAX_PAGES.mask != 0uL) {
                            (reply.readU16(28).toInt().takeIf { it != 0 } ?: FuseAbi.MAX_PAGES) * 4096
                        } else {
                            32 * 4096
                        }
                        val protocolLimit = if (features and FuseFeature.BIG_WRITES.mask != 0uL) {
                            FuseAbi.MAX_TRANSFER_SIZE
                        } else {
                            4096
                        }
                        val reported = reply.readU32(20).toULong().takeIf { it != 0uL }
                            ?: 4096uL
                        Negotiation(
                            minorVersion = minor,
                            features = features,
                            maxRead = minOf(maximumRead, pageLimit, FuseAbi.MAX_TRANSFER_SIZE),
                            maxReadAhead = minOf(
                                reply.readU32(8).toULong(),
                                maximumRead.toULong(),
                                pageLimit.toULong(),
                            ).toInt().let { it / 4096 * 4096 }.coerceAtLeast(4096),
                            maxWrite = minOf(
                                reported,
                                pageLimit.toULong(),
                                protocolLimit.toULong(),
                            ).toInt()
                                .coerceAtLeast(4096),
                        )
                    }
                }
            }
        }
        lock.withLock {
            if (state != State.INITIALIZING) return@withLock
            if (initialized == null) {
                disconnectLocked(
                    if (result is VfsResult.Err) result.error
                    else VfsError.fromErrno(Errno.EPROTO),
                    aborted = true,
                )
            } else {
                negotiation = initialized
                state = State.ACTIVE
                stateWaiters.wakeAll()
            }
        }
    }

    private fun acceptNotification(code: Int, reply: FuseReply): Int {
        val notification = FuseNotifyCode.fromValue(code) ?: return Errno.EINVAL
        val sink = lock.withLock { notificationSink } ?: return Errno.ENODEV
        return when (notification) {
            FuseNotifyCode.INVALIDATE_INODE -> {
                if (reply.bodySize != 24) return Errno.EINVAL
                sink.invalidateInode(
                    reply.readU64(0),
                    reply.readU64(8).toLong(),
                    reply.readU64(16).toLong(),
                )
                0
            }
            FuseNotifyCode.INVALIDATE_ENTRY -> {
                if (reply.bodySize < 16) return Errno.EINVAL
                val length = reply.readU32(8).toInt()
                if (length <= 0 || reply.bodySize != 16 + length || reply.readU32(12) != 0u) {
                    return Errno.EINVAL
                }
                val name = when (val parsed = VfsName.fromBytes(reply.bodyBytes(16, length))) {
                    is VfsResult.Ok -> parsed.value
                    is VfsResult.Err -> return parsed.error.errno
                }
                sink.invalidateEntry(reply.readU64(0), name)
                0
            }
            FuseNotifyCode.DELETE -> {
                if (reply.bodySize < 24) return Errno.EINVAL
                val length = reply.readU32(16).toInt()
                if (length <= 0 || reply.bodySize != 24 + length) return Errno.EINVAL
                val name = when (val parsed = VfsName.fromBytes(reply.bodyBytes(24, length))) {
                    is VfsResult.Ok -> parsed.value
                    is VfsResult.Err -> return parsed.error.errno
                }
                sink.invalidateEntry(reply.readU64(0), name, reply.readU64(8))
                0
            }
            FuseNotifyCode.POLL -> if (reply.bodySize == ULong.SIZE_BYTES) 0 else Errno.EINVAL
            else -> Errno.EOPNOTSUPP
        }
    }

    private fun requiredReadBufferLocked(): Int {
        val maxWrite = negotiation?.maxWrite ?: 0
        return maxOf(FuseAbi.MIN_READ_BUFFER, FuseAbi.IN_HEADER_SIZE + 40 + maxWrite)
    }

    private fun nextOutboundLocked(capacity: Int): OutboundRequest? {
        val next = outbound.firstOrNull()
        val urgent = next?.let {
            it.disconnectAfterRead || it.interruptTarget != null ||
                it.pending?.kind == PendingKind.INITIALIZATION
        } == true
        if (next != null && (forgotten.isEmpty() || urgent ||
                requestsSinceForget < REQUESTS_PER_FORGET_BATCH)
        ) {
            if (forgotten.isNotEmpty()) requestsSinceForget++
            return outbound.removeFirst()
        }
        if (forgotten.isEmpty()) return null
        requestsSinceForget = 0

        val limit = minOf(
            (capacity - FuseAbi.IN_HEADER_SIZE - 8) / 16,
            (FuseAbi.MAX_PACKET_SIZE - FuseAbi.IN_HEADER_SIZE - 8) / 16,
        )
        if (limit <= 0) return null
        val entries = buildList(minOf(limit, forgotten.size)) {
            val iterator = forgotten.iterator()
            repeat(minOf(limit, forgotten.size)) {
                val entry = iterator.next()
                add(Forget(entry.key, entry.value))
                iterator.remove()
            }
        }
        val request = FuseRequest(FuseOpcode.BATCH_FORGET, 0uL, 8 + entries.size * 16).apply {
            writeU32(0, entries.size.toUInt())
            entries.forEachIndexed { index, forget ->
                val offset = 8 + index * 16
                writeU64(offset, forget.nodeId)
                writeU64(offset + 8, forget.count)
            }
            prepare(0uL, VfsOperationContext.KERNEL)
        }
        return OutboundRequest(request, null, forgets = entries)
    }

    private fun queueInterruptLocked(request: PendingRequest) {
        if (request.interruptQueued || FuseOpcode.INTERRUPT in unsupported ||
            request.state != PendingState.SENT
        ) {
            return
        }
        val target = request.unique
        val interrupt = FuseRequest(FuseOpcode.INTERRUPT, 0uL, ULong.SIZE_BYTES).apply {
            writeU64(0, target)
            prepare(target or FuseAbi.INTERRUPT_UNIQUE_MASK, VfsOperationContext.KERNEL)
        }
        request.interruptQueued = true
        outbound.addFirst(OutboundRequest(interrupt, null, interruptTarget = target))
        readWaiters.wakeOne()
    }

    private fun acceptInterruptResponse(target: ULong, error: Int): Int = lock.withLock {
        val request = pending[target] ?: return@withLock Errno.ENOENT
        request.interruptQueued = false
        when (error) {
            -Errno.ENOSYS -> unsupported += FuseOpcode.INTERRUPT
            -Errno.EAGAIN -> queueInterruptLocked(request)
        }
        0
    }

    private fun hasOutboundLocked(): Boolean = outbound.isNotEmpty() || forgotten.isNotEmpty()

    private fun restoreOutboundLocked(message: OutboundRequest) {
        val entries = message.forgets
        if (entries == null) {
            outbound.addFirst(message)
            return
        }
        entries.forEach { forget ->
            forgotten[forget.nodeId] = (forgotten[forget.nodeId] ?: 0uL) + forget.count
        }
    }

    private fun disconnectLocked(error: VfsError, aborted: Boolean = false) {
        if (state == State.DISCONNECTED) return
        state = State.DISCONNECTED
        disconnectionError = error
        deviceDisconnectionErrno = if (aborted &&
            negotiation?.features?.and(FuseFeature.ABORT_ERROR.mask) != 0uL
        ) {
            Errno.ECONNABORTED
        } else {
            Errno.ENODEV
        }
        outbound.clear()
        forgotten.clear()
        pending.values.forEach { request ->
            request.state = PendingState.FINISHED
            request.result = VfsResult.Err(error)
            request.thread?.let(Scheduler::wake)
        }
        pending.clear()
        readWaiters.wakeAll()
        stateWaiters.wakeAll()
    }
}
