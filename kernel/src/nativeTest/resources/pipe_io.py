import ctypes
import errno
import json
import os
import signal
import struct
import tempfile


def reap(children):
    for child in children:
        assert os.waitpid(child, 0)[1] == 0


def atomic_writers():
    reader, writer = os.pipe()
    children = []
    for index in range(4):
        child = os.fork()
        if child == 0:
            os.close(reader)
            try:
                record = bytes([index]) * 4096
                for _ in range(64):
                    assert os.write(writer, record) == len(record)
                os._exit(0)
            except BaseException:
                os._exit(1)
        children.append(child)
    os.close(writer)
    pending = b''
    counts = [0] * len(children)
    while True:
        chunk = os.read(reader, 512)
        if not chunk:
            break
        pending += chunk
        if len(pending) < 4096:
            continue
        index = pending[0]
        assert pending == bytes([index]) * 4096
        counts[index] += 1
        pending = b''
    os.close(reader)
    reap(children)
    assert not pending and counts == [64] * len(children)


def mixed_read_and_splice():
    source, writer = os.pipe()
    reports = [os.pipe() for _ in range(4)]
    children = []
    for index, (_, report) in enumerate(reports):
        child = os.fork()
        if child == 0:
            os.close(writer)
            for read_end, write_end in reports:
                os.close(read_end)
                if write_end != report:
                    os.close(write_end)
            try:
                target, sink = os.pipe()
                records = bytearray()
                while True:
                    if index % 2:
                        count = os.splice(source, sink, 8)
                        chunk = os.read(target, count) if count else b''
                    else:
                        chunk = os.read(source, 8)
                    if not chunk:
                        break
                    assert len(chunk) == 8
                    records.extend(chunk)
                assert os.write(report, records) == len(records)
                os._exit(0)
            except BaseException:
                os._exit(1)
        children.append(child)
    os.close(source)
    for _, report in reports:
        os.close(report)
    expected = list(range(8192))
    payload = b''.join(value.to_bytes(8, 'little') for value in expected)
    assert os.write(writer, payload) == len(payload)
    os.close(writer)
    actual = bytearray()
    for report, _ in reports:
        while True:
            chunk = os.read(report, 65536)
            if not chunk:
                break
            actual.extend(chunk)
        os.close(report)
    reap(children)
    values = [int.from_bytes(actual[i:i + 8], 'little') for i in range(0, len(actual), 8)]
    assert sorted(values) == expected


def nonblocking_pipe():
    reader, writer = os.pipe2(os.O_NONBLOCK)
    record = b'a' * 4096
    capacity = 0
    while True:
        try:
            capacity += os.write(writer, record)
        except BlockingIOError as error:
            assert error.errno == errno.EAGAIN
            break
    assert os.read(reader, 512) == record[:512]
    try:
        os.write(writer, record)
        raise AssertionError('An atomic write must wait for enough space')
    except BlockingIOError:
        pass
    os.close(writer)
    remaining = bytearray()
    while True:
        chunk = os.read(reader, 65536)
        if not chunk:
            break
        remaining.extend(chunk)
    os.close(reader)
    assert remaining == b'a' * (capacity - 512)


def file_notifications():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
    modified, opened, deleted, exclude_unlinked = 0x2, 0x20, 0x200, 0x04000000
    descriptor = libc.inotify_init1(os.O_NONBLOCK)
    assert descriptor >= 0
    try:
        with tempfile.TemporaryDirectory() as directory:
            mask = modified | opened | deleted | exclude_unlinked
            watch = libc.inotify_add_watch(descriptor, os.fsencode(directory), mask)
            assert watch >= 0
            path = directory + '/data'
            file = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
            try:
                assert os.write(file, b'a') == 1
                notifications = os.read(descriptor, 4096)
                observed = 0
                offset = 0
                while offset < len(notifications):
                    identity, event, _, size = struct.unpack_from('iIII', notifications, offset)
                    name = notifications[offset + 16:offset + 16 + size].rstrip(b'\0')
                    assert identity == watch and name == b'data'
                    observed |= event
                    offset += 16 + size
                assert observed == opened | modified
                os.unlink(path)
                assert os.write(file, b'b') == 1
                notifications = os.read(descriptor, 4096)
                identity, event, _, size = struct.unpack_from('iIII', notifications)
                assert identity == watch and event == deleted
                assert len(notifications) == 16 + size
            finally:
                os.close(file)
    finally:
        os.close(descriptor)


signal.alarm(30)
results = {}
for test in (atomic_writers, mixed_read_and_splice, nonblocking_pipe, file_notifications):
    test()
    results[test.__name__] = True
signal.alarm(0)
print(json.dumps(results), flush=True)
