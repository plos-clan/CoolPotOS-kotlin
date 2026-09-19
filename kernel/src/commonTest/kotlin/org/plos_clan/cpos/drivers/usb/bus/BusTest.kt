package org.plos_clan.cpos.drivers.usb.bus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.InterfaceDescriptor

class BusTest {
    private class Host : HostController {
        val configurations = mutableListOf<List<UByte>>()
        val streamConfigurations = mutableListOf<List<Int>>()
        val requests = mutableListOf<UsbTransfer>()
        val cancellations = mutableListOf<TransferStatus>()
        val submitted = CompletableDeferred<Unit>()
        var failConfigurations = false
        var rejectSubmission = false
        var suspendSubmission = false
        var disabled = false
        var controlStatus = TransferStatus.COMPLETED

        override suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Unit? = Unit

        override suspend fun configureEndpoints(
            slotId: UByte,
            endpoints: List<UsbEndpoint>,
        ): Unit? {
            configurations.add(endpoints.map { it.desc.endpointAddress })
            streamConfigurations.add(endpoints.map { it.streamCount })
            return if (failConfigurations && configurations.size > 1) null else Unit
        }

        override suspend fun submit(slotId: UByte, transfer: UsbTransfer): Boolean {
            if (suspendSubmission) awaitCancellation()
            if (rejectSubmission) return false
            requests.add(transfer)
            submitted.complete(Unit)
            if (transfer.setup != null) transfer.complete(TransferResult(controlStatus, 0u))
            return true
        }

        override suspend fun cancel(
            slotId: UByte,
            endpointAddress: UByte,
            status: TransferStatus,
        ): Boolean {
            cancellations.add(status)
            requests
                .filter { it.endpointAddress == endpointAddress }
                .forEach {
                    it.complete(TransferResult(status, 0u))
                }
            return true
        }

        override suspend fun clearHalt(slotId: UByte, endpointAddress: UByte): Boolean = true

        override suspend fun allocateStreams(
            slotId: UByte,
            endpoints: List<UsbEndpoint>,
            count: Int,
        ): Int = count

        override suspend fun disableDevice(slotId: UByte) {
            disabled = true
        }
    }

    private fun device(host: Host): UsbDevice {
        val device = UsbDevice(host, 1u, 1, 4u)
        val iface = UsbInterface(device, InterfaceDescriptor(0u, 0u, 1u, 8u, 6u, 0x50u, 0u))
        iface.activeSetting.endpoints.add(UsbEndpoint(EndpointDescriptor(0x81u, 2u, 1024u, 0u)))
        val alternate =
            UsbAlternateSetting(iface.desc.copy(alternateSetting = 1u, interfaceProtocol = 0x62u))
        alternate.endpoints.add(UsbEndpoint(EndpointDescriptor(0x82u, 2u, 1024u, 0u)))
        iface.settings.add(alternate)
        device.interfaces.add(iface)
        device.updateEndpoints()
        return device
    }

    @Test
    fun switchesOnlySelectedInterfaceAndCommitsOwnershipAfterSuccess() = runBlocking {
        val host = Host()
        val device = device(host)
        val iface = device.interfaces.single()
        assertTrue(iface.selectAlternateSetting(1u))
        assertEquals(listOf(emptyList(), listOf(0x82u.toUByte())), host.configurations)
        assertEquals(1u.toUByte(), iface.desc.alternateSetting)
        assertEquals(iface, device.endpointOwner(0x82u))
        assertEquals(null, device.endpointOwner(0x81u))
        assertEquals(11u.toUByte(), host.requests.single().setup?.request)
    }

    @Test
    fun rejectedSettingRestoresOriginalEndpoints() = runBlocking {
        val host = Host().apply { controlStatus = TransferStatus.STALL }
        val device = device(host)
        device.interfaces.single().endpoints.single().streamCount = 7
        assertFalse(device.interfaces.single().selectAlternateSetting(1u))
        assertEquals(listOf(emptyList(), listOf(0x81u.toUByte())), host.configurations)
        assertEquals(0u.toUByte(), device.interfaces.single().desc.alternateSetting)
        assertFalse(host.disabled)
        assertEquals(listOf(emptyList(), listOf(7)), host.streamConfigurations)
    }

    @Test
    fun failedRollbackDisablesDevice() = runBlocking {
        val host = Host().apply { failConfigurations = true }
        val device = device(host)
        assertFalse(device.interfaces.single().selectAlternateSetting(1u))
        assertTrue(host.disabled)
        assertEquals(listOf(1u.toUShort(), 0u.toUShort()), host.requests.map { it.setup!!.value })
    }

    @Test
    fun deadlineStopsAcceptedDmaBeforeReturning() = runBlocking {
        val host = Host()
        val request = UsbTransfer(0x81u)
        assertEquals(TransferStatus.TIMEOUT, device(host).transfer(request, 10).status)
        assertEquals(listOf(TransferStatus.TIMEOUT), host.cancellations)
        assertTrue(request.isCompleted)
    }

    @Test
    fun cancellationDrainsAcceptedRequest() = runBlocking {
        val host = Host()
        val device = device(host)
        val request = UsbTransfer(0x81u)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { device.transfer(request, 60_000) }
        host.submitted.await()
        job.cancel()
        job.join()
        assertEquals(listOf(TransferStatus.CANCELLED), host.cancellations)
        assertTrue(request.isCompleted)
    }

    @Test
    fun timeoutBeforeSubmissionDoesNotCancelOtherRequests() = runBlocking {
        val host = Host().apply { suspendSubmission = true }
        assertEquals(TransferStatus.TIMEOUT, device(host).transfer(UsbTransfer(0x81u), 10).status)
        assertTrue(host.cancellations.isEmpty())
    }
}
