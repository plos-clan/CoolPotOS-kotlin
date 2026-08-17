#include <stdint.h>

#include "vdso.h"

enum {
    clock_realtime = 0,
    clock_monotonic = 1,
    clock_monotonic_raw = 4,
    clock_realtime_coarse = 5,
    clock_monotonic_coarse = 6,
    clock_boottime = 7,
    supported_clocks =
        (1u << clock_realtime) |
        (1u << clock_monotonic) |
        (1u << clock_monotonic_raw) |
        (1u << clock_realtime_coarse) |
        (1u << clock_monotonic_coarse) |
        (1u << clock_boottime),
    invalid_argument = 22,
    nanoseconds_per_second = 1000000000u,
};

__extension__ typedef unsigned __int128 uint128_t;

struct timespec {
    int64_t seconds;
    int64_t nanoseconds;
};

__attribute__((section(".vdso_clock_data"), visibility("hidden")))
const volatile vdso_clock_data_t __vdso_clock_data;

__attribute__((visibility("default")))
int __vdso_clock_gettime(int clock_id, struct timespec *result) {
    if ((unsigned int)clock_id > clock_boottime ||
        (supported_clocks & (1u << clock_id)) == 0)
        return -invalid_argument;

    uint32_t tsc_low;
    uint32_t tsc_high;
    __asm__ volatile(
        "lfence; rdtsc"
        : "=a"(tsc_low), "=d"(tsc_high)
        :
        : "memory"
    );
    const uint64_t tsc = ((uint64_t)tsc_high << 32) | tsc_low;
    const uint64_t elapsed = tsc - __vdso_clock_data.tsc_epoch;
    const uint64_t nanoseconds = (uint64_t)(
        ((uint128_t)elapsed * __vdso_clock_data.tsc_to_ns_multiplier) >>
            vdso_tsc_to_ns_shift
    );
    result->seconds = (int64_t)(nanoseconds / nanoseconds_per_second);
    result->nanoseconds = (int64_t)(nanoseconds % nanoseconds_per_second);
    return 0;
}
