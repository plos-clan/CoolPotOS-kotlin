#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "native.h"
#include "syscall.h"

#define EAGAIN 11
#define EBADF 9
#define EEXIST 17
#define EINVAL 22
#define ENOMEM 12
#define ENOSYS 38
#define ETIMEDOUT 110
#define SYS_gettid 186

#define PAGE_SIZE 0x1000u
#define BOOTSTRAP_VM_ARENA_SIZE (32u * 1024u * 1024u)
#define MAP_SHARED 0x01
#define MAP_PRIVATE 0x02
#define MAP_FIXED 0x10
#define MAP_ANONYMOUS 0x20
#define MAP_FIXED_NOREPLACE 0x100000
#define NS_PER_SEC ((uint64_t)1000000000)
#define CLOCK_REALTIME 0
#define CLOCK_MONOTONIC 1
#define CLOCK_MONOTONIC_RAW 4
#define CLOCK_REALTIME_COARSE 5
#define CLOCK_MONOTONIC_COARSE 6
#define CLOCK_BOOTTIME 7

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

struct timespec_arg {
    int64_t tv_sec;
    int64_t tv_nsec;
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

typedef void *(*vm_allocate_fn)(size_t);

static uint8_t bootstrap_vm_arena[BOOTSTRAP_VM_ARENA_SIZE]
    __attribute__((aligned(PAGE_SIZE)));
static size_t bootstrap_vm_used;
static struct vm_block *bootstrap_vm_free_list;
static vm_allocate_fn runtime_vm_allocate;
static struct vm_block *runtime_vm_released;
static uint8_t vm_lock;
static struct futex_bucket futex_buckets[futex_bucket_count];
void cpu_relax(void) { __asm__ volatile("pause" : : : "memory"); }

static void spin_lock(uint8_t *lock) {
    while (__atomic_test_and_set(lock, __ATOMIC_ACQUIRE))
        while (__atomic_load_n(lock, __ATOMIC_RELAXED))
            cpu_relax();
}

static void spin_unlock(uint8_t *lock) { __atomic_clear(lock, __ATOMIC_RELEASE); }

static inline size_t align_up(size_t n) {
    return (n + PAGE_SIZE - 1) & ~(PAGE_SIZE - 1);
}

static bool bootstrap_vm_contains(uintptr_t address, size_t size) {
    const uintptr_t start = (uintptr_t)bootstrap_vm_arena;
    return size <= sizeof(bootstrap_vm_arena) && address >= start &&
        address - start <= sizeof(bootstrap_vm_arena) - size;
}

static void *bootstrap_vm_alloc_locked(size_t size) {
    for (struct vm_block **link = &bootstrap_vm_free_list; *link; link = &(*link)->next) {
        struct vm_block *block = *link;
        if (block->size < size) continue;

        const size_t remaining = block->size - size;
        struct vm_block *next = block->next;
        if (remaining >= PAGE_SIZE) {
            next = (struct vm_block *)((uintptr_t)block + size);
            next->size = remaining;
            next->next = block->next;
        }
        *link = next;
        return block;
    }

    if (size > sizeof(bootstrap_vm_arena) - bootstrap_vm_used) return NULL;
    void *result = bootstrap_vm_arena + bootstrap_vm_used;
    bootstrap_vm_used += size;
    return result;
}

static void bootstrap_vm_free_locked(void *pointer, size_t size) {
    struct vm_block *block = (struct vm_block *)pointer;
    struct vm_block *previous = NULL;
    struct vm_block **link = &bootstrap_vm_free_list;

    while (*link && (uintptr_t)*link < (uintptr_t)block) {
        previous = *link;
        link = &(*link)->next;
    }

    block->size = size;
    block->next = *link;
    *link = block;

    if (block->next && (uintptr_t)block + block->size == (uintptr_t)block->next) {
        block->size += block->next->size;
        block->next = block->next->next;
    }

    if (previous && (uintptr_t)previous + previous->size == (uintptr_t)block) {
        previous->size += block->size;
        previous->next = block->next;
    }
}

bool runtime_vm_install(vm_allocate_fn allocate) {
    if (!allocate) return false;

    spin_lock(&vm_lock);
    if (runtime_vm_allocate) {
        spin_unlock(&vm_lock);
        return false;
    }
    runtime_vm_allocate = allocate;
    spin_unlock(&vm_lock);
    return true;
}

void *runtime_vm_take_released(void) {
    spin_lock(&vm_lock);
    struct vm_block *block = runtime_vm_released;
    if (block) runtime_vm_released = block->next;
    spin_unlock(&vm_lock);
    return block;
}

static inline bool interrupts_enabled(void) {
    uint64_t flags;
    __asm__ volatile("pushfq; popq %0" : "=r"(flags) : : "memory");
    return (flags & (1u << 9)) != 0;
}

static void wait_for_event(void) {
    const bool switched = fast_handoff_yield();
    if (!switched && interrupts_enabled()) {
        __asm__ volatile("cli; sti; hlt" : : : "memory");
    } else if (!switched) {
        for (unsigned int i = 0; i < 64; i++) cpu_relax();
    }
}

static long futex_deadline(const struct timespec_arg *time, uint64_t *deadline) {
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
    const struct timespec_arg *time
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
        const bool parked = waiter.task && (time
            ? fast_handoff_park_current_until(deadline)
            : fast_handoff_park_current());

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

        if (state == futex_waking) {
            if (waiter.task) fast_handoff_park_current();
            else cpu_relax();
        } else if (!parked) {
            cpu_relax();
        }
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
        __atomic_store_n(&selected->state, futex_woken, __ATOMIC_RELEASE);
        if (task) fast_handoff_unpark(task);
        selected = next;
    }
    return count;
}

static long futex_call(int *pointer, int operation, int expected, const struct timespec_arg *time) {
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

    const bool fixed_noreplace = (flags & MAP_FIXED_NOREPLACE) != 0;
    const bool fixed = (flags & MAP_FIXED) || fixed_noreplace;
    if (!(flags & MAP_ANONYMOUS)) {
        if (fd < 0) return -EBADF;
        return -ENOSYS;
    }

    size = align_up(size);
    if (!size) return -ENOMEM;

    void *result;
    if (!fixed) {
        spin_lock(&vm_lock);
        vm_allocate_fn allocate = runtime_vm_allocate;
        result = allocate ? NULL : bootstrap_vm_alloc_locked(size);
        spin_unlock(&vm_lock);
        if (allocate) result = allocate(size);
    } else {
        const uintptr_t address = (uintptr_t)hint;
        if (address & (PAGE_SIZE - 1)) return -EINVAL;

        spin_lock(&vm_lock);
        if (runtime_vm_allocate || !bootstrap_vm_contains(address, size)) {
            spin_unlock(&vm_lock);
            return -ENOMEM;
        }
        const size_t region_offset = address - (uintptr_t)bootstrap_vm_arena;
        if (fixed_noreplace && region_offset < bootstrap_vm_used) {
            spin_unlock(&vm_lock);
            return -EEXIST;
        }
        const size_t map_end = region_offset + size;
        if (map_end > bootstrap_vm_used) bootstrap_vm_used = map_end;
        result = (void *)address;
        spin_unlock(&vm_lock);
    }

    if (!result) return -ENOMEM;
    __builtin_memset(result, 0, size);
    return (long)(uintptr_t)result;
}

static long munmap_call(void *pointer, size_t size) {
    if (!pointer) return 0;
    if (!(size = align_up(size))) return -EINVAL;

    const uintptr_t address = (uintptr_t)pointer;

    if (address & (PAGE_SIZE - 1)) return -EINVAL;

    spin_lock(&vm_lock);
    if (bootstrap_vm_contains(address, size)) {
        bootstrap_vm_free_locked(pointer, size);
        spin_unlock(&vm_lock);
        return 0;
    }
    if (!runtime_vm_allocate) {
        spin_unlock(&vm_lock);
        return -EINVAL;
    }
    struct vm_block *block = (struct vm_block *)pointer;
    block->size = size;
    block->next = runtime_vm_released;
    runtime_vm_released = block;
    spin_unlock(&vm_lock);
    return 0;
}

static long write_call(int fd, const void *buffer, size_t count) {
    if (fd < 0) return -EBADF;
    if (fd <= 2) serial_print(buffer, count);
    return (long)count;
}

static long clone_call(void *stack, int *parent_tid, void *tls) {
    if (!stack || !parent_tid) return -EINVAL;

    if (!capture_sys_clone_context((uintptr_t)stack, (uintptr_t)tls))
        return -ENOMEM;
    const uint64_t tid = allocate_runtime_tid();
    if (tid > INT32_MAX) return -EAGAIN;
    *parent_tid = (int)tid;
    return (long)tid;
}

static long gettid_call(void) {
    int tid;
    __asm__ volatile("movl %%fs:24, %0" : "=r"(tid));
    return tid;
}

static long arch_prctl_call(int code, uint64_t pointer) {
    if (code != ARCH_SET_FS) return -EINVAL;
    wrmsr(ia32_fs_base_msr, pointer);
    set_kernel_runtime_fs_base(pointer);
    return 0;
}

static long clock_gettime_call(int clock_id, struct timespec_arg *tp) {
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
    case SYS_exit_group:
        for (;;)
            __asm__ volatile("hlt");
    case SYS_clone: {
        SKIP_ARG(uint64_t);
        void *stack = ARG(void *);
        int *parent_tid = ARG(int *);
        SKIP_ARG(void *);
        void *tls = ARG(void *);
        ret = clone_call(stack, parent_tid, tls);
        break;
    }
    case SYS_arch_prctl: {
        int code = ARG(int);
        uint64_t pointer = (uint64_t)ARG(void *);
        ret = arch_prctl_call(code, pointer);
        break;
    }
    case SYS_sched_yield:
        wait_for_event();
        ret = 0;
        break;
    case SYS_gettid:
        ret = gettid_call();
        break;
    case SYS_futex: {
        int *futex_ptr = ARG(int *);
        int operation = ARG(int);
        int expected = ARG(int);
        const struct timespec_arg *time = ARG(const struct timespec_arg *);
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
        ret = clock_gettime_call(clock_id, ARG(struct timespec_arg *));
        break;
    }
    }

    va_end(args);
    return ret;
}

#undef ARG
#undef SKIP_ARG
