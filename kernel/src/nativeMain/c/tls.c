#include "bridge.h"
#include <pthread.h>
#include <stdlib.h>
#include "generated/runtime-layout/runtime_layout.h"

static pthread_key_t runtime_key;
static const unsigned char ready_marker;
static bool key_ready;

#define TCB(pointer, type, field) (*(type *)((uintptr_t)(pointer) + Tcb_##field))

runtime_tls_t *runtime_tls_owner(uintptr_t tcb) {
    return (runtime_tls_t *)(tcb + Tcb_SIZE);
}

void *__wrap___rtld_allocateTcb(void) {
    extern const uint8_t __kernel_tls_start[], __kernel_tls_file_size[], __kernel_tls_size[], __kernel_tls_align[];
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

int __wrap_pthread_create(pthread_t *thread, const pthread_attr_t *attr, void *(*entry)(void *), void *arg) {
    void *stack = NULL;
    size_t size;
    if (attr) pthread_attr_getstack(attr, &stack, &size);
    const int result = __real_pthread_create(thread, attr, entry, arg);
    if (result) return result;
    runtime_tls_t *owner = runtime_tls_owner((uintptr_t)*thread);
    owner->owns_stack = stack == NULL;
    __atomic_store_n(&owner->created, true, __ATOMIC_RELEASE);
    fast_handoff_wake_bsp();
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
    return __atomic_load_n(&runtime_tls_owner(tcb)->created, __ATOMIC_ACQUIRE) &&
        !__atomic_load_n(&TCB(tcb, int, isJoinable), __ATOMIC_ACQUIRE);
}

void runtime_tls_destroy(uintptr_t tcb) {
    free((void *)runtime_tls_owner(tcb)->allocation);
}

void create_global_key(void) {
    const bool ready = pthread_key_create(&runtime_key, NULL) == 0;
    __atomic_store_n(&key_ready, ready, __ATOMIC_RELEASE);
}

bool can_use_runtime(void) {
    if (!__atomic_load_n(&key_ready, __ATOMIC_ACQUIRE)) return false;

    void *value = NULL;
    return mlibc_try_getspecific(runtime_key, &value) == 0 && value == &ready_marker;
}

void set_runtime_use_mask(bool enabled) {
    if (!__atomic_load_n(&key_ready, __ATOMIC_ACQUIRE)) return;
    pthread_setspecific(runtime_key, enabled ? &ready_marker : NULL);
}
