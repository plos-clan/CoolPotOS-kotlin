#include "native.h"

enum {
    nanoseconds_per_second = 1000000000u,
};

__extension__ typedef unsigned __int128 uint128_t;

struct tsc_clock {
    uint64_t epoch;
    uint64_t multiplier;
    uint64_t frequency;
};

static struct tsc_clock tsc;

uint64_t runtime_clock_duration(uint64_t value) {
    const uint128_t result =
        ((uint128_t)value * tsc.multiplier) >> vdso_tsc_to_ns_shift;
    return result > UINT64_MAX ? UINT64_MAX : (uint64_t)result;
}

bool runtime_clock_initialize(uint64_t frequency) {
    if (!frequency) return false;

    const uint64_t multiplier =
        ((uint64_t)nanoseconds_per_second << vdso_tsc_to_ns_shift) / frequency;
    if (!multiplier) return false;

    tsc.epoch = read_tsc();
    tsc.multiplier = multiplier;
    __atomic_store_n(&tsc.frequency, frequency, __ATOMIC_RELEASE);
    return true;
}

uint64_t runtime_clock_frequency(void) {
    return __atomic_load_n(&tsc.frequency, __ATOMIC_ACQUIRE);
}

uint64_t runtime_clock_nanos(void) {
    if (!runtime_clock_frequency()) return 0;
    return runtime_clock_duration(read_tsc() - tsc.epoch);
}

void runtime_clock_snapshot(vdso_clock_data_t *data) {
    data->tsc_epoch = tsc.epoch;
    data->tsc_to_ns_multiplier = tsc.multiplier;
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
