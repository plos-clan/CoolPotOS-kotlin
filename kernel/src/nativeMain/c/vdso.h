#pragma once

#include <stddef.h>
#include <stdint.h>

enum {
    vdso_tsc_to_ns_shift = 32,
};

typedef struct vdso_clock_data {
    uint64_t tsc_epoch;
    uint64_t tsc_to_ns_multiplier;
    int64_t realtime_seconds;
    uint64_t realtime_epoch;
} vdso_clock_data_t;

typedef struct vdso_image {
    const uint8_t *data;
    size_t size;
} vdso_image_t;

static inline uint64_t read_tsc(void) {
    uint32_t low;
    uint32_t high;
    __asm__ volatile("lfence; rdtsc" : "=a"(low), "=d"(high) : : "memory");
    return ((uint64_t)high << 32) | low;
}
