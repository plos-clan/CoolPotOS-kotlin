#include <limits.h>
#include <stdbool.h>
#include "bridge.h"
#include "native.h"

uint64_t kernel_runtime_fs_bases[cpu_slot_count];
bool fs_base_instructions;

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

int __fxstat(int, int, void *) {
    return -1;
}

union double_bits {
    double f64;
    unsigned long long u64;
};

static unsigned long long magnitude_bits(union double_bits bits) {
    return bits.u64 & 0x7fffffffffffffffULL;
}

int isnan(double x) { return magnitude_bits((union double_bits){.f64 = x}) > 0x7ff0000000000000ULL; }
int isinf(double x) { return magnitude_bits((union double_bits){.f64 = x}) == 0x7ff0000000000000ULL; }
__attribute__((optnone, noinline)) int __unorddf2(double a, double b) {
    return isnan(a) || isnan(b);
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

__attribute__((used)) uint64_t rdmsr(uint32_t msr) {
    if (msr == ia32_fs_base_msr && fs_base_instructions) {
        uint64_t value;
        __asm__ volatile("rdfsbase %0" : "=r"(value) : : "memory");
        return value;
    }
    uint32_t eax, edx;
    __asm__ volatile("rdmsr" : "=a"(eax), "=d"(edx) : "c"(msr) : "memory");
    return ((uint64_t)edx << 32) | eax;
}

__attribute__((used)) void wrmsr(uint32_t msr, uint64_t value) {
    if (msr == ia32_fs_base_msr && fs_base_instructions) {
        __asm__ volatile("wrfsbase %0" : : "r"(value) : "memory");
        return;
    }
    __asm__ volatile("wrmsr" : : "a"((uint32_t)value),
        "d"((uint32_t)(value >> 32)), "c"(msr) : "memory");
}

#define DEFINE_PORT_IO(bits) \
    uint##bits##_t io_in##bits(uint16_t port) { \
        uint##bits##_t value; \
        __asm__ volatile("in %1, %0" : "=a"(value) : "Nd"(port) : "memory"); \
        return value; \
    } \
    void io_out##bits(uint16_t port, uint##bits##_t value) { \
        __asm__ volatile("out %0, %1" : : "a"(value), "Nd"(port) : "memory"); \
    }
DEFINE_PORT_IO(8)
DEFINE_PORT_IO(16)
DEFINE_PORT_IO(32)
#undef DEFINE_PORT_IO

void enable_interrupt(void) { __asm__ volatile("sti" : : : "memory"); }
void disable_interrupt(void) { __asm__ volatile("cli" : : : "memory"); }

void pthread_exit(void *ret_val) __attribute__((noreturn));
int pthread_key_create(uint32_t *key, void (*destructor)(void *));

int __pthread_key_create(uint32_t *key, void (*destructor)(void *)) { return pthread_key_create(key, destructor); }

void asm_pause(void) { __asm__ volatile("pause" : : : "memory"); }

uint64_t irq_save(void) {
    uint64_t flags;
    __asm__ volatile("pushfq; popq %0; cli" : "=r"(flags) : : "memory");
    return flags;
}

void irq_restore(uint64_t flags) {
    if (flags & (1u << 9)) __asm__ volatile("sti" : : : "memory");
}

#define DEFINE_RANDOM_STEP(instruction) \
    bool instruction##64_step(uint64_t *out) { \
        uint64_t value; \
        unsigned char success; \
        __asm__ volatile( \
            #instruction " %0\nsetc %1" \
            : "=r"(value), "=qm"(success) : : "cc", "memory" \
        ); \
        if (success) *out = value; \
        return success; \
    }
DEFINE_RANDOM_STEP(rdrand)
DEFINE_RANDOM_STEP(rdseed)
#undef DEFINE_RANDOM_STEP

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

__attribute__((naked, used))
void fast_user_task_entry(void) {
    __asm__ volatile(
        ".cfi_undefined %rip\n"
        "movq %rsp, %r13\n"
        CPOS_LOAD_USER_FS
        "leaq 200(%r13), %rsp\n"
        "movq 112(%r13), %rax\n"
        "movw %ax, %ds\n"
        "movq 120(%r13), %rax\n"
        "movw %ax, %es\n"
        "jmp .Luser_iret\n"
    );
}

void (*const user_task_entry)(void) = fast_user_task_entry;

__attribute__((naked, used))
void asm_syscall_handle(void) {
    __asm__ volatile(
        ".cfi_undefined %rip\n"
        "cli\n"
        "cld\n"
        "swapgs\n"
        "movq %rsp, %gs:8\n"
        "movq %rax, %gs:16\n"
        "movq %gs:0, %rsp\n"
        "subq $" CPOS_ASM_STRINGIFY(KERNEL_ENTRY_FRAME_SIZE_VALUE) ", %rsp\n"
        CPOS_SAVE_GENERAL
        "movq %r13, 16(%rsp)\n"
        "movq %r12, 24(%rsp)\n"
        "movq %r11, 32(%rsp)\n"
        "movq %rcx, 72(%rsp)\n"
        "leaq 768(%rsp), %rdi\n"
        "xorl %eax, %eax\n"
        "movl $8, %ecx\n"
        "rep stosq\n"
        "movl $" CPOS_ASM_STRINGIFY(XSTATE_MASK_VALUE) ", %eax\n"
        "xorl %edx, %edx\n"
        "xsave64 256(%rsp)\n"
        "xrstor64 initial_xstate(%rip)\n"
        "xorq %rax, %rax\n"
        "movw %ds, %ax\n"
        "movq %rax, 112(%rsp)\n"
        "xorq %rax, %rax\n"
        "movw %es, %ax\n"
        "movq %rax, 120(%rsp)\n"
        "movl $0xc0000100, %edi\n"
        "call rdmsr\n"
        "movq %rax, 128(%rsp)\n"
        "movq %gs:24, %rsi\n"
        "movl $0xc0000100, %edi\n"
        "call wrmsr\n"
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
        "xorl %edi, %edi\n"
        "call fast_handoff_account_mode\n"
        "movq %rsp, %rdi\n"
        "sti\n"
        "call syscall_handler\n"
        "cli\n"
        "movl $1, %edi\n"
        "call fast_handoff_account_mode\n"
        "movq %rsp, %r13\n"
        CPOS_LOAD_USER_FS
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
        ".Luser_iret:\n"
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
        "movl $" CPOS_ASM_STRINGIFY(XSTATE_MASK_VALUE) ", %eax\n"
        "xorl %edx, %edx\n"
        "xrstor64 256(%r13)\n"
        CPOS_LOAD_GENERAL
        "movq 24(%r13), %r12\n"
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

__attribute__((naked, used))
void kernel_clone_thread_entry(void) {
    __asm__ volatile(
        ".cfi_undefined %rip\n"
        "sti\n"
        "popq %rax\n"
        "popq %rdi\n"
        "addq $8, %rsp\n"
        "call *%rax\n"
        "movq %rax, %rdi\n"
        "call pthread_exit\n"
        "ud2\n"
    );
}
