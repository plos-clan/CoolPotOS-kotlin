package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouteAddressTest {
    @Test
    fun replacesExistingAddressAndHonorsExclusiveCreation() {
        val intfc = checkNotNull(NetworkStack.interfaceByName("lo"))
        val address = Ipv4Address.fromBits(0x7f070809u)
        val payload = ByteArray(24)
        LittleEndianBuffer(payload).apply {
            writeU8(0, 2u)
            writeU8(1, 8u)
            writeU32(4, intfc.index.toUInt())
            writeU16(8, 8u)
            writeU16(10, 2u)
            writeU16(16, 8u)
            writeU16(18, 8u)
        }
        byteArrayOf(127, 7, 8, 9).copyInto(payload, 12)
        val process = checkNotNull(ProcessManager.currentThread()).process
        val socket = RouteNetlinkProtocol.createSocket(SocketType.RAW)
        fun update(flags: Int): Int {
            val bytes = NetlinkCodec.encode(
                20, flags or NetlinkAbi.NLM_F_REQUEST or NetlinkAbi.NLM_F_ACK, 1u, payload = payload,
            )
            val source = checkNotNull(ByteArrayBuffer(bytes).prepareRead(0, bytes.size))
            assertTrue(socket.sendSocket(SocketSendRequest(process, source, 0, bytes.size)).isSuccess)
            val reply = ByteArray(128)
            val destination = checkNotNull(ByteArrayBuffer(reply).prepareWrite(0, reply.size))
            var error = 0
            val echo = flags and NetlinkAbi.NLM_F_ECHO != 0
            repeat(if (echo) 2 else 1) { index ->
                val received = assertIs<VfsResult.Ok<SocketReceiveResult>>(
                    socket.receiveSocket(SocketReceiveRequest(destination, 0, reply.size, nonBlocking = true)),
                ).value
                val message = NetlinkCodec.decode(reply.copyOf(received.bytes)).single()
                if (echo && index == 0) {
                    assertEquals(20u.toUShort(), message.type)
                    assertEquals(0u, message.attributes(8)?.get(8)?.u32() ?: 0u)
                } else {
                    assertEquals(NetlinkAbi.NLMSG_ERROR.toUShort(), message.type)
                    error = message.payload.readU32(0).toInt()
                }
            }
            return error
        }
        try {
            assertEquals(0, update(NetlinkAbi.NLM_F_REPLACE))
            val original = NetworkStack.interfaceAddresses(intfc.index).single { it.address == address }
            assertEquals(-VfsError.ALREADY_EXISTS.errno, update(0))
            assertEquals(0, update(NetlinkAbi.NLM_F_REPLACE))
            LittleEndianBuffer(payload).writeU32(20, 0x200u)
            assertEquals(0, update(NetlinkAbi.NLM_F_REPLACE or NetlinkAbi.NLM_F_ECHO))
            val exclusive = update(NetlinkAbi.NLM_F_REPLACE or NetlinkAbi.NLM_F_EXCL)
            assertEquals(-VfsError.ALREADY_EXISTS.errno, exclusive)
            assertEquals(original, NetworkStack.interfaceAddresses(intfc.index).single { it.address == address })
        } finally {
            NetworkStack.removeAddress(intfc.index, address, 8)
            socket.release()
        }
    }
}
