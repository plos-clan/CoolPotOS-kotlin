package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.SocketOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PortTableTest {
    private val local = Ipv4SocketAddress(Ipv4Address.fromBits(0x7f000001u), 8080u)
    private val wildcard = local.copy(address = Ipv4Address.ANY)

    @Test
    fun reusePortRequiresOptInAndMatchingOwner() {
        val table = PortTable<Any>()
        val first = Any()
        val options = SocketOptions(reusePort = true)
        val conflict = VfsResult.Err(VfsError.ADDRESS_IN_USE)
        assertEquals(VfsResult.Ok(local), table.bind(first, local, options, 1000))
        assertEquals(conflict, table.bind(Any(), local, options, 1001))
        assertEquals(conflict, table.bind(Any(), wildcard, SocketOptions(), 1000))
        val second = Any()
        table.configure(local.port, first, SocketOptions(), 1000)
        assertEquals(conflict, table.bind(second, local, options, 1000))
        table.configure(local.port, first, options, 1000)
        assertEquals(VfsResult.Ok(local), table.bind(second, local, options, 1000))
        assertEquals(VfsResult.Ok(wildcard), table.bind(Any(), wildcard, options, 1000))
        val selected = mutableSetOf<Any>()
        for (port in 30000..30255) {
            val remote = local.copy(port = port.toUShort())
            val socket = table.select(local, remote, 1)
            assertSame(socket, table.select(local, remote, 1))
            selected += checkNotNull(socket)
        }
        assertEquals(setOf(first, second), selected)
        table.remove(local.port, first)
        assertSame(second, table.select(local, local, 1))
    }

    @Test
    fun peerAndDeviceChangesRepublishSelectionAndMulticastMembership() {
        val table = PortTable<Any>()
        val fallback = Any()
        val specific = Any()
        val options = SocketOptions(reusePort = true)
        val bound = options.copy(boundInterfaceIndex = 3)
        val remote = local.copy(port = 40000u)
        val other = local.copy(port = 40001u)
        assertEquals(VfsResult.Ok(wildcard), table.bind(fallback, wildcard, options, 1000))
        assertEquals(VfsResult.Ok(wildcard), table.bind(specific, wildcard, options, 1000))
        table.configure(local.port, specific, bound, 1000, remote, local.address)
        assertSame(specific, table.select(local, remote, 3))
        assertSame(fallback, table.select(local, other, 3))
        assertSame(fallback, table.select(local, remote, 4))
        val received = mutableSetOf<Any>()
        table.multicast(local, remote, 3, received::add)
        assertEquals(setOf(specific, fallback), received)
        table.configure(local.port, specific, bound, 1000)
        assertSame(specific, table.select(local, other, 3))
        assertSame(fallback, table.select(local, other, 4))
    }

    @Test
    fun reuseAddressAloneDoesNotShareListeners() {
        val table = PortTable<Any>()
        val first = Any()
        val second = Any()
        val options = SocketOptions(reuseAddress = true)
        assertEquals(VfsResult.Ok(wildcard), table.bind(first, wildcard, options, 0))
        assertEquals(VfsResult.Ok(local), table.bind(second, local, options, 0))
        assertEquals(VfsResult.Ok(Unit), table.listen(local.port, second))
        val conflict = VfsResult.Err(VfsError.ADDRESS_IN_USE)
        assertEquals(conflict, table.listen(local.port, first))
        assertEquals(conflict, table.bind(Any(), wildcard, options, 0))
    }
}
