@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.sysfs

import kotlin.concurrent.atomics.AtomicBoolean
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceNumber
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.DentryReference
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.vfs.VfsTimestamp
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

value class SysfsObjectHandle internal constructor(val id: ULong)

value class SysfsClassHandle internal constructor(val id: ULong)

value class SysfsBusHandle internal constructor(val id: ULong)

sealed interface SysfsParent {
    data object Devices : SysfsParent
    data class Object(val handle: SysfsObjectHandle) : SysfsParent
    data class Virtual(val category: String) : SysfsParent
}

sealed class SysfsAttribute(
    val name: String,
    val mode: UInt,
    val size: ULong,
)

class SysfsTextAttribute(
    name: String,
    mode: UInt = TEXT_MODE,
    private val reader: () -> VfsResult<ByteArray>,
    private val writer: ((ByteArray) -> VfsResult<Unit>)? = null,
) : SysfsAttribute(name, mode, TEXT_SIZE) {
    val writable: Boolean
        get() = writer != null

    fun show(): VfsResult<ByteArray> = reader()

    fun store(input: ByteArray): VfsResult<Unit> =
        writer?.invoke(input) ?: VfsResult.Err(VfsError.READ_ONLY)

    companion object {
        const val TEXT_MODE = 0x124u
        private const val TEXT_SIZE = 4096uL

        fun constant(name: String, value: String, mode: UInt = TEXT_MODE): SysfsTextAttribute {
            val bytes = value.encodeToByteArray()
            return SysfsTextAttribute(name, mode, { VfsResult.Ok(bytes.copyOf()) })
        }
    }
}

abstract class SysfsBinaryAttribute(
    name: String,
    size: ULong,
    mode: UInt = BINARY_MODE,
) : SysfsAttribute(name, mode, size) {
    init {
        require(size <= Long.MAX_VALUE.toULong())
    }

    open val writable: Boolean
        get() = false

    abstract fun read(
        offset: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult

    open fun write(
        offset: ULong,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(VfsError.READ_ONLY)

    companion object {
        const val BINARY_MODE = 0x180u
    }
}

data class SysfsIndexBinding(
    val name: String,
    val entryName: String? = null,
)

data class SysfsBindings(
    val deviceClass: SysfsIndexBinding? = null,
    val bus: SysfsIndexBinding? = null,
)

data class SysfsObjectSpec(
    val name: String,
    val parent: SysfsParent = SysfsParent.Devices,
    val mode: UInt = DIRECTORY_MODE,
    val uid: UInt = 0u,
    val gid: UInt = 0u,
    val attributes: List<SysfsAttribute> = emptyList(),
    val bindings: SysfsBindings = SysfsBindings(),
) {
    companion object {
        const val DIRECTORY_MODE = 0x16du
    }
}

sealed class SysfsDevicePublication {
    data class NewObject(val spec: SysfsObjectSpec) : SysfsDevicePublication()

    data class ExistingObject(
        val objectHandle: SysfsObjectHandle,
        val bindings: SysfsBindings = SysfsBindings(),
    ) : SysfsDevicePublication()

    companion object {
        fun virtual(
            category: String,
            name: String,
            deviceClass: String? = category,
            attributes: List<SysfsAttribute> = emptyList(),
        ): NewObject = NewObject(
            SysfsObjectSpec(
                name = name,
                parent = SysfsParent.Virtual(category),
                attributes = attributes,
                bindings = SysfsBindings(
                    deviceClass = deviceClass?.let(::SysfsIndexBinding),
                ),
            ),
        )
    }
}

internal enum class SysfsNodeState {
    LIVE,
    DEAD,
    RETIRED,
}

internal sealed class SysfsNode(
    val id: ULong,
    val parentId: ULong?,
    val name: VfsName,
    val mode: UInt,
    val uid: UInt,
    val gid: UInt,
    val createdAt: VfsTimestamp,
    val permanent: Boolean,
) {
    var state = SysfsNodeState.LIVE
    var references = 0

    abstract val type: InodeType

    class Directory(
        id: ULong,
        parentId: ULong?,
        name: VfsName,
        mode: UInt,
        uid: UInt,
        gid: UInt,
        createdAt: VfsTimestamp,
        permanent: Boolean,
        val mutableChildren: Boolean,
        val objectDirectory: Boolean = false,
    ) : SysfsNode(id, parentId, name, mode, uid, gid, createdAt, permanent) {
        var deviceBindings = 0

        override val type: InodeType
            get() = InodeType.DIRECTORY
    }

    class Attribute(
        id: ULong,
        parentId: ULong,
        name: VfsName,
        val attribute: SysfsAttribute,
        uid: UInt,
        gid: UInt,
        createdAt: VfsTimestamp,
    ) : SysfsNode(
        id,
        parentId,
        name,
        attribute.mode,
        uid,
        gid,
        createdAt,
        permanent = false,
    ) {

        override val type: InodeType
            get() = InodeType.REGULAR
    }

    class Link(
        id: ULong,
        parentId: ULong,
        name: VfsName,
        val targetId: ULong,
        createdAt: VfsTimestamp,
    ) : SysfsNode(
        id,
        parentId,
        name,
        SYMLINK_MODE,
        0u,
        0u,
        createdAt,
        permanent = false,
    ) {
        override val type: InodeType
            get() = InodeType.SYMLINK
    }

    companion object {
        private const val SYMLINK_MODE = 0x1ffu
    }
}

internal data class SysfsLookup(
    val node: SysfsNode?,
    val validity: CacheValidity,
    val reference: DentryReference? = null,
)

internal data class SysfsDirectoryEntry(
    val name: VfsName,
    val id: ULong,
    val type: InodeType,
)

internal class SysfsRegistry(
    private val now: () -> VfsTimestamp = VfsTimestamp::now,
) {
    private data class DeviceKey(val type: DeviceType, val number: DeviceNumber)

    private data class BusDirectory(
        val id: ULong,
        val devicesId: ULong,
        val driversId: ULong,
    )

    private data class DeviceBinding(
        val objectId: ULong,
        val ownsObject: Boolean,
        val devAttributeId: ULong,
        val devLinkId: ULong,
        val indexLinkIds: List<ULong>,
        val key: DeviceKey,
    )

    private data class PreparedObject(
        val spec: SysfsObjectSpec,
        val name: VfsName,
        val attributes: LinkedHashMap<String, SysfsAttribute>,
    )

    private val lock = RegistryLock()
    private val nodesById = mutableMapOf<ULong, SysfsNode>()
    private val retiredById = mutableMapOf<ULong, SysfsNode>()
    private val childrenIndex = mutableMapOf<ULong, LinkedHashMap<VfsName, ULong>>()
    private val classes = mutableMapOf<String, ULong>()
    private val buses = mutableMapOf<String, BusDirectory>()
    private val virtualDirectories = mutableMapOf<String, ULong>()
    private val linksByTarget = mutableMapOf<ULong, MutableSet<ULong>>()
    private val devIndex = mutableMapOf<DeviceKey, ULong>()
    private val deviceBindings = mutableMapOf<Device, DeviceBinding>()
    private var nextId = FIRST_DYNAMIC_ID

    init {
        val createdAt = now()
        installFixedDirectory(ROOT_ID, null, "", mutableChildren = false, createdAt)
        installFixedDirectory(DEVICES_ID, ROOT_ID, "devices", mutableChildren = true, createdAt)
        installFixedDirectory(CLASS_ID, ROOT_ID, "class", mutableChildren = true, createdAt)
        installFixedDirectory(BUS_ID, ROOT_ID, "bus", mutableChildren = true, createdAt)
        installFixedDirectory(DEV_ID, ROOT_ID, "dev", mutableChildren = false, createdAt)
        installFixedDirectory(KERNEL_ID, ROOT_ID, "kernel", mutableChildren = true, createdAt)
        installFixedDirectory(FIRMWARE_ID, ROOT_ID, "firmware", mutableChildren = true, createdAt)
        installFixedDirectory(DEV_CHAR_ID, DEV_ID, "char", mutableChildren = true, createdAt)
        installFixedDirectory(DEV_BLOCK_ID, DEV_ID, "block", mutableChildren = true, createdAt)
        installFixedDirectory(VIRTUAL_ID, DEVICES_ID, "virtual", mutableChildren = true, createdAt)
    }

    val root: SysfsNode.Directory
        get() = lock.withLock { nodesById.getValue(ROOT_ID) as SysfsNode.Directory }

    fun registerObject(spec: SysfsObjectSpec): VfsResult<SysfsObjectHandle> {
        val prepared = when (val result = prepare(spec)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return lock.withLock {
            when (val result = createObjectLocked(prepared)) {
                is VfsResult.Ok -> VfsResult.Ok(SysfsObjectHandle(result.value.id))
                is VfsResult.Err -> result
            }
        }
    }

    fun unregisterObject(handle: SysfsObjectHandle): VfsResult<Unit> = lock.withLock {
        val node = liveObjectLocked(handle) ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        removeObjectLocked(node)
    }

    fun registerDevice(
        device: Device,
        spec: SysfsObjectSpec,
    ): VfsResult<SysfsObjectHandle> {
        val prepared = when (val result = prepare(spec)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        if (prepared.attributes.containsKey(DEV_ATTRIBUTE)) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val devAttribute = deviceAttribute(device)
        return lock.withLock {
            val objectNode = when (val result = createObjectLocked(prepared)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return@withLock result
            }
            when (val result = bindDeviceLocked(
                device,
                objectNode,
                SysfsBindings(),
                devAttribute,
                ownsObject = true,
            )) {
                is VfsResult.Ok -> VfsResult.Ok(SysfsObjectHandle(objectNode.id))
                is VfsResult.Err -> {
                    removeObjectLocked(objectNode)
                    result
                }
            }
        }
    }

    fun registerDevice(
        device: Device,
        objectHandle: SysfsObjectHandle,
        bindings: SysfsBindings,
    ): VfsResult<SysfsObjectHandle> {
        if (!validBindings(bindings)) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val devAttribute = deviceAttribute(device)
        return lock.withLock {
            val objectNode = liveObjectLocked(objectHandle)
                ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            when (val result = bindDeviceLocked(
                device,
                objectNode,
                bindings,
                devAttribute,
                ownsObject = false,
            )) {
                is VfsResult.Ok -> VfsResult.Ok(objectHandle)
                is VfsResult.Err -> result
            }
        }
    }

    fun unregisterDevice(device: Device): VfsResult<Unit> = lock.withLock {
        val binding = deviceBindings.remove(device)
            ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        devIndex.remove(binding.key)
        removeLinkLocked(binding.devLinkId)
        binding.indexLinkIds.forEach(::removeLinkLocked)
        removeNodeLocked(binding.devAttributeId)

        val objectNode = nodesById[binding.objectId] as? SysfsNode.Directory
        if (objectNode != null) {
            check(objectNode.deviceBindings > 0)
            objectNode.deviceBindings--
        }
        if (binding.ownsObject && objectNode != null) removeObjectLocked(objectNode)
        else VfsResult.Ok(Unit)
    }

    fun registerClass(name: String): VfsResult<SysfsClassHandle> {
        if (!validName(name)) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return lock.withLock {
            if (classes.containsKey(name) ||
                childrenIndex.getValue(CLASS_ID).containsKey(vfsName(name))
            ) {
                return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
            }
            if (!reserveIdsLocked(1)) return@withLock VfsResult.Err(VfsError.NO_SPACE)
            VfsResult.Ok(SysfsClassHandle(createClassLocked(name)))
        }
    }

    fun unregisterClass(handle: SysfsClassHandle): VfsResult<Unit> = lock.withLock {
        val entry = classes.entries.firstOrNull { it.value == handle.id }
            ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        if (childrenIndex.getValue(handle.id).isNotEmpty()) {
            return@withLock VfsResult.Err(VfsError.BUSY)
        }
        classes.remove(entry.key)
        removeNodeLocked(handle.id)
        VfsResult.Ok(Unit)
    }

    fun registerBus(name: String): VfsResult<SysfsBusHandle> {
        if (!validName(name)) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return lock.withLock {
            if (buses.containsKey(name) ||
                childrenIndex.getValue(BUS_ID).containsKey(vfsName(name))
            ) {
                return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
            }
            if (!reserveIdsLocked(3)) return@withLock VfsResult.Err(VfsError.NO_SPACE)
            VfsResult.Ok(SysfsBusHandle(createBusLocked(name).id))
        }
    }

    fun unregisterBus(handle: SysfsBusHandle): VfsResult<Unit> = lock.withLock {
        val entry = buses.entries.firstOrNull { it.value.id == handle.id }
            ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        val bus = entry.value
        if (childrenIndex.getValue(bus.devicesId).isNotEmpty() ||
            childrenIndex.getValue(bus.driversId).isNotEmpty()
        ) {
            return@withLock VfsResult.Err(VfsError.BUSY)
        }
        buses.remove(entry.key)
        removeNodeLocked(bus.devicesId)
        removeNodeLocked(bus.driversId)
        removeNodeLocked(bus.id)
        VfsResult.Ok(Unit)
    }

    fun lookup(directoryId: ULong, name: VfsName): VfsResult<SysfsLookup> = lock.withLock {
        val directory = nodesById[directoryId] as? SysfsNode.Directory
            ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        val validity = if (directory.mutableChildren) CacheValidity.Volatile
            else CacheValidity.Persistent
        val childId = childrenIndex.getValue(directoryId)[name]
            ?: return@withLock VfsResult.Ok(SysfsLookup(null, validity))
        val child = nodesById[childId]
            ?: return@withLock VfsResult.Ok(SysfsLookup(null, validity))
        if (child.permanent) return@withLock VfsResult.Ok(SysfsLookup(child, validity))
        child.references++
        VfsResult.Ok(SysfsLookup(child, validity, NodeReference(this, child)))
    }

    fun snapshot(directoryId: ULong): VfsResult<List<SysfsDirectoryEntry>> = lock.withLock {
        val directory = nodesById[directoryId] as? SysfsNode.Directory
            ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        VfsResult.Ok(childrenIndex.getValue(directory.id).values.map { id ->
            val node = nodesById.getValue(id)
            SysfsDirectoryEntry(node.name, node.id, node.type)
        })
    }

    fun retain(node: SysfsNode): Boolean = lock.withLock {
        if (nodesById[node.id] !== node) return@withLock false
        node.references++
        true
    }

    fun releaseOpenReference(node: SysfsNode) = release(node)

    fun isLive(node: SysfsNode): Boolean = lock.withLock { nodesById[node.id] === node }

    fun readLink(link: SysfsNode.Link): VfsResult<VfsPathname> = lock.withLock {
        if (nodesById[link.id] !== link || nodesById[link.targetId] == null) {
            return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        }
        val source = pathComponentsLocked(checkNotNull(link.parentId))
        val target = pathComponentsLocked(link.targetId)
        var common = 0
        while (common < source.size && common < target.size && source[common] == target[common]) {
            common++
        }
        val components = ArrayList<String>(source.size - common + target.size - common)
        repeat(source.size - common) { components += ".." }
        components += target.drop(common)
        VfsResult.Ok(VfsPathname.fromString(components.joinToString("/").ifEmpty { "." }))
    }

    private fun prepare(spec: SysfsObjectSpec): VfsResult<PreparedObject> {
        if (!validName(spec.name) || spec.mode and PERMISSION_MASK.inv() != 0u ||
            spec.parent is SysfsParent.Virtual && !validName(spec.parent.category) ||
            !validBindings(spec.bindings)
        ) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val attributes = linkedMapOf<String, SysfsAttribute>()
        for (attribute in spec.attributes) {
            if (!validName(attribute.name) || attribute.mode and PERMISSION_MASK.inv() != 0u) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (attributes.put(attribute.name, attribute) != null) {
                return VfsResult.Err(VfsError.ALREADY_EXISTS)
            }
        }
        return VfsResult.Ok(PreparedObject(spec, vfsName(spec.name), attributes))
    }

    private fun validBindings(bindings: SysfsBindings): Boolean =
        listOfNotNull(bindings.deviceClass, bindings.bus).all { binding ->
            validName(binding.name) && (binding.entryName == null || validName(binding.entryName))
        }

    private fun createObjectLocked(prepared: PreparedObject): VfsResult<SysfsNode.Directory> {
        val spec = prepared.spec
        val existingParentId = when (val parent = spec.parent) {
            SysfsParent.Devices -> DEVICES_ID
            is SysfsParent.Object -> liveObjectLocked(parent.handle)?.id
                ?: return VfsResult.Err(VfsError.NOT_FOUND)
            is SysfsParent.Virtual -> virtualDirectories[parent.category]
        }
        val virtualCategory = (spec.parent as? SysfsParent.Virtual)?.category
        if (existingParentId != null &&
            childrenIndex.getValue(existingParentId).containsKey(prepared.name)
        ) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (existingParentId == null && virtualCategory != null &&
            childrenIndex.getValue(VIRTUAL_ID).containsKey(vfsName(virtualCategory))
        ) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val classBinding = spec.bindings.deviceClass
        val busBinding = spec.bindings.bus
        if (!indexEntryAvailableLocked(classBinding, classes, CLASS_ID, spec.name) ||
            !busEntryAvailableLocked(busBinding, spec.name)
        ) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }

        val newVirtual = if (existingParentId == null && virtualCategory != null) 1 else 0
        val newClass = if (classBinding != null && classes[classBinding.name] == null) 1 else 0
        val newBus = if (busBinding != null && buses[busBinding.name] == null) 3 else 0
        val linkCount = listOfNotNull(classBinding, busBinding).size
        if (!reserveIdsLocked(
                newVirtual + newClass + newBus + 1 + prepared.attributes.size + linkCount
            )
        ) {
            return VfsResult.Err(VfsError.NO_SPACE)
        }

        val parentId = existingParentId ?: createVirtualDirectoryLocked(checkNotNull(virtualCategory))
        val createdAt = now()
        val objectNode = createDirectoryLocked(
            parentId = parentId,
            name = prepared.name,
            mode = spec.mode,
            uid = spec.uid,
            gid = spec.gid,
            createdAt = createdAt,
            mutableChildren = true,
            objectDirectory = true,
        )
        prepared.attributes.values.forEach { attribute ->
            installNodeLocked(
                SysfsNode.Attribute(
                    allocateIdLocked(),
                    objectNode.id,
                    vfsName(attribute.name),
                    attribute,
                    spec.uid,
                    spec.gid,
                    createdAt,
                ),
            )
        }
        classBinding?.let { binding ->
            val directoryId = classes[binding.name] ?: createClassLocked(binding.name)
            addLinkLocked(directoryId, binding.entryName ?: spec.name, objectNode.id, createdAt)
        }
        busBinding?.let { binding ->
            val directory = buses[binding.name] ?: createBusLocked(binding.name)
            addLinkLocked(directory.devicesId, binding.entryName ?: spec.name, objectNode.id, createdAt)
        }
        return VfsResult.Ok(objectNode)
    }

    private fun bindDeviceLocked(
        device: Device,
        objectNode: SysfsNode.Directory,
        bindings: SysfsBindings,
        devAttribute: SysfsTextAttribute,
        ownsObject: Boolean,
    ): VfsResult<Unit> {
        if (deviceBindings.containsKey(device) ||
            childrenIndex.getValue(objectNode.id).containsKey(DEV_ATTRIBUTE_NAME)
        ) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val key = DeviceKey(device.type, device.number)
        val devDirectoryId = if (device.type == DeviceType.BLOCK) DEV_BLOCK_ID else DEV_CHAR_ID
        val devName = deviceNumberName(device)
        val objectName = objectNode.name.toString()
        if (devIndex.containsKey(key) ||
            childrenIndex.getValue(devDirectoryId).containsKey(vfsName(devName)) ||
            !indexEntryAvailableLocked(
                bindings.deviceClass,
                classes,
                CLASS_ID,
                objectName,
            ) || !busEntryAvailableLocked(bindings.bus, objectName)
        ) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }

        val newClass = if (bindings.deviceClass != null &&
            classes[bindings.deviceClass.name] == null
        ) 1 else 0
        val newBus = if (bindings.bus != null && buses[bindings.bus.name] == null) 3 else 0
        val indexCount = listOfNotNull(bindings.deviceClass, bindings.bus).size
        if (!reserveIdsLocked(newClass + newBus + 2 + indexCount)) {
            return VfsResult.Err(VfsError.NO_SPACE)
        }

        val createdAt = now()
        val devAttributeNode = SysfsNode.Attribute(
            allocateIdLocked(),
            objectNode.id,
            DEV_ATTRIBUTE_NAME,
            devAttribute,
            objectNode.uid,
            objectNode.gid,
            createdAt,
        )
        installNodeLocked(devAttributeNode)
        val devLinkId = addLinkLocked(devDirectoryId, devName, objectNode.id, createdAt)
        devIndex[key] = devLinkId
        val indexLinks = ArrayList<ULong>(indexCount)
        bindings.deviceClass?.let { binding ->
            val directoryId = classes[binding.name] ?: createClassLocked(binding.name)
            indexLinks += addLinkLocked(
                directoryId,
                binding.entryName ?: objectName,
                objectNode.id,
                createdAt,
            )
        }
        bindings.bus?.let { binding ->
            val directory = buses[binding.name] ?: createBusLocked(binding.name)
            indexLinks += addLinkLocked(
                directory.devicesId,
                binding.entryName ?: objectName,
                objectNode.id,
                createdAt,
            )
        }
        objectNode.deviceBindings++
        deviceBindings[device] = DeviceBinding(
            objectNode.id,
            ownsObject,
            devAttributeNode.id,
            devLinkId,
            indexLinks,
            key,
        )
        return VfsResult.Ok(Unit)
    }

    private fun removeObjectLocked(objectNode: SysfsNode.Directory): VfsResult<Unit> {
        if (objectNode.deviceBindings != 0) return VfsResult.Err(VfsError.BUSY)
        val children = childrenIndex.getValue(objectNode.id).values.map(nodesById::getValue)
        if (children.any { it is SysfsNode.Directory }) return VfsResult.Err(VfsError.BUSY)

        linksByTarget.remove(objectNode.id)?.toList()?.forEach(::removeLinkLocked)
        children.forEach { removeNodeLocked(it.id) }
        removeNodeLocked(objectNode.id)
        return VfsResult.Ok(Unit)
    }

    private fun indexEntryAvailableLocked(
        binding: SysfsIndexBinding?,
        directories: Map<String, ULong>,
        rootId: ULong,
        defaultEntryName: String,
    ): Boolean {
        if (binding == null) return true
        val directoryId = directories[binding.name] ?: return !childrenIndex.getValue(rootId)
            .containsKey(vfsName(binding.name))
        return !childrenIndex.getValue(directoryId)
            .containsKey(vfsName(binding.entryName ?: defaultEntryName))
    }

    private fun busEntryAvailableLocked(
        binding: SysfsIndexBinding?,
        defaultEntryName: String,
    ): Boolean {
        if (binding == null) return true
        val bus = buses[binding.name] ?: return !childrenIndex.getValue(BUS_ID)
            .containsKey(vfsName(binding.name))
        val entryName = binding.entryName ?: defaultEntryName
        return !childrenIndex.getValue(bus.devicesId).containsKey(vfsName(entryName))
    }

    private fun createVirtualDirectoryLocked(category: String): ULong {
        val node = createDirectoryLocked(
            parentId = VIRTUAL_ID,
            name = vfsName(category),
            permanent = true,
            mutableChildren = true,
        )
        virtualDirectories[category] = node.id
        return node.id
    }

    private fun createClassLocked(name: String): ULong {
        val node = createDirectoryLocked(
            parentId = CLASS_ID,
            name = vfsName(name),
            mutableChildren = true,
        )
        classes[name] = node.id
        return node.id
    }

    private fun createBusLocked(name: String): BusDirectory {
        val createdAt = now()
        val busNode = createDirectoryLocked(
            parentId = BUS_ID,
            name = vfsName(name),
            createdAt = createdAt,
            mutableChildren = false,
        )
        val devicesNode = createDirectoryLocked(
            parentId = busNode.id,
            name = DEVICES_NAME,
            createdAt = createdAt,
            mutableChildren = true,
        )
        val driversNode = createDirectoryLocked(
            parentId = busNode.id,
            name = DRIVERS_NAME,
            createdAt = createdAt,
            mutableChildren = true,
        )
        return BusDirectory(busNode.id, devicesNode.id, driversNode.id).also {
            buses[name] = it
        }
    }

    private fun addLinkLocked(
        parentId: ULong,
        name: String,
        targetId: ULong,
        createdAt: VfsTimestamp,
    ): ULong {
        val link = SysfsNode.Link(
            allocateIdLocked(),
            parentId,
            vfsName(name),
            targetId,
            createdAt,
        )
        installNodeLocked(link)
        linksByTarget.getOrPut(targetId) { mutableSetOf() } += link.id
        return link.id
    }

    private fun removeLinkLocked(id: ULong) {
        val link = nodesById[id] as? SysfsNode.Link ?: return
        linksByTarget[link.targetId]?.let { links ->
            links -= id
            if (links.isEmpty()) linksByTarget.remove(link.targetId)
        }
        removeNodeLocked(id)
    }

    private fun liveObjectLocked(handle: SysfsObjectHandle): SysfsNode.Directory? =
        (nodesById[handle.id] as? SysfsNode.Directory)?.takeIf { it.objectDirectory }

    private fun pathComponentsLocked(id: ULong): List<String> {
        val components = ArrayDeque<String>()
        var current = nodesById[id]
        while (current != null && current.parentId != null) {
            components.addFirst(current.name.toString())
            current = nodesById[current.parentId]
        }
        return components.toList()
    }

    private fun release(node: SysfsNode) = lock.withLock {
        check(node.references > 0)
        node.references--
        if (node.state == SysfsNodeState.DEAD && node.references == 0) {
            retiredById.remove(node.id)
            node.state = SysfsNodeState.RETIRED
        }
    }

    private fun removeNodeLocked(id: ULong) {
        val node = nodesById.remove(id) ?: return
        check(childrenIndex[id].isNullOrEmpty())
        childrenIndex.remove(id)
        node.parentId?.let { parentId -> childrenIndex[parentId]?.remove(node.name) }
        node.state = SysfsNodeState.DEAD
        if (node.references == 0) node.state = SysfsNodeState.RETIRED
        else retiredById[id] = node
    }

    private fun createDirectoryLocked(
        parentId: ULong,
        name: VfsName,
        mutableChildren: Boolean,
        permanent: Boolean = false,
        objectDirectory: Boolean = false,
        mode: UInt = SysfsObjectSpec.DIRECTORY_MODE,
        uid: UInt = 0u,
        gid: UInt = 0u,
        createdAt: VfsTimestamp = now(),
    ): SysfsNode.Directory = SysfsNode.Directory(
        allocateIdLocked(),
        parentId,
        name,
        mode,
        uid,
        gid,
        createdAt,
        permanent,
        mutableChildren,
        objectDirectory,
    ).also(::installNodeLocked)

    private fun installFixedDirectory(
        id: ULong,
        parentId: ULong?,
        name: String,
        mutableChildren: Boolean,
        createdAt: VfsTimestamp,
    ) = installNodeLocked(
        SysfsNode.Directory(
            id,
            parentId,
            vfsName(name),
            SysfsObjectSpec.DIRECTORY_MODE,
            0u,
            0u,
            createdAt,
            permanent = true,
            mutableChildren = mutableChildren,
        ),
    )

    private fun installNodeLocked(node: SysfsNode) {
        check(nodesById.put(node.id, node) == null)
        childrenIndex.getOrPut(node.id) { linkedMapOf() }
        node.parentId?.let { parentId ->
            check(childrenIndex.getValue(parentId).put(node.name, node.id) == null)
        }
    }

    private fun reserveIdsLocked(count: Int): Boolean =
        count >= 0 && count.toULong() <= ULong.MAX_VALUE - nextId

    private fun allocateIdLocked(): ULong = nextId++

    private fun validName(name: String): Boolean {
        val bytes = name.encodeToByteArray()
        return bytes.isNotEmpty() && bytes.size <= VfsName.MAX_LENGTH &&
            name != "." && name != ".." && '/' !in name && '\u0000' !in name
    }

    private fun vfsName(value: String): VfsName {
        val bytes = value.encodeToByteArray()
        return VfsName.fromPath(bytes, 0, bytes.size)
    }

    private fun deviceAttribute(device: Device): SysfsTextAttribute =
        SysfsTextAttribute.constant(DEV_ATTRIBUTE, deviceNumberName(device) + "\n")

    private fun deviceNumberName(device: Device): String =
        device.number.major.toString() + ":" + device.number.minor

    private class NodeReference(
        private val registry: SysfsRegistry,
        private val node: SysfsNode,
    ) : DentryReference {
        override fun release() = registry.release(node)
    }

    private class RegistryLock {
        private val held = AtomicBoolean(false)

        fun <T> withLock(block: () -> T): T {
            while (!held.compareAndSet(expectedValue = false, newValue = true)) {
                // Registry operations never run from IRQ context; contention is short-lived.
            }
            return try {
                block()
            } finally {
                held.store(false)
            }
        }
    }

    companion object {
        private const val PERMISSION_MASK = 0x1ffu
        private const val DEV_ATTRIBUTE = "dev"
        private val DEV_ATTRIBUTE_NAME = DEV_ATTRIBUTE.encodeToByteArray().let { bytes ->
            VfsName.fromPath(bytes, 0, bytes.size)
        }
        private val DEVICES_NAME = "devices".encodeToByteArray().let { bytes ->
            VfsName.fromPath(bytes, 0, bytes.size)
        }
        private val DRIVERS_NAME = "drivers".encodeToByteArray().let { bytes ->
            VfsName.fromPath(bytes, 0, bytes.size)
        }

        const val ROOT_ID = 1uL
        const val DEVICES_ID = 2uL
        const val CLASS_ID = 3uL
        const val BUS_ID = 4uL
        const val DEV_ID = 5uL
        const val KERNEL_ID = 6uL
        const val FIRMWARE_ID = 7uL
        const val DEV_CHAR_ID = 8uL
        const val DEV_BLOCK_ID = 9uL
        const val VIRTUAL_ID = 10uL
        private const val FIRST_DYNAMIC_ID = 16uL
    }
}
