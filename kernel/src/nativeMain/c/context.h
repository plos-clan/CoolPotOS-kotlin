#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define XSTATE_MASK_VALUE 7
#define XSTATE_SIZE_VALUE 832
#define KERNEL_ENTRY_FRAME_SIZE_VALUE 1088
#define CPOS_ASM_STRINGIFY_IMPL(value) #value
#define CPOS_ASM_STRINGIFY(value) CPOS_ASM_STRINGIFY_IMPL(value)

#define CPOS_GENERAL_REGISTERS(X) \
    X(r15, 0) X(r14, 8) X(r10, 40) X(r9, 48) X(r8, 56) \
    X(rbx, 64) X(rdx, 80) X(rsi, 88) X(rdi, 96) X(rbp, 104)
#define CPOS_SAVE_REGISTER(name, offset) "movq %" #name ", " #offset "(%rsp)\n"
#define CPOS_LOAD_REGISTER(name, offset) "movq " #offset "(%r13), %" #name "\n"
#define CPOS_SAVE_GENERAL CPOS_GENERAL_REGISTERS(CPOS_SAVE_REGISTER) \
    CPOS_SAVE_REGISTER(r13, 16) CPOS_SAVE_REGISTER(r12, 24) \
    CPOS_SAVE_REGISTER(r11, 32) CPOS_SAVE_REGISTER(rcx, 72)
#define CPOS_LOAD_GENERAL CPOS_GENERAL_REGISTERS(CPOS_LOAD_REGISTER)

#define CPOS_LOAD_USER_FS \
    "movq 128(%r13), %rdi\n" \
    "call leave_kernel_tls\n"

#define CPOS_RESTORE_SEGMENT(name, offset) \
    "movl %" #name ", %eax\n" \
    "cmpw " #offset "(%r13), %ax\n" \
    "je 5f\n" \
    "movw " #offset "(%r13), %" #name "\n" \
    "5:\n"

#define CPOS_USER_REGISTERS \
    CPOS_LOAD_GENERAL \
    "movq 24(%r13), %r12\n" \
    "movq 136(%r13), %rax\n" \
    "movq 16(%r13), %r13\n" \
    "swapgs\n"

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
typedef struct pt_regs {
    uint64_t r15, r14, r13, r12;
    uint64_t r11, r10, r9, r8;
    uint64_t rbx, rcx, rdx, rsi, rdi, rbp;
    uint64_t ds, es, fs_base, rax;
    uint64_t func, errcode;
    uint64_t rip, cs, rflags, rsp, ss;
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
_Static_assert(sizeof(xstate_legacy_t) == xstate_legacy_size,
    "invalid XSAVE legacy area size");

typedef struct xstate_header {
    uint64_t state_bv;
    uint64_t compacted_bv;
    uint64_t reserved[6];
} xstate_header_t;
_Static_assert(sizeof(xstate_header_t) == xstate_header_size,
    "invalid XSAVE header size");

typedef struct xstate {
    xstate_legacy_t legacy;
    xstate_header_t header;
    uint8_t ymm_high[xstate_ymm_size];
} __attribute__((aligned(64))) xstate_t;
_Static_assert(sizeof(xstate_t) == xstate_size, "invalid XSAVE area size");
_Static_assert(offsetof(xstate_t, header) == xstate_header_offset,
    "invalid XSAVE header offset");
_Static_assert(offsetof(xstate_t, ymm_high) == xstate_ymm_offset,
    "invalid XSAVE YMM offset");

typedef struct kernel_entry_frame {
    pt_regs_t regs;
    void *hardware_frame;
    xstate_t xstate;
} __attribute__((aligned(64))) kernel_entry_frame_t;
_Static_assert(offsetof(kernel_entry_frame_t, xstate) == 256,
    "invalid XSAVE area offset");
_Static_assert(sizeof(kernel_entry_frame_t) == KERNEL_ENTRY_FRAME_SIZE_VALUE,
    "invalid kernel entry frame size");

typedef struct fast_task fast_task_t;
typedef struct fast_cpu fast_cpu_t;
typedef struct { uint64_t user, system; } cpu_times_t;

struct fast_cpu {
    fast_task_t *current, *idle;
    fast_task_t *head, *tail, *sleepers;
    uint64_t *active_clock;
    const uint64_t *slices;
    size_t *counts;
    uint64_t lapic_id, queue_size, accounted_at, wake_sequence;
    uint64_t timer_deadline, quantum_deadline, worker_deadline;
    uint32_t levels, strongest, logical_id;
    uint8_t state, lock, reschedule, worker_pending, device_pending, balance, is_bsp;
    uint8_t mutator_pending;
} __attribute__((aligned(64)));

typedef struct {
    uint64_t r15, r14, r13, r12;
    uint64_t rbx, rbp, rip;
} switch_frame_t;
_Static_assert(sizeof(switch_frame_t) == 56, "invalid switch frame layout");

typedef struct { uint64_t root, active[]; } vm_space_t;

struct fast_task {
    uint64_t rsp;
    vm_space_t *vm;
    uint64_t kernel_rsp;
    uint64_t kernel_fs_base;
    uint64_t user_gs_base;
    fast_task_t *next, *previous, *child;
    uint64_t deadline;
    fast_cpu_t *cpu;
    uint32_t id, priority;
    uint8_t on_cpu;
    uint8_t *affinity;
    size_t affinity_size;
    uint8_t state, queued;
    bool mutator_turn;
    uint64_t wake_sequence;
    uint8_t user_interrupt_pending;
    cpu_times_t clock;
};

extern const xstate_t initial_xstate;
extern void (*const user_task_entry)(void);
