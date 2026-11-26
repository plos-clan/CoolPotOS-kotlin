package org.plos_clan.cpos.network

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

internal class PacketSlice(val bytes: ByteArray, val offset: Int, val size: Int) {
    fun drop(count: Int) = PacketSlice(bytes, offset + count, size - count)

    fun copyTo(destination: PreparedBufferDestination, start: Int, count: Int): Int =
        destination.copyFrom(start, bytes, offset, count)

    companion object {
        val EMPTY = PacketSlice(TcpCodec.EMPTY, 0, 0)
    }
}

internal class TcpPacketQueue {
    companion object {
        const val TCP_PAYLOAD_OFFSET = Ipv4OutputPacket.PAYLOAD_OFFSET + TcpCodec.MIN_HEADER_SIZE
    }

    private val packets = ArrayDeque<PacketSlice>()
    var size = 0
        private set

    fun append(packet: PacketSlice) {
        if (packet.size == 0) return
        packets.addLast(packet)
        size += packet.size
    }

    fun read(target: PreparedBufferDestination, offset: Int, count: Int, peek: Boolean): Int {
        var copied = 0
        var index = 0
        while (copied < count && index < packets.size) {
            val packet = packets[index]
            val length = minOf(count - copied, packet.size)
            val n = packet.copyTo(target, offset + copied, length)
            copied += n
            if (peek) index++ else {
                size -= n
                if (n == packet.size) packets.removeFirst() else packets[0] = packet.drop(n)
            }
            if (n != length) break
        }
        return copied
    }

    fun write(source: PreparedBufferSource, offset: Int, count: Int, mss: Int): Int {
        var copied = 0
        while (copied < count) {
            val length = minOf(count - copied, mss)
            val packet = Ipv4OutputPacket(TcpCodec.MIN_HEADER_SIZE + length)
            val n = source.copyTo(offset + copied, packet.bytes, TCP_PAYLOAD_OFFSET, length)
            val payload = PacketSlice(packet.bytes, TCP_PAYLOAD_OFFSET, n)
            append(payload)
            copied += n
            if (n < length) break
        }
        return copied
    }

    fun takeSegment(length: Int): Ipv4OutputPacket {
        val first = packets.first()
        if (first.size == length && first.offset == TCP_PAYLOAD_OFFSET &&
            first.bytes.size == TCP_PAYLOAD_OFFSET + length
        ) {
            packets.removeFirst()
            size -= length
            return Ipv4OutputPacket(first.bytes)
        }
        val frame = Ipv4OutputPacket(TcpCodec.MIN_HEADER_SIZE + length)
        val buffer = ByteArrayBuffer(frame.bytes)
        val target = checkNotNull(buffer.prepareWrite(TCP_PAYLOAD_OFFSET, length))
        check(read(target, TCP_PAYLOAD_OFFSET, length, peek = false) == length)
        return frame
    }

    fun clear() {
        packets.clear()
        size = 0
    }
}
