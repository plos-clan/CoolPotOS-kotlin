#include <bridge.h>

extern void setup_simd(void);
extern void kt_ap_start(void);

static _Atomic uint64_t tid = 4;

struct Tcb {
    struct Tcb *selfPointer;
    __SIZE_TYPE__ dtvSize;
    void **dtvPointers;
    int tid;
    int didExit;
};

void _ap_start(struct limine_mp_info *info) {
    disable_interrupt();
    setup_simd();
    wrmsr(0xC0000100, info->extra_argument); // write fs tls

    struct Tcb *tcb = (struct Tcb*)info->extra_argument;
    tcb->tid = tid++;

    kt_ap_start();
    for (;;) {
        __asm__ volatile ("hlt");
    }
}

void (*ap_start_ptr)(struct limine_mp_info *) = _ap_start;
