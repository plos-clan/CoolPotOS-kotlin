#include <bridge.h>
#include "native.h"

cpu_local_t locals[256];

struct idt_register {
    uint16_t size;
    void *ptr;
} __attribute__((packed));

struct Tcb {
    struct Tcb *selfPointer;
    __SIZE_TYPE__ dtvSize;
    void **dtvPointers;
    int tid;
    int didExit;
};

extern void setup_simd(void);
extern void kt_ap_start(void);
extern uint64_t kernel_runtime_fs_bases[256];

extern struct idt_register idt_pointer;

static _Atomic uint64_t tid = 4;

void _ap_start(struct limine_mp_info *info) {
    disable_interrupt();
    __asm__ volatile("lidt %0" : : "m"(idt_pointer) : "memory");
    setup_simd();
    wrmsr(0xC0000100, info->extra_argument); // write fs tls
    kernel_runtime_fs_bases[info->lapic_id & 0xffu] = info->extra_argument;

    struct Tcb *tcb = (struct Tcb*)info->extra_argument;
    tcb->tid = tid++;

    kt_ap_start();
    for (;;) {
        __asm__ volatile ("hlt");
    }
}

void (*ap_start_ptr)(struct limine_mp_info *) = _ap_start;
