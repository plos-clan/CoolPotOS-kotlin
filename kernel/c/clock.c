#include "native.h"

enum {
    cpuid_tsc_deadline_bit = 24,
    nanoseconds_per_second = 1000000000u,
    tsc_to_ns_shift = 32,
};

__extension__ typedef unsigned __int128 uint128_t;

static uint64_t tsc_epoch;
static uint64_t tsc_frequency;
static uint64_t tsc_to_ns_multiplier;

static bool supports_tsc_deadline(void) {
    uint32_t eax = 1;
    uint32_t ecx = 0;
    __asm__ volatile(
        "cpuid"
        : "+a"(eax), "+c"(ecx)
        :
        : "rbx", "rdx", "memory"
    );
    return (ecx & (1u << cpuid_tsc_deadline_bit)) != 0;
}

uint64_t read_tsc(void) {
    uint32_t low;
    uint32_t high;
    __asm__ volatile("lfence; rdtsc" : "=a"(low), "=d"(high) : : "memory");
    return ((uint64_t)high << 32) | low;
}

static uint64_t scale(uint64_t value, uint64_t multiplier, unsigned int shift) {
    const uint128_t result = ((uint128_t)value * multiplier) >> shift;
    return result > UINT64_MAX ? UINT64_MAX : (uint64_t)result;
}

uint64_t runtime_clock_initialize(uint64_t frequency) {
    if (!frequency || !supports_tsc_deadline()) return 0;

    tsc_to_ns_multiplier =
        ((uint64_t)nanoseconds_per_second << tsc_to_ns_shift) / frequency;
    if (!tsc_to_ns_multiplier) return 0;

    tsc_epoch = read_tsc();
    __atomic_store_n(&tsc_frequency, frequency, __ATOMIC_RELEASE);
    return frequency;
}

uint64_t runtime_clock_frequency(void) {
    return __atomic_load_n(&tsc_frequency, __ATOMIC_ACQUIRE);
}

uint64_t runtime_clock_nanos(void) {
    if (!runtime_clock_frequency()) return 0;
    return scale(read_tsc() - tsc_epoch, tsc_to_ns_multiplier, tsc_to_ns_shift);
}

uint64_t runtime_clock_deadline(uint64_t nanoseconds) {
    const uint64_t frequency = runtime_clock_frequency();
    if (!frequency || !nanoseconds) return 0;

    const uint128_t cycles =
        ((uint128_t)nanoseconds * frequency + nanoseconds_per_second - 1) /
        nanoseconds_per_second;
    if (cycles > UINT64_MAX - tsc_epoch) return UINT64_MAX;
    return tsc_epoch + (uint64_t)cycles;
}
