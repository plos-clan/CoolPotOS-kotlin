#include "bridge.h"
#include "native.h"

static gdt_entries_t gdt_entries;
static tss_t tss0;
static tss_stack_t tss_stack __attribute__((aligned(16)));
static const uint64_t gdt_template[] = {
    0, 0x00a09a0000000000, 0x00c0920000000000,
    0x00c0f20000000000, 0x00a0fa0000000000
};

static void setup_gdt(gdt_entries_t entries, tss_t *tss, tss_stack_t stack) {
    for (size_t i = 0; i < sizeof(gdt_template) / sizeof(*gdt_template); i++)
        entries[i] = gdt_template[i];

    const uint64_t address = (uint64_t)tss;
    entries[5] = ((address & 0xffffffU) << 16U)
        | (((address >> 24U) & 0xffU) << 56U)
        | ((uint64_t)0x89U << 40U)
        | (sizeof(*tss) - 1U);
    entries[6] = address >> 32U;
    tss->ist[0] = ((uint64_t)stack + sizeof(tss_stack_t)) & ~0xfULL;
    tss->io_map_base = sizeof(*tss);

    const descriptor_table_register_t pointer = {
        .size = sizeof(gdt_entries_t) - 1,
        .ptr = entries,
    };
    __asm__ volatile(
        "lgdt %[gdt]\n\t"
        "pushq $0x08\n\t"
        "leaq 1f(%%rip), %%rax\n\t"
        "pushq %%rax\n\t"
        "lretq\n\t"
        "1: movw $0x10, %%ax\n\t"
        "movw %%ax, %%ds\n\t"
        "movw %%ax, %%es\n\t"
        "movw %%ax, %%ss\n\t"
        "movw $0x28, %%ax\n\t"
        "ltr %%ax"
        :
        : [gdt] "m"(pointer)
        : "rax", "memory"
    );
}

void ap_gdt_setup(uint64_t lapic_id) {
    cpu_local_t *local = &locals[lapic_id % cpu_slot_count];
    setup_gdt(local->gdt_entries, &local->tss0, local->tss_stack);
}

void gdt_setup(void) {
    setup_gdt(gdt_entries, &tss0, tss_stack);
}

void set_kernel_stack(uint64_t lapic_id, uint64_t rsp, uint8_t is_bsp) {
    (is_bsp ? &tss0 : &locals[lapic_id % cpu_slot_count].tss0)->rsp[0] = rsp;
}
