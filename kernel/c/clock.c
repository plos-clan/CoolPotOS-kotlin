#include "native.h"

enum {
    cpuid_tsc_deadline_bit = 24,
    nanoseconds_per_second = 1000000000u,
};

__extension__ typedef unsigned __int128 uint128_t;

struct tsc_clock {
    uint64_t epoch;
    uint64_t multiplier;
    uint64_t frequency;
};

static struct tsc_clock tsc;

extern uint8_t _binary_vdso_so_start[];
extern uint8_t _binary_vdso_so_end[];

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

static uint64_t scale(uint64_t value) {
    const uint128_t result =
        ((uint128_t)value * tsc.multiplier) >> vdso_tsc_to_ns_shift;
    return result > UINT64_MAX ? UINT64_MAX : (uint64_t)result;
}

uint64_t runtime_clock_initialize(uint64_t frequency) {
    if (!frequency || !supports_tsc_deadline()) return 0;

    const uint64_t multiplier =
        ((uint64_t)nanoseconds_per_second << vdso_tsc_to_ns_shift) / frequency;
    if (!multiplier) return 0;

    tsc.epoch = read_tsc();
    tsc.multiplier = multiplier;
    __atomic_store_n(&tsc.frequency, frequency, __ATOMIC_RELEASE);
    return frequency;
}

uint64_t runtime_clock_frequency(void) {
    return __atomic_load_n(&tsc.frequency, __ATOMIC_ACQUIRE);
}

uint64_t runtime_clock_nanos(void) {
    if (!runtime_clock_frequency()) return 0;
    return scale(read_tsc() - tsc.epoch);
}

bool runtime_vdso_initialize(vdso_image_t *image) {
    const size_t size = (size_t)(
        _binary_vdso_so_end - _binary_vdso_so_start
    );
    if (!image || !runtime_clock_frequency() || size < sizeof(vdso_clock_data_t))
        return false;

    vdso_clock_data_t *data = (vdso_clock_data_t *)(
        _binary_vdso_so_end - sizeof(vdso_clock_data_t)
    );
    *data = (vdso_clock_data_t){
        .tsc_epoch = tsc.epoch,
        .tsc_to_ns_multiplier = tsc.multiplier,
    };
    *image = (vdso_image_t){
        .data = _binary_vdso_so_start,
        .size = size,
    };
    return true;
}

uint64_t runtime_clock_deadline(uint64_t nanoseconds) {
    const uint64_t frequency = runtime_clock_frequency();
    if (!frequency || !nanoseconds) return 0;

    const uint128_t cycles =
        ((uint128_t)nanoseconds * frequency + nanoseconds_per_second - 1) /
        nanoseconds_per_second;
    if (cycles > UINT64_MAX - tsc.epoch) return UINT64_MAX;
    return tsc.epoch + (uint64_t)cycles;
}
