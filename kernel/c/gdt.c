#include "bridge.h"
#include "native.h"

static gdt_entries_t gdt_entries;
static struct gdt_register gdt_pointer;
static tss_t tss0;
static tss_stack_t tss_stack __attribute__((aligned(16)));

static __attribute__((naked)) void _setcs_helper() {
    __asm__ volatile("pop %%rax\n\t"
                     "push %%rbx\n\t"
                     "push %%rax\n\t"
                     "lretq\n\t"::
            : "memory");
}

void tss_setup() {
    uint64_t address = (uint64_t) & tss0;
    uint64_t low_base = (address & 0xffffffU) << 16U;
    uint64_t mid_base = (address >> 24U & 0xffU) << 56U;
    uint64_t high_base = address >> 32U;
    uint64_t access_byte = (uint64_t) 0x89U << 40U;
    uint64_t limit = (uint32_t)(sizeof(tss_t) - 1U);
    gdt_entries[5] = low_base | mid_base | limit | access_byte;
    gdt_entries[6] = high_base;

    tss0.ist[0] = ((uint64_t) & tss_stack + sizeof(tss_stack_t)) & ~0xfULL;

    __asm__ volatile("ltr %[offset];" : : [offset] "rm"(0x28U) : "memory");
}

void ap_gdt_setup(uint64_t lapic_id) {
    cpu_local_t *local = &locals[lapic_id];
    local->gdt_entries[0] = 0x0000000000000000U;
    local->gdt_entries[1] = 0x00a09a0000000000U;
    local->gdt_entries[2] = 0x00c0920000000000U;
    local->gdt_entries[3] = 0x00c0f20000000000U;
    local->gdt_entries[4] = 0x00a0fa0000000000U;


    local->gdt_pointer.size = (uint16_t)((uint32_t)sizeof(gdt_entries_t) - 1U);
    local->gdt_pointer.ptr  = &local->gdt_entries;
    __asm__ volatile("lgdt %[ptr]\n\t"
                     "call *%%rax\n\t"
                     "mov %[dseg], %%ss\n\t"
            :
            : [ptr] "m"(local->gdt_pointer),
    [dseg] "rm"((uint16_t) 0x10U),
            "a"(&_setcs_helper),
            "b"((uint16_t) 0x8U)
    : "memory");

    uint64_t address = (uint64_t) &local->tss0;
    uint64_t low_base = (address & 0xffffffU) << 16U;
    uint64_t mid_base = (address >> 24U & 0xffU) << 56U;
    uint64_t high_base = address >> 32U;
    uint64_t access_byte = (uint64_t) 0x89U << 40U;
    uint64_t limit = (uint32_t)(sizeof(tss_t) - 1U);
    local->gdt_entries[5] = low_base | mid_base | limit | access_byte;
    local->gdt_entries[6] = high_base;

    local->tss0.ist[0] = ((uint64_t) &local->tss_stack + sizeof(tss_stack_t)) & ~0xfULL;

    __asm__ volatile("ltr %[offset];" : : [offset] "rm"(0x28U) : "memory");
}

void gdt_setup() {
    gdt_entries[0] = 0x0000000000000000U;
    gdt_entries[1] = 0x00a09a0000000000U;
    gdt_entries[2] = 0x00c0920000000000U;
    gdt_entries[3] = 0x00c0f20000000000U;
    gdt_entries[4] = 0x00a0fa0000000000U;

    gdt_pointer = (struct gdt_register) {
            .size = (uint16_t)((uint32_t)sizeof(gdt_entries_t) - 1U),
            .ptr  = &gdt_entries,
    };

    __asm__ volatile("lgdt %[ptr]\n\t"
                     "call *%%rax\n\t"
                     "mov %[dseg], %%ss\n\t"
            :
            : [ptr] "m"(gdt_pointer),
    [dseg] "rm"((uint16_t) 0x10U),
            "a"(&_setcs_helper),
            "b"((uint16_t) 0x8U)
    : "memory");

    tss_setup();
}

void set_kernel_stack(uint64_t lapic_id,uint64_t rsp) {
    if(lapic_id == 0) {
        tss0.rsp[0] = rsp;
        return;
    }
    cpu_local_t *local = &locals[lapic_id];
    local->tss0.rsp[0] = rsp;
}
