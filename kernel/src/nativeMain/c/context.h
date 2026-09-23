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
#define CPOS_SAVE_GENERAL CPOS_GENERAL_REGISTERS(CPOS_SAVE_REGISTER)
#define CPOS_LOAD_GENERAL CPOS_GENERAL_REGISTERS(CPOS_LOAD_REGISTER)

#define CPOS_LOAD_USER_FS \
    "movq 128(%r13), %rsi\n" \
    "movl $0xc0000100, %edi\n" \
    "call wrmsr\n"

enum {
    cpu_slot_count = 256,
    cpu_account_stride = 64 / sizeof(uint64_t),
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
typedef struct fast_node {
    struct fast_node *next, *previous, *child;
    uint64_t key;
    fast_task_t *task;
} fast_node_t;
typedef struct cpu_account {
    struct cpu_account *parent;
    uint64_t time[] __attribute__((aligned(64)));
} cpu_account_t;

typedef struct {
    uint64_t r15, r14, r13, r12;
    uint64_t rbx, rbp, rip;
} switch_frame_t;
_Static_assert(sizeof(switch_frame_t) == 56, "invalid switch frame layout");

struct fast_task {
    uint64_t rsp;
    uint64_t cr3;
    uint64_t kernel_rsp;
    uint64_t kernel_fs_base;
    uint64_t user_gs_base;
    fast_task_t *next;
    fast_node_t run, sleep;
    fast_cpu_t *cpu;
    uint64_t quantum_cycles;
    uint32_t id, weight;
    uint8_t on_cpu, sleeping, user_mode;
    uint8_t *affinity;
    size_t affinity_size;
    cpu_account_t *account;
    uint8_t state;
    uint8_t queued;
    uint64_t wake_sequence;
    uint8_t user_interrupt_pending;
    struct {
        uint64_t elapsed;
        uint64_t started;
        uint64_t sequence;
    } clock;
};

extern const xstate_t initial_xstate;
extern void (*const user_task_entry)(void);
