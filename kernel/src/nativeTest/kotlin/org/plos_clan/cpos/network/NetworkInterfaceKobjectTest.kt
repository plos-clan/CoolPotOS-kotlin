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
    fun publishesEverySyntheticUeventAction() {
        val published = mutableListOf<KobjectUevent>()
        val publisher = KobjectUeventPublisher(published::add)
        val uevent = NetworkInterfaceKobject(TestInterface, publisher).specification.attributes
            .single { it.name == "uevent" } as SysfsTextAttribute

        val terminators = listOf("", "\n", "\u0000")
        KobjectAction.entries.forEachIndexed { index, action ->
            assertIs<VfsResult.Ok<Unit>>(
                uevent.store(
                    "${action.wireName}${terminators[index % terminators.size]}"
                        .encodeToByteArray(),
                ),
            )
        }
        assertEquals(KobjectAction.entries, published.map(KobjectUevent::action))
        published.forEach { event ->
            assertEquals(
                listOf("SYNTH_UUID" to "0", "INTERFACE" to "lo", "IFINDEX" to "1"),
                event.environment,
            )
        }
    }

    @Test
    fun publishesSyntheticUeventArguments() {
        val published = mutableListOf<KobjectUevent>()
        val uevent = NetworkInterfaceKobject(
            TestInterface,
            KobjectUeventPublisher(published::add),
        ).specification.attributes.single { it.name == "uevent" } as SysfsTextAttribute

        val uuid = "fe4d7c9d-b8c6-4a70-9ef1-3d8a58d18eed"
        assertIs<VfsResult.Ok<Unit>>(uevent.store("add $uuid".encodeToByteArray()))
        assertIs<VfsResult.Ok<Unit>>(
            uevent.store("change $uuid A=1 B=abc\n".encodeToByteArray()),
        )

        assertEquals(
            listOf(KobjectAction.ADD, KobjectAction.CHANGE),
            published.map(KobjectUevent::action),
        )
        assertEquals(
            listOf(
                listOf("SYNTH_UUID" to uuid, "INTERFACE" to "lo", "IFINDEX" to "1"),
                listOf(
                    "SYNTH_UUID" to uuid,
                    "SYNTH_ARG_A" to "1",
                    "SYNTH_ARG_B" to "abc",
                    "INTERFACE" to "lo",
                    "IFINDEX" to "1",
                ),
            ),
            published.map(KobjectUevent::environment),
        )
    }

    @Test
    fun rejectsMalformedSyntheticUevents() {
        listOf(
            "",
            "addd",
            "change\n\n",
            "change argument",
            "CHANGE",
            "add ",
            "add fe4d7c9d-b8c6-4a70-9ef1-3d8a58d18ee",
            "add ge4d7c9d-b8c6-4a70-9ef1-3d8a58d18eed",
            "add fe4d7c9d-b8c6-4a70-9ef1-3d8a58d18eed ",
            "add fe4d7c9d-b8c6-4a70-9ef1-3d8a58d18eed A=",
            "add fe4d7c9d-b8c6-4a70-9ef1-3d8a58d18eed A-B=1",
        ).forEach { input ->
            assertNull(KobjectUeventRequest.parse(input.encodeToByteArray()))
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
