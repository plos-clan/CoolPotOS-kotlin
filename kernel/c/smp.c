#include "bridge.h"
#include "native.h"

cpu_local_t locals[cpu_slot_count];

struct tcb_layout {
    struct tcb_layout *self_pointer;
    __SIZE_TYPE__ dtv_size;
    void **dtv_pointers;
    int tid;
    int did_exit;
};

static __attribute__((noreturn)) void ap_start(struct limine_mp_info *info) {
    disable_interrupt();
    idt_load();
    setup_xstate();
    wrmsr(ia32_fs_base_msr, info->extra_argument);
    kernel_runtime_fs_bases[info->lapic_id % cpu_slot_count] = info->extra_argument;
    ((struct tcb_layout *)info->extra_argument)->tid = (int)allocate_runtime_tid();

    kt_ap_start();
    for (;;) __asm__ volatile("hlt");
}

void (*ap_start_ptr)(struct limine_mp_info *) = ap_start;
