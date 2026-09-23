package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.fs.vfs.AnonymousFileFactory
import org.plos_clan.cpos.fs.vfs.VfsNodeOperations
import org.plos_clan.cpos.fs.vfs.VfsPathResolver
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.Epoll
import org.plos_clan.cpos.fs.vfs.EpollControlOperation
import org.plos_clan.cpos.fs.vfs.EpollEvent
import org.plos_clan.cpos.fs.vfs.EpollEvents
import org.plos_clan.cpos.utils.PollEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UnixSocketQueueTest {
    @Test
    fun reportsUnreadOutputAcrossPartialReadsAndBufferResize() {
        val (sender, receiver) = pair(SocketType.STREAM)
        val data = ByteArray(4096)
        val source = checkNotNull(ByteArrayBuffer(data).prepareRead(0, data.size))
        val destination = checkNotNull(ByteArrayBuffer(data).prepareWrite(0, data.size))
        try {
            assertEquals(0, sender.outputQueueBytes())
            assertEquals(data.size, sender.send(UnixSendRequest(source, 0, data.size, credentials)).bytesTransferred)
            assertEquals(data.size, sender.outputQueueBytes())
            assertEquals(0, receiver.outputQueueBytes())
            sender.setSendBufferSize(0)
            assertEquals(data.size, sender.outputQueueBytes())
            assertIs<VfsResult.Ok<UnixReceiveResult>>(
                receiver.receive(UnixReceiveRequest(destination, 0, 1000, nonBlocking = true, peek = true)),
            )
            assertEquals(data.size, sender.outputQueueBytes())
            assertIs<VfsResult.Ok<UnixReceiveResult>>(
                receiver.receive(UnixReceiveRequest(destination, 0, 1000, nonBlocking = true)),
            )
            assertEquals(data.size - 1000, sender.outputQueueBytes())
            receiver.shutdown(SocketShutdownMode.READ)
            assertEquals(0, sender.outputQueueBytes())
        } finally {
            sender.release()
            receiver.release()
        }
    }

    @Test
    fun countsAllQueuedRecords() {
        val (sender, receiver) = pair(SocketType.SEQUENCED_PACKET)
        val data = ByteArray(200)
        val source = checkNotNull(ByteArrayBuffer(data).prepareRead(0, data.size))
        val destination = checkNotNull(ByteArrayBuffer(data).prepareWrite(0, data.size))
        try {
            assertEquals(100, sender.send(UnixSendRequest(source, 0, 100, credentials)).bytesTransferred)
            assertEquals(200, sender.send(UnixSendRequest(source, 0, 200, credentials)).bytesTransferred)
            assertEquals(300, sender.outputQueueBytes())
            assertEquals(100, assertIs<VfsResult.Ok<UnixReceiveResult>>(
                receiver.receive(UnixReceiveRequest(destination, 0, data.size, nonBlocking = true)),
            ).value.bytes)
            assertEquals(200, sender.outputQueueBytes())
        } finally {
            sender.release()
            receiver.release()
        }
    }

    @Test
    fun rearmsEdgeTriggeredInputWhenQueueRefillsBetweenPolls() {
        val vfs = Vfs()
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Sysfs))
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Sysfs.name)).value
        val namespace = UnixSocketNamespace()
        val caller = VfsOperationContext.KERNEL
        val pair = vfs.createUnixSocketPair(
            caller, context, SocketType.STREAM, credentials, true, namespace,
        )
        val files =
            assertIs<VfsResult.Ok<Pair<OpenFileDescription, OpenFileDescription>>>(pair).value
        val sender = files.first.backend as UnixSocket
        val receiver = files.second.backend as UnixSocket
        val epoll = Epoll()
        val events = mutableListOf<EpollEvent>()
        val bytes = ByteArray(8)
        val source = checkNotNull(ByteArrayBuffer(bytes).prepareRead(0, bytes.size))
        val destination = checkNotNull(ByteArrayBuffer(bytes).prepareWrite(0, bytes.size))
        try {
            assertIs<VfsResult.Ok<Unit>>(epoll.control(
                1, files.second, EpollControlOperation.ADD,
                EpollEvent(PollEvents.POLLIN.toUInt() or EpollEvents.EDGE_TRIGGERED, 42uL),
            ))
            repeat(2) {
                assertEquals(bytes.size, sender.send(UnixSendRequest(source, 0, bytes.size, credentials)).bytesTransferred)
                epoll.collect(caller, 1, events)
                assertEquals(listOf(EpollEvent(PollEvents.POLLIN.toUInt(), 42uL)), events)
                epoll.collect(caller, 1, events)
                assertEquals(emptyList(), events)
                assertIs<VfsResult.Ok<UnixReceiveResult>>(
                    receiver.receive(UnixReceiveRequest(destination, 0, bytes.size, nonBlocking = true)),
                )
            }
        } finally {
            epoll.release()
            files.first.release()
            files.second.release()
            context.release()
        }
    }

    @Test
    fun halfClosedJournalStreamReportsInputWithoutSpuriousErrors() {
        val vfs = Vfs()
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Sysfs))
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Sysfs.name)).value
        val namespace = UnixSocketNamespace()
        val caller = VfsOperationContext.KERNEL
        val pair = vfs.createUnixSocketPair(
            caller, context, SocketType.STREAM, credentials, true, namespace,
        )
        val files =
            assertIs<VfsResult.Ok<Pair<OpenFileDescription, OpenFileDescription>>>(pair).value
        val sender = files.first.backend as UnixSocket
        val receiver = files.second.backend as UnixSocket
        val bytes = byteArrayOf(42)
        val buffer = ByteArrayBuffer(bytes)
        val source = checkNotNull(buffer.prepareRead(0, bytes.size))
        val request = UnixSendRequest(source, 0, bytes.size, credentials)
        try {
            assertIs<VfsResult.Ok<Unit>>(sender.shutdown(SocketShutdownMode.READ))
            assertIs<VfsResult.Ok<Unit>>(receiver.shutdown(SocketShutdownMode.WRITE))
            assertEquals(0L, files.second.poll(caller, PollEvents.POLLIN))
            assertEquals(1, sender.send(request).bytesTransferred)
            assertEquals(PollEvents.POLLIN.toLong(), files.second.poll(caller, PollEvents.POLLIN))
            assertIs<VfsResult.Ok<Unit>>(sender.shutdown(SocketShutdownMode.WRITE))
            val closed = PollEvents.POLLIN or PollEvents.POLLHUP
            assertEquals(closed.toLong(), files.second.poll(caller, PollEvents.POLLIN))
        } finally {
            files.first.release()
            files.second.release()
            context.release()
        }
    }

    private fun pair(type: SocketType): Pair<UnixSocket, UnixSocket> {
        val paths = VfsPathResolver(40)
        val subsystem = UnixSocketSubsystem(paths, VfsNodeOperations(paths), AnonymousFileFactory())
        val namespace = UnixSocketNamespace()
        val sender = subsystem.newSocket(type, credentials, namespace)
        val receiver = subsystem.newSocket(type, credentials, namespace)
        assertIs<VfsResult.Ok<Unit>>(sender.pairWith(receiver, credentials))
        return sender to receiver
    }

    private companion object {
        val credentials = UnixCredentials(1, 0u, 0u)
    }
}
