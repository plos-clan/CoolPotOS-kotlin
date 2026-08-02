#pragma once

#include <limine.h>
#include "os_terminal.h"

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
extern volatile struct limine_module_request module_request;
extern volatile struct limine_executable_cmdline_request cmdline_request;

void gdt_setup(void);
void idt_setup(void);
void ap_gdt_setup(uint64_t lapic_id);
void set_kernel_stack(uint64_t lapic_id, uint64_t rsp, uint8_t is_bsp);
uint64_t read_cr3(void);
uint64_t read_cr2(void);
void write_cr3(uint64_t value);
void invlpg(uint64_t address);
uint64_t rdmsr(uint32_t msr);
void wrmsr(uint32_t msr, uint64_t value);
void io_out8(uint16_t port, uint8_t value);
void enable_interrupt(void);
void disable_interrupt(void);
uint64_t get_sys_clone_recorded_count(void);
uint64_t get_sys_clone_stack_at(uint64_t index);
uint64_t get_sys_clone_tls_at(uint64_t index);
uint64_t get_kernel_idle_entry_address(void);
uint64_t get_kernel_clone_thread_entry_address(void);
uint64_t get_asm_syscall_handle_address(void);
void setup_syscall_cpu(uint64_t lapic_id, uint8_t is_bsp);
void wait_for_interrupt(void);
void asm_pause(void);
bool runtime_vm_add_region(void *base, size_t size);
void runtime_clock_configure_hpet(void *base, uint64_t period_femtoseconds);
extern void (*ap_start_ptr)(struct limine_mp_info *);
void *__rtld_allocateTcb(void);
void asm_syscall_handle(void);

void *malloc(size_t size);
void free(void *ptr);

void register_interrupt_handler(
    uint16_t vector,
    void (*handler)(void *, uint64_t, uint64_t),
    uint8_t ist,
    uint8_t flags
);

#ifdef __cplusplus
}
#endif
