#pragma once

#include <limine.h>
#include "os_terminal.h"
#include "vdso.h"
#include "context.h"

#ifdef __cplusplus
extern "C" {
#endif

extern volatile struct limine_framebuffer_request framebuffer_request;
extern volatile struct limine_hhdm_request hhdm_request;
extern volatile struct limine_memmap_request memmap_request;
extern volatile struct limine_mp_request mp_request;
extern volatile struct limine_rsdp_request rsdp_request;
extern volatile struct limine_executable_file_request executable_file_request;
extern volatile struct limine_executable_cmdline_request cmdline_request;
extern volatile struct limine_tsc_frequency_request tsc_frequency_request;

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
extern bool fs_base_instructions;
void enable_cr4(uint64_t flags);
void open_smap();
void close_smap();
uint8_t io_in8(uint16_t port);
uint16_t io_in16(uint16_t port);
uint32_t io_in32(uint16_t port);
void io_out8(uint16_t port, uint8_t value);
void io_out16(uint16_t port, uint16_t value);
void io_out32(uint16_t port, uint32_t value);
void enable_interrupt(void);
void disable_interrupt(void);
uint64_t irq_save(void);
void irq_restore(uint64_t flags);
bool can_use_runtime(void);
void set_runtime_use_mask(bool enabled);
uint64_t get_asm_syscall_handle_address(void);
void setup_syscall_cpu(uint64_t lapic_id, uint8_t is_bsp);
void wait_for_interrupt(void);
void cpu_relax(void);
void asm_pause(void);
void fast_handoff_configure_lapic(uint8_t x2apic, uint64_t mmio_base);
void fast_handoff_set_user_interrupt_handler(void (*handler)(void *frame));
void fast_handoff_request_user_interrupt(uint64_t task);
bool fast_handoff_yield(void);
uint64_t fast_handoff_prepare_park(void);
bool fast_handoff_park_current(uint64_t deadline_ns, uint64_t sequence);
bool fast_handoff_unpark(uint64_t task);
bool fast_handoff_affinity_settled(uint64_t task);
void fast_handoff_set_affinity(uint64_t task, uint8_t *mask, size_t size);
void fast_handoff_set_weight(uint64_t task, uint32_t weight);
void fast_handoff_account_mode(uint8_t user);
void fast_handoff_set_account(uint64_t task, cpu_account_t *account);
uint64_t fast_handoff_account_time(cpu_account_t *account, size_t index);
uint64_t fast_handoff_queue_size(uint64_t lapic_id);
uint64_t fast_handoff_service(void);
void fast_handoff_wake_bsp(void);
void fast_handoff_park_kotlin(uint64_t deadline_ns, uint64_t wake_sequence, bool may_sleep);
_Noreturn void fast_handoff_idle(void);
bool fast_handoff_configure_timer(uint8_t vector);
uint64_t fast_handoff_create_task(
    uint32_t id,
    uint64_t cr3,
    uint64_t kernel_rsp,
    uint64_t kernel_fs_base,
    uint64_t quantum_cycles, uint32_t weight
);
bool fast_handoff_bind_current(
    uint64_t task,
    uint64_t lapic_id,
    uint8_t is_bsp, uint32_t logical_id
);
bool fast_handoff_finish_bootstrap(uint64_t task);
bool fast_handoff_enqueue(uint64_t task, uint64_t lapic_id);
uint8_t fast_handoff_task_state(uint64_t task);
void fast_handoff_set_task_state(uint64_t task, uint8_t state);
uint64_t fast_handoff_current_task_handle(void);
bool fast_handoff_replace_address_space(uint64_t task, uint64_t cr3);
bool runtime_vm_install(void *(*allocate)(size_t));
extern void *(*const runtime_allocate_callback)(size_t);
void *runtime_vm_take_released(void);
uint64_t runtime_clock_initialize(uint64_t frequency);
uint64_t runtime_clock_frequency(void);
uint64_t runtime_clock_nanos(void);
bool runtime_vdso_initialize(vdso_image_t *image);
extern void (*ap_start_ptr)(struct limine_mp_info *);
void *__rtld_allocateTcb(void);
typedef struct {
    uintptr_t allocation;
    uint64_t task;
    uint8_t owns_stack;
} runtime_tls_t;
runtime_tls_t *runtime_tls_owner(uintptr_t tcb);
bool fast_handoff_task_has_exited(uint64_t task);
bool runtime_tls_reclaimable(uintptr_t tcb);
void runtime_tls_destroy(uintptr_t tcb);
uint64_t fast_handoff_take_exited_runtime(void);
_Noreturn void fast_handoff_exit_current(void);
int munmap(void *pointer, size_t size);
void asm_syscall_handle(void);

void *malloc(size_t size);
void free(void *ptr);

int cp_zstd_decompress(void *destination, size_t capacity,
                       const void *source, size_t size);

void register_interrupt_handler(
    uint16_t vector,
    void (*handler)(void *, uint64_t, uint64_t, uint64_t),
    uint8_t ist,
    uint8_t flags
);
extern void (*const kotlin_exception_callbacks[])(void *, uint64_t, uint64_t, uint64_t);

typedef struct {
    uint32_t eax;
    uint32_t ebx;
    uint32_t ecx;
    uint32_t edx;
} cpuid_result_t;

void x86_cpuid(uint32_t leaf,uint32_t subleaf,cpuid_result_t *result);
bool rdrand64_step(uint64_t *out);
bool rdseed64_step(uint64_t *out);

#ifdef __cplusplus
}
#endif
