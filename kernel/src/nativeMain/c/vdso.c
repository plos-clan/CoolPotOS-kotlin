#include "vdso.h"

enum {
    clock_monotonic = 1,
    clock_monotonic_raw = 4,
    clock_monotonic_coarse = 6,
    clock_boottime = 7,
    supported_clocks = (1 << clock_monotonic) | (1 << clock_monotonic_raw) |
        (1 << clock_monotonic_coarse) | (1 << clock_boottime),
    syscall_clock_gettime = 228,
    nanoseconds_per_second = 1000000000,
};

struct timespec {
    int64_t tv_sec;
    int64_t tv_nsec;
};

__attribute__((section(".vdso_clock_data"), visibility("hidden"), used))
const volatile vdso_clock_data_t __vdso_clock_data = {0};

int __vdso_clock_gettime(int clock, struct timespec *time) {
    if ((unsigned)clock > clock_boottime || !(supported_clocks & (1u << clock))) {
        long result = syscall_clock_gettime;
        __asm__ volatile("syscall"
            : "+a"(result)
            : "D"(clock), "S"(time)
            : "rcx", "r11", "memory"
        );
        return (int)result;
    }

    uint32_t low;
    uint32_t high;
    __asm__ volatile("lfence; rdtsc" : "=a"(low), "=d"(high) : : "memory");
    const uint64_t cycles = (((uint64_t)high << 32) | low) - __vdso_clock_data.tsc_epoch;
    __extension__ typedef unsigned __int128 uint128_t;
    const uint64_t nanos = ((uint128_t)cycles * __vdso_clock_data.tsc_to_ns_multiplier) >>
        vdso_tsc_to_ns_shift;
    time->tv_sec = nanos / nanoseconds_per_second;
    time->tv_nsec = nanos % nanoseconds_per_second;
    return 0;
}
