#include <limits.h>
#include <stdbool.h>
#include "bridge.h"
#include "native.h"

#if defined(__clang__)
#  define NO_OPTIMIZE __attribute__((optnone, noinline))
#elif defined(__GNUC__)
#  define NO_OPTIMIZE __attribute__((optimize("O0"), noinline))
#else
#  define NO_OPTIMIZE
#endif

extern void *realloc(void *ptr, size_t size);

uint64_t kernel_runtime_fs_bases[cpu_slot_count];

void set_kernel_runtime_fs_base(uint64_t pointer) {
    uint32_t eax = 1, ebx;
    __asm__ volatile("cpuid" : "+a"(eax), "=b"(ebx) : "c"(0) : "edx", "memory");
    kernel_runtime_fs_bases[ebx >> 24] = pointer;
}

int get_nprocs(void) {
    const struct limine_mp_response *response = mp_request.response;
    if (!response || response->cpu_count == 0) return 1;
    if (response->cpu_count > INT_MAX) return INT_MAX;
    return (int)response->cpu_count;
}

int __fxstat(int version, int fd, void *statbuf) {
    (void)version;
    (void)fd;
    (void)statbuf;
    return -1;
}

union double_bits {
    double f64;
    unsigned long long u64;
};

static int is_nan_bits(union double_bits bits) {
    return (bits.u64 & 0x7ff0000000000000ULL) == 0x7ff0000000000000ULL
        && (bits.u64 & 0x000fffffffffffffULL) != 0;
}

int isnan(double x) { return is_nan_bits((union double_bits){.f64 = x}); }
NO_OPTIMIZE int __unorddf2(double a, double b) {
    return is_nan_bits((union double_bits){.f64 = a})
        || is_nan_bits((union double_bits){.f64 = b});
}

void _ZdlPv(void *ptr) { free(ptr); }
void _ZdaPv(void *ptr) __attribute__((alias("_ZdlPv")));
void _ZdlPvm(void *ptr, size_t size) { (void)size; free(ptr); }
void _ZdaPvm(void *ptr, size_t size) __attribute__((alias("_ZdlPvm")));
#define DEFINE_CR_READER(reg) \
    uint64_t read_cr##reg(void) { \
        uint64_t value; \
        __asm__ volatile("mov %%cr" #reg ", %0" : "=r"(value)); \
        return value; \
    }
DEFINE_CR_READER(2)
DEFINE_CR_READER(3)
#undef DEFINE_CR_READER

void write_cr3(uint64_t value) {
    __asm__ volatile("mov %0, %%cr3" : : "r"(value) : "memory");
}

void invlpg(uint64_t address) {
    __asm__ volatile("invlpg (%0)" : : "r"(address) : "memory");
}

uint64_t rdmsr(uint32_t msr) {
    uint32_t eax, edx;
    __asm__ volatile("rdmsr" : "=a"(eax), "=d"(edx) : "c"(msr) : "memory");
    return ((uint64_t)edx << 32) | eax;
}

void wrmsr(uint32_t msr, uint64_t value) {
    __asm__ volatile("wrmsr" : : "a"((uint32_t)value),
        "d"((uint32_t)(value >> 32)), "c"(msr) : "memory");
}

enum {
    serial_com1 = 0x3F8,
    serial_line_status = 5,
    serial_tx_empty = 0x20
};

uint8_t io_in8(uint16_t port) {
    uint8_t value;
    __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port) : "memory");
    return value;
}

uint16_t io_in16(uint16_t port) {
    uint16_t value;
    __asm__ volatile("inw %1, %0" : "=a"(value) : "Nd"(port) : "memory");
    return value;
}

uint32_t io_in32(uint16_t port) {
    uint32_t value;
    __asm__ volatile("inl %1, %0" : "=a"(value) : "Nd"(port) : "memory");
    return value;
}

void io_out8(uint16_t port, uint8_t value) {
    __asm__ volatile("outb %0, %1" : : "a"(value), "Nd"(port) : "memory");
}

void io_out16(uint16_t port, uint16_t value) {
    __asm__ volatile("outw %0, %1" : : "a"(value), "Nd"(port) : "memory");
}

void io_out32(uint16_t port, uint32_t value) {
    __asm__ volatile("outl %0, %1" : : "a"(value), "Nd"(port) : "memory");
}

void enable_interrupt(void) { __asm__ volatile("sti" : : : "memory"); }
void disable_interrupt(void) { __asm__ volatile("cli" : : : "memory"); }

struct clone_context_record {
    uint64_t stack;
    uint64_t tls;
};

static struct clone_context_record *clone_records;
static uint64_t clone_count;
static uint64_t clone_capacity;
static uint64_t next_runtime_tid = 2;

struct runtime_tcb_prefix {
    struct runtime_tcb_prefix *self_pointer;
    size_t dtv_size;
    void **dtv_pointers;
    int tid;
    int did_exit;
};

uint64_t allocate_runtime_tid(void) {
    return __atomic_fetch_add(&next_runtime_tid, 1, __ATOMIC_RELAXED);
}

uint64_t create_kernel_runtime_tcb(void) {
    struct runtime_tcb_prefix *tcb = __rtld_allocateTcb();
    if (!tcb) return 0;
    tcb->tid = (int)allocate_runtime_tid();
    return (uintptr_t)tcb;
}

static bool ensure_clone_capacity(uint64_t needed) {
    if (needed <= clone_capacity) return true;

    uint64_t new_capacity = clone_capacity ? clone_capacity : 64;
    while (new_capacity < needed) {
        if (new_capacity > UINT64_MAX / 2) return false;
        new_capacity *= 2;
    }
    if (new_capacity > SIZE_MAX / sizeof(*clone_records)) return false;

    void *new_records = realloc(clone_records, new_capacity * sizeof(*clone_records));
    if (!new_records) return false;
    clone_records = new_records;
    clone_capacity = new_capacity;
    return true;
}

bool capture_sys_clone_context(uint64_t stack, uint64_t tls) {
    if (clone_count == UINT64_MAX || !ensure_clone_capacity(clone_count + 1)) return false;
    clone_records[clone_count++] = (struct clone_context_record){stack, tls};
    return true;
}

uint64_t get_sys_clone_recorded_count(void) { return clone_count; }
uint64_t get_sys_clone_stack_at(uint64_t index) { return index < clone_count ? clone_records[index].stack : 0; }
uint64_t get_sys_clone_tls_at(uint64_t index) { return index < clone_count ? clone_records[index].tls : 0; }

void pthread_exit(void *ret_val) __attribute__((noreturn));
int pthread_key_create(uint32_t *key, void (*destructor)(void *));

static __attribute__((naked, noreturn)) void kernel_clone_thread_entry(void) {
    __asm__ volatile(
        "popq %rax\n"
        "popq %rdi\n"
        "addq $8, %rsp\n"
        "call *%rax\n"
        "movq %rax, %rdi\n"
        "call pthread_exit\n"
        "ud2\n"
    );
}

uint64_t get_kernel_clone_thread_entry_address(void) { return (uintptr_t)&kernel_clone_thread_entry; }
int __pthread_key_create(uint32_t *key, void (*destructor)(void *)) { return pthread_key_create(key, destructor); }

static void serial_init(void) {
    io_out8(serial_com1 + 1, 0x00);
    io_out8(serial_com1 + 3, 0x80);
    io_out8(serial_com1 + 0, 0x03);
    io_out8(serial_com1 + 1, 0x00);
    io_out8(serial_com1 + 3, 0x03);
    io_out8(serial_com1 + 2, 0xC7);
    io_out8(serial_com1 + 4, 0x0B);
}

static void serial_write_byte(uint8_t value) {
    while (!(io_in8(serial_com1 + serial_line_status) & serial_tx_empty)) {}
    io_out8(serial_com1, value);
}

void asm_pause(void) { __asm__ volatile("pause" : : : "memory"); }

uint64_t irq_save(void) {
    uint64_t flags;
    __asm__ volatile("pushfq; popq %0; cli" : "=r"(flags) : : "memory");
    return flags;
}

void irq_restore(uint64_t flags) {
    if (flags & (1u << 9)) __asm__ volatile("sti" : : : "memory");
}

void serial_print(const char *buffer, size_t size) {
    static bool initialized;

    if (!buffer) return;
    if (!initialized) {
        serial_init();
        initialized = true;
    }

    for (size_t i = 0; i < size; i++) {
        if (buffer[i] == '\n') serial_write_byte('\r');
        serial_write_byte((uint8_t)buffer[i]);
    }
}

void setup_syscall_cpu(uint64_t lapic_id, uint8_t is_bsp) {
    cpu_local_t *local = &locals[lapic_id % cpu_slot_count];
    syscall_cpu_state_t *state = &local->syscall;
    const uint64_t user_gs_base = rdmsr(ia32_gs_base_msr);
    const uintptr_t stack_top =
        ((uintptr_t)local->syscall_stack + sizeof(local->syscall_stack)) & ~0x3fULL;

    state->kernel_rsp = stack_top;
    state->user_rsp = 0;
    state->user_rax = 0;
    state->kernel_fs_base = rdmsr(ia32_fs_base_msr);
    state->scheduler_cpu = 0;

    set_kernel_stack(lapic_id, stack_top, is_bsp);
    wrmsr(ia32_gs_base_msr, (uintptr_t)state);
    wrmsr(ia32_kernel_gs_base_msr, user_gs_base);
}

uint64_t get_asm_syscall_handle_address(void) {
    return (uintptr_t)&asm_syscall_handle;
}

__attribute__((naked, used)) void asm_syscall_handle(void) {
    __asm__ volatile(
        "cli\n"
        "cld\n"
        "swapgs\n"

        "movq %rsp, %gs:8\n"
        "movq %rax, %gs:16\n"
        "movq %gs:0, %rsp\n"

        "subq $832, %rsp\n"
        "movq %r15, 0(%rsp)\n"
        "movq %r14, 8(%rsp)\n"
        "movq %r13, 16(%rsp)\n"
        "movq %r12, 24(%rsp)\n"
        "movq %r11, 32(%rsp)\n"
        "movq %r10, 40(%rsp)\n"
        "movq %r9, 48(%rsp)\n"
        "movq %r8, 56(%rsp)\n"
        "movq %rbx, 64(%rsp)\n"
        "movq %rcx, 72(%rsp)\n"
        "movq %rdx, 80(%rsp)\n"
        "movq %rsi, 88(%rsp)\n"
        "movq %rdi, 96(%rsp)\n"
        "movq %rbp, 104(%rsp)\n"

        "leaq 768(%rsp), %rdi\n"
        "xorl %eax, %eax\n"
        "movl $8, %ecx\n"
        "rep stosq\n"
        "movl $3, %eax\n"
        "xorl %edx, %edx\n"
        "xsaveopt64 256(%rsp)\n"
        "xrstor64 initial_xstate(%rip)\n"

        "xorq %rax, %rax\n"
        "movw %ds, %ax\n"
        "movq %rax, 112(%rsp)\n"
        "xorq %rax, %rax\n"
        "movw %es, %ax\n"
        "movq %rax, 120(%rsp)\n"

        "movl $0xc0000100, %ecx\n"
        "rdmsr\n"
        "shlq $32, %rdx\n"
        "orq %rdx, %rax\n"
        "movq %rax, 128(%rsp)\n"
        "movq %gs:24, %rax\n"
        "movq %rax, %rdx\n"
        "shrq $32, %rdx\n"
        "movl $0xc0000100, %ecx\n"
        "wrmsr\n"

        "movq %gs:16, %rax\n"
        "movq %rax, 136(%rsp)\n"
        "movq %rax, 144(%rsp)\n"
        "movq $0, 152(%rsp)\n"
        "movq 72(%rsp), %rax\n"
        "movq %rax, 160(%rsp)\n"
        "movq $0x23, 168(%rsp)\n"
        "movq 32(%rsp), %rax\n"
        "movq %rax, 176(%rsp)\n"
        "movq %gs:8, %rax\n"
        "movq %rax, 184(%rsp)\n"
        "movq $0x1b, 192(%rsp)\n"

        "movq %rsp, %rdi\n"
        "sti\n"
        "call syscall_handler\n"
        "cli\n"
        "movq %rsp, %r13\n"

        "movq 128(%r13), %rax\n"
        "movq %rax, %rdx\n"
        "shrq $32, %rdx\n"
        "movl $0xc0000100, %ecx\n"
        "wrmsr\n"

        "movl $0x1b, %eax\n"
        "movw %ax, %ds\n"
        "movw %ax, %es\n"

        "cmpq $0x23, 168(%r13)\n"
        "jne 1f\n"
        "cmpq $0x1b, 192(%r13)\n"
        "jne 1f\n"
        "movabsq $0x0000800000000000, %rax\n"
        "cmpq %rax, 160(%r13)\n"
        "jae 1f\n"
        "cmpq %rax, 184(%r13)\n"
        "jae 1f\n"
        "testq $0x30100, 176(%r13)\n"
        "jnz 1f\n"

        "movq $1, %gs:16\n"
        "movq 160(%r13), %rcx\n"
        "movq 176(%r13), %r11\n"
        "andq $-159745, %r11\n"
        "orq $2, %r11\n"
        "jmp 2f\n"

        "1:\n"
        "movq $0, %gs:16\n"
        "pushq 192(%r13)\n"
        "pushq 184(%r13)\n"
        "movq 176(%r13), %rax\n"
        "andq $-159745, %rax\n"
        "orq $2, %rax\n"
        "pushq %rax\n"
        "pushq 168(%r13)\n"
        "pushq 160(%r13)\n"

        "2:\n"
        "movl $3, %eax\n"
        "xorl %edx, %edx\n"
        "xrstor64 256(%r13)\n"
        "movq 0(%r13), %r15\n"
        "movq 8(%r13), %r14\n"
        "movq 24(%r13), %r12\n"
        "movq 40(%r13), %r10\n"
        "movq 48(%r13), %r9\n"
        "movq 56(%r13), %r8\n"
        "movq 64(%r13), %rbx\n"
        "movq 80(%r13), %rdx\n"
        "movq 88(%r13), %rsi\n"
        "movq 96(%r13), %rdi\n"
        "movq 104(%r13), %rbp\n"
        "movq 136(%r13), %rax\n"

        "cmpq $0, %gs:16\n"
        "je 3f\n"
        "movq 184(%r13), %rsp\n"
        "movq 16(%r13), %r13\n"
        "swapgs\n"
        "sysretq\n"

        "3:\n"
        "movq 32(%r13), %r11\n"
        "movq 72(%r13), %rcx\n"
        "movq 16(%r13), %r13\n"
        "swapgs\n"
        "iretq\n"
    );
}
