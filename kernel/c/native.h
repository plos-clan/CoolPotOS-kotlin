#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {
    cpu_slot_count = 256,
    ia32_fs_base_msr = 0xc0000100u,
    ia32_kernel_gs_base_msr = 0xc0000102u,
    syscall_stack_size = 32 * 1024
};
typedef uint64_t gdt_entries_t[7];
typedef uint8_t tss_stack_t[4096];
typedef uint8_t syscall_stack_t[syscall_stack_size];

typedef struct {
    uint16_t size;
    void *ptr;
} __attribute__((packed)) descriptor_table_register_t;
_Static_assert(sizeof(descriptor_table_register_t) == 10, "invalid descriptor register layout");

typedef struct {
    uint32_t reserved0;
    uint64_t rsp[3];
    uint64_t reserved1;
    uint64_t ist[7];
    uint64_t reserved2;
    uint16_t reserved3;
    uint16_t io_map_base;
} __attribute__((packed)) tss_t;
_Static_assert(sizeof(tss_t) == 104, "invalid TSS layout");

typedef struct pt_regs {
    uint64_t r15;
    uint64_t r14;
    uint64_t r13;
    uint64_t r12;
    uint64_t r11;
    uint64_t r10;
    uint64_t r9;
    uint64_t r8;
    uint64_t rbx;
    uint64_t rcx;
    uint64_t rdx;
    uint64_t rsi;
    uint64_t rdi;
    uint64_t rbp;
    uint64_t ds;
    uint64_t es;
    uint64_t fs_base;
    uint64_t rax;
    uint64_t func;
    uint64_t errcode;
    uint64_t rip;
    uint64_t cs;
    uint64_t rflags;
    uint64_t rsp;
    uint64_t ss;
} __attribute__((packed)) pt_regs_t;
_Static_assert(sizeof(pt_regs_t) == 200, "invalid register frame layout");

typedef struct syscall_cpu_state {
    uint64_t kernel_rsp;
    uint64_t user_rsp;
    uint64_t user_rax;
    uint64_t kernel_fs_base;
} syscall_cpu_state_t;
_Static_assert(offsetof(syscall_cpu_state_t, kernel_rsp) == 0, "invalid syscall kernel RSP offset");
_Static_assert(offsetof(syscall_cpu_state_t, user_rsp) == 8, "invalid syscall user RSP offset");
_Static_assert(offsetof(syscall_cpu_state_t, user_rax) == 16, "invalid syscall RAX offset");
_Static_assert(offsetof(syscall_cpu_state_t, kernel_fs_base) == 24, "invalid syscall FS offset");

typedef struct cpu_local {
    gdt_entries_t gdt_entries;
    tss_t tss0;
    tss_stack_t tss_stack __attribute__((aligned(16)));
    syscall_cpu_state_t syscall;
    syscall_stack_t syscall_stack __attribute__((aligned(16)));
} cpu_local_t;

extern cpu_local_t locals[cpu_slot_count];
extern uint64_t kernel_runtime_fs_base;
extern uint64_t kernel_runtime_fs_bases[cpu_slot_count];

void setup_simd(void);
void idt_load(void);
void kt_ap_start(void);
void do_irq(void *regs, uint64_t irq_num);
bool capture_sys_clone_context(uint64_t stack, uint64_t tls);
void set_kernel_runtime_fs_base(uint64_t pointer);
void serial_print(const char *buffer, size_t size);
bool runtime_vm_add_region(void *base, size_t size);
void runtime_clock_configure_hpet(void *base, uint64_t period_femtoseconds);
void wrmsr(uint32_t msr, uint64_t value);
