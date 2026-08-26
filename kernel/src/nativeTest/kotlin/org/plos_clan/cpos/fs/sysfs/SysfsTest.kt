package org.plos_clan.cpos.fs.sysfs

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceNumber
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.vfs.VfsTimestamp
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SysfsTest {
    @Test
    fun projectsOneCanonicalObjectThroughAllIndexes() {
        val registry = registry()
        val device = device("null", 1u, 3u)
        val handle = registry.registerDevice(
            device,
            SysfsObjectSpec(
                name = "null",
                parent = SysfsParent.Virtual("mem"),
                bindings = SysfsBindings(
                    deviceClass = SysfsIndexBinding("mem"),
                    bus = SysfsIndexBinding("platform"),
                ),
            ),
        ).value()

        val category = registry.child(SysfsRegistry.VIRTUAL_ID, "mem")
        val canonical = registry.child(category.id, "null")
        assertEquals(handle.id, canonical.id)
        assertEquals(
            "1:3\n",
            ((registry.child(canonical.id, "dev") as SysfsNode.Attribute)
                .attribute as SysfsTextAttribute).show().value().decodeToString(),
        )

        val classDirectory = registry.child(SysfsRegistry.CLASS_ID, "mem")
        val classLink = registry.child(classDirectory.id, "null") as SysfsNode.Link
        val busDirectory = registry.child(SysfsRegistry.BUS_ID, "platform")
        val busDevices = registry.child(busDirectory.id, "devices")
        val busLink = registry.child(busDevices.id, "null") as SysfsNode.Link
        val devLink = registry.child(SysfsRegistry.DEV_CHAR_ID, "1:3") as SysfsNode.Link
        assertEquals("../../devices/virtual/mem/null", registry.readLink(classLink).value().toString())
        assertEquals(
            "../../../devices/virtual/mem/null",
            registry.readLink(busLink).value().toString(),
        )
        assertEquals("../../devices/virtual/mem/null", registry.readLink(devLink).value().toString())
        assertEquals(4, setOf(canonical.id, classLink.id, busLink.id, devLink.id).size)

        registry.unregisterDevice(device).value()
        assertNull(registry.childOrNull(category.id, "null"))
        assertNull(registry.childOrNull(classDirectory.id, "null"))
        assertNull(registry.childOrNull(busDevices.id, "null"))
        assertNull(registry.childOrNull(SysfsRegistry.DEV_CHAR_ID, "1:3"))
    }

    @Test
    fun directorySnapshotsRemainStableWhileTheRegistryChanges() {
        val registry = registry()
        val first = registry.registerObject(
            SysfsObjectSpec("first", SysfsParent.Virtual("snapshot")),
        ).value()
        val category = registry.child(SysfsRegistry.VIRTUAL_ID, "snapshot")
        val openedSnapshot = registry.snapshot(category.id).value()

        registry.registerObject(
            SysfsObjectSpec("second", SysfsParent.Virtual("snapshot")),
        ).value()
        assertEquals(listOf("first"), openedSnapshot.map { it.name.toString() })
        assertEquals(
            listOf("first", "second"),
            registry.snapshot(category.id).value().map { it.name.toString() },
        )

        registry.unregisterObject(first).value()
        assertNull(registry.childOrNull(category.id, "first"))
        assertEquals(
            listOf("second"),
            registry.snapshot(category.id).value().map { it.name.toString() },
        )
    }

    @Test
    fun neverReusesObjectOrIndexIdsWhenDeviceNumbersAreReused() {
        val registry = registry()
        val firstDevice = device("first", 42u, 7u)
        val firstObject = registry.registerDevice(
            firstDevice,
            SysfsObjectSpec("endpoint", SysfsParent.Virtual("reuse")),
        ).value()
        val firstLink = registry.child(SysfsRegistry.DEV_CHAR_ID, "42:7").id
        registry.unregisterDevice(firstDevice).value()

        val secondDevice = device("second", 42u, 7u)
        val secondObject = registry.registerDevice(
            secondDevice,
            SysfsObjectSpec("endpoint", SysfsParent.Virtual("reuse")),
        ).value()
        val secondLink = registry.child(SysfsRegistry.DEV_CHAR_ID, "42:7").id

        assertTrue(secondObject.id > firstObject.id)
        assertTrue(secondLink > firstLink)
        assertNotEquals(firstObject.id, secondObject.id)
        registry.unregisterDevice(secondDevice).value()
    }

    @Test
    fun retainedAttributeProviderSurvivesRemovalAndThenRetires() {
        val registry = registry()
        val objectHandle = registry.registerObject(
            SysfsObjectSpec(
                name = "object",
                attributes = listOf(
                    SysfsTextAttribute("value", reader = {
                        VfsResult.Ok("ready\n".encodeToByteArray())
                    }),
                ),
            ),
        ).value()
        val objectNode = registry.child(SysfsRegistry.DEVICES_ID, "object")
        val lookup = registry.lookup(objectNode.id, name("value")).value()
        val attributeNode = assertIs<SysfsNode.Attribute>(lookup.node)
        val attribute = assertIs<SysfsTextAttribute>(attributeNode.attribute)
        assertTrue(registry.retain(attributeNode))

        registry.unregisterObject(objectHandle).value()
        assertTrue(!registry.isLive(attributeNode))
        assertEquals("ready\n", attribute.show().value().decodeToString())
        assertEquals(SysfsNodeState.DEAD, attributeNode.state)

        registry.releaseOpenReference(attributeNode)
        lookup.reference?.release()
        assertEquals(SysfsNodeState.RETIRED, attributeNode.state)
    }

    @Test
    fun explicitHierarchyRejectsImplicitRecursiveRemoval() {
        val registry = registry()
        val parent = registry.registerObject(SysfsObjectSpec("parent")).value()
        val child = registry.registerObject(
            SysfsObjectSpec("child", SysfsParent.Object(parent)),
        ).value()

        val busy = assertIs<VfsResult.Err>(registry.unregisterObject(parent))
        assertEquals(VfsError.BUSY, busy.error)
        registry.unregisterObject(child).value()
        registry.unregisterObject(parent).value()
        assertNull(registry.childOrNull(SysfsRegistry.DEVICES_ID, "parent"))
    }

    @Test
    fun preservesBinaryAttributeSizeAndProvider() {
        val binary = object : SysfsBinaryAttribute("config", 4096uL) {
            override fun read(
                offset: ULong,
                destination: PreparedBufferDestination,
                destinationOffset: Int,
                count: Int,
            ): IoResult = IoResult.success(0)
        }
        val registry = registry()
        val objectHandle = registry.registerObject(
            SysfsObjectSpec("binary", attributes = listOf(binary)),
        ).value()
        val objectNode = registry.child(SysfsRegistry.DEVICES_ID, "binary")
        val attributeNode = assertIs<SysfsNode.Attribute>(registry.child(objectNode.id, "config"))

        assertEquals(4096uL, attributeNode.attribute.size)
        assertSame(attributeNode.attribute, binary)
        registry.unregisterObject(objectHandle).value()
    }

    private fun registry(): SysfsRegistry = SysfsRegistry { VfsTimestamp(1L, 0u) }

    private fun SysfsRegistry.child(directoryId: ULong, name: String): SysfsNode =
        assertNotNull(childOrNull(directoryId, name))

    private fun SysfsRegistry.childOrNull(directoryId: ULong, name: String): SysfsNode? {
        val lookup = lookup(directoryId, name(name)).value()
        lookup.reference?.release()
        return lookup.node
    }

    private fun name(value: String): VfsName {
        val bytes = value.encodeToByteArray()
        return VfsName.fromPath(bytes, 0, bytes.size)
    }

    private fun device(name: String, major: UInt, minor: UInt): Device = Device(
        name,
        DeviceType.CHARACTER,
        assertNotNull(DeviceNumber.create(major, minor)),
        TestBackend,
    )

    private fun <T> VfsResult<T>.value(): T = when (this) {
        is VfsResult.Ok -> value
        is VfsResult.Err -> error("unexpected VFS error: $error")
    }

    private data object TestBackend : DeviceBackend {
        override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
            -VfsError.NOT_SUPPORTED.errno.toLong()

        override fun poll(device: Device, events: Int): Long = 0

        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            offset: ULong,
            size: ULong,
        ): Long = 0

        override fun write(
            device: Device,
            buffer: PreparedBufferSource,
            bufferOffset: Int,
            offset: ULong,
            size: ULong,
        ): Long = size.toLong()
    }
}
