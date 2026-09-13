#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

extern void Kotlin_initRuntimeIfNeeded(void);
extern bool Kotlin_Debugging_isThreadStateRunnable(void);
extern void Kotlin_mm_switchThreadStateRunnable(void);
extern void Kotlin_mm_switchThreadStateNative(void);
extern void *kotlin_runtime_allocate(size_t);
extern void kotlin_serial_print(const char *, size_t);
extern void kotlin_do_irq(uint64_t);
extern void kotlin_handle_exception(void *, uint64_t, uint64_t, uint64_t, int32_t);

#define CALLBACK(type, name, parameters, invoke, result) \
    __attribute__((used, retain)) type name parameters { \
        Kotlin_initRuntimeIfNeeded(); \
        const bool runnable = Kotlin_Debugging_isThreadStateRunnable(); \
        if (!runnable) Kotlin_mm_switchThreadStateRunnable(); \
        invoke; \
        if (!runnable) Kotlin_mm_switchThreadStateNative(); \
        return result; \
    }

CALLBACK(void *, runtime_allocate, (size_t size),
    void *result = kotlin_runtime_allocate(size), result)
CALLBACK(void, serial_print, (const char *buffer, size_t size),
    kotlin_serial_print(buffer, size), )
CALLBACK(void, do_irq, (uint64_t irq), kotlin_do_irq(irq), )

__attribute__((used, retain))
void *(*const runtime_allocate_callback)(size_t) = runtime_allocate;

#define EXCEPTION_VECTORS \
    X(0) X(1) X(2) X(3) X(4) X(5) X(6) X(7) \
    X(8) X(9) X(10) X(11) X(12) X(13) X(14) X(15) \
    X(16) X(17) X(18) X(19) X(20) X(21) X(22) X(23) \
    X(24) X(25) X(26) X(27) X(28) X(29) X(30) X(31)

#define X(vector) \
    static CALLBACK(void, kotlin_exception_##vector, \
        (void *frame, uint64_t error, uint64_t rbp, uint64_t address), \
        kotlin_handle_exception(frame, error, rbp, address, vector), )
EXCEPTION_VECTORS
#undef X

__attribute__((used, retain))
void (*const kotlin_exception_callbacks[])(void *, uint64_t, uint64_t, uint64_t) = {
#define X(vector) [vector] = kotlin_exception_##vector,
    EXCEPTION_VECTORS
#undef X
};

#undef EXCEPTION_VECTORS
#undef CALLBACK
