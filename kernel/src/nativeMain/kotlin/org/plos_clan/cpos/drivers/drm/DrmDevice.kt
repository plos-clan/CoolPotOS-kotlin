package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceIoEvent
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.drivers.WaitablePositionlessDeviceBackend
import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectHandle
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.vfs.MappableFile
import org.plos_clan.cpos.fs.vfs.MappedFile
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.time.MonotonicClock
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class DrmDevice(
    private val output: DrmOutput,
    private val clock: MonotonicClock,
) : PositionlessDeviceBackend {
    private val lock = KernelMutex()
    private var master: Client? = null
    private var scanout: DrmScanout? = null
    private var power = DrmPower.ON
    private var nextFramebuffer = DrmProperty.entries.maxOf { it.id } + 1u
    private val framebuffers = mutableMapOf<UInt, DrmFramebuffer>()

    fun register(index: Int, parent: SysfsObjectHandle) {
        val directory = SysfsObjectSpec(name = "drm", parent = SysfsParent.Object(parent))
        val handle = when (val result = Sysfs.registerObject(directory)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return
        }
        val bindings = SysfsBindings(deviceClass = SysfsIndexBinding("drm"))
        val spec = SysfsObjectSpec(
            name = "card$index",
            parent = SysfsParent.Object(handle),
            bindings = bindings,
            links = mapOf("device" to parent),
        )
        val publication = SysfsDevicePublication.NewObject(spec)
        val registration = DeviceRegistration(
            name = "dri/card$index",
            type = DeviceType.CHARACTER,
            major = LinuxDeviceMajor.DRM.number,
            minor = index.toUInt(),
            backend = this,
            sysfs = publication,
        )
        checkNotNull(DeviceManager.register(registration))
    }

    override fun open(device: Device): VfsResult<DeviceBackend> = lock.withLock {
        val client = Client()
        if (master == null) master = client
        VfsResult.Ok(client)
    }

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long = -Errno.ENOTTY.toLong()
    override fun poll(device: Device, events: Int): Long = 0

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EAGAIN.toLong()

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EINVAL.toLong()

    private inner class Client : PositionlessDeviceBackend by this@DrmDevice,
        WaitablePositionlessDeviceBackend, MappableFile {
        private var authority: Authority = master?.authority ?: Authority(this)
        private var magic = 0u
        private var universalPlanes = false
        private val buffers = DrmFileBuffers()
        private val events = DrmEvents(clock)
        private val ownedFramebuffers = mutableSetOf<UInt>()

        override fun subscribe(device: Device, subscription: PollSubscription) = events.subscribe(subscription)

        override fun poll(device: Device, events: Int): Long = this.events.poll(events)

        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
        ): Long = events.read(buffer, bufferOffset, size)

        override fun await(device: Device, event: DeviceIoEvent, count: Int): Boolean =
            event != DeviceIoEvent.READABLE || events.await()

        override fun close(device: Device) = lock.withLock {
            authority.revoke(magic)
            val current = scanout
            if (current != null && current.framebufferId in ownedFramebuffers) disable()
            ownedFramebuffers.forEach { framebuffers.remove(it)?.close() }
            ownedFramebuffers.clear()
            buffers.close()
            if (master === this) master = null
        }

        override fun map(
            file: OpenFileDescription,
            shared: Boolean,
            access: ULong,
            maximumAccess: ULong,
            offset: ULong,
            length: ULong,
        ): VfsResult<MappedFile> = lock.withLock {
            if (!shared) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            if (access and 4uL != 0uL) return@withLock VfsResult.Err(VfsError.PERMISSION_DENIED)
            buffers.map(file, offset, length, maximumAccess and 4uL.inv())
        }

        override fun ioctl(device: Device, command: Int, args: UserMemory): Long {
            val operation = DrmCommand.from(command) ?: return -Errno.ENOTTY.toLong()
            val bytes = args.copyFromUser(operation.size) ?: return -Errno.EFAULT.toLong()
            val argument = DrmArgument(bytes, args)
            if (operation.direction and 2 != 0 && !args.isWritable(operation.size)) return -Errno.EFAULT.toLong()
            val process = ProcessManager.currentProcess() ?: return -Errno.ESRCH.toLong()
            val imported = if (operation == DrmCommand.PRIME_FD_TO_HANDLE) {
                val descriptor = argument.data.readU32(8).toInt()
                process.fdTable.acquire(descriptor) ?: return -Errno.EBADF.toLong()
            } else null
            val result = try {
                lock.withLock { dispatch(operation, argument, imported) }
            } catch (_: OutOfMemoryError) {
                return -Errno.ENOMEM.toLong()
            } finally {
                imported?.release()
            }
            if (result != 0L) return result
            if (operation.direction and 2 == 0 || operation == DrmCommand.PRIME_HANDLE_TO_FD) return 0
            return if (argument.write()) 0 else -Errno.EFAULT.toLong()
        }

        private fun dispatch(
            command: DrmCommand,
            argument: DrmArgument,
            imported: OpenFileDescription?,
        ): Long {
            val data = argument.data
            return when (command) {
                DrmCommand.VERSION -> version(argument)
                DrmCommand.GET_UNIQUE -> {
                    data.writeU64(0, 0uL)
                    0
                }
                DrmCommand.GET_MAGIC -> {
                    if (magic == 0u) magic = authority.issue() ?: return -Errno.ENOSPC.toLong()
                    data.writeU32(0, magic)
                    0
                }
                DrmCommand.AUTH_MAGIC -> {
                    if (master !== this) return -Errno.EACCES.toLong()
                    if (authority.revoke(data.readU32(0))) 0 else -Errno.EINVAL.toLong()
                }
                DrmCommand.CREATE_LEASE -> {
                    if (master !== this) -Errno.EACCES.toLong() else -Errno.EOPNOTSUPP.toLong()
                }
                DrmCommand.PRIME_HANDLE_TO_FD -> {
                    val process = ProcessManager.currentProcess() ?: return -Errno.ESRCH.toLong()
                    buffers.export(argument, process)
                }
                DrmCommand.PRIME_FD_TO_HANDLE -> buffers.import(checkNotNull(imported), data)
                DrmCommand.GET_CAP -> capability(data)
                DrmCommand.SET_CLIENT_CAP -> clientCapability(data)
                DrmCommand.SET_MASTER -> {
                    if (master === this) return 0
                    val capabilities = ProcessManager.currentThread()?.capabilities
                    if (capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) return -Errno.EACCES.toLong()
                    if (master != null) return -Errno.EBUSY.toLong()
                    if (authority.owner !== this) {
                        authority.revoke(magic)
                        authority = Authority(this)
                        magic = 0u
                    }
                    master = this
                    0
                }
                DrmCommand.DROP_MASTER -> {
                    if (master !== this) return -Errno.EINVAL.toLong()
                    master = null
                    0
                }
                DrmCommand.GET_RESOURCES -> resources(argument)
                DrmCommand.GET_CRTC -> crtc(argument)
                DrmCommand.SET_CRTC -> setCrtc(argument)
                DrmCommand.CURSOR, DrmCommand.CURSOR2 -> cursor(data)
                DrmCommand.CREATE_DUMB -> buffers.create(data)
                DrmCommand.MAP_DUMB -> buffers.mappingOffset(data)
                DrmCommand.DESTROY_DUMB -> buffers.destroy(data.readU32(0))
                DrmCommand.GEM_CLOSE -> {
                    if (data.readU32(4) != 0u) return -Errno.EINVAL.toLong()
                    buffers.destroy(data.readU32(0))
                }
                DrmCommand.ADD_FRAMEBUFFER, DrmCommand.ADD_FRAMEBUFFER2 -> addFramebuffer(command, data)
                DrmCommand.REMOVE_FRAMEBUFFER -> closeFramebuffer(data.readU32(0), keepScanout = false)
                DrmCommand.CLOSE_FRAMEBUFFER -> {
                    if (data.readU32(4) != 0u) return -Errno.EINVAL.toLong()
                    closeFramebuffer(data.readU32(0), keepScanout = true)
                }
                DrmCommand.GET_FRAMEBUFFER -> getFramebuffer(data)
                DrmCommand.DIRTY_FRAMEBUFFER -> dirtyFramebuffer(data)
                DrmCommand.PAGE_FLIP -> pageFlip(data)
                DrmCommand.GET_ENCODER -> {
                    if (data.readU32(0) != ENCODER) return -Errno.ENOENT.toLong()
                    data.writeU32(4, 0u)
                    data.writeU32(8, if (scanout == null) 0u else CRTC)
                    data.writeU32(12, 1u)
                    data.writeU32(16, 0u)
                    0
                }
                DrmCommand.GET_GAMMA, DrmCommand.SET_GAMMA -> {
                    if (data.readU32(0) != CRTC) -Errno.ENOENT.toLong() else -Errno.ENOSYS.toLong()
                }
                DrmCommand.GET_CONNECTOR -> connector(argument)
                DrmCommand.GET_PLANE_RESOURCES -> {
                    val planes = if (universalPlanes) listOf(PLANE) else emptyList()
                    if (argument.array(0, 8, planes)) 0 else -Errno.EFAULT.toLong()
                }
                DrmCommand.GET_PLANE -> plane(argument)
                DrmCommand.GET_OBJECT_PROPERTIES -> properties(argument)
                DrmCommand.GET_PROPERTY -> {
                    val property = DrmProperty.entries.firstOrNull { it.id == data.readU32(16) }
                        ?: return -Errno.ENOENT.toLong()
                    if (property.query(argument)) 0 else -Errno.EFAULT.toLong()
                }
                DrmCommand.SET_CONNECTOR_PROPERTY -> setProperty(
                    data.readU32(12), 0xc0c0c0c0u, data.readU32(8), data.readU64(0),
                )
                DrmCommand.SET_OBJECT_PROPERTY -> setProperty(
                    data.readU32(12), data.readU32(16), data.readU32(8), data.readU64(0),
                )
            }
        }

        private fun disable() {
            scanout?.close()
            scanout = null
            power = DrmPower.OFF
            output.disable()
        }

        private fun crtc(argument: DrmArgument): Long {
            val data = argument.data
            if (data.readU32(12) != CRTC) return -Errno.ENOENT.toLong()
            argument.bytes.fill(0, 16)
            val current = scanout ?: return 0
            data.writeU32(16, current.framebufferId)
            data.writeU32(20, current.x)
            data.writeU32(24, current.y)
            data.writeU32(32, 1u)
            output.mode.bytes.copyInto(argument.bytes, 36)
            return 0
        }

        private fun setCrtc(argument: DrmArgument): Long {
            if (master !== this) return -Errno.EACCES.toLong()
            val data = argument.data
            if (data.readU32(12) != CRTC) return -Errno.ENOENT.toLong()
            if (data.readU32(32) == 0u) {
                if (data.readU32(8) != 0u) return -Errno.EINVAL.toLong()
                disable()
                return 0
            }
            if (data.readU32(32) != 1u || data.readU32(8) != 1u) return -Errno.EINVAL.toLong()
            val process = ProcessManager.currentProcess() ?: return -Errno.EFAULT.toLong()
            val connectorMemory = UserMemory(process.addressSpace, data.readU64(0))
            val connector = connectorMemory.readUIntLE() ?: return -Errno.EFAULT.toLong()
            if (connector != CONNECTOR) return -Errno.ENOENT.toLong()
            val mode = argument.bytes.copyOfRange(36, 68)
            if (!mode.contentEquals(output.mode.bytes.copyOfRange(0, 32))) return -Errno.EINVAL.toLong()
            val id = data.readU32(16)
            val framebuffer = framebuffer(id) ?: return -Errno.ENOENT.toLong()
            val current = DrmScanout.create(framebuffer, id, data.readU32(20), data.readU32(24), output.mode)
                ?: return -Errno.ENOSPC.toLong()
            output.present(current)
            power = DrmPower.ON
            scanout?.close()
            scanout = current
            return 0
        }

        private fun pageFlip(data: LittleEndianBuffer): Long {
            if (master !== this) return -Errno.EACCES.toLong()
            if (data.readU32(0) != CRTC) return -Errno.ENOENT.toLong()
            val flags = data.readU32(8)
            if (flags and 1u.inv() != 0u || data.readU32(12) != 0u) return -Errno.EINVAL.toLong()
            val previous = scanout ?: return -Errno.EBUSY.toLong()
            val id = data.readU32(4)
            val framebuffer = framebuffer(id) ?: return -Errno.ENOENT.toLong()
            val event = if (flags == 0u) null else events.prepare(data.readU64(16), CRTC)
            if (flags != 0u && event == null) return -Errno.EBUSY.toLong()
            val current = DrmScanout.create(framebuffer, id, previous.x, previous.y, output.mode)
                ?: return -Errno.ENOSPC.toLong()
            if (power == DrmPower.ON) output.present(current)
            scanout = current
            previous.close()
            if (event != null) events.complete(event)
            return 0
        }

        private fun addFramebuffer(command: DrmCommand, data: LittleEndianBuffer): Long {
            val modern = command == DrmCommand.ADD_FRAMEBUFFER2
            val width = data.readU32(4)
            val height = data.readU32(8)
            if (width > output.mode.width.toUInt() || height > output.mode.height.toUInt()) {
                return -Errno.EINVAL.toLong()
            }
            val format = if (modern) {
                output.formats.firstOrNull { it.fourcc == data.readU32(12) }
            } else {
                DrmFormat.legacy(data.readU32(16), data.readU32(20))
            } ?: return -Errno.EINVAL.toLong()
            if (format.layout != output.format.layout) return -Errno.EINVAL.toLong()
            if (modern && data.readU32(16) != 0u) return -Errno.EINVAL.toLong()
            if (modern) {
                for (index in 1..3) {
                    val unused = data.readU32(20 + index * 4) or data.readU32(36 + index * 4) or
                        data.readU32(52 + index * 4)
                    if (unused != 0u) return -Errno.EINVAL.toLong()
                }
            }
            val handle = data.readU32(if (modern) 20 else 24)
            val buffer = buffers.get(handle) ?: return -Errno.ENOENT.toLong()
            val pitch = data.readU32(if (modern) 36 else 12)
            val offset = if (modern) data.readU32(52) else 0u
            if (nextFramebuffer == 0u) return -Errno.ENOSPC.toLong()
            val framebuffer = DrmFramebuffer.create(buffer, width, height, pitch, offset, format)
                ?: return -Errno.EINVAL.toLong()
            val id = nextFramebuffer++
            try {
                framebuffers[id] = framebuffer
                ownedFramebuffers.add(id)
            } catch (error: OutOfMemoryError) {
                framebuffers.remove(id)
                ownedFramebuffers.remove(id)
                framebuffer.close()
                throw error
            }
            data.writeU32(0, id)
            return 0
        }

        private fun closeFramebuffer(id: UInt, keepScanout: Boolean): Long {
            if (!ownedFramebuffers.remove(id)) return -Errno.ENOENT.toLong()
            val framebuffer = checkNotNull(framebuffers.remove(id))
            if (!keepScanout && scanout?.framebufferId == id) disable()
            framebuffer.close()
            return 0
        }

        private fun getFramebuffer(data: LittleEndianBuffer): Long {
            val framebuffer = framebuffer(data.readU32(0)) ?: return -Errno.ENOENT.toLong()
            data.writeU32(4, framebuffer.width)
            data.writeU32(8, framebuffer.height)
            data.writeU32(12, framebuffer.pitch)
            data.writeU32(16, framebuffer.format.bitsPerPixel)
            data.writeU32(20, framebuffer.format.depth)
            val capabilities = ProcessManager.currentThread()?.capabilities
            val privileged = master === this || capabilities?.hasEffective(CapEnum.SYS_ADMIN) == true
            val handle = if (!privileged) 0u else when (val result = buffers.add(framebuffer.buffer)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return -result.error.errno.toLong()
            }
            data.writeU32(24, handle)
            return 0
        }

        private fun dirtyFramebuffer(data: LittleEndianBuffer): Long {
            val id = data.readU32(0)
            val framebuffer = framebuffer(id) ?: return -Errno.ENOENT.toLong()
            val count = data.readU32(12)
            if (data.readU32(4) != 0u || count > 256u) return -Errno.EINVAL.toLong()
            if (count != 0u) {
                val process = ProcessManager.currentProcess() ?: return -Errno.EFAULT.toLong()
                val memory = UserMemory(process.addressSpace, data.readU64(16))
                val clips = memory.copyFromUser(count.toInt() * 8) ?: return -Errno.EFAULT.toLong()
                val rectangles = LittleEndianBuffer(clips)
                for (index in 0 until count.toInt()) {
                    val start = index * 8
                    val x1 = rectangles.readU16(start).toUInt()
                    val y1 = rectangles.readU16(start + 2).toUInt()
                    val x2 = rectangles.readU16(start + 4).toUInt()
                    val y2 = rectangles.readU16(start + 6).toUInt()
                    if (x1 >= x2 || y1 >= y2 || x2 > framebuffer.width || y2 > framebuffer.height) {
                        return -Errno.EINVAL.toLong()
                    }
                }
            }
            val current = scanout
            if (current?.framebufferId == id && power == DrmPower.ON) output.present(current)
            return 0
        }

        private fun cursor(data: LittleEndianBuffer): Long {
            if (master !== this) return -Errno.EACCES.toLong()
            if (data.readU32(4) != CRTC) return -Errno.ENOENT.toLong()
            val flags = data.readU32(0)
            if (flags == 0u || flags and 3u.inv() != 0u) return -Errno.EINVAL.toLong()
            val disabling = flags and 1u != 0u && data.readU32(24) == 0u
            return if (disabling) 0 else -Errno.ENXIO.toLong()
        }

        private fun version(argument: DrmArgument): Long {
            val data = argument.data
            data.writeU32(0, 1u)
            data.writeU32(4, 0u)
            data.writeU32(8, 0u)
            val copied = argument.string(16, "cpos-boot") &&
                argument.string(32, "20261009") &&
                argument.string(48, "Boot framebuffer KMS")
            return if (copied) 0 else -Errno.EFAULT.toLong()
        }

        private fun capability(data: LittleEndianBuffer): Long {
            val value = when (data.readU64(0)) {
                2uL, 7uL, 8uL, 9uL, 0x10uL, 0x13uL,
                0x14uL, 0x15uL, 0x16uL, 0x17uL -> 0uL
                1uL, 4uL, 6uL, 0x12uL -> 1uL
                3uL -> output.format.depth.toULong()
                5uL -> 3uL
                0x11uL -> 0uL
                else -> return -Errno.EINVAL.toLong()
            }
            data.writeU64(8, value)
            return 0
        }

        private fun clientCapability(data: LittleEndianBuffer): Long {
            val capability = data.readU64(0)
            val value = data.readU64(8)
            if (value > 1uL) return -Errno.EINVAL.toLong()
            when (capability) {
                1uL, 4uL -> Unit
                2uL -> universalPlanes = value == 1uL
                else -> return -Errno.EINVAL.toLong()
            }
            return 0
        }

        private fun resources(argument: DrmArgument): Long {
            val copied = argument.array(0, 32, ownedFramebuffers.toList()) && argument.array(8, 36, listOf(CRTC)) &&
                argument.array(16, 40, listOf(CONNECTOR)) && argument.array(24, 44, listOf(ENCODER))
            val data = argument.data
            data.writeU32(48, 1u)
            data.writeU32(52, output.mode.width.toUInt())
            data.writeU32(56, 1u)
            data.writeU32(60, output.mode.height.toUInt())
            return if (copied) 0 else -Errno.EFAULT.toLong()
        }

        private fun connector(argument: DrmArgument): Long {
            val data = argument.data
            if (data.readU32(48) != CONNECTOR) return -Errno.ENOENT.toLong()
            if (!argument.array(0, 40, listOf(ENCODER))) return -Errno.EFAULT.toLong()
            val copyMode = data.readU32(32) >= 1u
            if (copyMode && !argument.copy(data.readU64(8), output.mode.bytes)) return -Errno.EFAULT.toLong()
            data.writeU32(32, 1u)
            val properties = listOf(DrmProperty.DPMS to power.value)
            if (!argument.properties(16, 36, properties)) return -Errno.EFAULT.toLong()
            data.writeU32(44, if (scanout == null) 0u else ENCODER)
            data.writeU32(52, 0u)
            data.writeU32(56, 1u)
            data.writeU32(60, 1u)
            argument.bytes.fill(0, 64)
            return 0
        }

        private fun plane(argument: DrmArgument): Long {
            val data = argument.data
            if (data.readU32(0) != PLANE) return -Errno.ENOENT.toLong()
            val copied = argument.array(24, 20, output.formats.map { it.fourcc })
            data.writeU32(4, if (scanout == null) 0u else CRTC)
            data.writeU32(8, scanout?.framebufferId ?: 0u)
            data.writeU32(12, 1u)
            data.writeU32(16, 0u)
            return if (copied) 0 else -Errno.EFAULT.toLong()
        }

        private fun properties(argument: DrmArgument): Long {
            val data = argument.data
            val id = data.readU32(20)
            val type = data.readU32(24)
            val expected = when (id) {
                CRTC -> 0xccccccccu
                CONNECTOR -> 0xc0c0c0c0u
                PLANE -> 0xeeeeeeeeu
                else -> return -Errno.ENOENT.toLong()
            }
            if (type != 0u && type != expected) return -Errno.ENOENT.toLong()
            val properties = when (id) {
                CONNECTOR -> listOf(DrmProperty.DPMS to power.value)
                PLANE -> listOf(DrmProperty.TYPE to 1uL)
                else -> emptyList()
            }
            return if (argument.properties(0, 16, properties)) 0 else -Errno.EFAULT.toLong()
        }

        private fun setProperty(id: UInt, type: UInt, property: UInt, value: ULong): Long {
            if (master !== this) return -Errno.EACCES.toLong()
            val expected = when (id) {
                CONNECTOR -> 0xc0c0c0c0u
                CRTC -> 0xccccccccu
                PLANE -> 0xeeeeeeeeu
                else -> return -Errno.ENOENT.toLong()
            }
            if (type != 0u && type != expected) return -Errno.ENOENT.toLong()
            if (id == PLANE && property == DrmProperty.TYPE.id) return -Errno.EINVAL.toLong()
            if (id != CONNECTOR || property != DrmProperty.DPMS.id) return -Errno.ENOENT.toLong()
            val requested = DrmPower.from(value) ?: return -Errno.EINVAL.toLong()
            if (power == requested) return 0
            power = requested
            val current = scanout ?: return 0
            if (requested == DrmPower.ON) output.present(current) else output.blank()
            return 0
        }
    }

    private fun framebuffer(id: UInt): DrmFramebuffer? {
        val current = scanout
        return framebuffers[id] ?: current?.takeIf { it.framebufferId == id }?.framebuffer
    }

    private class Authority(val owner: Client) {
        private var nextMagic = 1u
        private val pending = mutableSetOf<UInt>()

        fun issue(): UInt? {
            if (nextMagic == 0u) return null
            val magic = nextMagic++
            pending.add(magic)
            return magic
        }

        fun revoke(magic: UInt): Boolean = pending.remove(magic)
    }

    private companion object {
        const val CRTC = 1u
        const val ENCODER = 2u
        const val CONNECTOR = 3u
        const val PLANE = 4u
    }
}
