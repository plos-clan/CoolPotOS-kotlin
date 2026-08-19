#include <limine.h>
#include <stdbool.h>
#include <stdint.h>
#include "bridge.h"
#include "native.h"

typedef __UINTPTR_TYPE__ boot_uptr_t;
void _start(void) __attribute__((noreturn));
extern void kernel_main(void);
extern void __dlapi_enter(boot_uptr_t *entry_stack);

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
LIMINE_REQUEST(limine_module_request, module_request, .id = LIMINE_MODULE_REQUEST_ID);
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
    .header.state_bv = xstate_mask,
};
static char boot_argv0[] = "kernel";

static uint8_t boot_random[16] = "ARny-MLIBC-TLS!";

enum {
    at_null = 0,
    at_phdr = 3,
    at_phent = 4,
    at_phnum = 5,
    at_pagesz = 6,
    at_entry = 9,
    at_secure = 23,
    at_random = 25,
    at_execfn = 31
};

enum {
    elf_ident_mag0 = 0x7f,
    elf_ident_mag1 = 'E',
    elf_ident_mag2 = 'L',
    elf_ident_mag3 = 'F',
    elf_ident_class = 4,
    elf_class_64 = 2,
    elf_type_dyn = 3
};

struct elf64_ehdr {
    uint8_t e_ident[16];
    uint16_t e_type;
    uint16_t e_machine;
    uint32_t e_version;
    uint64_t e_entry;
    uint64_t e_phoff;
    uint64_t e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize;
    uint16_t e_phentsize;
    uint16_t e_phnum;
    uint16_t e_shentsize;
    uint16_t e_shnum;
    uint16_t e_shstrndx;
};

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

void setup_smep() {
    uint64_t cr4;
    __asm__ volatile("mov %%cr4, %0": "=r"(cr4):: "memory");
    cr4 |= (1ULL << 20);
    __asm__ volatile("mov %0, %%cr4":: "r"(cr4): "memory");
}

void setup_smap() {
    uint64_t cr4;
    __asm__ volatile("mov %%cr4, %0" : "=r"(cr4));
    cr4 |= (1ULL << 21);
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

static bool setup_entry_stack(boot_uptr_t *entry_stack) {
    struct limine_executable_file_response *response = executable_file_request.response;
    struct limine_file *file = response ? response->executable_file : NULL;
    if (!file || !file->address || file->size < sizeof(struct elf64_ehdr)) return false;

    boot_uptr_t elf_base = (boot_uptr_t)file->address;
    const struct elf64_ehdr *ehdr = (const struct elf64_ehdr *)elf_base;

    if (ehdr->e_ident[0] != elf_ident_mag0 || ehdr->e_ident[1] != elf_ident_mag1 ||
        ehdr->e_ident[2] != elf_ident_mag2 || ehdr->e_ident[3] != elf_ident_mag3 ||
        ehdr->e_ident[elf_ident_class] != elf_class_64) {
        return false;
    }

    const uint64_t phdr_size = (uint64_t)ehdr->e_phentsize * ehdr->e_phnum;
    if (!ehdr->e_phoff || !phdr_size || ehdr->e_phoff > file->size ||
        phdr_size > file->size - ehdr->e_phoff ||
        (boot_uptr_t)ehdr->e_phoff > UINTPTR_MAX - elf_base) {
        return false;
    }

    boot_uptr_t phdr_val = elf_base + (boot_uptr_t)ehdr->e_phoff;
    boot_uptr_t entry_val = (boot_uptr_t)ehdr->e_entry;

    if (ehdr->e_type == elf_type_dyn) {
        if (entry_val > UINTPTR_MAX - elf_base) return false;
        entry_val += elf_base;
    }

    const boot_uptr_t initial_stack[] = {
        1, (boot_uptr_t)boot_argv0, 0, 0,
        at_phdr, phdr_val, at_phent, ehdr->e_phentsize, at_phnum, ehdr->e_phnum,
        at_pagesz, 0x1000, at_entry, entry_val, at_secure, 0,
        at_random, (boot_uptr_t)boot_random,
        at_execfn, (boot_uptr_t)boot_argv0, at_null, 0,
    };
    __builtin_memcpy(entry_stack, initial_stack, sizeof(initial_stack));
    return true;
}

void wait_for_interrupt(void) { __asm__ volatile("hlt" : : : "memory"); }

void _start(void) {
    boot_uptr_t entry_stack[22] __attribute__((aligned(16)));

    if (!LIMINE_BASE_REVISION_SUPPORTED(limine_base_revision) ||
        !framebuffer_request.response ||
        framebuffer_request.response->framebuffer_count < 1 ||
        !setup_entry_stack(entry_stack)) {
        halt_forever();
    }

    setup_xstate();
    __dlapi_enter(entry_stack);
    kernel_main();
    halt_forever();
}
