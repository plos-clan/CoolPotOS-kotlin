#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "syscall.h"

extern void capture_sys_clone_context(uint64_t stack, uint64_t entry);

#define EAGAIN 11
#define EBADF 9
#define EEXIST 17
#define EINVAL 22
#define ENOMEM 12
#define ENOSYS 38
#define ETIMEDOUT 110

#define PAGE_SIZE 0x1000u
#define VM_ARENA_SIZE (64u * 1024u * 1024u)
#define MAP_SHARED 0x01
#define MAP_PRIVATE 0x02
#define MAP_FIXED 0x10
#define MAP_ANONYMOUS 0x20
#define MAP_FIXED_NOREPLACE 0x100000
#define IA32_FS_BASE 0xc0000100
#define NS_PER_SEC ((uint64_t)1000000000)
#define FUTEX_SPIN_SLICE ((uint64_t)64)

struct timespec_arg {
    int64_t tv_sec;
    int64_t tv_nsec;
};

struct vm_block {
    size_t size;
    struct vm_block *next;
};

static uint8_t vm_arena[VM_ARENA_SIZE] __attribute__((aligned(PAGE_SIZE)));
static size_t vm_bump;
static struct vm_block *vm_free_list;
static int vm_lock;

static void wrmsr(uint32_t msr, uint64_t value) {
    uint32_t eax = (uint32_t)value;
    uint32_t edx = (uint32_t)(value >> 32);
    __asm__ volatile("wrmsr" : : "c"(msr), "a"(eax), "d"(edx));
}

static void cpu_relax(void) {
    __asm__ volatile("pause" : : : "memory");
}

static void spin_lock(int *lock) {
    while(__atomic_test_and_set(lock, __ATOMIC_ACQUIRE)) {
        while(__atomic_load_n(lock, __ATOMIC_RELAXED)) {
            cpu_relax();
        }
    }
}

static void spin_unlock(int *lock) {
    __atomic_clear(lock, __ATOMIC_RELEASE);
}

static inline size_t align_up(size_t n) {
    return (n + PAGE_SIZE - 1) & ~(PAGE_SIZE - 1);
}

static void *vm_alloc_locked(size_t size) {
    for(struct vm_block **link = &vm_free_list; *link; link = &(*link)->next) {
        struct vm_block *block = *link;
        if(block->size < size)
            continue;

        size_t remaining = block->size - size;
        struct vm_block *next = block->next;
        if(remaining >= PAGE_SIZE) {
            next = (struct vm_block *)((uint64_t)block + size);
            next->size = remaining;
            next->next = block->next;
        }
        *link = next;
        return block;
    }

    if(vm_bump > VM_ARENA_SIZE - size)
        return NULL;

    void *result = vm_arena + vm_bump;
    vm_bump += size;
    return result;
}

static void vm_free_locked(void *pointer, size_t size) {
    struct vm_block *block = (struct vm_block *)pointer;
    struct vm_block *previous = NULL;
    struct vm_block **link = &vm_free_list;

    while(*link && *link < block) {
        previous = *link;
        link = &(*link)->next;
    }

    block->size = size;
    block->next = *link;
    *link = block;

    if(block->next && (uint64_t)block + block->size == (uint64_t)block->next) {
        block->size += block->next->size;
        block->next = block->next->next;
    }

    if(previous && (uint64_t)previous + previous->size == (uint64_t)block) {
        previous->size += block->size;
        previous->next = block->next;
    }
}

static long futex_budget(const struct timespec_arg *time, uint64_t *budget) {
    if(time->tv_nsec < 0 || time->tv_nsec >= (int64_t)NS_PER_SEC)
        return -EINVAL;
    if(time->tv_sec == 0 && time->tv_nsec == 0)
        return -ETIMEDOUT;

    uint64_t sec = (uint64_t)time->tv_sec;
    uint64_t timeout_ns = UINT64_MAX;
    if(sec <= UINT64_MAX / NS_PER_SEC) {
        uint64_t nsec = (uint64_t)time->tv_nsec;
        timeout_ns = sec * NS_PER_SEC;
        if(nsec > UINT64_MAX - timeout_ns)
            timeout_ns = UINT64_MAX;
        else
            timeout_ns += nsec;
    }

    *budget = timeout_ns / FUTEX_SPIN_SLICE;
    if(*budget != UINT64_MAX) (*budget)++;
    return 0;
}

static long futex_call(int *pointer, int operation, int expected, const struct timespec_arg *time) {
    if(!pointer)
        return -EINVAL;

    int command = operation & 0x7f;
    if(command == FUTEX_WAKE) return 0;
    if(command != FUTEX_WAIT) return -ENOSYS;
    if(__atomic_load_n(pointer, __ATOMIC_ACQUIRE) != expected)
        return -EAGAIN;

    uint64_t spin_budget = 0;
    if(time) {
        long e = futex_budget(time, &spin_budget);
        if(e)
            return e;
    }

    while(__atomic_load_n(pointer, __ATOMIC_ACQUIRE) == expected) {
        cpu_relax();
        if(time) {
            if(!spin_budget) return -ETIMEDOUT;
            spin_budget--;
        }
    }

    return 0;
}

static long mmap_call(void *hint, size_t size, int prot, int flags, int fd, int64_t offset) {
    if(!size) return -EINVAL;
    if(size >= (size_t)__PTRDIFF_MAX__) return -ENOMEM;
    if(offset < 0 || ((uint64_t)offset & (PAGE_SIZE - 1)))
        return -EINVAL;

    int map_type = flags & (MAP_SHARED | MAP_PRIVATE);
    if(map_type != MAP_SHARED && map_type != MAP_PRIVATE)
        return -EINVAL;

    bool fixed_noreplace = (flags & MAP_FIXED_NOREPLACE) != 0;
    bool fixed = (flags & MAP_FIXED) || fixed_noreplace;
    if(!(flags & MAP_ANONYMOUS)) {
        if(fd < 0) return -EBADF;
        return -ENOSYS;
    }

    (void)prot;
    size = align_up(size);
    if(!size) return -ENOMEM;

    void *result = NULL;
    long err = 0;
    uint64_t arena_base = (uint64_t)vm_arena;

    spin_lock(&vm_lock);

    if(!fixed) {
        result = vm_alloc_locked(size);
        if(!result) {
            err = -ENOMEM;
            goto unlock;
        }
    } else {
        uint64_t address = (uint64_t)hint;
        if(address & (PAGE_SIZE - 1)) {
            err = -EINVAL;
            goto unlock;
        }

        size_t arena_offset = (size_t)(address - arena_base);
        if(arena_offset > VM_ARENA_SIZE - size) {
            err = -ENOMEM;
            goto unlock;
        }
        if(fixed_noreplace && arena_offset < vm_bump) {
            err = -EEXIST;
            goto unlock;
        }

        size_t map_end = arena_offset + size;
        if(map_end > vm_bump)
            vm_bump = map_end;

        result = (void *)address;
    }

unlock:
    spin_unlock(&vm_lock);
    if(err) return err;
    __builtin_memset(result, 0, size);
    return (long)(uint64_t)result;
}

static long munmap_call(void *pointer, size_t size) {
    if(!pointer) return 0;
    if(!(size = align_up(size))) return -EINVAL;

    uint64_t start = (uint64_t)vm_arena;
    uint64_t end = start + VM_ARENA_SIZE;
    uint64_t address = (uint64_t)pointer;

    if(address < start || address > end || size > end - address)
        return -EINVAL;
    if(address & (PAGE_SIZE - 1))
        return -EINVAL;

    spin_lock(&vm_lock);
    vm_free_locked(pointer, size);
    spin_unlock(&vm_lock);
    return 0;
}

#define ARG(type) va_arg(args, type)
#define SKIP_ARG(type) (void)va_arg(args, type)

long syscall(long number, ...) {
    va_list args;
    va_start(args, number);
    long ret = -ENOSYS;

    switch(number) {
    case SYS_write: {
        int fd = ARG(int);
        const void* buffer = ARG(const void *);
        size_t count = ARG(size_t);
        ret = fd < 0 ? -EBADF : (long)count;
        if(fd < 2) {
            extern void serial_print(const char *buffer, size_t size);
            serial_print(buffer,count);
        }
        break;
    }
    case SYS_exit:
    case SYS_exit_group:
        for(;;) __asm__ volatile("hlt");
    case SYS_clone: {
        SKIP_ARG(uint64_t);
        void *stack = ARG(void *);
        int *parent_tid = ARG(int *);
        if(stack) {
            uint64_t stack_addr = (uint64_t)(uintptr_t)stack;
            uint64_t entry_addr = *(const uint64_t *)(uintptr_t)stack_addr;
            capture_sys_clone_context(stack_addr, entry_addr);
        }

        if(stack && parent_tid) {
            *parent_tid = 2;
            ret = 2;
        } else {
            ret = -EINVAL;
        }
        break;
    }
    case SYS_arch_prctl: {
        int code = ARG(int);
        uint64_t pointer = (uint64_t)ARG(void *);
#if defined(__x86_64__)
        if(code == ARCH_SET_FS) {
            wrmsr(IA32_FS_BASE, pointer);
            ret = 0;
        } else {
            ret = -EINVAL;
        }
#endif
        break;
    }
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
        ret = munmap_call(ARG(void *), ARG(size_t));
        break;
    }
    case SYS_clock_gettime: {
        SKIP_ARG(int);
        struct timespec_arg *tp = ARG(struct timespec_arg *);
        if(tp) {
            tp->tv_sec = tp->tv_nsec = 0;
            ret = 0;
        } else {
            ret = -EINVAL;
        }
        break;
    }
    case SYS_rt_sigaction:
        ret = 0;
        break;
    }

    va_end(args);
    return ret;
}

#undef ARG
#undef SKIP_ARG
