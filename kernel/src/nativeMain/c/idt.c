#include "bridge.h"
#include "native.h"

enum {
    idt_vector_count = 256,
    irq_vector_base = 32,
    irq_stub_size = 10,
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

typedef void (*kotlin_interrupt_handler_t)(
    interrupt_frame_t *frame,
    uint64_t error_code,
    uint64_t rbp
);

extern uint8_t irq_stub_base[];

static struct idt_entry idt_entries[idt_vector_count];
static kotlin_interrupt_handler_t kotlin_handlers[idt_vector_count];
static const descriptor_table_register_t idt_pointer = {
    .size = sizeof(idt_entries) - 1,
    .ptr = idt_entries,
};

__attribute__((noinline, force_align_arg_pointer))
static void dispatch_kotlin_handler(
    kotlin_interrupt_handler_t handler,
    interrupt_frame_t *frame,
    uint64_t error_code,
    uint64_t rbp
) {
    xstate_t xstate;
    initialize_xstate_header(&xstate);
    save_xstate(&xstate);
    restore_xstate(&initial_xstate);

    const bool from_user = (frame->cs & 3u) != 0;
    uint64_t user_fs_base = 0;

    if (from_user) {
        user_fs_base = rdmsr(ia32_fs_base_msr);
        __asm__ volatile("swapgs" : : : "memory");
        uint64_t kernel_fs_base;
        __asm__ volatile("movq %%gs:24, %0" : "=r"(kernel_fs_base));
        wrmsr(ia32_fs_base_msr, kernel_fs_base);
    }

    handler(frame, error_code, rbp);

    if (from_user) {
        wrmsr(ia32_fs_base_msr, user_fs_base);
        __asm__ volatile("swapgs" : : : "memory");
    }

    restore_xstate(&xstate);
}

static __attribute__((noreturn)) void halt_forever(void) {
    for (;;) __asm__ volatile("cli; hlt");
}

static inline __attribute__((always_inline)) uint64_t read_rbp(void) {
    uint64_t rbp;
    __asm__ volatile("movq (%%rbp), %0" : "=r"(rbp));
    return rbp;
}

static void set_idt_gate(uint16_t vector, void *handler, uint8_t ist, uint8_t flags) {
    const uint64_t address = (uint64_t)handler;
    idt_entries[vector] = (struct idt_entry){
        .offset_low = address,
        .selector = 0x08,
        .ist = ist & 0x7u,
        .flags = flags,
        .offset_mid = address >> 16,
        .offset_hi = address >> 32,
    };
}

#define EXCEPTION_NO_ERROR_CODE_LIST \
    X(0) X(1) X(2) X(3) X(4) X(5) X(6) X(7) X(9) X(15) X(16) X(18) X(19) \
    X(20) X(22) X(23) X(24) X(25) X(26) X(27) X(28) X(31)

#define EXCEPTION_WITH_ERROR_CODE_LIST \
    X(8) X(10) X(11) X(12) X(13) X(14) X(17) X(21) X(29) X(30)

#define X(vector) \
    __attribute__((interrupt)) static void isr_no_error_##vector(interrupt_frame_t *frame) { \
        kotlin_interrupt_handler_t handler = kotlin_handlers[vector]; \
        if (!handler) halt_forever(); \
        dispatch_kotlin_handler(handler, frame, 0, read_rbp()); \
    }
EXCEPTION_NO_ERROR_CODE_LIST
#undef X

#define X(vector) \
    __attribute__((interrupt)) static void isr_with_error_##vector(interrupt_frame_t *frame, uint64_t error_code) { \
        kotlin_interrupt_handler_t handler = kotlin_handlers[vector]; \
        if (!handler) halt_forever(); \
        dispatch_kotlin_handler(handler, frame, error_code, read_rbp()); \
    }
EXCEPTION_WITH_ERROR_CODE_LIST
#undef X

static void *const exception_entry_stub[irq_vector_base] = {
#define X(vector) [vector] = (void *)isr_no_error_##vector,
    EXCEPTION_NO_ERROR_CODE_LIST
#undef X
#define X(vector) [vector] = (void *)isr_with_error_##vector,
    EXCEPTION_WITH_ERROR_CODE_LIST
#undef X
};

static __attribute__((naked, used)) void irq_common_entry(void) {
    __asm__ volatile(
        "pushq %rax\n"
        "leaq 8(%rsp), %rax\n"
        "andq $-64, %rsp\n"
        "subq $" CPOS_ASM_STRINGIFY(KERNEL_ENTRY_FRAME_SIZE_VALUE) ", %rsp\n"
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
        "movq -8(%rax), %rdx\n"
        "movq %rdx, 136(%rsp)\n"
        "movq %rax, 200(%rsp)\n"
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
        "xorq %rax, %rax\n"
        "movq 200(%rsp), %rdx\n"
        "movq (%rdx), %rax\n"
        "movq %rax, 144(%rsp)\n"
        "xorq %rax, %rax\n"
        "movq %rax, 152(%rsp)\n"
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
        "jmp 2f\n"
        "1:\n"
        "leaq 32(%rdx), %rax\n"
        "movq %rax, 184(%rsp)\n"
        "xorq %rax, %rax\n"
        "movw %ss, %ax\n"
        "movq %rax, 192(%rsp)\n"
        "2:\n"
        "movq %gs:24, %rax\n"
        "movq %rax, %rdx\n"
        "shrq $32, %rdx\n"
        "movl $0xc0000100, %ecx\n"
        "wrmsr\n"
        "movq 200(%rsp), %rdx\n"
        "movq %rsp, %r13\n"
        "movq %rsp, %rdi\n"
        "movq (%rdx), %rsi\n"
        "andq $-16, %rsp\n"
        "call fast_handoff_irq\n"
        "movq %r13, %rsp\n"
        "testb %al, %al\n"
        "jz 4f\n"
        "movl $" CPOS_ASM_STRINGIFY(XSTATE_MASK_VALUE) ", %eax\n"
        "xorl %edx, %edx\n"
        "xrstor64 256(%r13)\n"
        "4:\n"
        "movq 128(%r13), %rax\n"
        "movq %rax, %rdx\n"
        "shrq $32, %rdx\n"
        "movl $0xc0000100, %ecx\n"
        "wrmsr\n"

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
        "swapgs\n"
        "3:\n"
        "movq 112(%r13), %rax\n"
        "movw %ax, %ds\n"
        "movq 120(%r13), %rax\n"
        "movw %ax, %es\n"
        "movq 0(%r13), %r15\n"
        "movq 8(%r13), %r14\n"
        "movq 32(%r13), %r11\n"
        "movq 40(%r13), %r10\n"
        "movq 48(%r13), %r9\n"
        "movq 56(%r13), %r8\n"
        "movq 64(%r13), %rbx\n"
        "movq 72(%r13), %rcx\n"
        "movq 88(%r13), %rsi\n"
        "movq 96(%r13), %rdi\n"
        "movq 104(%r13), %rbp\n"
        "movq 136(%r13), %rax\n"
        "movq 80(%r13), %rdx\n"
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

void idt_setup(void) {
    __builtin_memset(idt_entries, 0, sizeof(idt_entries));
    __builtin_memset(kotlin_handlers, 0, sizeof(kotlin_handlers));
    for (uint16_t vector = 0; vector < irq_vector_base; vector++) {
        if (!exception_entry_stub[vector]) continue;
        set_idt_gate(vector, exception_entry_stub[vector], vector == 8 ? 1 : 0, 0x8e);
    }

    for (uint16_t vector = irq_vector_base; vector < idt_vector_count; vector++) {
        const uint16_t irq_index = (uint16_t)(vector - irq_vector_base);
        uint8_t *stub = irq_stub_base + ((uint64_t)irq_index * irq_stub_size);

        set_idt_gate(vector, stub, 0, 0x8e);
    }
    idt_load();
}

void register_interrupt_handler(
    uint16_t vector,
    void (*handler)(void *interrupt_frame, uint64_t error_code, uint64_t rbp),
    uint8_t ist,
    uint8_t flags
) {
    if (vector >= irq_vector_base || !exception_entry_stub[vector]) {
        return;
    }

    kotlin_handlers[vector] = (kotlin_interrupt_handler_t)handler;
    set_idt_gate(vector, exception_entry_stub[vector], vector == 8 ? 1 : ist, flags);
}
