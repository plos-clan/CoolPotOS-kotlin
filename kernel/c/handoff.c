#include "bridge.h"
#include "native.h"

extern void do_irq(void *regs, uint64_t irq_num);

enum {
    timer_irq = 1,
    scheduler_vector = 32,
    spurious_irq = 224,
    lapic_id_register = 0x20,
    lapic_eoi_register = 0xb0,
    lapic_icr_register = 0x300,
    lapic_icr_high_register = 0x310,
    lapic_timer_register = 0x320,
    lapic_delivery_pending = 1u << 12,
    lapic_timer_tsc_deadline = 1u << 18,
    x2apic_msr_base = 0x800,
    ps2_queue_capacity = 256,
    ps2_queue_mask = ps2_queue_capacity - 1,
    ps2_queue_empty = 0x100,
};

enum fast_cpu_state {
    cpu_offline,
    cpu_bootstrapping,
    cpu_online,
};

enum fast_task_state {
    task_ready,
    task_running,
    task_blocked,
    task_zombie,
};

enum fast_schedule_request {
    schedule_reschedule,
    schedule_park,
};

enum fast_schedule_result {
    schedule_rejected,
    schedule_same_task,
    schedule_switched,
};

typedef struct fast_task fast_task_t;
typedef struct fast_cpu fast_cpu_t;

typedef struct {
    uint64_t r15;
    uint64_t r14;
    uint64_t r13;
    uint64_t r12;
    uint64_t rbx;
    uint64_t rbp;
    uint64_t rip;
} switch_frame_t;
_Static_assert(sizeof(switch_frame_t) == 56, "invalid switch frame layout");

struct fast_task {
    uint64_t rsp;
    uint64_t cr3;
    uint64_t kernel_rsp;
    uint64_t kernel_fs_base;
    uint64_t id;
    fast_task_t *next;
    fast_cpu_t *cpu;
    uint8_t state;
    uint8_t queued;
    uint8_t wake_pending;
};
_Static_assert(
    sizeof(fast_task_t) == 64,
    "scheduler metadata must fit in one cache line"
);

struct fast_cpu {
    fast_task_t *current;
    fast_task_t *idle;
    fast_task_t *head;
    fast_task_t *tail;
    uint64_t queue_size;
    uint8_t is_bsp;
    enum fast_cpu_state state;
    uint8_t lock;
    uint64_t lapic_id;
    uint64_t timer_deadline;
    uint64_t wake_sequence;
} __attribute__((aligned(64)));

static fast_cpu_t fast_cpus[cpu_slot_count];
static uint8_t handoff_enabled;
static uint8_t lapic_x2apic;
static uint64_t lapic_mmio_base;
static uint64_t scheduler_tick_cycles;
static uint64_t bsp_lapic_id = UINT64_MAX;
static uint8_t ps2_queue[ps2_queue_capacity];
static uint16_t ps2_queue_head;
static uint16_t ps2_queue_tail;
static uint16_t ps2_data_port;
static uint16_t ps2_status_port;
static uint64_t ps2_irq_num;

static enum fast_schedule_result fast_handoff_schedule(
    fast_cpu_t *cpu,
    xstate_t *interrupted_xstate,
    enum fast_schedule_request request
);

__attribute__((naked, noinline))
static void fast_switch_to(uint64_t *, uint64_t) {
    __asm__ volatile(
        "pushq %rbp\n"
        "pushq %rbx\n"
        "pushq %r12\n"
        "pushq %r13\n"
        "pushq %r14\n"
        "pushq %r15\n"
        "movq %rsp, (%rdi)\n"
        "movq %rsi, %rsp\n"
        "popq %r15\n"
        "popq %r14\n"
        "popq %r13\n"
        "popq %r12\n"
        "popq %rbx\n"
        "popq %rbp\n"
        "retq\n"
    );
}

__attribute__((naked, noreturn))
static void fast_kernel_task_entry(void) {
    __asm__ volatile(
        "sti\n"
        "movq %r12, %rdi\n"
        "jmpq *%r13\n"
    );
}

__attribute__((naked, noreturn))
static void fast_user_task_entry(void) {
    __asm__ volatile(
        "movq %rsp, %r13\n"
        "movl $3, %eax\n"
        "xorl %edx, %edx\n"
        "xrstor64 256(%r13)\n"
        "movq 128(%r13), %rax\n"
        "movq %rax, %rdx\n"
        "shrq $32, %rdx\n"
        "movl $0xc0000100, %ecx\n"
        "wrmsr\n"
        "leaq 200(%r13), %rsp\n"
        "pushq 192(%r13)\n"
        "pushq 184(%r13)\n"
        "pushq 176(%r13)\n"
        "pushq 168(%r13)\n"
        "pushq 160(%r13)\n"
        "movq 112(%r13), %rax\n"
        "movw %ax, %ds\n"
        "movq 120(%r13), %rax\n"
        "movw %ax, %es\n"
        "movq 0(%r13), %r15\n"
        "movq 8(%r13), %r14\n"
        "movq 24(%r13), %r12\n"
        "movq 32(%r13), %r11\n"
        "movq 40(%r13), %r10\n"
        "movq 48(%r13), %r9\n"
        "movq 56(%r13), %r8\n"
        "movq 64(%r13), %rbx\n"
        "movq 72(%r13), %rcx\n"
        "movq 80(%r13), %rdx\n"
        "movq 88(%r13), %rsi\n"
        "movq 96(%r13), %rdi\n"
        "movq 104(%r13), %rbp\n"
        "movq 136(%r13), %rax\n"
        "movq 16(%r13), %r13\n"
        "swapgs\n"
        "iretq\n"
    );
}

static inline fast_task_t *task_from_handle(uint64_t handle) {
    return (fast_task_t *)(uintptr_t)handle;
}

static inline uint64_t interrupt_save(void) {
    uint64_t flags;
    __asm__ volatile("pushfq; popq %0; cli" : "=r"(flags) : : "memory");
    return flags;
}

static inline void interrupt_restore(uint64_t flags) {
    if (flags & (1u << 9)) __asm__ volatile("sti" : : : "memory");
}

static inline void lock_cpu(fast_cpu_t *cpu) {
    while (__atomic_test_and_set(&cpu->lock, __ATOMIC_ACQUIRE))
        __asm__ volatile("pause");
}

static inline void unlock_cpu(fast_cpu_t *cpu) {
    __atomic_clear(&cpu->lock, __ATOMIC_RELEASE);
}

static inline enum fast_task_state task_state_load(const fast_task_t *task) {
    return (enum fast_task_state)__atomic_load_n(
        &task->state,
        __ATOMIC_ACQUIRE
    );
}

static inline void task_state_store(
    fast_task_t *task,
    enum fast_task_state state
) {
    __atomic_store_n(&task->state, state, __ATOMIC_RELEASE);
}

static uint64_t current_lapic_id(void) {
    if (lapic_x2apic)
        return rdmsr(x2apic_msr_base + (lapic_id_register >> 4));
    if (!lapic_mmio_base) return 0;
    return *(volatile uint32_t *)(uintptr_t)(lapic_mmio_base + lapic_id_register) >> 24;
}

static fast_cpu_t *current_cpu(void) {
    fast_cpu_t *cpu;
    __asm__ volatile("movq %%gs:32, %0" : "=r"(cpu));
    return cpu;
}

static uint32_t lapic_read(uint32_t reg) {
    if (lapic_x2apic)
        return (uint32_t)rdmsr(x2apic_msr_base + (reg >> 4));
    if (!lapic_mmio_base) return 0;
    return *(volatile uint32_t *)(uintptr_t)(lapic_mmio_base + reg);
}

static void lapic_write(uint32_t reg, uint64_t value) {
    if (lapic_x2apic) {
        wrmsr(x2apic_msr_base + (reg >> 4), value);
    } else if (lapic_mmio_base) {
        *(volatile uint32_t *)(uintptr_t)(lapic_mmio_base + reg) = value;
    }
}

static void lapic_eoi(uint64_t irq_num) {
    if (irq_num == spurious_irq) return;
    lapic_write(lapic_eoi_register, 0);
}

static void lapic_send_reschedule(uint64_t lapic_id) {
    if (lapic_x2apic) {
        lapic_write(lapic_icr_register, lapic_id << 32 | scheduler_vector);
        return;
    }

    while (lapic_read(lapic_icr_register) & lapic_delivery_pending)
        __asm__ volatile("pause");
    lapic_write(lapic_icr_high_register, (lapic_id & 0xffu) << 24);
    lapic_write(lapic_icr_register, scheduler_vector);
}

static void set_timer_deadline(fast_cpu_t *cpu, uint64_t deadline) {
    cpu->timer_deadline = deadline;
    wrmsr(ia32_tsc_deadline_msr, deadline);
}

static void update_scheduler_timer(fast_cpu_t *cpu) {
    if (!scheduler_tick_cycles || cpu->state != cpu_online ||
        cpu->current == cpu->idle) {
        set_timer_deadline(cpu, 0);
        return;
    }

    const uint64_t now = read_tsc();
    const uint64_t latest = now > UINT64_MAX - scheduler_tick_cycles
        ? UINT64_MAX : now + scheduler_tick_cycles;
    if (cpu->timer_deadline > now && cpu->timer_deadline <= latest) return;
    set_timer_deadline(cpu, latest);
}

static void wake_cpu(fast_cpu_t *cpu) {
    __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
    if (cpu->state == cpu_offline) return;
    if (cpu != current_cpu())
        lapic_send_reschedule(cpu->lapic_id);
}

static bool queue_push(fast_cpu_t *cpu, fast_task_t *task) {
    if (!task || task->queued) return false;

    task->queued = true;
    task->next = NULL;
    if (cpu->tail) cpu->tail->next = task;
    else cpu->head = task;
    cpu->tail = task;
    cpu->queue_size++;
    return true;
}

static fast_task_t *queue_pop(fast_cpu_t *cpu) {
    while (cpu->head) {
        fast_task_t *task = cpu->head;
        cpu->head = task->next;
        if (!cpu->head) cpu->tail = NULL;
        cpu->queue_size--;
        task->next = NULL;
        task->queued = false;
        if (task_state_load(task) == task_ready) {
            task_state_store(task, task_running);
            return task;
        }
    }
    return NULL;
}

static enum fast_schedule_result fast_handoff_schedule(
    fast_cpu_t *cpu,
    xstate_t *interrupted_xstate,
    enum fast_schedule_request request
) {
    const uint64_t flags = interrupt_save();
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE) ||
        cpu->state == cpu_offline || !cpu->current) {
        interrupt_restore(flags);
        return schedule_rejected;
    }

    lock_cpu(cpu);
    fast_task_t *previous = cpu->current;
    bool select_next = true;
    if (request == schedule_park) {
        if (previous == cpu->idle || task_state_load(previous) != task_running) {
            unlock_cpu(cpu);
            interrupt_restore(flags);
            return schedule_rejected;
        }
        if (previous->wake_pending) {
            previous->wake_pending = false;
            select_next = false;
        } else {
            task_state_store(previous, task_blocked);
        }
    }

    fast_task_t *next = previous;
    if (select_next &&
        (cpu->head || task_state_load(previous) != task_running)) {
        if (task_state_load(previous) == task_running) {
            task_state_store(previous, task_ready);
            if (!queue_push(cpu, previous))
                task_state_store(previous, task_running);
        }

        next = queue_pop(cpu);
        if (!next && task_state_load(previous) == task_running) next = previous;
        if (!next && cpu->state == cpu_online && cpu->idle && cpu->idle->rsp) {
            next = cpu->idle;
            task_state_store(next, task_running);
        }
        if (!next) {
            if (request == schedule_park)
                task_state_store(previous, task_running);
            next = previous;
        }
    }
    cpu->current = next;
    update_scheduler_timer(cpu);
    unlock_cpu(cpu);

    enum fast_schedule_result result = schedule_same_task;
    if (next != previous) result = schedule_switched;
    else if (request == schedule_park && select_next)
        result = schedule_rejected;
    const bool switched = result == schedule_switched;

    if (switched) {
        if (interrupted_xstate) {
            initialize_xstate_header(interrupted_xstate);
            save_xstate(interrupted_xstate);
            restore_xstate(&initial_xstate);
        }
        if (previous->cr3 != next->cr3) write_cr3(next->cr3);
        if (next->kernel_rsp) {
            set_kernel_stack(cpu->lapic_id, next->kernel_rsp, cpu->is_bsp);
            locals[cpu->lapic_id % cpu_slot_count].syscall.kernel_rsp =
                next->kernel_rsp;
        }
        const uint64_t runtime_fs_base =
            kernel_runtime_fs_bases[cpu->lapic_id % cpu_slot_count];
        const uint64_t previous_fs_base = previous->kernel_fs_base
            ? previous->kernel_fs_base : runtime_fs_base;
        const uint64_t kernel_fs_base = next->kernel_fs_base
            ? next->kernel_fs_base
            : runtime_fs_base;
        locals[cpu->lapic_id % cpu_slot_count].syscall.kernel_fs_base =
            kernel_fs_base;
        if (previous_fs_base != kernel_fs_base)
            wrmsr(ia32_fs_base_msr, kernel_fs_base);
        fast_switch_to(&previous->rsp, next->rsp);
    }
    interrupt_restore(flags);
    return result;
}

void fast_handoff_configure_lapic(uint8_t x2apic, uint64_t mmio_base) {
    lapic_x2apic = x2apic != 0;
    lapic_mmio_base = mmio_base;
}

bool fast_handoff_configure_timer(uint8_t vector, uint32_t frequency_hz) {
    const uint64_t tsc_hz = runtime_clock_frequency();
    if (vector != scheduler_vector || !frequency_hz || tsc_hz < frequency_hz)
        return false;

    scheduler_tick_cycles = (tsc_hz + frequency_hz - 1) / frequency_hz;
    lapic_write(
        lapic_timer_register,
        vector | lapic_timer_tsc_deadline
    );
    fast_cpu_t *cpu = &fast_cpus[current_lapic_id() % cpu_slot_count];
    set_timer_deadline(cpu, 0);
    return true;
}

bool fast_handoff_yield(void) {
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE)) return false;

    fast_cpu_t *cpu = current_cpu();
    if (cpu->state == cpu_offline) return false;
    return fast_handoff_schedule(
        cpu,
        NULL,
        schedule_reschedule
    ) == schedule_switched;
}

bool fast_handoff_park_current(void) {
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE)) return false;
    return fast_handoff_schedule(
        current_cpu(),
        NULL,
        schedule_park
    ) != schedule_rejected;
}

bool fast_handoff_unpark(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    if (!task) return false;

    fast_cpu_t *cpu = __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE);
    if (!cpu) return false;

    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    const enum fast_task_state state = task_state_load(task);
    bool success = state != task_zombie;
    bool wake = false;
    if (state == task_blocked) {
        task_state_store(task, task_ready);
        wake = queue_push(cpu, task);
        if (!wake) {
            task_state_store(task, task_blocked);
            success = false;
        }
    } else if (success) {
        task->wake_pending = true;
    }
    unlock_cpu(cpu);
    if (wake) wake_cpu(cpu);
    interrupt_restore(flags);
    return success;
}

uint64_t fast_handoff_wake_sequence(void) {
    fast_cpu_t *cpu = current_cpu();
    return __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
}

void fast_handoff_wake_bsp(void) {
    const uint64_t lapic_id = __atomic_load_n(&bsp_lapic_id, __ATOMIC_ACQUIRE);
    if (lapic_id == UINT64_MAX) return;

    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    wake_cpu(cpu);
}

/* The Kotlin/Native interop wrapper keeps this entire call in Native state. */
void fast_handoff_park_kotlin(uint64_t deadline_ns, uint64_t wake_sequence) {
    const uint64_t flags = interrupt_save();
    fast_cpu_t *cpu = current_cpu();
    if (__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE) &&
        cpu->state != cpu_offline)
        fast_handoff_schedule(cpu, NULL, schedule_reschedule);
    lock_cpu(cpu);
    const bool idle = cpu->state == cpu_online && cpu->current == cpu->idle &&
        !cpu->head && wake_sequence ==
            __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
    const uint64_t deadline = idle
        ? runtime_clock_deadline(deadline_ns) : 0;
    const bool sleep = idle && (!deadline || deadline > read_tsc());
    if (idle) set_timer_deadline(cpu, sleep ? deadline : 0);
    unlock_cpu(cpu);

    if (sleep && (flags & (1u << 9)))
        __asm__ volatile("sti; hlt; cli" : : : "memory");
    interrupt_restore(flags);
}

_Noreturn void fast_handoff_idle(void) {
    for (;;) {
        const uint64_t sequence = fast_handoff_wake_sequence();
        fast_handoff_park_kotlin(0, sequence);
    }
}

void fast_handoff_configure_ps2(
    uint64_t irq_num,
    uint16_t data_port,
    uint16_t status_port
) {
    __atomic_store_n(&ps2_queue_head, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&ps2_queue_tail, 0, __ATOMIC_RELAXED);
    ps2_data_port = data_port;
    ps2_status_port = status_port;
    __atomic_store_n(&ps2_irq_num, irq_num, __ATOMIC_RELEASE);
}

uint16_t fast_handoff_read_ps2_scan_code(void) {
    const uint16_t tail = __atomic_load_n(&ps2_queue_tail, __ATOMIC_RELAXED);
    if (tail == __atomic_load_n(&ps2_queue_head, __ATOMIC_ACQUIRE))
        return ps2_queue_empty;

    const uint8_t scan_code = ps2_queue[tail];
    __atomic_store_n(
        &ps2_queue_tail,
        (tail + 1) & ps2_queue_mask,
        __ATOMIC_RELEASE
    );
    return scan_code;
}

static bool fast_handoff_ps2_irq(uint64_t irq_num) {
    if (!irq_num || irq_num != __atomic_load_n(&ps2_irq_num, __ATOMIC_ACQUIRE))
        return false;

    if (!(io_in8(ps2_status_port) & 1u)) return true;

    const uint8_t scan_code = io_in8(ps2_data_port);
    const uint16_t head = __atomic_load_n(&ps2_queue_head, __ATOMIC_RELAXED);
    const uint16_t next = (head + 1) & ps2_queue_mask;
    if (next != __atomic_load_n(&ps2_queue_tail, __ATOMIC_ACQUIRE)) {
        ps2_queue[head] = scan_code;
        __atomic_store_n(&ps2_queue_head, next, __ATOMIC_RELEASE);
    }
    return true;
}

uint64_t fast_handoff_create_task(
    uint64_t id,
    uint64_t cr3,
    uint64_t kernel_rsp,
    uint64_t kernel_fs_base
) {
    fast_task_t *task = malloc(sizeof(*task));
    if (!task) return 0;
    __builtin_memset(task, 0, sizeof(*task));
    task->id = id;
    task->cr3 = cr3;
    task->kernel_rsp = kernel_rsp;
    task->kernel_fs_base = kernel_fs_base;
    task_state_store(task, task_ready);
    return (uintptr_t)task;
}

void fast_handoff_init_kernel(
    uint64_t handle,
    uint64_t entry,
    uint64_t rsp,
    uint64_t argument,
    uint64_t fs_base
) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !entry || rsp < sizeof(switch_frame_t)) return;
    switch_frame_t *frame = (switch_frame_t *)(uintptr_t)
        (rsp - sizeof(switch_frame_t));
    *frame = (switch_frame_t){
        .r13 = entry,
        .r12 = argument,
        .rip = (uintptr_t)&fast_kernel_task_entry,
    };
    task->kernel_rsp = rsp;
    task->kernel_fs_base = fs_base;
    __atomic_store_n(&task->rsp, (uintptr_t)frame, __ATOMIC_RELEASE);
    task_state_store(task, task_ready);
}

static void install_user_registers(
    fast_task_t *task,
    const pt_regs_t *registers,
    const xstate_t *xstate
) {
    const uintptr_t frame_address =
        (task->kernel_rsp - sizeof(kernel_entry_frame_t)) & ~0x3fULL;
    kernel_entry_frame_t *frame = (kernel_entry_frame_t *)frame_address;
    __builtin_memset(frame, 0, sizeof(*frame));
    frame->regs = *registers;
    frame->xstate = *xstate;
    switch_frame_t *context = (switch_frame_t *)frame - 1;
    *context = (switch_frame_t){
        .rip = (uintptr_t)&fast_user_task_entry,
    };
    __atomic_store_n(&task->rsp, (uintptr_t)context, __ATOMIC_RELEASE);
    task_state_store(task, task_ready);
}

void fast_handoff_init_user(
    uint64_t handle,
    uint64_t entry,
    uint64_t rsp,
    uint64_t fs_base
) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !entry || !rsp || !task->kernel_rsp) return;
    const pt_regs_t registers = {
        .ds = 0x1b,
        .es = 0x1b,
        .fs_base = fs_base,
        .rip = entry,
        .cs = 0x23,
        .rflags = 0x202,
        .rsp = rsp,
        .ss = 0x1b,
    };
    install_user_registers(task, &registers, &initial_xstate);
}

void fast_handoff_init_user_registers(
    uint64_t handle,
    const uint64_t *registers,
    uint64_t rsp,
    uint64_t fs_base
) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !registers || !rsp || !task->kernel_rsp) return;
    pt_regs_t snapshot;
    __builtin_memcpy(&snapshot, registers, sizeof(snapshot));
    snapshot.rax = 0;
    snapshot.func = 0;
    snapshot.errcode = 0;
    snapshot.rsp = rsp;
    snapshot.fs_base = fs_base;
    const fast_task_t *parent = current_cpu()->current;
    const xstate_t *parent_xstate = (const xstate_t *)(uintptr_t)
        (parent->kernel_rsp - sizeof(kernel_entry_frame_t) +
            offsetof(kernel_entry_frame_t, xstate));
    install_user_registers(
        task,
        &snapshot,
        parent_xstate
    );
}

bool fast_handoff_bind_current(
    uint64_t handle,
    uint64_t lapic_id,
    uint8_t is_bsp
) {
    fast_task_t *task = task_from_handle(handle);
    if (!task) return false;
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    if (cpu->state != cpu_offline) {
        unlock_cpu(cpu);
        interrupt_restore(flags);
        return false;
    }
    cpu->current = task;
    cpu->idle = task;
    cpu->is_bsp = is_bsp != 0;
    cpu->lapic_id = lapic_id;
    locals[lapic_id % cpu_slot_count].syscall.scheduler_cpu = (uintptr_t)cpu;
    __atomic_store_n(&task->cpu, cpu, __ATOMIC_RELEASE);
    if (cpu->is_bsp)
        __atomic_store_n(&bsp_lapic_id, lapic_id, __ATOMIC_RELEASE);
    cpu->state = cpu_bootstrapping;
    if (!task->kernel_fs_base)
        task->kernel_fs_base = rdmsr(ia32_fs_base_msr);
    task_state_store(task, task_running);
    task->queued = false;
    unlock_cpu(cpu);
    interrupt_restore(flags);
    return true;
}

bool fast_handoff_finish_bootstrap(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !task->cpu) return false;

    fast_cpu_t *cpu = task->cpu;
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    const bool finished = cpu->state == cpu_bootstrapping &&
        cpu->current == task && cpu->idle == task;
    if (finished) cpu->state = cpu_online;
    unlock_cpu(cpu);
    interrupt_restore(flags);
    return finished;
}

bool fast_handoff_enqueue(uint64_t handle, uint64_t lapic_id) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !__atomic_load_n(&task->rsp, __ATOMIC_ACQUIRE))
        return false;
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    fast_cpu_t *owner = __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE);
    if (!owner) {
        fast_cpu_t *unassigned = NULL;
        if (__atomic_compare_exchange_n(
                &task->cpu,
                &unassigned,
                cpu,
                false,
                __ATOMIC_RELEASE,
                __ATOMIC_ACQUIRE
            )) {
            owner = cpu;
        } else {
            owner = unassigned;
        }
    }
    const enum fast_task_state state = task_state_load(task);
    const bool accepted = owner == cpu && task != cpu->current &&
        state != task_running &&
        state != task_zombie && queue_push(cpu, task);
    if (accepted) task_state_store(task, task_ready);
    unlock_cpu(cpu);
    if (accepted) wake_cpu(cpu);
    interrupt_restore(flags);
    return accepted;
}

void fast_handoff_set_enabled(uint8_t enabled) {
    __atomic_store_n(&handoff_enabled, enabled != 0, __ATOMIC_RELEASE);
}

uint64_t fast_handoff_cpu_load(uint64_t lapic_id) {
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    const uint64_t result = cpu->state != cpu_offline
        ? cpu->queue_size +
            (cpu->state != cpu_online || cpu->current != cpu->idle)
        : UINT64_MAX;
    unlock_cpu(cpu);
    interrupt_restore(flags);
    return result;
}

uint8_t fast_handoff_task_state(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    return task ? task_state_load(task) : task_zombie;
}

void fast_handoff_set_task_state(uint64_t handle, uint8_t state) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || state > task_zombie) return;

    fast_cpu_t *cpu = __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE);
    if (!cpu) {
        task_state_store(task, (enum fast_task_state)state);
        return;
    }

    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    task_state_store(task, (enum fast_task_state)state);
    if (state == task_zombie) task->wake_pending = false;
    unlock_cpu(cpu);
    interrupt_restore(flags);
}

uint64_t fast_handoff_current_task_id(void) {
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE)) return UINT64_MAX;
    const fast_task_t *task = __atomic_load_n(
        &current_cpu()->current,
        __ATOMIC_ACQUIRE
    );
    return task ? task->id : UINT64_MAX;
}

bool fast_handoff_replace_address_space(uint64_t handle, uint64_t cr3) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !cr3) return false;

    fast_cpu_t *cpu = current_cpu();
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    const bool current = cpu->current == task;
    if (current) task->cr3 = cr3;
    unlock_cpu(cpu);
    if (current) write_cr3(cr3);
    interrupt_restore(flags);
    return current;
}

void fast_handoff_reset_user_xstate(void) {
    const fast_task_t *task = current_cpu()->current;
    xstate_t *state = (xstate_t *)(uintptr_t)
        (task->kernel_rsp - sizeof(kernel_entry_frame_t) +
            offsetof(kernel_entry_frame_t, xstate));
    *state = initial_xstate;
}

__attribute__((used)) bool fast_handoff_irq(pt_regs_t *regs, uint64_t irq_num) {
    fast_cpu_t *cpu = current_cpu();

    if (irq_num != timer_irq) {
        __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
        if (fast_handoff_ps2_irq(irq_num)) {
            lapic_eoi(irq_num);
        } else if (irq_num == spurious_irq) {
            lapic_eoi(irq_num);
        } else {
            xstate_t *xstate = &((kernel_entry_frame_t *)regs)->xstate;
            initialize_xstate_header(xstate);
            save_xstate(xstate);
            restore_xstate(&initial_xstate);
            do_irq(regs, irq_num);
            restore_xstate(xstate);
        }
        return false;
    }

    cpu->timer_deadline = 0;
    lapic_eoi(irq_num);
    if (__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE) &&
        cpu->state != cpu_offline) {
        return fast_handoff_schedule(
            cpu,
            &((kernel_entry_frame_t *)regs)->xstate,
            schedule_reschedule
        ) == schedule_switched;
    } else {
        update_scheduler_timer(cpu);
    }
    return false;
}
