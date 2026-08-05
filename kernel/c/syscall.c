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

#define PAGE_SIZE 0x1000u
#define BOOTSTRAP_VM_ARENA_SIZE (32u * 1024u * 1024u)
#define VM_MAX_REGIONS 8u
#define MAP_SHARED 0x01
#define MAP_PRIVATE 0x02
#define MAP_FIXED 0x10
#define MAP_ANONYMOUS 0x20
#define MAP_FIXED_NOREPLACE 0x100000
#define NS_PER_SEC ((uint64_t)1000000000)
#define FEMTOSECONDS_PER_NANOSECOND ((uint64_t)1000000)
#define HPET_MAIN_COUNTER_OFFSET 0xf0u
#define CLOCK_REALTIME 0
#define CLOCK_MONOTONIC 1
#define CLOCK_MONOTONIC_RAW 4
#define CLOCK_REALTIME_COARSE 5
#define CLOCK_MONOTONIC_COARSE 6
#define CLOCK_BOOTTIME 7

struct timespec_arg {
    int64_t tv_sec;
    int64_t tv_nsec;
};

struct vm_block {
    size_t size;
    struct vm_block *next;
};

struct vm_region {
    uint8_t *base;
    size_t size;
    size_t bump;
};

static uint8_t bootstrap_vm_arena[BOOTSTRAP_VM_ARENA_SIZE]
    __attribute__((aligned(PAGE_SIZE)));
static struct vm_region vm_regions[VM_MAX_REGIONS] = {
    {bootstrap_vm_arena, sizeof(bootstrap_vm_arena), 0}
};
static size_t vm_region_count = 1;
static struct vm_block *vm_free_list;
static uint8_t vm_lock;
static uint64_t futex_epoch;
static uint64_t futex_waiter_count;
static volatile uint64_t *runtime_hpet_counter;
static uint64_t runtime_hpet_period_femtoseconds;
static uint64_t runtime_clock_offset_ns;
static uint64_t runtime_clock_last_ns;

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

static struct vm_region *vm_find_region(uintptr_t address, size_t size) {
    for (size_t i = 0; i < vm_region_count; i++) {
        struct vm_region *region = &vm_regions[i];
        const uintptr_t start = (uintptr_t)region->base;
        if (size <= region->size && address >= start &&
            address - start <= region->size - size)
            return region;
    }
    return NULL;
}

static void *vm_alloc_locked(size_t size) {
    for (struct vm_block **link = &vm_free_list; *link; link = &(*link)->next) {
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

    for (size_t i = vm_region_count; i > 0; i--) {
        struct vm_region *region = &vm_regions[i - 1];
        if (size > region->size - region->bump) continue;

        void *result = region->base + region->bump;
        region->bump += size;
        return result;
    }
    return NULL;
}

static void vm_free_locked(void *pointer, size_t size) {
    struct vm_block *block = (struct vm_block *)pointer;
    struct vm_block *previous = NULL;
    struct vm_block **link = &vm_free_list;

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

bool runtime_vm_add_region(void *base, size_t size) {
    const uintptr_t address = (uintptr_t)base;
    if (!base || !size || (address & (PAGE_SIZE - 1)) || (size & (PAGE_SIZE - 1)))
        return false;
    if (size > UINTPTR_MAX - address) return false;
    const uintptr_t region_end = address + size;

    spin_lock(&vm_lock);
    if (vm_region_count == VM_MAX_REGIONS) {
        spin_unlock(&vm_lock);
        return false;
    }

    for (size_t i = 0; i < vm_region_count; i++) {
        const uintptr_t start = (uintptr_t)vm_regions[i].base;
        const uintptr_t end = start + vm_regions[i].size;
        if (address < end && start < region_end) {
            spin_unlock(&vm_lock);
            return false;
        }
    }

    vm_regions[vm_region_count++] = (struct vm_region){base, size, 0};
    spin_unlock(&vm_lock);
    return true;
}

static uint64_t hpet_ticks_to_ns(uint64_t ticks, uint64_t period_femtoseconds) {
    const uint64_t whole = ticks / FEMTOSECONDS_PER_NANOSECOND;
    const uint64_t remainder = ticks % FEMTOSECONDS_PER_NANOSECOND;
    if (whole > UINT64_MAX / period_femtoseconds) return UINT64_MAX;

    const uint64_t whole_ns = whole * period_femtoseconds;
    const uint64_t remainder_ns = remainder * period_femtoseconds /
        FEMTOSECONDS_PER_NANOSECOND;
    if (remainder_ns > UINT64_MAX - whole_ns) return UINT64_MAX;
    return whole_ns + remainder_ns;
}

static uint64_t publish_clock(uint64_t candidate) {
    uint64_t previous = __atomic_load_n(&runtime_clock_last_ns, __ATOMIC_ACQUIRE);
    while (candidate > previous) {
        if (__atomic_compare_exchange_n(&runtime_clock_last_ns, &previous, candidate,
                false, __ATOMIC_RELEASE, __ATOMIC_ACQUIRE))
            return candidate;
    }
    return previous;
}

static uint64_t clock_now_ns(void) {
    const uint64_t period = __atomic_load_n(
        &runtime_hpet_period_femtoseconds, __ATOMIC_ACQUIRE);
    volatile uint64_t *counter = runtime_hpet_counter;
    if (period && counter) {
        const uint64_t raw = hpet_ticks_to_ns(*counter, period);
        const uint64_t offset = runtime_clock_offset_ns;
        return publish_clock(raw > UINT64_MAX - offset ? UINT64_MAX : raw + offset);
    }

    return __atomic_add_fetch(&runtime_clock_last_ns, 1000u, __ATOMIC_ACQ_REL);
}

void runtime_clock_configure_hpet(void *base, uint64_t period_femtoseconds) {
    if (!base || !period_femtoseconds) return;

    volatile uint64_t *counter = (volatile uint64_t *)
        ((uintptr_t)base + HPET_MAIN_COUNTER_OFFSET);
    const uint64_t raw = hpet_ticks_to_ns(*counter, period_femtoseconds);
    const uint64_t previous = __atomic_load_n(&runtime_clock_last_ns, __ATOMIC_ACQUIRE);

    runtime_hpet_counter = counter;
    runtime_clock_offset_ns = previous > raw ? previous - raw : 0;
    __atomic_store_n(&runtime_hpet_period_femtoseconds,
        period_femtoseconds, __ATOMIC_RELEASE);
}

static inline bool interrupts_enabled(void) {
    uint64_t flags;
    __asm__ volatile("pushfq; popq %0" : "=r"(flags) : : "memory");
    return (flags & (1u << 9)) != 0;
}

static void wait_for_event(void) {
    if (interrupts_enabled()) {
        __asm__ volatile("hlt" : : : "memory");
    } else {
        for (unsigned int i = 0; i < 64; i++) cpu_relax();
    }
}

static long futex_deadline(const struct timespec_arg *time, uint64_t *deadline) {
    if (time->tv_sec < 0 || time->tv_nsec < 0 || time->tv_nsec >= (int64_t)NS_PER_SEC)
        return -EINVAL;
    if (time->tv_sec == 0 && time->tv_nsec == 0)
        return -ETIMEDOUT;

    const uint64_t sec = time->tv_sec;
    const uint64_t nsec = time->tv_nsec;
    const uint64_t timeout = sec > (UINT64_MAX - nsec) / NS_PER_SEC
        ? UINT64_MAX : sec * NS_PER_SEC + nsec;
    const uint64_t now = clock_now_ns();
    *deadline = timeout > UINT64_MAX - now ? UINT64_MAX : now + timeout;
    return 0;
}

static long futex_call(int *pointer, int operation, int expected, const struct timespec_arg *time) {
    if (!pointer)
        return -EINVAL;

    const int command = operation & 0x7f;
    if (command == FUTEX_WAKE) {
        __atomic_add_fetch(&futex_epoch, 1u, __ATOMIC_RELEASE);
        const uint64_t waiters = __atomic_load_n(&futex_waiter_count, __ATOMIC_ACQUIRE);
        const uint64_t requested = expected > 0 ? (uint64_t)expected : 0;
        return (long)(waiters < requested ? waiters : requested);
    }
    if (command != FUTEX_WAIT) return -ENOSYS;
    if (__atomic_load_n(pointer, __ATOMIC_ACQUIRE) != expected)
        return -EAGAIN;

    uint64_t deadline = 0;
    if (time) {
        const long error = futex_deadline(time, &deadline);
        if (error) return error;
    }

    const uint64_t observed_epoch = __atomic_load_n(&futex_epoch, __ATOMIC_ACQUIRE);
    __atomic_add_fetch(&futex_waiter_count, 1u, __ATOMIC_ACQ_REL);
    while (__atomic_load_n(pointer, __ATOMIC_ACQUIRE) == expected) {
        if (__atomic_load_n(&futex_epoch, __ATOMIC_ACQUIRE) != observed_epoch) {
            __atomic_sub_fetch(&futex_waiter_count, 1u, __ATOMIC_ACQ_REL);
            return 0;
        }
        if (time && clock_now_ns() >= deadline) {
            __atomic_sub_fetch(&futex_waiter_count, 1u, __ATOMIC_ACQ_REL);
            return -ETIMEDOUT;
        }
        wait_for_event();
    }

    __atomic_sub_fetch(&futex_waiter_count, 1u, __ATOMIC_ACQ_REL);
    return 0;
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

    void *result = NULL;
    long err = 0;

    spin_lock(&vm_lock);

    if (!fixed) {
        result = vm_alloc_locked(size);
        if (!result) {
            err = -ENOMEM;
            goto unlock;
        }
    } else {
        const uintptr_t address = (uintptr_t)hint;
        if (address & (PAGE_SIZE - 1)) {
            err = -EINVAL;
            goto unlock;
        }

        struct vm_region *region = vm_find_region(address, size);
        if (!region) {
            err = -ENOMEM;
            goto unlock;
        }
        const size_t region_offset = address - (uintptr_t)region->base;
        if (fixed_noreplace && region_offset < region->bump) {
            err = -EEXIST;
            goto unlock;
        }

        const size_t map_end = region_offset + size;
        if (map_end > region->bump)
            region->bump = map_end;

        result = (void *)address;
    }

unlock:
    spin_unlock(&vm_lock);
    if (err) return err;
    __builtin_memset(result, 0, size);
    return (long)(uintptr_t)result;
}

static long munmap_call(void *pointer, size_t size) {
    if (!pointer) return 0;
    if (!(size = align_up(size))) return -EINVAL;

    const uintptr_t address = (uintptr_t)pointer;

    if (!vm_find_region(address, size))
        return -EINVAL;
    if (address & (PAGE_SIZE - 1))
        return -EINVAL;

    spin_lock(&vm_lock);
    vm_free_locked(pointer, size);
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
    *parent_tid = 2;
    return 2;
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

    const uint64_t now = clock_now_ns();
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
    case SYS_rt_sigaction: ret = 0; break;
    }

    va_end(args);
    return ret;
}

#undef ARG
#undef SKIP_ARG
