#ifndef BRIDGE_H
#define BRIDGE_H

#include <limine.h>

#ifdef __cplusplus
extern "C" {
#endif

extern volatile struct limine_framebuffer_request framebuffer_request;
extern volatile struct limine_stack_size_request stack_size_request;
extern volatile struct limine_hhdm_request hhdm_request;
extern volatile struct limine_memmap_request memmap_request;
extern volatile struct limine_mp_request mp_request;
extern volatile struct limine_rsdp_request rsdp_request;
extern volatile struct limine_executable_file_request executable_file_request;

extern void gdt_setup();
extern void idt_setup();

extern uint64_t read_cr3(void);
extern uint64_t read_cr2(void);
extern void invlpg(uint64_t address);

extern void register_interrupt_handler(
    uint16_t vector,
    void (*handler)(void *, uint64_t, uint64_t),
    const uint8_t ist,
    const uint8_t flags
);

#ifdef __cplusplus
}
#endif

#endif
