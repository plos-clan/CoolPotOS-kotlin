package org.plos_clan.cpos.drivers.input

import org.plos_clan.cpos.tasks.PollSource
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceIoEvent
import org.plos_clan.cpos.drivers.WaitablePositionlessDeviceBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

internal enum class InputEventType(val value: UShort) {
    SYNCHRONIZATION(0u),
    KEY(1u),
    RELATIVE(2u),
    ABSOLUTE(3u),
    REPEAT(20u),
}

internal enum class RelativeAxis(val code: UShort) {
    X(0u),
    Y(1u),
    WHEEL(8u),
    HORIZONTAL_WHEEL(6u),
}

internal data class InputId(
    val bus: UShort,
    val vendor: UShort = 0u,
    val product: UShort = 0u,
    val version: UShort = 0u,
) {
    fun toByteArray(): ByteArray = ByteArray(SIZE_BYTES).also { bytes ->
        LittleEndianBuffer(bytes).apply {
            writeU16(0, bus)
            writeU16(2, vendor)
            writeU16(4, product)
            writeU16(6, version)
        }
    }

    companion object {
        const val SIZE_BYTES = 8
        const val BUS_USB: UShort = 0x03u
        const val BUS_I8042: UShort = 0x11u
    }
}

internal enum class InputClock(val id: Int) {
    REALTIME(0),
    MONOTONIC(1),
    BOOTTIME(7),
}

internal data class InputEvent(
    val timestampNanos: ULong,
    val type: InputEventType,
    val code: UShort,
    val value: Int,
) {
    fun writeTo(bytes: ByteArray, clock: InputClock) {
        val realtime = if (clock == InputClock.REALTIME) RealtimeClock.atMonotonic(timestampNanos) else null
        val seconds = realtime?.seconds?.toULong() ?: (timestampNanos / NANOS_PER_SECOND)
        val nanos = realtime?.nanoseconds?.toULong() ?: (timestampNanos % NANOS_PER_SECOND)
        val output = LittleEndianBuffer(bytes)
        output.writeU64(0, seconds)
        output.writeU64(8, nanos / NANOS_PER_MICROSECOND)
        output.writeU16(16, type.value)
        output.writeU16(18, code)
        output.writeU32(20, value.toUInt())
    }

    companion object {
        const val SIZE_BYTES = 24
        const val SYN_REPORT: UShort = 0u
        const val SYN_DROPPED: UShort = 3u
        private const val NANOS_PER_SECOND = 1_000_000_000uL
        private const val NANOS_PER_MICROSECOND = 1_000uL
    }
}

internal data class RepeatSettings(val delayMillis: Int, val periodMillis: Int)

internal interface RepeatController {
    fun repeatSettings(): RepeatSettings
    fun configureRepeat(settings: RepeatSettings): Boolean
}

internal interface InputEventSink {
    fun receive(event: InputEvent)
}

internal class EvdevDevice(
    private val name: String,
    private val physicalPath: String,
    private val id: InputId,
    supportedKeys: Collection<UShort>,
    supportedRelativeAxes: Collection<RelativeAxis>,
    private val repeatController: RepeatController?,
    supportedAbsoluteAxes: Map<AbsoluteAxis, AbsoluteAxisInfo> = emptyMap(),
) : DeviceBackend, InputEventSink {
    private val lock = IrqSpinLock()
    private val clients = mutableListOf<EvdevClient>()
    private val keyCapabilities = ByteArray(KEY_BITMAP_BYTES)
    private val keyState = ByteArray(KEY_BITMAP_BYTES)
    private val relativeCapabilities = ByteArray(RELATIVE_BITMAP_BYTES)
    private val absoluteCapabilities = ByteArray(ULong.SIZE_BYTES)
    private val absoluteAxes = if (supportedAbsoluteAxes.isEmpty()) null else
        Array(64) { AbsoluteAxisInfo() }
    private val eventTypeCapabilities = ByteArray(EVENT_TYPE_BITMAP_BYTES)
    private var grabbed: EvdevClient? = null
    private var disconnected = false
    private var publication: InputPublication? = null

    init {
        require(supportedKeys.all { it.toInt() <= KEY_MAX })
        supportedKeys.forEach { keyCapabilities.setBit(it.toInt(), true) }
        supportedRelativeAxes.forEach { relativeCapabilities.setBit(it.code.toInt(), true) }
        eventTypeCapabilities.setBit(InputEventType.SYNCHRONIZATION.value.toInt(), true)
        if (supportedKeys.isNotEmpty()) {
            eventTypeCapabilities.setBit(InputEventType.KEY.value.toInt(), true)
        }
        if (supportedRelativeAxes.isNotEmpty()) {
            eventTypeCapabilities.setBit(InputEventType.RELATIVE.value.toInt(), true)
        }
        for ((axis, info) in supportedAbsoluteAxes) {
            absoluteCapabilities.setBit(axis.code.toInt(), true)
            checkNotNull(absoluteAxes)[axis.code.toInt()] = info.copy()
        }
        if (supportedAbsoluteAxes.isNotEmpty()) {
            eventTypeCapabilities.setBit(InputEventType.ABSOLUTE.value.toInt(), true)
        }
        if (repeatController != null) {
            eventTypeCapabilities.setBit(InputEventType.REPEAT.value.toInt(), true)
        }
    }

    fun install(index: Int = allocateIndex()): Boolean {
        val capabilities = mapOf(
            "ev" to eventTypeCapabilities, "key" to keyCapabilities, "rel" to relativeCapabilities,
            "abs" to absoluteCapabilities, "msc" to EMPTY_EVENT_CAPABILITIES,
            "led" to EMPTY_EVENT_CAPABILITIES, "snd" to EMPTY_EVENT_CAPABILITIES,
            "ff" to EMPTY_FORCE_FEEDBACK_CAPABILITIES, "sw" to EMPTY_EVENT_CAPABILITIES,
        )
        publication = InputPublication.create(index, this, name, physicalPath, id, capabilities)
        return publication != null
    }

    fun uninstall(): Boolean {
        lock.withLock {
            disconnected = true
            grabbed = null
            clients.forEach { it.revoke() }
        }
        val registration = publication ?: return false
        publication = null
        registration.close()
        return true
    }

    override fun open(device: Device): VfsResult<DeviceBackend> = lock.withLock {
        if (disconnected) return@withLock VfsResult.Err(VfsError.NO_DEVICE)
        val client = EvdevClient(this)
        clients.add(client)
        VfsResult.Ok(client)
    }

    override fun receive(event: InputEvent) = lock.withLock {
        if (disconnected) return@withLock
        if (event.type == InputEventType.KEY && event.value != KeyAction.REPEATED.value) {
            val code = event.code.toInt()
            val pressed = event.value == KeyAction.PRESSED.value
            if (code > KEY_MAX) return@withLock
            val mask = 1 shl (code % Byte.SIZE_BITS)
            val previous = keyState[code / Byte.SIZE_BITS].toInt() and mask != 0
            if (previous == pressed) return@withLock
            keyState.setBit(code, pressed)
        }
        var forwarded = event
        if (event.type == InputEventType.ABSOLUTE) {
            val axis = absoluteAxes?.getOrNull(event.code.toInt()) ?: return@withLock
            if (!absoluteCapabilities.hasBit(event.code.toInt()) || !axis.update(event.value)) return@withLock
            if (axis.value != event.value) forwarded = event.copy(value = axis.value)
        }
        grabbed?.receive(forwarded) ?: clients.forEach { it.receive(forwarded) }
    }

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
        -Errno.ENODEV.toLong()

    override fun poll(device: Device, events: Int): Long = -Errno.ENODEV.toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long = -Errno.ENODEV.toLong()

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long = -Errno.ENODEV.toLong()

    private fun ioctl(client: EvdevClient, command: Int, args: UserMemory): Long {
        val request = command.toUInt()
        if (request.ioctlType != EVDEV_IOCTL_TYPE) return -Errno.ENOTTY.toLong()
        val size = request.ioctlSize
        val number = request.ioctlNumber
        val direction = request.ioctlDirection

        return when (number) {
            0x01 if direction.hasRead && size >= Int.SIZE_BYTES ->
                args.copyResult(intBytes(EVDEV_VERSION), fixedSize = true)

            0x02 if direction.hasRead && size >= InputId.SIZE_BYTES ->
                args.copyResult(id.toByteArray(), fixedSize = true)

            0x03 if direction.hasRead && size >= REPEAT_BYTES -> {
                val repeat = repeatController?.repeatSettings() ?: return -Errno.ENOSYS.toLong()
                args.copyResult(ByteArray(REPEAT_BYTES).also { bytes ->
                    LittleEndianBuffer(bytes).apply {
                        writeU32(0, repeat.delayMillis.toUInt())
                        writeU32(UInt.SIZE_BYTES, repeat.periodMillis.toUInt())
                    }
                }, fixedSize = true)
            }

            0x03 if direction.hasWrite && size >= REPEAT_BYTES -> {
                if (repeatController == null) return -Errno.ENOSYS.toLong()
                val bytes = args.copyFromUser(REPEAT_BYTES) ?: return -Errno.EFAULT.toLong()
                val input = LittleEndianBuffer(bytes)
                val delay = input.readU32(0).toInt()
                val period = input.readU32(UInt.SIZE_BYTES).toInt()
                val settings = RepeatSettings(delay, period)
                if (delay < 0 || period < 0 ||
                    !repeatController.configureRepeat(settings)
                ) -Errno.EINVAL.toLong() else 0L
            }

            0x06 if direction.hasRead -> args.copyCString(name, size)
            0x07 if direction.hasRead -> args.copyCString(physicalPath, size)
            0x08 if direction.hasRead -> args.copyCString("", size)
            0x09 if direction.hasRead -> args.copyBitmap(ByteArray(PROPERTY_BITMAP_BYTES), size)
            0x18 if direction.hasRead -> args.copyBitmap(lock.withLock { keyState.copyOf() }, size)
            in 0x19..0x1b if direction.hasRead -> args.copyBitmap(EMPTY_EVENT_CAPABILITIES, size)
            in 0x20..0x3f if direction.hasRead -> {
                val bitmap = when (number - 0x20) {
                    0 -> eventTypeCapabilities
                    InputEventType.KEY.value.toInt() -> keyCapabilities
                    InputEventType.RELATIVE.value.toInt() -> relativeCapabilities
                    InputEventType.ABSOLUTE.value.toInt() -> absoluteCapabilities
                    4, 5, 17, 18 -> EMPTY_EVENT_CAPABILITIES
                    21 -> EMPTY_FORCE_FEEDBACK_CAPABILITIES
                    else -> return -Errno.EINVAL.toLong()
                }
                args.copyBitmap(bitmap, size)
            }

            in 0x40..0x7f if direction == 2 -> {
                val axes = absoluteAxes ?: return -Errno.EINVAL.toLong()
                val bytes = lock.withLock { axes[number - 0x40].encode() }
                args.copyResult(bytes.copyOf(minOf(size, bytes.size)), fixedSize = true)
            }

            in 0xc0..0xff if direction == 1 -> {
                val axes = absoluteAxes ?: return -Errno.EINVAL.toLong()
                if (number - 0xc0 == 0x2f) return -Errno.EINVAL.toLong()
                val bytes = args.copyFromUser(minOf(size, AbsoluteAxisInfo.SIZE_BYTES))
                    ?: return -Errno.EFAULT.toLong()
                val info = AbsoluteAxisInfo.decode(bytes)
                lock.withLock { axes[number - 0xc0] = info }
                0L
            }

            0x90 if direction.hasWrite && size >= Int.SIZE_BYTES -> {
                setGrab(client, args.address != 0uL)
            }

            0x91 if direction.hasWrite && size >= Int.SIZE_BYTES -> {
                if (args.address != 0uL) return -Errno.EINVAL.toLong()
                lock.withLock {
                    if (grabbed === client) grabbed = null
                    client.revoke()
                }
                0L
            }

            0xa0 if direction.hasWrite && size >= Int.SIZE_BYTES -> {
                val clockId = args.readInt() ?: return -Errno.EFAULT.toLong()
                val clock = InputClock.entries.find { it.id == clockId } ?: return -Errno.EINVAL.toLong()
                client.selectClock(clock)
                0L
            }

            else -> -Errno.ENOTTY.toLong()
        }
    }

    private fun setGrab(client: EvdevClient, enabled: Boolean): Long = lock.withLock {
        if (enabled) {
            if (grabbed != null) -Errno.EBUSY.toLong()
            else {
                grabbed = client
                0L
            }
        } else if (grabbed !== client) {
            -Errno.EINVAL.toLong()
        } else {
            grabbed = null
            0L
        }
    }

    private fun close(client: EvdevClient) = lock.withLock {
        clients.remove(client)
        if (grabbed === client) grabbed = null
    }

    private class EvdevClient(
        private val owner: EvdevDevice,
    ) : WaitablePositionlessDeviceBackend, InputEventSink {
        private class Waiter(val thread: Thread) {
            var ready = false
        }

        private val changes = PollSource()
        override fun subscribe(device: Device, subscription: PollSubscription) =
            subscription.watch(changes)

        private val lock = IrqSpinLock()
        private val queue = arrayOfNulls<InputEvent>(QUEUE_EVENTS)
        private val waiters = ArrayDeque<Waiter>()
        private val eventBytes = ByteArray(InputEvent.SIZE_BYTES)
        private var head = 0
        private var tail = 0
        private var size = 0
        private var committed = 0
        private var overflow = false
        private var revoked = false
        private var clock = InputClock.REALTIME

        override fun receive(event: InputEvent) = lock.withLock {
            if (revoked) return@withLock
            if (overflow) {
                if (event.type == InputEventType.SYNCHRONIZATION &&
                    event.code == InputEvent.SYN_REPORT
                ) {
                    clearQueue()
                    enqueue(
                        InputEvent(
                            event.timestampNanos,
                            InputEventType.SYNCHRONIZATION,
                            InputEvent.SYN_DROPPED,
                            0,
                        ),
                    )
                    enqueue(event)
                    overflow = false
                    commitFrame()
                }
                return@withLock
            }
            if (size == queue.size) {
                overflow = true
                return@withLock
            }
            enqueue(event)
            if (event.type == InputEventType.SYNCHRONIZATION &&
                event.code == InputEvent.SYN_REPORT
            ) commitFrame()
        }

        override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
            if (lock.withLock { revoked }) -Errno.ENODEV.toLong()
            else owner.ioctl(this, command, args)

        override fun poll(device: Device, events: Int): Long = lock.withLock {
            val available = when {
                revoked -> PollEvents.POLLHUP or PollEvents.POLLERR
                committed != 0 -> PollEvents.NORMAL_INPUT
                else -> 0
            }
            (
                (available and events) or
                    (available and PollEvents.UNCONDITIONALLY_REPORTED)
                ).toLong()
        }

        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
        ): Long {
            val requested = size.toInt()
            if (requested < InputEvent.SIZE_BYTES) return -Errno.EINVAL.toLong()
            return lock.withLock {
                if (revoked) return@withLock -Errno.ENODEV.toLong()
                if (committed == 0) return@withLock -Errno.EAGAIN.toLong()

                val count = minOf(requested / InputEvent.SIZE_BYTES, committed)
                var transferred = 0
                repeat(count) {
                    val event = queue[tail] ?: return@withLock -Errno.EIO.toLong()
                    event.writeTo(eventBytes, clock)
                    val copied = buffer.copyFrom(
                        bufferOffset + transferred,
                        eventBytes,
                        0,
                        InputEvent.SIZE_BYTES,
                    )
                    if (copied != InputEvent.SIZE_BYTES) {
                        return@withLock if (transferred == 0) -Errno.EFAULT.toLong()
                        else transferred.toLong()
                    }
                    queue[tail] = null
                    tail = (tail + 1) % queue.size
                    this.size--
                    committed--
                    transferred += InputEvent.SIZE_BYTES
                }
                transferred.toLong()
            }
        }

        override fun write(
            device: Device,
            buffer: PreparedBufferSource,
            bufferOffset: Int,
            size: ULong,
        ): Long = -Errno.EINVAL.toLong()

        override fun await(device: Device, event: DeviceIoEvent, count: Int): Boolean {
            if (event != DeviceIoEvent.READABLE) return true
            val thread = checkNotNull(ProcessManager.currentThread())
            val waiter = Waiter(thread)
            val queued = lock.withLock {
                if (committed != 0 || revoked) false
                else {
                    waiters.addLast(waiter)
                    true
                }
            }
            if (!queued) return true
            while (true) {
                val sequence = Scheduler.preparePark()
                if (lock.withLock { waiter.ready }) break
                if (thread.hasPendingSignal() || !Scheduler.parkCurrent(sequence)) {
                    lock.withLock { waiters.remove(waiter) }
                    return false
                }
            }
            return true
        }

        override fun close(device: Device) {
            revoke()
            owner.close(this)
        }

        fun selectClock(selected: InputClock) = lock.withLock {
            if (clock == selected) return@withLock
            clock = selected
            if (size == 0) return@withLock
            clearQueue()
            val dropped = InputEvent(
                TscClock.nanoTime(), InputEventType.SYNCHRONIZATION, InputEvent.SYN_DROPPED, 0,
            )
            enqueue(dropped)
        }

        fun revoke() = lock.withLock {
            if (revoked) return@withLock
            revoked = true
            changes.signal()
            clearQueue()
            wakeReaders()
        }

        private fun enqueue(event: InputEvent) {
            queue[head] = event
            head = (head + 1) % queue.size
            size++
        }

        private fun commitFrame() {
            changes.signal()
            committed = size
            wakeReaders()
        }

        private fun clearQueue() {
            queue.fill(null)
            head = 0
            tail = 0
            size = 0
            committed = 0
        }

        private fun wakeReaders() {
            while (waiters.isNotEmpty()) {
                val waiter = waiters.removeFirst()
                waiter.ready = true
                Scheduler.wake(waiter.thread)
            }
        }
    }

    companion object {
        private val indexLock = IrqSpinLock()
        private var nextIndex = 0

        fun allocateIndex(): Int = indexLock.withLock { nextIndex++ }

        private const val EVDEV_IOCTL_TYPE = 0x45
        private const val EVDEV_VERSION = 0x0001_0001
        private const val KEY_MAX = 0x2ff
        private const val KEY_BITMAP_BYTES = (KEY_MAX + Byte.SIZE_BITS) / Byte.SIZE_BITS
        private const val RELATIVE_BITMAP_BYTES = ULong.SIZE_BYTES
        private const val PROPERTY_BITMAP_BYTES = ULong.SIZE_BYTES
        private const val EVENT_TYPE_BITMAP_BYTES = ULong.SIZE_BYTES
        private const val REPEAT_BYTES = 8
        private const val QUEUE_EVENTS = 256
        private val EMPTY_EVENT_CAPABILITIES = ByteArray(ULong.SIZE_BYTES)
        private val EMPTY_FORCE_FEEDBACK_CAPABILITIES = ByteArray(ULong.SIZE_BYTES * 2)

        private fun intBytes(value: Int): ByteArray = ByteArray(Int.SIZE_BYTES).also { bytes ->
            LittleEndianBuffer(bytes).writeU32(0, value.toUInt())
        }

        private fun ByteArray.hasBit(index: Int): Boolean {
            if (index !in 0 until size * Byte.SIZE_BITS) return false
            val mask = 1 shl (index % Byte.SIZE_BITS)
            return this[index / Byte.SIZE_BITS].toInt() and mask != 0
        }

        private fun ByteArray.setBit(index: Int, enabled: Boolean) {
            if (index !in 0 until size * Byte.SIZE_BITS) return
            val mask = 1 shl (index % Byte.SIZE_BITS)
            val byteIndex = index / Byte.SIZE_BITS
            this[byteIndex] = if (enabled) {
                (this[byteIndex].toInt() or mask).toByte()
            } else {
                (this[byteIndex].toInt() and mask.inv()).toByte()
            }
        }

        private val UInt.ioctlNumber: Int
            get() = (this and 0xffu).toInt()
        private val UInt.ioctlType: Int
            get() = (this shr 8 and 0xffu).toInt()
        private val UInt.ioctlSize: Int
            get() = (this shr 16 and 0x3fffu).toInt()
        private val UInt.ioctlDirection: Int
            get() = (this shr 30 and 0x03u).toInt()
        private val Int.hasWrite: Boolean
            get() = this and 1 != 0
        private val Int.hasRead: Boolean
            get() = this and 2 != 0

        private fun UserMemory.readInt(): Int? = copyFromUser(Int.SIZE_BYTES)
            ?.let { LittleEndianBuffer(it).readU32(0).toInt() }

        private fun UserMemory.copyResult(bytes: ByteArray, fixedSize: Boolean): Long {
            if (!copyToUser(bytes)) return -Errno.EFAULT.toLong()
            return if (fixedSize) 0L else bytes.size.toLong()
        }

        private fun UserMemory.copyCString(value: String, requested: Int): Long {
            if (requested == 0) return 0L
            val encoded = value.encodeToByteArray()
            val bytes = ByteArray(minOf(requested, encoded.size + 1))
            encoded.copyInto(bytes, endIndex = minOf(encoded.size, bytes.size))
            return copyResult(bytes, fixedSize = false)
        }

        private fun UserMemory.copyBitmap(bitmap: ByteArray, requested: Int): Long {
            val bytes = bitmap.copyOf(minOf(requested, bitmap.size))
            return copyResult(bytes, fixedSize = false)
        }
    }
}
