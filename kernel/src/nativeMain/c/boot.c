#include <elf.h>
#include <limine.h>
#include <stdbool.h>
#include <stdint.h>
#include "bridge.h"

void _start(void) __attribute__((noreturn));
extern void kernel_main(void);
extern void __dlapi_enter(uintptr_t *entry_stack);

#define LIMINE_ITEM(section_name) __attribute__((used, section(section_name)))
#define LIMINE_REQUEST(type, name, ...) \
    LIMINE_ITEM(".limine_requests") volatile struct type name = {__VA_ARGS__}

LIMINE_ITEM(".limine_requests") static volatile uint64_t limine_base_revision[] = LIMINE_BASE_REVISION(4);
LIMINE_REQUEST(limine_framebuffer_request, framebuffer_request,
    .id = LIMINE_FRAMEBUFFER_REQUEST_ID);
LIMINE_REQUEST(limine_stack_size_request, stack_size_request,
    .id = LIMINE_STACK_SIZE_REQUEST_ID, .stack_size = 1024 * 1024);
LIMINE_REQUEST(limine_hhdm_request, hhdm_request, .id = LIMINE_HHDM_REQUEST_ID);
LIMINE_REQUEST(limine_memmap_request, memmap_request, .id = LIMINE_MEMMAP_REQUEST_ID);
LIMINE_REQUEST(limine_mp_request, mp_request,
    .id = LIMINE_MP_REQUEST_ID, .flags = LIMINE_MP_REQUEST_X86_64_X2APIC);
LIMINE_REQUEST(limine_rsdp_request, rsdp_request, .id = LIMINE_RSDP_REQUEST_ID);
LIMINE_REQUEST(limine_executable_file_request, executable_file_request,
    .id = LIMINE_EXECUTABLE_FILE_REQUEST_ID);
LIMINE_REQUEST(limine_executable_cmdline_request, cmdline_request,
    .id = LIMINE_EXECUTABLE_CMDLINE_REQUEST_ID);
LIMINE_REQUEST(limine_tsc_frequency_request, tsc_frequency_request,
    .id = LIMINE_TSC_FREQUENCY_REQUEST_ID);
LIMINE_ITEM(".limine_requests_start")
static volatile uint64_t limine_requests_start_marker[] = LIMINE_REQUESTS_START_MARKER;
LIMINE_ITEM(".limine_requests_end")
static volatile uint64_t limine_requests_end_marker[] = LIMINE_REQUESTS_END_MARKER;
#undef LIMINE_REQUEST
#undef LIMINE_ITEM

const xstate_t initial_xstate = {
    .legacy.control_word = 0x037f,
    .legacy.mxcsr = 0x1f80,
};
static char boot_argv0[] = "kernel";

static uint8_t boot_random[16] = "ARny-MLIBC-TLS!";

void setup_xstate(void) {
    uint64_t cr0, cr4;
    __asm__ volatile("mov %%cr0, %0" : "=r"(cr0));
    __asm__ volatile("mov %%cr4, %0" : "=r"(cr4));
    cr0 = (cr0 & ~((1u << 2) | (1u << 3))) |
        (1u << 1) | (1u << 5) | (1u << 16);
    cr4 |= (1u << 9) | (1u << 10) | (1u << 18);
    __asm__ volatile("mov %0, %%cr0" : : "r"(cr0) : "memory");
    __asm__ volatile("mov %0, %%cr4" : : "r"(cr4) : "memory");
    __asm__ volatile(
        "xsetbv"
        :
        : "c"(0), "a"(xstate_mask), "d"(0)
        : "memory"
    );
    wrmsr(0xc0000080u, rdmsr(0xc0000080u) | (1ULL << 11));
    restore_xstate(&initial_xstate);
}

void enable_cr4(uint64_t flags) {
    uint64_t cr4;
    __asm__ volatile("mov %%cr4, %0" : "=r"(cr4));
    cr4 |= flags;
    __asm__ volatile("mov %0, %%cr4" :: "r"(cr4) : "memory");
}

void open_smap() {
    __asm__ volatile("clac" ::: "cc", "memory");
}

void close_smap() {
    __asm__ volatile("stac" ::: "cc", "memory");
}

static __attribute__((noreturn)) void halt_forever(void) {
    for (;;) __asm__ volatile("hlt");
}

void wait_for_interrupt(void) { __asm__ volatile("hlt" : : : "memory"); }

void _start(void) {
    if (!LIMINE_BASE_REVISION_SUPPORTED(limine_base_revision)) halt_forever();
    struct limine_executable_file_response *response = executable_file_request.response;
    struct limine_file *file = response ? response->executable_file : NULL;
    if (!file || !file->address || file->size < sizeof(Elf64_Ehdr)) halt_forever();

    uintptr_t elf_base = (uintptr_t)file->address;
    const Elf64_Ehdr *ehdr = (const Elf64_Ehdr *)elf_base;

    if (__builtin_memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64) halt_forever();

    const uint64_t phdr_size = (uint64_t)ehdr->e_phentsize * ehdr->e_phnum;
    if (!ehdr->e_phoff || !phdr_size || ehdr->e_phoff > file->size ||
        phdr_size > file->size - ehdr->e_phoff ||
        (uintptr_t)ehdr->e_phoff > UINTPTR_MAX - elf_base) {
        halt_forever();
    }

    uintptr_t phdr_val = elf_base + (uintptr_t)ehdr->e_phoff;
    uintptr_t entry_val = (uintptr_t)ehdr->e_entry;

    if (ehdr->e_type == ET_DYN) {
        if (entry_val > UINTPTR_MAX - elf_base) halt_forever();
        entry_val += elf_base;
    }

    uintptr_t entry_stack[] __attribute__((aligned(16))) = {
        1, (uintptr_t)boot_argv0, 0, 0,
        AT_PHDR, phdr_val, AT_PHENT, ehdr->e_phentsize, AT_PHNUM, ehdr->e_phnum,
        AT_PAGESZ, 0x1000, AT_ENTRY, entry_val, AT_SECURE, 0,
        AT_RANDOM, (uintptr_t)boot_random,
        AT_EXECFN, (uintptr_t)boot_argv0, AT_NULL, 0,
    };

    setup_xstate();
    __dlapi_enter(entry_stack);
    initialize_runtime_access();
    kernel_main();
    halt_forever();
}
