#include "bridge.h"
#include <pthread.h>

static pthread_key_t runtime_key;
static const unsigned char ready_marker;
static bool key_ready;

uintptr_t kernel_tls_template(void) {
    extern const uint8_t __kernel_tls_start[];
    extern const uint8_t __kernel_tls_file_size[];
    extern const uint8_t __kernel_tls_size[];
    extern const uint8_t __kernel_tls_align[];
    static const uint8_t *const template[] = {
        __kernel_tls_start,
        __kernel_tls_file_size,
        __kernel_tls_size,
        __kernel_tls_align,
    };
    return (uintptr_t)template;
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
