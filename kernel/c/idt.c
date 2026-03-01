#include "bridge.h"

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

typedef void (*kotlin_interrupt_handler_t)(interrupt_frame_t *frame, uint64_t error_code);

static struct idt_register idt_pointer;
static struct idt_entry idt_entries[256];
static kotlin_interrupt_handler_t kotlin_handle[256];

__attribute__((noinline, force_align_arg_pointer))
static void dispatch_kotlin_handler(
    kotlin_interrupt_handler_t handler,
    interrupt_frame_t *frame,
    uint64_t error_code
) {
    handler(frame, error_code);
}

static __attribute__((noreturn)) void halt_forever(void) {
    for (;;) {
        __asm__ volatile("cli; hlt");
    }
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
        dispatch_kotlin_handler(handler, frame, 0); \
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
        dispatch_kotlin_handler(handler, frame, error_code); \
    }
EXCEPTION_WITH_ERROR_CODE_LIST
#undef X

static void *const exception_entry_stub[32] = {
#define X(vector) [vector] = (void *)isr_no_error_##vector,
    EXCEPTION_NO_ERROR_CODE_LIST
#undef X
#define X(vector) [vector] = (void *)isr_with_error_##vector,
    EXCEPTION_WITH_ERROR_CODE_LIST
#undef X
};

void idt_setup() {
    for (uint16_t vector = 0; vector < 256; vector++) {
        idt_entries[vector] = (struct idt_entry){0};
        kotlin_handle[vector] = 0;
    }

    for (uint16_t vector = 0; vector < 32; vector++) {
        if (!exception_entry_stub[vector]) {
            continue;
        }
        set_idt_gate(vector, exception_entry_stub[vector], vector == 8 ? 1 : 0, 0x8e);
    }

    idt_pointer.size = (uint16_t)(sizeof(idt_entries) - 1u);
    idt_pointer.ptr = idt_entries;
    __asm__ volatile("lidt %0" : : "m"(idt_pointer) : "memory");
}

void register_interrupt_handler(
    uint16_t vector,
    void (*handler)(void *interrupt_frame, uint64_t error_code),
    const uint8_t ist,
    const uint8_t flags
) {
    if (vector >= 32 || !exception_entry_stub[vector]) {
        return;
    }

    uint8_t effective_ist = vector == 8 ? 1 : 0;
    (void)ist;

    kotlin_handle[vector] = (kotlin_interrupt_handler_t)handler;
    set_idt_gate(vector, exception_entry_stub[vector], effective_ist, flags);
}
