#include <stddef.h>
#include <stdint.h>

extern void *realloc(void *ptr, size_t size);
extern void free(void *);
void serial_print(const char *buffer, size_t size);

int get_nprocs(void) {
    return 1;
}

int __fxstat(int version, int fd, void *statbuf) {
    (void)version;
    (void)fd;
    (void)statbuf;
    return -1;
}

long syscall(long number, ...) {
    (void)number;
    return -1;
}

int isnan(double x) {
    union {
        double f64;
        unsigned long long u64;
    } bits;
    bits.f64 = x;
    return (bits.u64 & 0x7ff0000000000000ULL) == 0x7ff0000000000000ULL
        && (bits.u64 & 0x000fffffffffffffULL) != 0;
}

int __unorddf2(double a, double b) {
    union { double f; unsigned long long u; } ua = {a}, ub = {b};
    unsigned long long ea = (ua.u >> 52) & 0x7ff;
    unsigned long long ma = ua.u & 0x000fffffffffffffULL;
    unsigned long long eb = (ub.u >> 52) & 0x7ff;
    unsigned long long mb = ub.u & 0x000fffffffffffffULL;
    return (ea == 0x7ff && ma) || (eb == 0x7ff && mb);
}

void _ZdlPv(void *ptr) {
    free(ptr);
}

void _ZdlPvm(void *ptr, size_t size) {
    (void)size;
    free(ptr);
}

void _ZdlPvj(void *ptr, unsigned int size) {
    (void)size;
    free(ptr);
}

void _ZdaPv(void *ptr) {
    free(ptr);
}

void _ZdaPvm(void *ptr, size_t size) {
    (void)size;
    free(ptr);
}

void _ZdaPvj(void *ptr, unsigned int size) {
    (void)size;
    free(ptr);
}

uint64_t read_cr3(void) {
    uint64_t value;
    __asm__ volatile ("mov %%cr3, %0" : "=r"(value));
    return value;
}

uint64_t read_cr2(void) {
    uint64_t value;
    __asm__ volatile ("mov %%cr2, %0" : "=r"(value));
    return value;
}

void invlpg(uint64_t address) {
    __asm__ volatile ("invlpg (%0)" : : "r"(address) : "memory");
}

uint64_t rdmsr(uint32_t msr) {
    uint32_t eax;
    uint32_t edx;
    __asm__ volatile ("rdmsr" : "=a"(eax), "=d"(edx) : "c"(msr) : "memory");
    return ((uint64_t)edx << 32) | eax;
}

void wrmsr(uint32_t msr, uint64_t value) {
    uint32_t eax = (uint32_t)value;
    uint32_t edx = (uint32_t)(value >> 32);
    __asm__ volatile ("wrmsr" : : "a"(eax), "d"(edx), "c"(msr) : "memory");
}

enum {
    serial_com1 = 0x3F8,
    serial_line_status = 5,
    serial_tx_empty = 0x20
};

static inline void outb(uint16_t port, uint8_t value) {
    __asm__ volatile ("outb %0, %1" : : "a"(value), "Nd"(port));
}

void io_out8(uint16_t port, uint8_t value) {
    outb(port, value);
}

void enable_interrupt() {
    __asm__ volatile("sti");
}

void disable_interrupt() {
    __asm__ volatile("cli");
}

struct clone_context_record {
    uint64_t stack;
    uint64_t entry;
};

static struct clone_context_record *sys_clone_records = 0;
static uint64_t sys_clone_recorded_count = 0;
static uint64_t sys_clone_capacity = 0;
static uint64_t clone_call_count = 0;

static int ensure_sys_clone_record_capacity(uint64_t needed_count) {
    if (needed_count <= sys_clone_capacity) {
        return 1;
    }

    uint64_t new_capacity = sys_clone_capacity ? sys_clone_capacity : 64;
    while (new_capacity < needed_count) {
        if (new_capacity > (UINT64_MAX / 2)) {
            return 0;
        }
        new_capacity *= 2;
    }

    const size_t bytes = (size_t)(new_capacity * sizeof(struct clone_context_record));
    if (bytes / sizeof(struct clone_context_record) != new_capacity) {
        return 0;
    }

    void *new_memory = realloc(sys_clone_records, bytes);
    if (!new_memory) {
        return 0;
    }

    sys_clone_records = (struct clone_context_record *)new_memory;
    sys_clone_capacity = new_capacity;
    return 1;
}

void capture_sys_clone_context(uint64_t stack, uint64_t entry) {
    if (ensure_sys_clone_record_capacity(sys_clone_recorded_count + 1)) {
        const uint64_t index = sys_clone_recorded_count++;
        sys_clone_records[index].stack = stack;
        sys_clone_records[index].entry = entry;
    }
    clone_call_count += 1;
}

uint64_t get_last_sys_clone_stack(void) {
    if (!sys_clone_recorded_count) {
        return 0;
    }
    return sys_clone_records[sys_clone_recorded_count - 1].stack;
}

uint64_t get_last_sys_clone_entry(void) {
    if (!sys_clone_recorded_count) {
        return 0;
    }
    return sys_clone_records[sys_clone_recorded_count - 1].entry;
}

uint64_t get_sys_clone_call_count(void) {
    return clone_call_count;
}

uint64_t get_sys_clone_recorded_count(void) {
    return sys_clone_recorded_count;
}

uint64_t get_sys_clone_stack_at(uint64_t index) {
    if (index >= sys_clone_recorded_count) {
        return 0;
    }
    return sys_clone_records[index].stack;
}

uint64_t get_sys_clone_entry_at(uint64_t index) {
    if (index >= sys_clone_recorded_count) {
        return 0;
    }
    return sys_clone_records[index].entry;
}

static __attribute__((noreturn)) void kernel_idle_thread_entry(void) {
    for (;;) {
        __asm__ volatile("hlt");
    }
}

uint64_t get_kernel_idle_entry_address(void) {
    return (uint64_t)(uintptr_t)&kernel_idle_thread_entry;
}

static inline uint8_t inb(uint16_t port) {
    uint8_t value;
    __asm__ volatile ("inb %1, %0" : "=a"(value) : "Nd"(port));
    return value;
}

static void serial_init(void) {
    outb(serial_com1 + 1, 0x00);
    outb(serial_com1 + 3, 0x80);
    outb(serial_com1 + 0, 0x03);
    outb(serial_com1 + 1, 0x00);
    outb(serial_com1 + 3, 0x03);
    outb(serial_com1 + 2, 0xC7);
    outb(serial_com1 + 4, 0x0B);
}

static void serial_write_byte(uint8_t value) {
    while (!(inb(serial_com1 + serial_line_status) & serial_tx_empty)) {}
    outb(serial_com1, value);
}

void serial_print(const char *buffer, size_t size) {
    static int initialized = 0;

    if (!initialized) {
        serial_init();
        initialized = 1;
    }
    if (!buffer) {
        return;
    }

    for (size_t i = 0; i < size; i++) {
        if (buffer[i] == '\n') {
            serial_write_byte('\r');
        }
        serial_write_byte((uint8_t)buffer[i]);
    }
}
