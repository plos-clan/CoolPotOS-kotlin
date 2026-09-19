package org.plos_clan.cpos.drivers.usb.adapt.msc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.plos_clan.cpos.drivers.scsi.ScsiDirection
import org.plos_clan.cpos.drivers.scsi.ScsiSense
import org.plos_clan.cpos.drivers.scsi.ScsiStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.utils.BigEndianBuffer

class UasTest {
    @Test
    fun streamsCompleteOutOfOrderWithoutMixingBuffers() = runBlocking {
        val host = StorageHost()
        host.onSubmit = { if (it.endpointAddress == 1u.toUByte()) host.complete(it) }
        val transport = assertNotNull(UasTransport.create(host.interfaceFor(true), host::allocate))
        val first = ByteArray(512)
        val second = ByteArray(512)
        val a =
            async(start = CoroutineStart.UNDISPATCHED) {
                transport.execute(
                    0uL,
                    byteArrayOf(0x28),
                    ScsiDirection.IN,
                    ByteArrayBuffer(first),
                    0,
                    512,
                )
            }
        val b =
            async(start = CoroutineStart.UNDISPATCHED) {
                transport.execute(
                    0uL,
                    byteArrayOf(0x28),
                    ScsiDirection.IN,
                    ByteArrayBuffer(second),
                    0,
                    512,
                )
            }
        val reads = host.requests.filter { it.endpointAddress == 0x83u.toUByte() }
        assertEquals(listOf(1, 2), reads.map { it.streamId.toInt() })
        host.status(2)
        yield()
        assertFalse(b.isCompleted)
        host.complete(reads[1], ByteArray(512) { 2 })
        assertEquals(512, b.await().actualLength)
        assertFalse(a.isCompleted)
        host.complete(reads[0], ByteArray(512) { 1 })
        host.status(1)
        assertEquals(ScsiStatus.GOOD, a.await().status)
        assertContentEquals(ByteArray(512) { 1 }, first)
        assertContentEquals(ByteArray(512) { 2 }, second)
        transport.close()
        assertTrue(host.memory.all { it.closed })
    }

    @Test
    fun highSpeedWaitsForReadyBeforeSubmittingData() = runBlocking {
        val host = StorageHost()
        val submitted = CompletableDeferred<UsbTransfer>()
        host.onSubmit = {
            if (it.endpointAddress == 1u.toUByte()) host.complete(it)
            if (it.endpointAddress == 0x83u.toUByte()) submitted.complete(it)
        }
        val transport =
            assertNotNull(UasTransport.create(host.interfaceFor(true, 3u), host::allocate))
        val operation =
            async(start = CoroutineStart.UNDISPATCHED) {
                transport.execute(
                    0uL,
                    byteArrayOf(0x28),
                    ScsiDirection.IN,
                    ByteArrayBuffer(ByteArray(512)),
                    0,
                    512,
                )
            }
        assertTrue(host.requests.none { it.endpointAddress == 0x83u.toUByte() })
        host.complete(host.requests.first(), byteArrayOf(6, 0, 0, 1))
        val data = submitted.await()
        assertEquals(0u.toUShort(), data.streamId)
        host.complete(data, ByteArray(512))
        host.status(1, stream = 0)
        assertEquals(ScsiStatus.GOOD, operation.await().status)
        transport.close()
    }

    @Test
    fun autosenseAndAdditionalCdbLengthUseWireEncoding() = runBlocking {
        val host = StorageHost()
        host.onSubmit = { if (it.endpointAddress == 1u.toUByte()) host.complete(it) }
        val transport = assertNotNull(UasTransport.create(host.interfaceFor(true), host::allocate))
        val cdb = ByteArray(32) { it.toByte() }
        val operation =
            async(start = CoroutineStart.UNDISPATCHED) {
                transport.execute(0x4001000000000000uL, cdb)
            }
        val command = host.bytes(host.requests.last())
        assertEquals(16.toByte(), command[6])
        assertEquals(0x4001000000000000uL, BigEndianBuffer(command).read(8, 8))
        assertContentEquals(cdb, command.copyOfRange(16, 48))
        host.status(1, 2, sense = byteArrayOf(0x72, 2, 0x3a, 0, 0, 0, 0, 0))
        val result = operation.await()
        assertEquals(ScsiStatus.CHECK_CONDITION, result.status)
        assertEquals(ScsiSense(2, 0x3a, 0), result.sense)
        transport.close()
    }

    @Test
    fun checkConditionCancelsOnlyItsOwnDataStream() = runBlocking {
        val host = StorageHost()
        host.onSubmit = { if (it.endpointAddress == 1u.toUByte()) host.complete(it) }
        val transport = assertNotNull(UasTransport.create(host.interfaceFor(true), host::allocate))
        val operations =
            List(2) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    transport.execute(
                        0uL,
                        byteArrayOf(0x28),
                        ScsiDirection.IN,
                        ByteArrayBuffer(ByteArray(512)),
                        0,
                        512,
                    )
                }
            }
        host.status(1, 2, sense = byteArrayOf(0x72, 3, 0x11, 0, 0, 0, 0, 0))
        val failed = operations[0].await()
        assertEquals(ScsiStatus.CHECK_CONDITION, failed.status)
        assertEquals(ScsiSense(3, 0x11, 0), failed.sense)
        assertTrue(transport.connected)
        val other =
            host.requests.single {
                it.endpointAddress == 0x83u.toUByte() && it.streamId == 2u.toUShort()
            }
        assertFalse(other.isCompleted)
        host.complete(other, ByteArray(512))
        host.status(2)
        assertEquals(ScsiStatus.GOOD, operations[1].await().status)
        transport.close()
    }

    @Test
    fun malformedStatusStopsAllStreamsBeforeFreeingDma() = runBlocking {
        val host = StorageHost()
        host.onSubmit = { if (it.endpointAddress == 1u.toUByte()) host.complete(it) }
        val transport = assertNotNull(UasTransport.create(host.interfaceFor(true), host::allocate))
        val operations =
            List(2) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    transport.execute(
                        0uL,
                        byteArrayOf(0x28),
                        ScsiDirection.IN,
                        ByteArrayBuffer(ByteArray(512)),
                        0,
                        512,
                    )
                }
            }
        host.status(9, stream = 1)
        operations.forEach { assertEquals(ScsiStatus.NO_DEVICE, it.await().status) }
        assertFalse(transport.connected)
        assertTrue(host.requests.all { it.isCompleted })
        transport.close()
        assertTrue(host.memory.all { it.closed })
    }

    @Test
    fun disconnectDrainsActiveAndQueuedCommands() = runBlocking {
        val host = StorageHost().also { it.streams = 1 }
        host.onSubmit = { if (it.endpointAddress == 1u.toUByte()) host.complete(it) }
        val transport = assertNotNull(UasTransport.create(host.interfaceFor(true), host::allocate))
        val active =
            async(start = CoroutineStart.UNDISPATCHED) { transport.execute(0uL, ByteArray(6)) }
        val queued =
            async(start = CoroutineStart.UNDISPATCHED) { transport.execute(0uL, ByteArray(6)) }
        transport.close()
        assertEquals(ScsiStatus.NO_DEVICE, active.await().status)
        assertEquals(ScsiStatus.NO_DEVICE, queued.await().status)
        assertTrue(host.memory.all { it.closed })
    }

    @Test
    fun invalidPipesAndMissingSuperSpeedStreamsRejectBinding() = runBlocking {
        val host = StorageHost()
        val iface = host.interfaceFor(true)
        iface.endpoints[1].extraDescriptors[0][2] = 1
        assertNull(UasTransport.create(iface, host::allocate))
        host.streams = 0
        assertNull(UasTransport.create(host.interfaceFor(true), host::allocate))
    }
}
