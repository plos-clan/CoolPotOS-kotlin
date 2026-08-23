package org.plos_clan.cpos.drivers.input

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceIoEvent
import org.plos_clan.cpos.drivers.WaitablePositionlessDeviceBackend
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
    REPEAT(20u),
}

internal enum class KeyAction(val value: Int) {
    RELEASED(0),
    PRESSED(1),
    REPEATED(2),
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

internal data class InputEvent(
    val timestampNanos: ULong,
    val type: InputEventType,
    val code: UShort,
    val value: Int,
) {
    fun writeTo(bytes: ByteArray) {
        val output = LittleEndianBuffer(bytes)
        output.writeU64(0, timestampNanos / NANOS_PER_SECOND)
        output.writeU64(8, timestampNanos % NANOS_PER_SECOND / NANOS_PER_MICROSECOND)
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
    supportedKeys: Collection<KeyCode>,
    private val repeatController: RepeatController,
) : DeviceBackend, InputEventSink {
    private val lock = IrqSpinLock()
    private val clients = mutableListOf<EvdevClient>()
    private val keyCapabilities = ByteArray(KEY_BITMAP_BYTES)
    private val keyState = ByteArray(KEY_BITMAP_BYTES)
    private var grabbed: EvdevClient? = null

    init {
        supportedKeys.forEach { keyCapabilities.setBit(it.linuxCode.toInt(), true) }
    }

    override fun open(device: Device): DeviceBackend = EvdevClient(this).also { client ->
        lock.withLock { clients += client }
    }

    override fun receive(event: InputEvent) {
        lock.withLock {
            if (event.type == InputEventType.KEY && event.value != KeyAction.REPEATED.value) {
                keyState.setBit(event.code.toInt(), event.value == KeyAction.PRESSED.value)
            }
            grabbed?.receive(event) ?: clients.forEach { it.receive(event) }
        }
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
                val repeat = repeatController.repeatSettings()
                args.copyResult(ByteArray(REPEAT_BYTES).also { bytes ->
                    LittleEndianBuffer(bytes).apply {
                        writeU32(0, repeat.delayMillis.toUInt())
                        writeU32(UInt.SIZE_BYTES, repeat.periodMillis.toUInt())
                    }
                }, fixedSize = true)
            }

            0x03 if direction.hasWrite && size >= REPEAT_BYTES -> {
                val bytes = args.copyFromUser(REPEAT_BYTES) ?: return -Errno.EFAULT.toLong()
                val input = LittleEndianBuffer(bytes)
                val delay = input.readU32(0).toInt()
                val period = input.readU32(UInt.SIZE_BYTES).toInt()
                if (delay < 0 || period < 0 ||
                    !repeatController.configureRepeat(RepeatSettings(delay, period))
                ) -Errno.EINVAL.toLong() else 0L
            }

            0x06 if direction.hasRead -> args.copyCString(name, size)
            0x07 if direction.hasRead -> args.copyCString(physicalPath, size)
            0x08 if direction.hasRead -> args.copyCString("", size)
            0x09 if direction.hasRead -> args.copyBitmap(ByteArray(PROPERTY_BITMAP_BYTES), size)
            0x18 if direction.hasRead -> args.copyBitmap(lock.withLock { keyState.copyOf() }, size)
            in 0x20..0x3f if direction.hasRead -> {
                val bitmap = when (number - 0x20) {
                    0 -> EVENT_TYPE_CAPABILITIES
                    InputEventType.KEY.value.toInt() -> keyCapabilities
                    InputEventType.REPEAT.value.toInt() -> REPEAT_CAPABILITIES
                    else -> EMPTY_EVENT_CAPABILITIES
                }
                args.copyBitmap(bitmap, size)
            }

            0x90 if direction.hasWrite && size >= Int.SIZE_BYTES -> {
                val enabled = args.readInt() ?: return -Errno.EFAULT.toLong()
                if (enabled !in 0..1) -Errno.EINVAL.toLong() else setGrab(client, enabled == 1)
            }

            0x91 if direction.hasWrite && size >= Int.SIZE_BYTES -> {
                val value = args.readInt() ?: return -Errno.EFAULT.toLong()
                if (value != 0) -Errno.EINVAL.toLong() else {
                    client.revoke()
                    0L
                }
            }

            0xa0 if direction.hasWrite && size >= Int.SIZE_BYTES -> {
                val clockId = args.readInt() ?: return -Errno.EFAULT.toLong()
                if (clockId in SUPPORTED_CLOCK_IDS) 0L else -Errno.EINVAL.toLong()
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
                revoked -> PollEvents.POLLHUP
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
                    event.writeTo(eventBytes)
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
            while (!lock.withLock { waiter.ready }) {
                if (thread.hasPendingSignal() || !Scheduler.parkCurrent()) {
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

        fun revoke() = lock.withLock {
            if (revoked) return@withLock
            revoked = true
            clearQueue()
            wakeReaders()
        }

        private fun enqueue(event: InputEvent) {
            queue[head] = event
            head = (head + 1) % queue.size
            size++
        }

        private fun commitFrame() {
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

    private companion object {
        const val EVDEV_IOCTL_TYPE = 0x45
        const val EVDEV_VERSION = 0x0001_0001
        const val KEY_MAX = 0x2ff
        const val KEY_BITMAP_BYTES = (KEY_MAX + Byte.SIZE_BITS) / Byte.SIZE_BITS
        const val PROPERTY_BITMAP_BYTES = 4
        const val REPEAT_BYTES = 8
        const val QUEUE_EVENTS = 256
        val SUPPORTED_CLOCK_IDS = setOf(0, 1, 7)
        val EVENT_TYPE_CAPABILITIES = ByteArray(4).apply {
            setBit(InputEventType.SYNCHRONIZATION.value.toInt(), true)
            setBit(InputEventType.KEY.value.toInt(), true)
            setBit(InputEventType.REPEAT.value.toInt(), true)
        }
        val REPEAT_CAPABILITIES = byteArrayOf(0x03)
        val EMPTY_EVENT_CAPABILITIES = ByteArray(0)

        fun intBytes(value: Int): ByteArray = ByteArray(Int.SIZE_BYTES).also { bytes ->
            LittleEndianBuffer(bytes).writeU32(0, value.toUInt())
        }

        fun ByteArray.setBit(index: Int, enabled: Boolean) {
            if (index !in 0 until size * Byte.SIZE_BITS) return
            val mask = 1 shl (index % Byte.SIZE_BITS)
            val byteIndex = index / Byte.SIZE_BITS
            this[byteIndex] = if (enabled) {
                (this[byteIndex].toInt() or mask).toByte()
            } else {
                (this[byteIndex].toInt() and mask.inv()).toByte()
            }
        }

        val UInt.ioctlNumber: Int
            get() = (this and 0xffu).toInt()
        val UInt.ioctlType: Int
            get() = (this shr 8 and 0xffu).toInt()
        val UInt.ioctlSize: Int
            get() = (this shr 16 and 0x3fffu).toInt()
        val UInt.ioctlDirection: Int
            get() = (this shr 30 and 0x03u).toInt()
        val Int.hasWrite: Boolean
            get() = this and 1 != 0
        val Int.hasRead: Boolean
            get() = this and 2 != 0

        fun UserMemory.readInt(): Int? = copyFromUser(Int.SIZE_BYTES)
            ?.let { LittleEndianBuffer(it).readU32(0).toInt() }

        fun UserMemory.copyResult(bytes: ByteArray, fixedSize: Boolean): Long {
            if (!copyToUser(bytes)) return -Errno.EFAULT.toLong()
            return if (fixedSize) 0L else bytes.size.toLong()
        }

        fun UserMemory.copyCString(value: String, requested: Int): Long {
            if (requested == 0) return 0L
            val encoded = value.encodeToByteArray()
            val bytes = ByteArray(minOf(requested, encoded.size + 1))
            encoded.copyInto(bytes, endIndex = minOf(encoded.size, bytes.size))
            return copyResult(bytes, fixedSize = false)
        }

        fun UserMemory.copyBitmap(bitmap: ByteArray, requested: Int): Long {
            val bytes = bitmap.copyOf(minOf(requested, bitmap.size))
            return copyResult(bytes, fixedSize = false)
        }
    }
}
