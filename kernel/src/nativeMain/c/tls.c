#include "bridge.h"
#include "native.h"
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include "generated/runtime-layout/runtime_layout.h"

static _Thread_local bool runtime_enabled;
static bool runtime_access_ready;
static uint64_t next_runtime_tid = 2;

#define TCB(pointer, type, field) (*(type *)((uintptr_t)(pointer) + Tcb_##field))

runtime_tls_t *runtime_tls_owner(uintptr_t tcb) {
    return (runtime_tls_t *)(tcb + Tcb_SIZE);
}

void *__wrap___rtld_allocateTcb(void) {
    extern const uint8_t __kernel_tls_start[], __kernel_tls_file_size[], __kernel_tls_size[], __kernel_tls_align[];
    const uint64_t tid = __atomic_fetch_add(&next_runtime_tid, 1, __ATOMIC_RELAXED);
    if (tid > INT32_MAX / 2) return NULL;
    uintptr_t align = (uintptr_t)__kernel_tls_align > Tcb_ALIGN ? (uintptr_t)__kernel_tls_align : Tcb_ALIGN;
    if (align < LocalKeys_ALIGN) align = LocalKeys_ALIGN;
    const uintptr_t tls = ((uintptr_t)__kernel_tls_size + align - 1) & ~(align - 1);
    const uintptr_t keys = (Tcb_SIZE + sizeof(runtime_tls_t) + LocalKeys_ALIGN - 1) & ~(LocalKeys_ALIGN - 1);
    const uintptr_t dtv = keys + LocalKeys_SIZE;
    void *allocation = calloc(1, tls + align - 1 + dtv + sizeof(uintptr_t));
    if (!allocation) return NULL;
    const uintptr_t tcb = ((uintptr_t)allocation + tls + align - 1) & ~(align - 1);
    __builtin_memcpy((void *)(tcb - tls), __kernel_tls_start, (uintptr_t)__kernel_tls_file_size);
    TCB(tcb, uintptr_t, selfPointer) = tcb;
    TCB(tcb, int, tid) = (int)tid;
    TCB(tcb, uintptr_t, dtvSize) = tls != 0;
    TCB(tcb, uintptr_t, dtvPointers) = tcb + dtv;
    *(uintptr_t *)(tcb + dtv) = tcb - tls;
    TCB(tcb, uintptr_t, localKeys) = tcb + keys;
    uintptr_t current;
    __asm__ volatile("movq %%fs:0, %0" : "=r"(current));
    TCB(tcb, uintptr_t, stackCanary) = TCB(current, uintptr_t, stackCanary);
    runtime_tls_owner(tcb)->allocation = (uintptr_t)allocation;
    return (void *)tcb;
}

int __real_pthread_create(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *);
int __real_pthread_join(pthread_t, void **);
int __real_pthread_detach(pthread_t);

long runtime_thread_prepare(void *stack, int *parent_tid, void *tls) {
    if (!stack || !parent_tid || !tls) return -EINVAL;
    const int tid = TCB(tls, int, tid);
    if (tid <= 0) return -EAGAIN;
    *parent_tid = tid;
    const bool use_runtime = can_use_runtime();
    if (use_runtime) set_runtime_use_mask(false);
    const bool started = fast_handoff_prepare_runtime((uintptr_t)stack, (uintptr_t)tls);
    if (use_runtime) set_runtime_use_mask(true);
    if (!started) *parent_tid = 0;
    return started ? (long)tid : -ENOMEM;
}

int runtime_thread_id(void) {
    int tid;
    __asm__ volatile("movl %%fs:%c1, %0" : "=r"(tid) : "i"(Tcb_tid));
    return tid;
}

int __wrap_pthread_create(pthread_t *thread, const pthread_attr_t *attr, void *(*entry)(void *), void *arg) {
    void *stack = NULL;
    size_t size;
    if (attr) pthread_attr_getstack(attr, &stack, &size);
    const int result = __real_pthread_create(thread, attr, entry, arg);
    if (result) return result;
    runtime_tls_t *owner = runtime_tls_owner((uintptr_t)*thread);
    owner->owns_stack = stack == NULL;
    fast_handoff_publish_runtime(owner->task);
    return 0;
}

int __wrap_pthread_join(pthread_t thread, void **value) {
    const int result = __real_pthread_join(thread, value);
    if (!result) {
        while (!fast_handoff_task_has_exited(runtime_tls_owner((uintptr_t)thread)->task)) fast_handoff_yield();
        __atomic_store_n(&TCB(thread, int, isJoinable), 0, __ATOMIC_RELEASE);
    }
    fast_handoff_wake_bsp();
    return result;
}

int __wrap_pthread_detach(pthread_t thread) {
    const int result = __real_pthread_detach(thread);
    fast_handoff_wake_bsp();
    return result;
}

bool runtime_tls_reclaimable(uintptr_t tcb) {
    return !__atomic_load_n(&TCB(tcb, int, isJoinable), __ATOMIC_ACQUIRE);
}

void runtime_tls_destroy(uintptr_t tcb) {
    free((void *)runtime_tls_owner(tcb)->allocation);
}

void initialize_runtime_access(void) {
    __atomic_store_n(&runtime_access_ready, true, __ATOMIC_RELEASE);
}

bool can_use_runtime(void) {
    return __atomic_load_n(&runtime_access_ready, __ATOMIC_ACQUIRE) && runtime_enabled;
}

void set_runtime_use_mask(bool enabled) {
    if (__atomic_load_n(&runtime_access_ready, __ATOMIC_ACQUIRE)) runtime_enabled = enabled;
}
