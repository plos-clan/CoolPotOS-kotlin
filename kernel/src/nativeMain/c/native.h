#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "vdso.h"

#define XSTATE_MASK_VALUE 7
#define XSTATE_SIZE_VALUE 832
#define KERNEL_ENTRY_FRAME_SIZE_VALUE 1088
#define CPOS_ASM_STRINGIFY_IMPL(value) #value
#define CPOS_ASM_STRINGIFY(value) CPOS_ASM_STRINGIFY_IMPL(value)

enum {
    cpu_slot_count = 256,
    ia32_fs_base_msr = 0xc0000100u,
    ia32_gs_base_msr = 0xc0000101u,
    ia32_kernel_gs_base_msr = 0xc0000102u,
    ia32_tsc_deadline_msr = 0x6e0u,
    syscall_stack_size = 32 * 1024,
    xstate_x87 = 1u << 0,
    xstate_sse = 1u << 1,
    xstate_avx = 1u << 2,
    xstate_mask = xstate_x87 | xstate_sse | xstate_avx,
    xstate_legacy_size = 512,
    xstate_header_size = 64,
    xstate_header_offset = xstate_legacy_size,
    xstate_ymm_offset = xstate_header_offset + xstate_header_size,
    xstate_ymm_size = 256,
    xstate_size = XSTATE_SIZE_VALUE,
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

typedef struct xstate_legacy {
    uint16_t control_word;
    uint16_t status_word;
    uint8_t tag_word;
    uint8_t reserved0;
    uint16_t opcode;
    uint64_t instruction_pointer;
    uint64_t data_pointer;
    uint32_t mxcsr;
    uint32_t mxcsr_mask;
    uint8_t registers[384];
    uint8_t reserved1[96];
} __attribute__((packed)) xstate_legacy_t;
_Static_assert(
    sizeof(xstate_legacy_t) == xstate_legacy_size,
    "invalid XSAVE legacy area size"
);

typedef struct xstate_header {
    uint64_t state_bv;
    uint64_t compacted_bv;
    uint64_t reserved[6];
} xstate_header_t;
_Static_assert(
    sizeof(xstate_header_t) == xstate_header_size,
    "invalid XSAVE header size"
);

typedef struct xstate {
    xstate_legacy_t legacy;
    xstate_header_t header;
    uint8_t ymm_high[xstate_ymm_size];
} __attribute__((aligned(64))) xstate_t;
_Static_assert(sizeof(xstate_t) == xstate_size, "invalid XSAVE area size");
_Static_assert(
    offsetof(xstate_t, header) == xstate_header_offset,
    "invalid XSAVE header offset"
);
_Static_assert(
    offsetof(xstate_t, ymm_high) == xstate_ymm_offset,
    "invalid XSAVE YMM offset"
);

typedef struct kernel_entry_frame {
    pt_regs_t regs;
    void *hardware_frame;
    xstate_t xstate;
} __attribute__((aligned(64))) kernel_entry_frame_t;
_Static_assert(
    offsetof(kernel_entry_frame_t, xstate) == 256,
    "invalid XSAVE area offset"
);
_Static_assert(
    sizeof(kernel_entry_frame_t) == KERNEL_ENTRY_FRAME_SIZE_VALUE,
    "invalid kernel entry frame size"
);

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
extern const xstate_t initial_xstate;

static inline void initialize_xstate_header(xstate_t *state) {
    __builtin_memset(&state->header, 0, sizeof(state->header));
}

static inline void save_xstate(xstate_t *state) {
    __asm__ volatile(
        "xsaveopt64 %0"
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
bool fast_handoff_yield(void);
bool fast_handoff_park_current(void);
bool fast_handoff_unpark(uint64_t task);
uint64_t fast_handoff_service(void);
void fast_handoff_wake_bsp(void);
void fast_handoff_park_kotlin(uint64_t deadline_ns, uint64_t wake_sequence);
_Noreturn void fast_handoff_idle(void);
bool fast_handoff_configure_timer(uint8_t vector, uint32_t frequency_hz);
bool fast_handoff_finish_bootstrap(uint64_t task);
bool fast_handoff_replace_address_space(uint64_t task, uint64_t cr3);
void fast_handoff_reset_user_xstate(void);
uint64_t fast_handoff_current_task_id(void);
bool capture_sys_clone_context(uint64_t stack, uint64_t tls);
uint64_t allocate_runtime_tid(void);
uint64_t create_kernel_runtime_tcb(void);
void set_kernel_runtime_fs_base(uint64_t pointer);
void serial_print(const char *buffer, size_t size);
bool runtime_vm_install(void *(*allocate)(size_t));
void *runtime_vm_take_released(void);
uint64_t read_tsc(void);
uint64_t runtime_clock_initialize(uint64_t frequency);
uint64_t runtime_clock_frequency(void);
uint64_t runtime_clock_nanos(void);
bool runtime_vdso_initialize(vdso_image_t *image);
uint64_t runtime_clock_deadline(uint64_t nanoseconds);
void wrmsr(uint32_t msr, uint64_t value);
