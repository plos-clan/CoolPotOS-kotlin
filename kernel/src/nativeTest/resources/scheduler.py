import ctypes
import json
import os
import select
import signal
import statistics
import time


def readiness():
    reader, writer = os.pipe2(os.O_NONBLOCK)
    with select.epoll() as epoll:
        epoll.register(reader, select.EPOLLIN | select.EPOLLET)
        assert epoll.poll(0) == []
        os.write(writer, b'ab')
        assert epoll.poll(1) == [(reader, select.EPOLLIN)]
        assert epoll.poll(0) == []
        assert os.read(reader, 1) == b'a'
        assert epoll.poll(0) == []
        assert os.read(reader, 1) == b'b'
        os.write(writer, b'c')
        assert epoll.poll(1) == [(reader, select.EPOLLIN)]
        epoll.modify(reader, select.EPOLLIN | select.EPOLLONESHOT)
        assert epoll.poll(1) == [(reader, select.EPOLLIN)]
        assert epoll.poll(0) == []
        epoll.modify(reader, select.EPOLLIN)
        assert epoll.poll(1) == [(reader, select.EPOLLIN)]
        assert os.read(reader, 1) == b'c'
        started = time.monotonic()
        assert epoll.poll(0.05) == []
        assert time.monotonic() - started >= 0.04
        os.close(writer)
        assert epoll.poll(1)[0][1] & select.EPOLLHUP
    os.close(reader)
    return True


def affinity():
    original = os.sched_getaffinity(0)
    try:
        for cpu in sorted(original) * 16:
            os.sched_setaffinity(0, {cpu})
            assert os.sched_getaffinity(0) == {cpu}
            result = (ctypes.c_uint * 2)(0, 0xAABBCCDD)
            node = (ctypes.c_uint * 2)(0, 0xAABBCCDD)
            libc = ctypes.CDLL(None, use_errno=True)
            assert libc.syscall(309, ctypes.byref(result), ctypes.byref(node), 0) == 0
            assert result[0] == cpu and result[1] == node[1] == 0xAABBCCDD
            os.sched_yield()
    finally:
        os.sched_setaffinity(0, original)
    return sorted(original)


def eventfd():
    libc = ctypes.CDLL(None, use_errno=True)
    descriptor = libc.eventfd(0, os.O_NONBLOCK)
    assert descriptor >= 0
    try:
        with select.epoll() as epoll:
            epoll.register(descriptor, select.EPOLLIN | select.EPOLLET)
            assert epoll.poll(0) == []
            for _ in range(2):
                os.write(descriptor, (1).to_bytes(8, 'little'))
                assert epoll.poll(1) == [(descriptor, select.EPOLLIN)]
                assert epoll.poll(0) == []
            epoll.modify(descriptor, select.EPOLLOUT | select.EPOLLET)
            assert epoll.poll(1) == [(descriptor, select.EPOLLOUT)]
            os.write(descriptor, (1).to_bytes(8, 'little'))
            assert epoll.poll(0) == []
    finally:
        os.close(descriptor)
    return True


def sleepers(count):
    ready_read, ready_write = os.pipe()
    pipes = [os.pipe() for _ in range(count)]
    children = []
    for index, (reader, writer) in enumerate(pipes):
        pid = os.fork()
        if pid == 0:
            try:
                for other_read, other_write in pipes:
                    os.close(other_write)
                    if other_read != reader:
                        os.close(other_read)
                os.close(ready_read)
                with select.epoll() as epoll:
                    epoll.register(reader, select.EPOLLIN)
                    os.write(ready_write, b'R')
                    started = time.process_time_ns()
                    assert epoll.poll(10)
                    elapsed = time.process_time_ns() - started
                    os.write(ready_write, (str(elapsed) + '\n').encode())
                os._exit(0)
            except BaseException:
                os._exit(1)
        children.append(pid)
    os.close(ready_write)
    for reader, writer in pipes:
        os.close(reader)
    acknowledged = b''
    while len(acknowledged) < count:
        acknowledged += os.read(ready_read, count - len(acknowledged))
    time.sleep(0.25)
    start = time.monotonic_ns()
    for reader, writer in pipes:
        os.write(writer, b'W')
        os.close(writer)
    data = b''
    while True:
        block = os.read(ready_read, 4096)
        if not block:
            break
        data += block
    os.close(ready_read)
    for pid in children:
        assert os.waitpid(pid, 0)[1] == 0
    samples = [int(line) for line in data.splitlines()]
    assert len(samples) == count
    assert statistics.median(samples) < 40_000_000
    return {'count': count, 'cpu_ns_median': statistics.median(samples),
            'completion_ns': time.monotonic_ns() - start}


def accounting():
    root = '/sys/fs/cgroup'
    path = root + '/scheduler-verification-' + str(os.getpid())
    os.mkdir(path)
    reader, writer = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(writer)
        os.read(reader, 1)
        end = time.monotonic() + 0.15
        value = 1
        while time.monotonic() < end:
            for _ in range(500):
                value = (value * 13 + 7) % 65521
            os.getpid()
        os._exit(0)
    os.close(reader)
    try:
        with open(path + '/cgroup.procs', 'w') as output:
            output.write(str(pid))
        os.write(writer, b'R')
        os.close(writer)
        assert os.waitpid(pid, 0)[1] == 0
        with open(path + '/cpu.stat') as source:
            values = {key: int(value) for key, value in map(str.split, source)}
        assert values['usage_usec'] > 0
        assert values['user_usec'] > 0
        assert values['system_usec'] > 0
        assert abs(values['usage_usec'] - values['user_usec'] - values['system_usec']) <= 1
        return values
    finally:
        os.rmdir(path)


def signals():
    class Interrupted(Exception):
        pass

    def interrupt(number, frame):
        raise Interrupted()

    previous = signal.signal(signal.SIGUSR1, interrupt)
    pid = os.fork()
    if pid == 0:
        time.sleep(0.05)
        os.kill(os.getppid(), signal.SIGUSR1)
        os._exit(0)
    try:
        with select.epoll() as epoll:
            try:
                epoll.poll(2)
                raise AssertionError('signal did not interrupt epoll')
            except Interrupted:
                pass
        assert os.waitpid(pid, 0)[1] == 0
        return True
    finally:
        signal.signal(signal.SIGUSR1, previous)


def fairness():
    cpu = min(os.sched_getaffinity(0))
    start_read, start_write = os.pipe()
    result_read, result_write = os.pipe()
    children = []
    for priority in (0, 10):
        pid = os.fork()
        if pid == 0:
            os.close(start_write)
            os.close(result_read)
            os.sched_setaffinity(0, {cpu})
            os.nice(priority)
            os.write(result_write, b'R')
            os.read(start_read, 1)
            end = time.monotonic() + 0.8
            operations = 0
            while time.monotonic() < end:
                for _ in range(1000):
                    operations += 1
            os.write(result_write, (str(priority) + ' ' + str(operations) + '\n').encode())
            os._exit(0)
        children.append(pid)
    os.close(start_read)
    os.close(result_write)
    acknowledged = b''
    while len(acknowledged) < len(children):
        acknowledged += os.read(result_read, len(children) - len(acknowledged))
    os.write(start_write, b'RR')
    os.close(start_write)
    with os.fdopen(result_read) as source:
        values = {int(key): int(value) for key, value in map(str.split, source)}
    for pid in children:
        assert os.waitpid(pid, 0)[1] == 0
    assert values[0] > values[10] * 2
    return values


results = {}
failures = {}
checks = [
    ('readiness', readiness),
    ('eventfd', eventfd),
    ('signals', signals),
    ('affinity', affinity),
    ('sleepers', lambda: [sleepers(count) for count in (4, 32)]),
    ('fairness', fairness),
    ('accounting', accounting),
]
for name, operation in checks:
    try:
        results[name] = operation()
        print(json.dumps({name: results[name]}), flush=True)
    except Exception as failure:
        failures[name] = repr(failure)
        print(json.dumps({name: {'error': repr(failure)}}), flush=True)
if failures:
    print('SCHEDULER_FAILED ' + json.dumps(failures), flush=True)
    raise SystemExit(1)
print('SCHEDULER_VERIFIED ' + json.dumps(results), flush=True)
