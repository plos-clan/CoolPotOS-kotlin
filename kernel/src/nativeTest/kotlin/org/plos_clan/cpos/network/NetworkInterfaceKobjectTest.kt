package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkInterfaceKobjectTest {
    @Test
    fun projectsCanonicalLoopbackSysfsProperties() {
        val specification = NetworkInterfaceKobject(TestInterface, IgnoringPublisher).specification
        val attributes = specification.attributes.associateBy { it.name }

        assertEquals("lo", specification.name)
        assertEquals(SysfsParent.Virtual("net"), specification.parent)
        assertEquals(SysfsIndexBinding("net"), specification.bindings.deviceClass)
        val uevent = assertIs<SysfsTextAttribute>(attributes["uevent"])
        assertEquals(0x1a4u, uevent.mode)
        assertTrue(uevent.writable)
        assertEquals("INTERFACE=lo\nIFINDEX=1\n", attributes.text("uevent"))
        assertEquals("1\n", attributes.text("ifindex"))
        assertEquals("1\n", attributes.text("iflink"))
        assertEquals("772\n", attributes.text("type"))
        assertEquals("00:00:00:00:00:00\n", attributes.text("address"))
        assertEquals("00:00:00:00:00:00\n", attributes.text("broadcast"))
        assertEquals("0x9\n", attributes.text("flags"))
        assertEquals("unknown\n", attributes.text("operstate"))
        assertEquals("1\n", attributes.text("carrier"))
        assertFalse(attributes.containsKey("speed"))
    }

    @Test
    fun publishesEveryKernelUeventAction() {
        val published = mutableListOf<KobjectUevent>()
        val publisher = KobjectUeventPublisher(published::add)
        val uevent = NetworkInterfaceKobject(TestInterface, publisher).specification.attributes
            .single { it.name == "uevent" } as SysfsTextAttribute

        KobjectAction.entries.forEach { action ->
            assertIs<VfsResult.Ok<Unit>>(
                uevent.store("${action.wireName}\n".encodeToByteArray()),
            )
            assertEquals(action, KobjectAction.parse(action.wireName.encodeToByteArray()))
            assertEquals(action, KobjectAction.parse("${action.wireName}\u0000".encodeToByteArray()))
        }
        assertEquals(KobjectAction.entries, published.map(KobjectUevent::action))
    }

    @Test
    fun rejectsMalformedKernelUeventActions() {
        listOf("", "addd", "change\n\n", "change argument", "CHANGE").forEach { input ->
            assertNull(KobjectAction.parse(input.encodeToByteArray()))
        }

        val published = mutableListOf<KobjectUevent>()
        val uevent = NetworkInterfaceKobject(
            TestInterface,
            KobjectUeventPublisher(published::add),
        ).specification.attributes
            .single { it.name == "uevent" } as SysfsTextAttribute
        val result = assertIs<VfsResult.Err>(uevent.store("invalid\n".encodeToByteArray()))
        assertEquals(VfsError.INVALID_ARGUMENT, result.error)
        assertTrue(published.isEmpty())
    }

    @Test
    fun encodesKernelUeventAsNulSeparatedEnvironment() {
        val published = mutableListOf<KobjectUevent>()
        NetworkInterfaceKobject(
            TestInterface,
            KobjectUeventPublisher(published::add),
        ).publish(KobjectAction.ADD)
        val event = published.single()

        assertEquals(
            "add@/devices/virtual/net/lo\u0000" +
                "ACTION=add\u0000" +
                "DEVPATH=/devices/virtual/net/lo\u0000" +
                "SUBSYSTEM=net\u0000" +
                "INTERFACE=lo\u0000" +
                "IFINDEX=1\u0000" +
                "SEQNUM=7\u0000",
            event.encode(7uL).decodeToString(),
        )
    }

    private fun Map<String, *>.text(name: String): String {
        val attribute = assertIs<SysfsTextAttribute>(get(name))
        return when (val result = attribute.show()) {
            is VfsResult.Ok -> result.value.decodeToString()
            is VfsResult.Err -> error("unexpected VFS error: ${result.error}")
        }
    }

    private data object TestInterface : NetworkInterfaceView {
        override val index = 1
        override val name = "lo"
        override val kind = NetworkInterfaceKind.LOOPBACK
        override val hardwareAddress = MacAddress.ZERO
        override val mtu = 65_535
        override val configurationFlags = 0x9u
        override val operationalState = NetworkInterfaceState.UNKNOWN
        override val carrier = true
        override val linkSpeedBitsPerSecond = 0uL
    }

    private object IgnoringPublisher : KobjectUeventPublisher {
        override fun publish(event: KobjectUevent) = Unit
    }
}
