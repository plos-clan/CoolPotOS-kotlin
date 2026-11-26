#include "bridge.h"
#include "native.h"

enum {
    idt_vector_count = 256,
    irq_vector_base = 32,
    irq_stub_size = 10,
    nmi_vector = 2,
    breakpoint_vector = 3,
    double_fault_vector = 8,
    page_fault_vector = 14,
};

struct idt_entry {
    uint16_t offset_low;
    uint16_t selector;
    uint8_t ist;
    uint8_t flags;
    uint16_t offset_mid;
    uint32_t offset_hi;
    uint32_t reserved;
} __attribute__((packed));
_Static_assert(sizeof(struct idt_entry) == 16, "invalid IDT entry layout");

typedef struct interrupt_frame {
    uint64_t rip;
    uint64_t cs;
    uint64_t rflags;
    uint64_t rsp;
    uint64_t ss;
} __attribute__((packed)) interrupt_frame_t;
_Static_assert(sizeof(interrupt_frame_t) == 40, "invalid interrupt frame layout");

extern uint8_t irq_stub_base[];

static struct idt_entry idt_entries[idt_vector_count];
static const descriptor_table_register_t idt_pointer = {
    .size = sizeof(idt_entries) - 1,
    .ptr = idt_entries,
};

__attribute__((noinline, force_align_arg_pointer))
static void dispatch_exception(
    int32_t vector,
    interrupt_frame_t *frame,
    uint64_t error_code,
    uint64_t rbp,
    uint64_t fault_address
) {
    xstate_t xstate;
    enter_kernel_xstate(&xstate);

    const bool from_user = (frame->cs & 3u) != 0;
    uint64_t user_fs_base = 0;

    if (from_user) {
        __asm__ volatile("swapgs" : : : "memory");
        user_fs_base = enter_kernel_tls();
    }

    exception_handler(frame, error_code, rbp, fault_address, vector);

    if (from_user) {
        leave_kernel_tls(user_fs_base);
        __asm__ volatile("swapgs" : : : "memory");
    }

    restore_xstate(&xstate);
}

static __attribute__((noreturn)) void halt_forever(void) {
    for (;;) __asm__ volatile("cli; hlt");
}

__attribute__((interrupt)) static void handle_nmi(interrupt_frame_t *) {
    if (!tlb_handle_nmi()) halt_forever();
}

static inline __attribute__((always_inline)) uint64_t read_rbp(void) {
    uint64_t rbp;
    __asm__ volatile("movq (%%rbp), %0" : "=r"(rbp));
    return rbp;
}

static void set_idt_gate(uint16_t vector, void *handler) {
    const uint64_t address = (uint64_t)handler;
    uint8_t ist = 0;
    if (vector == nmi_vector) ist = 2;
    if (vector == double_fault_vector) ist = 1;
    idt_entries[vector] = (struct idt_entry){
        .offset_low = address,
        .selector = 0x08,
        .ist = ist,
        .flags = vector == breakpoint_vector ? 0xee : 0x8e,
        .offset_mid = address >> 16,
        .offset_hi = address >> 32,
    };
}

#define EXCEPTION_NO_ERROR_CODE_LIST \
    X(0) X(1) X(3) X(4) X(5) X(6) X(7) X(9) X(15) X(16) X(18) X(19) \
    X(20) X(22) X(23) X(24) X(25) X(26) X(27) X(28) X(31)

#define EXCEPTION_WITH_ERROR_CODE_LIST \
    X(8) X(10) X(11) X(12) X(13) X(14) X(17) X(21) X(29) X(30)

#define DEFINE_EXCEPTION(vector, arguments, error) \
    __attribute__((interrupt)) static void isr_##vector arguments { \
        const uint64_t address = vector == page_fault_vector ? read_cr2() : 0; \
        dispatch_exception(vector, frame, error, read_rbp(), address); \
    }
#define X(vector) DEFINE_EXCEPTION(vector, (interrupt_frame_t *frame), 0)
EXCEPTION_NO_ERROR_CODE_LIST
#undef X
#define X(vector) DEFINE_EXCEPTION(vector, (interrupt_frame_t *frame, uint64_t error_code), error_code)
EXCEPTION_WITH_ERROR_CODE_LIST
#undef X
#undef DEFINE_EXCEPTION

static void *const exception_entry_stub[irq_vector_base] = {
#define X(vector) [vector] = (void *)isr_##vector,
    EXCEPTION_NO_ERROR_CODE_LIST
    EXCEPTION_WITH_ERROR_CODE_LIST
#undef X
};

static __attribute__((naked, used)) void irq_common_entry(void) {
    __asm__ volatile(
        ".cfi_undefined %rip\n"
        "cld\n"
        "pushq %rax\n"
        "leaq 8(%rsp), %rax\n"
        "andq $-64, %rsp\n"
        "subq $" CPOS_ASM_STRINGIFY(KERNEL_ENTRY_FRAME_SIZE_VALUE) ", %rsp\n"
        CPOS_SAVE_GENERAL
        "movq -8(%rax), %rdx\n"
        "movq %rdx, 136(%rsp)\n"
        "movq %rax, 200(%rsp)\n"
        "movq %rax, %rdx\n"
        "movl %ds, %eax\n"
        "movq %rax, 112(%rsp)\n"
        "movl %es, %eax\n"
        "movq %rax, 120(%rsp)\n"
        "movq (%rdx), %rax\n"
        "movq %rax, 144(%rsp)\n"
        "movq $0, 152(%rsp)\n"
        "movq 8(%rdx), %rax\n"
        "movq %rax, 160(%rsp)\n"
        "movq 16(%rdx), %rax\n"
        "movq %rax, 168(%rsp)\n"
        "movq 24(%rdx), %rax\n"
        "movq %rax, 176(%rsp)\n"

        "testb $3, 16(%rdx)\n"
        "jz 1f\n"
        "movq 32(%rdx), %rax\n"
        "movq %rax, 184(%rsp)\n"
        "movq 40(%rdx), %rax\n"
        "movq %rax, 192(%rsp)\n"
        "swapgs\n"
        "call enter_kernel_tls\n"
        "movq %rax, 128(%rsp)\n"
        "jmp 2f\n"
        "1:\n"
        "leaq 32(%rdx), %rax\n"
        "movq %rax, 184(%rsp)\n"
        "movl %ss, %eax\n"
        "movq %rax, 192(%rsp)\n"
        "2:\n"
        "movq 200(%rsp), %rdx\n"
        "movq %rsp, %r13\n"
        "movq %rsp, %rdi\n"
        "movq (%rdx), %rsi\n"
        "call fast_handoff_irq\n"
        "testb %al, %al\n"
        "jz 4f\n"
        "movl $" CPOS_ASM_STRINGIFY(XSTATE_MASK_VALUE) ", %eax\n"
        "xorl %edx, %edx\n"
        "xrstor64 256(%r13)\n"
        "4:\n"

        "movq 200(%r13), %r12\n"
        "movq 160(%r13), %rax\n"
        "movq %rax, 8(%r12)\n"
        "movq 168(%r13), %rax\n"
        "movq %rax, 16(%r12)\n"
        "movq 176(%r13), %rax\n"
        "movq %rax, 24(%r12)\n"

        "testb $3, 168(%r13)\n"
        "jz 3f\n"
        "movq 184(%r13), %rax\n"
        "movq %rax, 32(%r12)\n"
        "movq 192(%r13), %rax\n"
        "movq %rax, 40(%r12)\n"
        CPOS_LOAD_USER_FS
        "swapgs\n"
        "3:\n"
        CPOS_RESTORE_SEGMENT(ds, 112)
        CPOS_RESTORE_SEGMENT(es, 120)
        CPOS_LOAD_GENERAL
        "movq 32(%r13), %r11\n"
        "movq 72(%r13), %rcx\n"
        "movq 136(%r13), %rax\n"
        "leaq 8(%r12), %rsp\n"
        "movq 24(%r13), %r12\n"
        "movq 16(%r13), %r13\n"
        "iretq\n"
    );
}

__asm__(
".global irq_stub_base\n"
"irq_stub_base:\n"
".set irq_num, 1\n"
".rept 224\n"
"    .byte 0x68\n"
"    .long irq_num\n"
"    jmp irq_common_entry\n"
"    .set irq_num, irq_num + 1\n"
".endr\n"
);

void idt_load(void) {
    __asm__ volatile("lidt %0" : : "m"(idt_pointer) : "memory");
}

void idt_setup(uint32_t exception_mask) {
    for (uint16_t vector = 0; vector < irq_vector_base; vector++) {
        void *entry = (void *)halt_forever;
        if (vector == nmi_vector) entry = (void *)handle_nmi;
        else if (exception_mask & (1u << vector)) entry = exception_entry_stub[vector];
        set_idt_gate(vector, entry);
    }

    for (uint16_t vector = irq_vector_base; vector < idt_vector_count; vector++) {
        const uint16_t irq_index = (uint16_t)(vector - irq_vector_base);
        uint8_t *stub = irq_stub_base + ((uint64_t)irq_index * irq_stub_size);

        set_idt_gate(vector, stub);
    }
    idt_load();
}
