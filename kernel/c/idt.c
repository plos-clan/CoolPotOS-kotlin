#include "bridge.h"

enum {
    idt_vector_count = 256,
    irq_vector_base = 32,
    irq_stub_size = 10,
    irq_stub_count = idt_vector_count - irq_vector_base
};

struct idt_register {
    uint16_t size;
    void *ptr;
} __attribute__((packed));

struct idt_entry {
    uint16_t offset_low;
    uint16_t selector;
    uint8_t ist;
    uint8_t flags;
    uint16_t offset_mid;
    uint32_t offset_hi;
    uint32_t reserved;
} __attribute__((packed));

typedef struct interrupt_frame {
    uint64_t rip;
    uint64_t cs;
    uint64_t rflags;
    uint64_t rsp;
    uint64_t ss;
} __attribute__((packed)) interrupt_frame_t;

struct pt_regs {
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
    uint64_t rax;
    uint64_t func;
    uint64_t errcode;
    uint64_t rip;
    uint64_t cs;
    uint64_t rflags;
    uint64_t rsp;
    uint64_t ss;
} __attribute__((packed));

typedef void (*kotlin_interrupt_handler_t)(
    interrupt_frame_t *frame,
    uint64_t error_code,
    uint64_t rbp
);

extern void do_irq(void *regs, uint64_t irq_num);
extern uint8_t irq_stub_base[];

static struct idt_register idt_pointer;
static struct idt_entry idt_entries[idt_vector_count];
static kotlin_interrupt_handler_t kotlin_handle[idt_vector_count];

__attribute__((noinline, force_align_arg_pointer))
static void dispatch_kotlin_handler(
    kotlin_interrupt_handler_t handler,
    interrupt_frame_t *frame,
    uint64_t error_code,
    uint64_t rbp
) {
    handler(frame, error_code, rbp);
}

static __attribute__((noreturn)) void halt_forever(void) {
    for (;;) {
        __asm__ volatile("cli; hlt");
    }
}

static inline __attribute__((always_inline)) uint64_t read_rbp(void) {
    uint64_t rbp;
    __asm__ volatile ("movq (%%rbp), %0" : "=r"(rbp));
    return rbp;
}

static void set_idt_gate(uint16_t vector, void *handler, uint8_t ist, uint8_t flags) {
    const uint64_t address = (uint64_t)handler;
    struct idt_entry *entry = &idt_entries[vector];

    entry->offset_low = (uint16_t)(address & 0xffffu);
    entry->selector = 0x08;
    entry->ist = ist & 0x7u;
    entry->flags = flags;
    entry->offset_mid = (uint16_t)((address >> 16) & 0xffffu);
    entry->offset_hi = (uint32_t)((address >> 32) & 0xffffffffu);
    entry->reserved = 0;
}

#define EXCEPTION_NO_ERROR_CODE_LIST \
    X(0) X(2) X(5) X(6) X(7) X(9) X(15) X(16) X(18) X(19) X(20) X(22) X(23) X(24) X(25) X(26) X(27) X(28) X(31)

#define EXCEPTION_WITH_ERROR_CODE_LIST \
    X(8) X(10) X(11) X(12) X(13) X(14) X(17) X(21) X(29) X(30)

#define X(vector) \
    __attribute__((interrupt)) static void isr_no_error_##vector(interrupt_frame_t *frame) { \
        kotlin_interrupt_handler_t handler = kotlin_handle[vector]; \
        if (!handler) { \
            halt_forever(); \
        } \
        dispatch_kotlin_handler(handler, frame, 0, read_rbp()); \
    }
EXCEPTION_NO_ERROR_CODE_LIST
#undef X

#define X(vector) \
    __attribute__((interrupt)) static void isr_with_error_##vector(interrupt_frame_t *frame, uint64_t error_code) { \
        kotlin_interrupt_handler_t handler = kotlin_handle[vector]; \
        if (!handler) { \
            (void)error_code; \
            halt_forever(); \
        } \
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

__attribute__((naked)) void irq_common_entry(void) {
    __asm__ volatile(
        "subq $192, %rsp\n"
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
        "movq %rax, 128(%rsp)\n"
        "xorq %rax, %rax\n"
        "movw %ds, %ax\n"
        "movq %rax, 112(%rsp)\n"
        "xorq %rax, %rax\n"
        "movw %es, %ax\n"
        "movq %rax, 120(%rsp)\n"
        "xorq %rax, %rax\n"
        "leaq 192(%rsp), %rdx\n"
        "movq (%rdx), %rax\n"
        "movq %rax, 136(%rsp)\n"
        "xorq %rax, %rax\n"
        "movq %rax, 144(%rsp)\n"
        "movq 8(%rdx), %rax\n"
        "movq %rax, 152(%rsp)\n"
        "movq 16(%rdx), %rax\n"
        "movq %rax, 160(%rsp)\n"
        "movq 24(%rdx), %rax\n"
        "movq %rax, 168(%rsp)\n"
        "movq 32(%rdx), %rax\n"
        "movq %rax, 176(%rsp)\n"
        "movq 40(%rdx), %rax\n"
        "movq %rax, 184(%rsp)\n"
        "movq %rsp, %rdi\n"
        "movq (%rdx), %rsi\n"
        "call do_irq\n"
        "movq %rsp, %r13\n"
        "movq 176(%r13), %rdx\n"
        "subq $40, %rdx\n"
        "movq 152(%r13), %rax\n"
        "movq %rax, 0(%rdx)\n"
        "movq 160(%r13), %rax\n"
        "movq %rax, 8(%rdx)\n"
        "movq 168(%r13), %rax\n"
        "movq %rax, 16(%rdx)\n"
        "movq 176(%r13), %rax\n"
        "movq %rax, 24(%rdx)\n"
        "movq 184(%r13), %rax\n"
        "movq %rax, 32(%rdx)\n"
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
        "movq 128(%r13), %rax\n"
        "movq %rdx, %rsp\n"
        "movq 80(%r13), %rdx\n"
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

void idt_setup() {
    for (uint16_t vector = 0; vector < idt_vector_count; vector++) {
        idt_entries[vector] = (struct idt_entry){0};
        kotlin_handle[vector] = 0;
    }

    for (uint16_t vector = 0; vector < irq_vector_base; vector++) {
        if (!exception_entry_stub[vector]) {
            continue;
        }
        set_idt_gate(vector, exception_entry_stub[vector], vector == 8 ? 1 : 0, 0x8e);
    }

    for (uint16_t vector = irq_vector_base; vector < idt_vector_count; vector++) {
        const uint16_t irq_index = (uint16_t)(vector - irq_vector_base);
        if (irq_index >= irq_stub_count) {
            continue;
        }

        uint8_t *stub = irq_stub_base + ((uint64_t)irq_index * irq_stub_size);
        set_idt_gate(vector, stub, 0, 0x8e);
    }

    idt_pointer.size = (uint16_t)(sizeof(idt_entries) - 1u);
    idt_pointer.ptr = idt_entries;
    __asm__ volatile("lidt %0" : : "m"(idt_pointer) : "memory");
}

void register_interrupt_handler(
    uint16_t vector,
    void (*handler)(void *interrupt_frame, uint64_t error_code, uint64_t rbp),
    const uint8_t ist,
    const uint8_t flags
) {
    if (vector >= irq_vector_base || !exception_entry_stub[vector]) {
        return;
    }

    uint8_t effective_ist = vector == 8 ? 1 : 0;
    (void)ist;

    kotlin_handle[vector] = (kotlin_interrupt_handler_t)handler;
    set_idt_gate(vector, exception_entry_stub[vector], effective_ist, flags);
}
