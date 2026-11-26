#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "vdso.h"
#include "context.h"

typedef uint64_t gdt_entries_t[7];
typedef uint8_t tss_stack_t[2][4096];
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

typedef struct syscall_cpu_state {
    uint64_t kernel_rsp;
    uint64_t user_rsp;
    uint32_t xsave_features;
    uint64_t kernel_fs_base;
    uint64_t scheduler_cpu;
} syscall_cpu_state_t;
#define CPOS_CPU_STATE ((volatile syscall_cpu_state_t __seg_gs *)0)
_Static_assert(offsetof(syscall_cpu_state_t, kernel_rsp) == 0, "invalid syscall kernel RSP offset");
_Static_assert(offsetof(syscall_cpu_state_t, user_rsp) == 8, "invalid syscall user RSP offset");

typedef struct cpu_local {
    struct tlb_request *shootdown;
    gdt_entries_t gdt_entries;
    tss_t tss0;
    tss_stack_t tss_stack __attribute__((aligned(16)));
    syscall_cpu_state_t syscall;
    syscall_stack_t syscall_stack __attribute__((aligned(16)));
} cpu_local_t;

extern cpu_local_t locals[cpu_slot_count];
extern uint64_t kernel_runtime_fs_bases[cpu_slot_count];

static inline void save_xstate(xstate_t *state) {
    __asm__ volatile("xsave64 %0" : "+m"(*state)
                     : "a"(xstate_mask), "d"(0) : "memory");
}

static inline void restore_xstate(const xstate_t *state) {
    __asm__ volatile("xrstor64 %0"
                     : : "m"(*state), "a"(xstate_mask), "d"(0) : "memory");
}

static inline void enter_kernel_xstate(xstate_t *state) {
    __builtin_memset(&state->header, 0, sizeof(state->header));
    save_xstate(state);
    restore_xstate(&initial_xstate);
}

void setup_xstate(void);
void idt_load(void);
void exception_handler(void *frame, uint64_t error, uint64_t rbp, uint64_t address, int32_t vector);
void kt_ap_start(void);
bool fast_handoff_irq(pt_regs_t *regs, uint64_t irq_num);
bool runtime_thread_registered(void);
bool runtime_rendezvous_requested(void);
void fast_handoff_runtime_yield(void);
void fast_handoff_account_mode(bool user);
bool tlb_handle_nmi(void);
void fast_handoff_publish_runtime(uint64_t task);
void kernel_clone_thread_entry(void);
void set_kernel_runtime_fs_base(uint64_t pointer);
void serial_print(const char *buffer, size_t size);
uint64_t irq_save(void);
void irq_restore(uint64_t flags);
void *runtime_allocate(size_t size);
void runtime_vm_enable(void);
void *runtime_vm_take_released(void);
bool runtime_clock_initialize(uint64_t frequency);
uint64_t runtime_clock_frequency(void);
uint64_t runtime_clock_nanos(void);
uint64_t runtime_clock_duration(uint64_t cycles);
extern const uint8_t embedded_vdso_start[] __asm__("_binary_vdso_so_start");
extern const uint8_t embedded_vdso_end[] __asm__("_binary_vdso_so_end");
void runtime_clock_snapshot(vdso_clock_data_t *data);
uint64_t runtime_clock_deadline(uint64_t nanoseconds);
void wrmsr(uint32_t msr, uint64_t value);
uint64_t enter_kernel_tls(void);
void leave_kernel_tls(uint64_t user);
void initialize_runtime_access(void);
bool can_use_runtime(void);
long runtime_thread_prepare(void *stack, int *parent_tid, void *tls);
int runtime_thread_id(void);
