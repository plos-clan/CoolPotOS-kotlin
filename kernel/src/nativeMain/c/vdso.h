#pragma once

#include <stddef.h>
#include <stdint.h>

enum {
    vdso_tsc_to_ns_shift = 32,
};

typedef struct vdso_clock_data {
    uint64_t tsc_epoch;
    uint64_t tsc_to_ns_multiplier;
} vdso_clock_data_t;

typedef struct vdso_image {
    const uint8_t *data;
    size_t size;
} vdso_image_t;
