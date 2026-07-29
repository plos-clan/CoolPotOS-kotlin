#include "bridge.h"
#include "native.h"

static gdt_entries_t gdt_entries;
static struct gdt_register gdt_pointer;
static tss_t tss0;
static tss_stack_t tss_stack __attribute__((aligned(16)));
static const uint64_t gdt_template[] = {
    0, 0x00a09a0000000000, 0x00c0920000000000,
    0x00c0f20000000000, 0x00a0fa0000000000
};

static __attribute__((naked)) void _setcs_helper() {
    __asm__ volatile("pop %%rax\n\t"
                     "push %%rbx\n\t"
                     "push %%rax\n\t"
                     "lretq\n\t"::
            : "memory");
}

static void setup_gdt(
    gdt_entries_t entries,
    struct gdt_register *pointer,
    tss_t *tss,
    tss_stack_t stack
) {
    for (uint8_t i = 0; i < sizeof(gdt_template) / sizeof(*gdt_template); i++)
        entries[i] = gdt_template[i];

    *pointer = (struct gdt_register){
        .size = sizeof(gdt_entries_t) - 1,
        .ptr = entries,
    };
    __asm__ volatile("lgdt %[ptr]\n\t"
                     "call *%%rax\n\t"
                     "mov %[dseg], %%ss\n\t"
            :
            : [ptr] "m"(*pointer),
              [dseg] "rm"((uint16_t)0x10U),
              "a"(&_setcs_helper),
              "b"((uint16_t)0x8U)
            : "memory");

    const uint64_t address = (uint64_t)tss;
    entries[5] = ((address & 0xffffffU) << 16U)
        | (((address >> 24U) & 0xffU) << 56U)
        | ((uint64_t)0x89U << 40U)
        | (sizeof(tss_t) - 1U);
    entries[6] = address >> 32U;
    tss->ist[0] = ((uint64_t)stack + sizeof(tss_stack_t)) & ~0xfULL;
    __asm__ volatile("ltr %[offset];" : : [offset] "rm"(0x28U) : "memory");
}

void ap_gdt_setup(uint64_t lapic_id) {
    cpu_local_t *local = &locals[lapic_id];
    setup_gdt(local->gdt_entries, &local->gdt_pointer, &local->tss0, local->tss_stack);
}

void gdt_setup() {
    setup_gdt(gdt_entries, &gdt_pointer, &tss0, tss_stack);
}

void set_kernel_stack(uint64_t lapic_id, uint64_t rsp, uint8_t is_bsp) {
    (is_bsp ? &tss0 : &locals[lapic_id].tss0)->rsp[0] = rsp;
}
