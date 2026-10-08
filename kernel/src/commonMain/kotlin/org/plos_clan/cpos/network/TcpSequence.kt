package org.plos_clan.cpos.network

internal object TcpSequence {
    fun before(first: UInt, second: UInt): Boolean = (first - second).toInt() < 0

    fun after(first: UInt, second: UInt): Boolean = before(second, first)

    fun between(value: UInt, first: UInt, last: UInt): Boolean =
        !before(value, first) && !after(value, last)

    fun distance(first: UInt, second: UInt): UInt = second - first

    class Generator(private val key0: ULong, private val key1: ULong) {
        fun initial(local: Ipv4SocketAddress, remote: Ipv4SocketAddress, nanos: ULong): UInt {
            val localAddress = local.address.value.toULong()
            val remoteAddress = remote.address.value.toULong() shl 32
            val addresses = localAddress or remoteAddress
            val localPort = local.port.toULong()
            val remotePort = remote.port.toULong() shl 16
            val ports = localPort or remotePort
            val hash = Hash(key0, key1)
            val offset = hash.digest(addresses, ports).toUInt()
            return offset + (nanos / 4_000uL).toUInt()
        }

        private class Hash(key0: ULong, key1: ULong) {
            private var v0 = key0 xor 0x736f6d6570736575uL
            private var v1 = key1 xor 0x646f72616e646f6duL
            private var v2 = key0 xor 0x6c7967656e657261uL
            private var v3 = key1 xor 0x7465646279746573uL

            fun digest(addresses: ULong, ports: ULong): ULong {
                compress(addresses)
                compress(ports or (12uL shl 56))
                v2 = v2 xor 0xffuL
                repeat(4) { round() }
                return v0 xor v1 xor v2 xor v3
            }

            private fun compress(word: ULong) {
                v3 = v3 xor word
                repeat(2) { round() }
                v0 = v0 xor word
            }

            private fun round() {
                v0 += v1
                v1 = v1.rotateLeft(13) xor v0
                v0 = v0.rotateLeft(32)
                v2 += v3
                v3 = v3.rotateLeft(16) xor v2
                v0 += v3
                v3 = v3.rotateLeft(21) xor v0
                v2 += v1
                v1 = v1.rotateLeft(17) xor v2
                v2 = v2.rotateLeft(32)
            }
        }
    }
}
