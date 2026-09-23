#include "vdso.h"

enum {
    clock_realtime = 0,
    clock_monotonic = 1,
    clock_monotonic_raw = 4,
    clock_realtime_coarse = 5,
    clock_monotonic_coarse = 6,
    clock_boottime = 7,
    supported_clocks = (1 << clock_realtime) | (1 << clock_realtime_coarse) |
        (1 << clock_monotonic) | (1 << clock_monotonic_raw) |
        (1 << clock_monotonic_coarse) | (1 << clock_boottime),
    syscall_clock_gettime = 228,
    nanoseconds_per_second = 1000000000,
};

struct timespec { int64_t tv_sec, tv_nsec; };
struct timeval { int64_t tv_sec, tv_usec; };
struct timezone { int tz_minuteswest, tz_dsttime; };

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
    uint64_t nanos = ((uint128_t)cycles * __vdso_clock_data.tsc_to_ns_multiplier) >>
        vdso_tsc_to_ns_shift;
    const bool realtime = clock == clock_realtime || clock == clock_realtime_coarse;
    if (realtime) nanos -= __vdso_clock_data.realtime_epoch;
    const int64_t seconds = realtime ? __vdso_clock_data.realtime_seconds : 0;
    time->tv_sec = seconds + (int64_t)(nanos / nanoseconds_per_second);
    time->tv_nsec = nanos % nanoseconds_per_second;
    return 0;
}

int __vdso_gettimeofday(struct timeval *time, struct timezone *timezone) {
    if (time) {
        struct timespec now;
        __vdso_clock_gettime(clock_realtime, &now);
        time->tv_sec = now.tv_sec;
        time->tv_usec = now.tv_nsec / 1000;
    }
    if (timezone) *timezone = (struct timezone){0};
    return 0;
}
