#include <bridge.h>

extern void setup_simd(void);
extern void kt_ap_start(void);

void _ap_start(struct limine_mp_info *) {
    disable_interrupt();
    setup_simd();
   // kt_ap_start();
    for (;;) {
        __asm__ volatile ("hlt");
    }
}

void (*ap_start_ptr)(struct limine_mp_info *) = _ap_start;
