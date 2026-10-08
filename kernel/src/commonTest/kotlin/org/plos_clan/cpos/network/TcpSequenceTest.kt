package org.plos_clan.cpos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TcpSequenceTest {
    private val local = Ipv4SocketAddress(Ipv4Address.fromBits(0x03020100u), 0x0908u)
    private val remote = Ipv4SocketAddress(Ipv4Address.fromBits(0x07060504u), 0x0b0au)
    private val generator = TcpSequence.Generator(0x0706050403020100uL, 0x0f0e0d0c0b0a0908uL)

    @Test
    fun matchesSipHashReferenceVectorForTwelveBytes() {
        assertEquals(0x860ee5fbu, generator.initial(local, remote, 0uL))
    }

    @Test
    fun advancesPerTupleClockAndSeparatesEndpointsAndSecrets() {
        val first = generator.initial(local, remote, 0uL)
        assertEquals(first + 250_000u, generator.initial(local, remote, 1_000_000_000uL))
        assertEquals(first, generator.initial(local, remote, 0uL))
        assertNotEquals(first, generator.initial(remote, local, 0uL))
        val anotherPort = remote.copy(port = 80u)
        assertNotEquals(first, generator.initial(local, anotherPort, 0uL))
        val other = TcpSequence.Generator(1uL, 2uL)
        assertNotEquals(first, other.initial(local, remote, 0uL))
    }

    @Test
    fun clockAndComparisonsWrapInSerialNumberSpace() {
        val first = generator.initial(local, remote, 0uL)
        val period = (UInt.MAX_VALUE.toULong() + 1uL) * 4_000uL
        assertEquals(first, generator.initial(local, remote, period))
        assertTrue(TcpSequence.after(1u, UInt.MAX_VALUE))
        assertTrue(TcpSequence.before(UInt.MAX_VALUE, 1u))
        assertTrue(TcpSequence.between(0u, UInt.MAX_VALUE, 1u))
        assertFalse(TcpSequence.after(1u, 1u))
        assertFalse(TcpSequence.between(2u, UInt.MAX_VALUE, 1u))
    }
}
