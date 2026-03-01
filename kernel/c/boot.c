#include <limine.h>
#include <stdbool.h>
#include <stdint.h>
#include <bridge.h>

typedef __UINTPTR_TYPE__ boot_uptr_t;
extern void kernel_main(void);
extern void __dlapi_enter(boot_uptr_t *entry_stack);

__attribute__((used, section(".limine_requests")))
static volatile uint64_t limine_base_revision[] = LIMINE_BASE_REVISION(4);

__attribute__((used, section(".limine_requests")))
volatile struct limine_framebuffer_request framebuffer_request = {
    .id = LIMINE_FRAMEBUFFER_REQUEST_ID,
};

__attribute__((used, section(".limine_requests")))
volatile struct limine_stack_size_request stack_size_request = {
    .id = LIMINE_STACK_SIZE_REQUEST_ID,
    .stack_size = 1024 * 1024,
};

__attribute__((used, section(".limine_requests")))
volatile struct limine_hhdm_request hhdm_request = {
    .id = LIMINE_HHDM_REQUEST_ID,
};

__attribute__((used, section(".limine_requests")))
volatile struct limine_memmap_request memmap_request = {
    .id = LIMINE_MEMMAP_REQUEST_ID,
};

__attribute__((used, section(".limine_requests")))
volatile struct limine_mp_request mp_request = {
    .id = LIMINE_MP_REQUEST_ID,
    .flags = LIMINE_MP_REQUEST_X86_64_X2APIC,
};

__attribute__((used, section(".limine_requests")))
volatile struct limine_rsdp_request rsdp_request = {
    .id = LIMINE_RSDP_REQUEST_ID,
};

__attribute__((used, section(".limine_requests")))
volatile struct limine_executable_file_request executable_file_request = {
    .id = LIMINE_EXECUTABLE_FILE_REQUEST_ID,
};

__attribute__((used, section(".limine_requests_start")))
static volatile uint64_t limine_requests_start_marker[] = LIMINE_REQUESTS_START_MARKER;

__attribute__((used, section(".limine_requests_end")))
static volatile uint64_t limine_requests_end_marker[] = LIMINE_REQUESTS_END_MARKER;

static uint32_t default_mxcsr = 0x1f80;
static char boot_argv0[] = "kernel";

static uint8_t boot_random[16] = {
    0x41, 0x52, 0x6e, 0x79, 0x2d, 0x4d, 0x4c, 0x49,
    0x42, 0x43, 0x2d, 0x54, 0x4c, 0x53, 0x21, 0x00
};

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

static uint64_t read_cr0(void) {
    uint64_t value;
    __asm__ volatile ("mov %%cr0, %0" : "=r"(value));
    return value;
}

static void write_cr0(uint64_t value) {
    __asm__ volatile ("mov %0, %%cr0" : : "r"(value) : "memory");
}

static uint64_t read_cr4(void) {
    uint64_t value;
    __asm__ volatile ("mov %%cr4, %0" : "=r"(value));
    return value;
}

static void write_cr4(uint64_t value) {
    __asm__ volatile ("mov %0, %%cr4" : : "r"(value) : "memory");
}

static void setup_simd(void) {
    uint64_t cr0 = read_cr0();
    uint64_t cr4 = read_cr4();

    cr0 &= ~(1u << 2);
    cr0 |= (1u << 1);
    cr0 |= (1u << 5);
    cr0 &= ~(1u << 3);
    write_cr0(cr0);

    cr4 |= (1u << 9);
    cr4 |= (1u << 10);
    write_cr4(cr4);

    __asm__ volatile ("fninit");
    __asm__ volatile ("ldmxcsr %0" : : "m"(default_mxcsr));
}

static void halt_forever(void) __attribute__((noreturn));
static void halt_forever(void) {
    for (;;) {
        __asm__ volatile ("hlt");
    }
}

static bool setup_entry_stack(boot_uptr_t *entry_stack) {
    struct limine_executable_file_response *response = executable_file_request.response;
    boot_uptr_t elf_base = (boot_uptr_t)response->executable_file->address;
    const struct elf64_ehdr *ehdr = (const struct elf64_ehdr *)elf_base;

    if (ehdr->e_ident[0] != elf_ident_mag0 || ehdr->e_ident[1] != elf_ident_mag1 ||
        ehdr->e_ident[2] != elf_ident_mag2 || ehdr->e_ident[3] != elf_ident_mag3 ||
        ehdr->e_ident[elf_ident_class] != elf_class_64) {
        return false;
    }

    if (!ehdr->e_phoff || !ehdr->e_phentsize || !ehdr->e_phnum) {
        return false;
    }
    if ((boot_uptr_t)ehdr->e_phoff > (~(boot_uptr_t)0 - elf_base)) {
        return false;
    }

    boot_uptr_t phdr_val  = elf_base + (boot_uptr_t)ehdr->e_phoff;
    boot_uptr_t phent_val = (boot_uptr_t)ehdr->e_phentsize;
    boot_uptr_t phnum_val = (boot_uptr_t)ehdr->e_phnum;
    boot_uptr_t entry_val = (boot_uptr_t)ehdr->e_entry;

    if (ehdr->e_type == elf_type_dyn) {
        if (entry_val > (~(boot_uptr_t)0 - elf_base)) {
            return false;
        }
        entry_val += elf_base;
    }

    entry_stack[0]  = 1;
    entry_stack[1]  = (boot_uptr_t)boot_argv0;
    entry_stack[2]  = 0;
    entry_stack[3]  = 0;
    
    entry_stack[4]  = at_phdr;     entry_stack[5]  = phdr_val;
    entry_stack[6]  = at_phent;    entry_stack[7]  = phent_val;
    entry_stack[8]  = at_phnum;    entry_stack[9]  = phnum_val;
    entry_stack[10] = at_pagesz;   entry_stack[11] = 0x1000;
    entry_stack[12] = at_entry;    entry_stack[13] = entry_val;
    entry_stack[14] = at_secure;   entry_stack[15] = 0;
    entry_stack[16] = at_random;   entry_stack[17] = (boot_uptr_t)boot_random;
    entry_stack[18] = at_execfn;   entry_stack[19] = (boot_uptr_t)boot_argv0;
    entry_stack[20] = at_null;     entry_stack[21] = 0;

    return true;
}

void _start(void) {
    boot_uptr_t entry_stack[24] __attribute__((aligned(16)));

    if (!LIMINE_BASE_REVISION_SUPPORTED(limine_base_revision) ||
        !framebuffer_request.response ||
        framebuffer_request.response->framebuffer_count < 1 ||
        !executable_file_request.response ||
        !setup_entry_stack(entry_stack)) {
        halt_forever();
    }

    setup_simd(); __dlapi_enter(entry_stack); kernel_main(); halt_forever();
}
