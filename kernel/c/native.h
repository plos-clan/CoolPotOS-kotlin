#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {
    cpu_slot_count = 256,
    ia32_fs_base_msr = 0xc0000100u
};
typedef uint64_t gdt_entries_t[7];
typedef uint8_t tss_stack_t[4096];

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

typedef struct cpu_local {
    gdt_entries_t gdt_entries;
    tss_t tss0;
    tss_stack_t tss_stack __attribute__((aligned(16)));
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
void wrmsr(uint32_t msr, uint64_t value);
