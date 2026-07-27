#include <stdint.h>

typedef uint64_t gdt_entries_t[7];
typedef uint8_t tss_stack_t[4096];
typedef struct tss tss_t;

struct gdt_register {
    uint16_t size;
    void *ptr;
} __attribute__((packed));

struct tss {
    uint32_t unused0;
    uint64_t rsp[3];
    uint64_t unused1;
    uint64_t ist[7];
    uint64_t unused2;
    uint16_t unused3;
    uint16_t iopb;
} __attribute__((packed));

typedef struct cpu_local {
    gdt_entries_t gdt_entries;
    struct gdt_register gdt_pointer;
    tss_t tss0;
    tss_stack_t tss_stack __attribute__((aligned(16)));
}cpu_local_t;

extern cpu_local_t locals[256];
