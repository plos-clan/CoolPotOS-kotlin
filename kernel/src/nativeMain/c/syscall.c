#include <errno.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/mman.h>
#include <time.h>
#include "bridge.h"
#include "native.h"
#include "syscall.h"

#define SYS_gettid 186

#define PAGE_SIZE 0x1000u
#define BOOTSTRAP_VM_ARENA_SIZE (32u * 1024u * 1024u)
#define MAP_FIXED_NOREPLACE 0x100000
#define NS_PER_SEC ((uint64_t)1000000000)

enum {
    futex_command_mask = 0x7f,
    futex_private_flag = 0x80,
    futex_bucket_bits = 8,
    futex_bucket_count = 1u << futex_bucket_bits,
};

enum futex_waiter_state {
    futex_waiting,
    futex_waking,
    futex_woken,
};

struct vm_block {
    size_t size;
    struct vm_block *next;
};

struct futex_waiter {
    struct futex_waiter *next;
    struct futex_waiter *previous;
    int *address;
    uint64_t task;
    enum futex_waiter_state state;
};

struct futex_bucket {
    struct futex_waiter *head;
    struct futex_waiter *tail;
    uint8_t lock;
};

static uint8_t bootstrap_vm_arena[BOOTSTRAP_VM_ARENA_SIZE]
    __attribute__((aligned(PAGE_SIZE)));
static uint8_t bootstrap_pages[BOOTSTRAP_VM_ARENA_SIZE / PAGE_SIZE];
static bool runtime_vm_online;
static struct vm_block *runtime_vm_released;
static uint8_t vm_lock;
static struct futex_bucket futex_buckets[futex_bucket_count];

static void spin_lock(uint8_t *lock) {
    while (__atomic_test_and_set(lock, __ATOMIC_ACQUIRE))
        while (__atomic_load_n(lock, __ATOMIC_RELAXED))
            if (!fast_handoff_yield()) asm_pause();
}

static void spin_unlock(uint8_t *lock) { __atomic_clear(lock, __ATOMIC_RELEASE); }

static inline size_t align_up(size_t n) {
    return (n + PAGE_SIZE - 1) & ~(PAGE_SIZE - 1);
}

static int bootstrap_map(void **mapping, size_t size, int flags) {
    const size_t pages = size / PAGE_SIZE;
    const size_t capacity = sizeof(bootstrap_pages);
    if (pages > capacity) return -ENOMEM;
    size_t first = 0;
    if (flags & (MAP_FIXED | MAP_FIXED_NOREPLACE)) {
        const uintptr_t address = (uintptr_t)*mapping;
        if (address % PAGE_SIZE) return -EINVAL;
        if (__atomic_load_n(&runtime_vm_online, __ATOMIC_ACQUIRE)) return -ENOMEM;
        first = (address - (uintptr_t)bootstrap_vm_arena) / PAGE_SIZE;
        if (first > capacity - pages) return -ENOMEM;
        if ((flags & MAP_FIXED_NOREPLACE) && __builtin_memchr(bootstrap_pages + first, 1, pages))
            return -EEXIST;
    } else {
        while (first <= capacity - pages) {
            const uint8_t *available = __builtin_memchr(bootstrap_pages + first, 0, capacity - first);
            if (!available) return -ENOMEM;
            first = available - bootstrap_pages;
            if (first > capacity - pages) return -ENOMEM;
            const uint8_t *used = __builtin_memchr(available, 1, pages);
            if (!used) break;
            first = used - bootstrap_pages + 1;
        }
        if (first > capacity - pages) return -ENOMEM;
    }
    __builtin_memset(bootstrap_pages + first, 1, pages);
    *mapping = bootstrap_vm_arena + first * PAGE_SIZE;
    return 0;
}

void runtime_vm_enable(void) {
    __atomic_store_n(&runtime_vm_online, true, __ATOMIC_RELEASE);
}

void *runtime_vm_take_released(void) {
    if (!__atomic_load_n(&runtime_vm_released, __ATOMIC_ACQUIRE)) return NULL;
    spin_lock(&vm_lock);
    struct vm_block *block = runtime_vm_released;
    if (block) __atomic_store_n(&runtime_vm_released, block->next, __ATOMIC_RELEASE);
    spin_unlock(&vm_lock);
    return block;
}

static long futex_deadline(const struct timespec *time, uint64_t *deadline) {
    if (time->tv_sec < 0 || time->tv_nsec < 0 || time->tv_nsec >= (int64_t)NS_PER_SEC)
        return -EINVAL;

    const uint64_t sec = time->tv_sec;
    const uint64_t nsec = time->tv_nsec;
    const uint64_t timeout = sec > (UINT64_MAX - nsec) / NS_PER_SEC
        ? UINT64_MAX : sec * NS_PER_SEC + nsec;
    const uint64_t now = runtime_clock_nanos();
    *deadline = timeout > UINT64_MAX - now ? UINT64_MAX : now + timeout;
    return 0;
}

static struct futex_bucket *futex_bucket_for(const int *address) {
    const uintptr_t key = (uintptr_t)address / _Alignof(int);
    return &futex_buckets[
        ((uint64_t)key * UINT64_C(11400714819323198485)) >>
            (sizeof(uint64_t) * 8 - futex_bucket_bits)
    ];
}

static void futex_remove_locked(
    struct futex_bucket *bucket,
    struct futex_waiter *waiter
) {
    if (waiter->previous) waiter->previous->next = waiter->next;
    else bucket->head = waiter->next;
    if (waiter->next) waiter->next->previous = waiter->previous;
    else bucket->tail = waiter->previous;
    waiter->next = NULL;
    waiter->previous = NULL;
}

static long futex_wait(
    int *pointer,
    int expected,
    const struct timespec *time
) {
    uint64_t deadline = 0;
    if (time) {
        const long error = futex_deadline(time, &deadline);
        if (error) return error;
    }
    struct futex_bucket *bucket = futex_bucket_for(pointer);
    struct futex_waiter waiter = {
        .address = pointer,
        .state = futex_waiting,
    };
    uint64_t interrupt_flags = irq_save();
    spin_lock(&bucket->lock);
    if (__atomic_load_n(pointer, __ATOMIC_ACQUIRE) != expected) {
        spin_unlock(&bucket->lock);
        irq_restore(interrupt_flags);
        return -EAGAIN;
    }
    waiter.task = fast_handoff_current_task_handle();
    waiter.previous = bucket->tail;
    if (bucket->tail) bucket->tail->next = &waiter;
    else bucket->head = &waiter;
    bucket->tail = &waiter;
    spin_unlock(&bucket->lock);
    irq_restore(interrupt_flags);

    for (;;) {
        const uint64_t sequence = fast_handoff_prepare_park();
        interrupt_flags = irq_save();
        spin_lock(&bucket->lock);
        const enum futex_waiter_state state = __atomic_load_n(
            &waiter.state,
            __ATOMIC_ACQUIRE
        );
        if (state == futex_woken) {
            spin_unlock(&bucket->lock);
            irq_restore(interrupt_flags);
            return 0;
        }
        if (state == futex_waiting && time && runtime_clock_nanos() >= deadline) {
            futex_remove_locked(bucket, &waiter);
            spin_unlock(&bucket->lock);
            irq_restore(interrupt_flags);
            return -ETIMEDOUT;
        }
        spin_unlock(&bucket->lock);
        irq_restore(interrupt_flags);

        const bool waiting = state == futex_waiting && waiter.task;
        const bool parked = waiting && fast_handoff_park_current(deadline, sequence);
        if (!parked && !fast_handoff_yield()) asm_pause();
    }
}

static long futex_wake(int *pointer, int requested) {
    if (requested <= 0) return 0;

    struct futex_bucket *bucket = futex_bucket_for(pointer);
    struct futex_waiter *selected = NULL;
    struct futex_waiter **selected_tail = &selected;
    const uint64_t interrupt_flags = irq_save();
    spin_lock(&bucket->lock);
    long count = 0;
    for (struct futex_waiter *waiter = bucket->head, *next;
         waiter && count < requested; waiter = next) {
        next = waiter->next;
        if (waiter->address != pointer) continue;

        futex_remove_locked(bucket, waiter);
        __atomic_store_n(&waiter->state, futex_waking, __ATOMIC_RELEASE);
        *selected_tail = waiter;
        selected_tail = &waiter->next;
        count++;
    }
    spin_unlock(&bucket->lock);
    irq_restore(interrupt_flags);

    while (selected) {
        struct futex_waiter *next = selected->next;
        const uint64_t task = selected->task;
        if (task) fast_handoff_unpark(task);
        __atomic_store_n(&selected->state, futex_woken, __ATOMIC_RELEASE);
        selected = next;
    }
    return count;
}

static long futex_call(int *pointer, int operation, int expected, const struct timespec *time) {
    if (!pointer || (uintptr_t)pointer % _Alignof(int)) return -EINVAL;

    const unsigned int flags = (unsigned int)operation & ~futex_command_mask;
    if (flags & ~futex_private_flag) return -EINVAL;

    switch (operation & futex_command_mask) {
    case FUTEX_WAIT:
        return futex_wait(pointer, expected, time);
    case FUTEX_WAKE:
        return futex_wake(pointer, expected);
    default:
        return -ENOSYS;
    }
}

static long mmap_call(void *hint, size_t size, int prot, int flags, int fd, int64_t offset) {
    if (!size) return -EINVAL;
    if (size >= (size_t)__PTRDIFF_MAX__) return -ENOMEM;
    if (offset < 0 || ((uint64_t)offset & (PAGE_SIZE - 1)))
        return -EINVAL;
    (void)prot;

    const int map_type = flags & (MAP_SHARED | MAP_PRIVATE);
    if (map_type != MAP_SHARED && map_type != MAP_PRIVATE)
        return -EINVAL;

    if (!(flags & MAP_ANONYMOUS)) return fd < 0 ? -EBADF : -ENOSYS;

    size = align_up(size);

    const bool fixed = flags & (MAP_FIXED | MAP_FIXED_NOREPLACE);
    void *result = hint;
    if (!fixed && __atomic_load_n(&runtime_vm_online, __ATOMIC_ACQUIRE) && can_use_runtime()) {
        result = runtime_allocate(size);
        if (!result) return -ENOMEM;
    } else {
        spin_lock(&vm_lock);
        const int error = bootstrap_map(&result, size, flags);
        spin_unlock(&vm_lock);
        if (error) return error;
    }
    __builtin_memset(result, 0, size);
    return (long)result;
}

static long munmap_call(void *pointer, size_t size) {
    if (!pointer) return 0;
    const uintptr_t address = (uintptr_t)pointer;
    size = align_up(size);
    if (!size || address % PAGE_SIZE) return -EINVAL;
    const size_t offset = address - (uintptr_t)bootstrap_vm_arena;
    if (offset < sizeof(bootstrap_vm_arena)) {
        if (size > sizeof(bootstrap_vm_arena) - offset) return -EINVAL;
        spin_lock(&vm_lock);
        __builtin_memset(bootstrap_pages + offset / PAGE_SIZE, 0, size / PAGE_SIZE);
    } else {
        if (!__atomic_load_n(&runtime_vm_online, __ATOMIC_ACQUIRE)) return -EINVAL;
        struct vm_block *block = pointer;
        block->size = size;
        spin_lock(&vm_lock);
        block->next = runtime_vm_released;
        __atomic_store_n(&runtime_vm_released, block, __ATOMIC_RELEASE);
    }
    spin_unlock(&vm_lock);
    return 0;
}

static long write_call(int fd, const void *buffer, size_t count) {
    if (fd < 0) return -EBADF;
    if (fd <= 2 && can_use_runtime()) serial_print(buffer, count);
    return (long)count;
}

static long arch_prctl_call(int code, uint64_t pointer) {
    if (code != ARCH_SET_FS) return -EINVAL;
    wrmsr(ia32_fs_base_msr, pointer);
    set_kernel_runtime_fs_base(pointer);
    return 0;
}

static long clock_gettime_call(int clock_id, struct timespec *tp) {
    if (!tp) return -EINVAL;
    if (clock_id != CLOCK_REALTIME && clock_id != CLOCK_MONOTONIC &&
        clock_id != CLOCK_MONOTONIC_RAW && clock_id != CLOCK_REALTIME_COARSE &&
        clock_id != CLOCK_MONOTONIC_COARSE && clock_id != CLOCK_BOOTTIME)
        return -EINVAL;

    const uint64_t now = runtime_clock_nanos();
    tp->tv_sec = (int64_t)(now / NS_PER_SEC);
    tp->tv_nsec = (int64_t)(now % NS_PER_SEC);
    return 0;
}

#define ARG(type) va_arg(args, type)
#define SKIP_ARG(type) (void)va_arg(args, type)

long syscall(long number, ...) {
    va_list args;
    va_start(args, number);
    long ret = -ENOSYS;

    switch (number) {
    case SYS_write: {
        int fd = ARG(int);
        const void *buffer = ARG(const void *);
        size_t count = ARG(size_t);
        ret = write_call(fd, buffer, count);
        break;
    }
    case SYS_exit:
        set_runtime_use_mask(false);
        fast_handoff_exit_current();
    case SYS_exit_group:
        for (;;)
            __asm__ volatile("hlt");
    case SYS_clone: {
        SKIP_ARG(uint64_t);
        void *stack = ARG(void *);
        int *parent_tid = ARG(int *);
        SKIP_ARG(void *);
        void *tls = ARG(void *);
        ret = runtime_thread_prepare(stack, parent_tid, tls);
        break;
    }
    case SYS_arch_prctl: {
        int code = ARG(int);
        uint64_t pointer = (uint64_t)ARG(void *);
        ret = arch_prctl_call(code, pointer);
        break;
    }
    case SYS_sched_yield:
        fast_handoff_runtime_yield();
        ret = 0;
        break;
    case SYS_gettid:
        ret = runtime_thread_id();
        break;
    case SYS_futex: {
        int *futex_ptr = ARG(int *);
        int operation = ARG(int);
        int expected = ARG(int);
        const struct timespec *time = ARG(const struct timespec *);
        ret = futex_call(futex_ptr, operation, expected, time);
        break;
    }
    case SYS_mmap: {
        void *hint = ARG(void *);
        size_t size = ARG(size_t);
        int prot = ARG(int);
        int flags = ARG(int);
        int fd = ARG(int);
        int64_t offset = ARG(int64_t);
        ret = mmap_call(hint, size, prot, flags, fd, offset);
        break;
    }
    case SYS_munmap: {
        void *pointer = ARG(void *);
        ret = munmap_call(pointer, ARG(size_t));
        break;
    }
    case SYS_clock_gettime: {
        int clock_id = ARG(int);
        ret = clock_gettime_call(clock_id, ARG(struct timespec *));
        break;
    }
    }

    va_end(args);
    return ret;
}

#undef ARG
#undef SKIP_ARG
