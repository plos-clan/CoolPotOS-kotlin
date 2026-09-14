#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "vdso.h"
#include "context.h"

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

typedef struct syscall_cpu_state {
    uint64_t kernel_rsp;
    uint64_t user_rsp;
    uint64_t user_rax;
    uint64_t kernel_fs_base;
    uint64_t scheduler_cpu;
} syscall_cpu_state_t;
_Static_assert(offsetof(syscall_cpu_state_t, kernel_rsp) == 0, "invalid syscall kernel RSP offset");
_Static_assert(offsetof(syscall_cpu_state_t, user_rsp) == 8, "invalid syscall user RSP offset");
_Static_assert(offsetof(syscall_cpu_state_t, user_rax) == 16, "invalid syscall RAX offset");
_Static_assert(offsetof(syscall_cpu_state_t, kernel_fs_base) == 24, "invalid syscall FS offset");
_Static_assert(offsetof(syscall_cpu_state_t, scheduler_cpu) == 32, "invalid scheduler CPU offset");

typedef struct cpu_local {
    gdt_entries_t gdt_entries;
    tss_t tss0;
    tss_stack_t tss_stack __attribute__((aligned(16)));
    syscall_cpu_state_t syscall;
    syscall_stack_t syscall_stack __attribute__((aligned(16)));
} cpu_local_t;

extern cpu_local_t locals[cpu_slot_count];
extern uint64_t kernel_runtime_fs_bases[cpu_slot_count];

static inline void initialize_xstate_header(xstate_t *state) {
    __builtin_memset(&state->header, 0, sizeof(state->header));
}

static inline void save_xstate(xstate_t *state) {
    __asm__ volatile(
        "xsave64 %0"
        : "+m"(*state)
        : "a"(xstate_mask), "d"(0)
        : "memory"
    );
}

static inline void restore_xstate(const xstate_t *state) {
    __asm__ volatile(
        "xrstor64 %0"
        :
        : "m"(*state), "a"(xstate_mask), "d"(0)
        : "memory"
    );
}

void setup_xstate(void);
void idt_load(void);
void kt_ap_start(void);
void do_irq(uint64_t irq_num);
bool fast_handoff_irq(pt_regs_t *regs, uint64_t irq_num);
bool fast_handoff_prepare_runtime(uint64_t stack, uint64_t tls);
void fast_handoff_publish_runtime(uint64_t task);
void kernel_clone_thread_entry(void);
void set_kernel_runtime_fs_base(uint64_t pointer);
void serial_print(const char *buffer, size_t size);
uint64_t irq_save(void);
void irq_restore(uint64_t flags);
bool runtime_vm_install(void *(*allocate)(size_t));
void *runtime_vm_take_released(void);
uint64_t read_tsc(void);
uint64_t runtime_clock_initialize(uint64_t frequency);
uint64_t runtime_clock_frequency(void);
uint64_t runtime_clock_nanos(void);
bool runtime_vdso_initialize(vdso_image_t *image);
uint64_t runtime_clock_deadline(uint64_t nanoseconds);
void wrmsr(uint32_t msr, uint64_t value);
void create_global_key(void);
bool can_use_runtime(void);
long runtime_thread_prepare(void *stack, int *parent_tid, void *tls);
int runtime_thread_id(void);
