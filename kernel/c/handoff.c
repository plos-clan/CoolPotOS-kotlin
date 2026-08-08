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
    fpu_state_offset = 208,
    fpu_state_size = 512,
    task_ready = 0,
    task_running = 1,
    task_blocked = 2,
    task_zombie = 3,
    park_idle = 0,
    park_requested = 1,
    park_parked = 2,
    park_notified = 3,
    ps2_queue_capacity = 256,
    ps2_queue_mask = ps2_queue_capacity - 1,
    ps2_queue_empty = 0x100,
};

enum fast_cpu_state {
    cpu_offline,
    cpu_bootstrapping,
    cpu_online,
};

typedef struct fast_task fast_task_t;

struct fast_task {
    pt_regs_t regs;
    uint8_t fpu[fpu_state_size] __attribute__((aligned(16)));
    uint64_t cr3;
    uint64_t kernel_rsp;
    uint64_t kernel_fs_base;
    uint64_t id;
    fast_task_t *next;
    uint8_t state;
    uint8_t queued;
    uint8_t context_valid;
    uint8_t user_context;
    uint8_t park_state;
};
_Static_assert(
    offsetof(fast_task_t, fpu) == fpu_state_offset,
    "fast handoff FPU state must match the IRQ frame layout"
);
_Static_assert(
    _Alignof(fast_task_t) >= 16,
    "FXSAVE/FXRSTOR require a 16-byte-aligned task context"
);

typedef struct {
    fast_task_t *current;
    fast_task_t *idle;
    fast_task_t *head;
    fast_task_t *tail;
    uint64_t queue_size;
    uint8_t is_bsp;
    enum fast_cpu_state state;
    uint8_t lock;
    uint64_t timer_deadline;
    uint64_t wake_sequence;
} __attribute__((aligned(64))) fast_cpu_t;

static fast_cpu_t fast_cpus[cpu_slot_count];
static uint8_t handoff_enabled;
static uint8_t yield_requested[cpu_slot_count];
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

static inline uint8_t task_state_load(const fast_task_t *task) {
    return __atomic_load_n(&task->state, __ATOMIC_ACQUIRE);
}

static inline void task_state_store(fast_task_t *task, uint8_t state) {
    __atomic_store_n(&task->state, state, __ATOMIC_RELEASE);
}

static uint64_t current_lapic_id(void) {
    if (lapic_x2apic)
        return rdmsr(x2apic_msr_base + (lapic_id_register >> 4));
    if (!lapic_mmio_base) return 0;
    return *(volatile uint32_t *)(uintptr_t)(lapic_mmio_base + lapic_id_register) >> 24;
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

static void wake_cpu(uint64_t lapic_id) {
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
    if (cpu->state == cpu_offline) return;

    __atomic_store_n(
        &yield_requested[lapic_id % cpu_slot_count], 1, __ATOMIC_RELEASE);
    if (lapic_id != current_lapic_id()) lapic_send_reschedule(lapic_id);
}

static bool queue_push(fast_cpu_t *cpu, fast_task_t *task) {
    uint8_t expected = 0;
    if (!task || !__atomic_compare_exchange_n(
            &task->queued,
            &expected,
            1,
            false,
            __ATOMIC_ACQ_REL,
            __ATOMIC_ACQUIRE
        ))
        return false;

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
        uint8_t expected = task_ready;
        if (__atomic_compare_exchange_n(
                &task->state,
                &expected,
                task_running,
                false,
                __ATOMIC_ACQ_REL,
                __ATOMIC_ACQUIRE
            )) {
            __atomic_store_n(&task->queued, 0, __ATOMIC_RELEASE);
            return task;
        }
        __atomic_store_n(&task->queued, 0, __ATOMIC_RELEASE);
    }
    return NULL;
}

static void save_task(fast_task_t *task, pt_regs_t *regs) {
    task->regs = *regs;
    __builtin_memcpy(
        task->fpu,
        (const uint8_t *)regs + fpu_state_offset,
        fpu_state_size
    );
    __atomic_store_n(&task->context_valid, 1, __ATOMIC_RELEASE);
}

static void restore_task(const fast_task_t *task, pt_regs_t *regs) {
    *regs = task->regs;
    __builtin_memcpy(
        (uint8_t *)regs + fpu_state_offset,
        task->fpu,
        fpu_state_size
    );
}

static void initialize_fpu(fast_task_t *task) {
    __builtin_memset(task->fpu, 0, sizeof(task->fpu));
    task->fpu[0] = 0x7f;
    task->fpu[1] = 0x03;
    task->fpu[24] = 0x80;
    task->fpu[25] = 0x1f;
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

void fast_handoff_yield(void) {
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE)) return;

    const uint64_t slot = current_lapic_id() % cpu_slot_count;
    if (fast_cpus[slot].state == cpu_offline) return;
    __atomic_store_n(&yield_requested[slot], 1, __ATOMIC_RELEASE);
    __asm__ volatile("int $0x20" : : : "memory");
}

bool fast_handoff_park_current(void) {
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE)) return false;

    const uint64_t flags = interrupt_save();
    const uint64_t slot = current_lapic_id() % cpu_slot_count;
    fast_cpu_t *cpu = &fast_cpus[slot];
    fast_task_t *task = cpu->current;
    if (cpu->state == cpu_offline || !task || task == cpu->idle) {
        interrupt_restore(flags);
        return false;
    }

    uint8_t expected = park_idle;
    if (__atomic_compare_exchange_n(
            &task->park_state,
            &expected,
            park_requested,
            false,
            __ATOMIC_ACQ_REL,
            __ATOMIC_ACQUIRE
        )) {
        __atomic_store_n(&yield_requested[slot], 1, __ATOMIC_RELEASE);
        __asm__ volatile("int $0x20" : : : "memory");
        interrupt_restore(flags);
        return true;
    }
    if (expected != park_notified || !__atomic_compare_exchange_n(
            &task->park_state,
            &expected,
            park_idle,
            false,
            __ATOMIC_ACQ_REL,
            __ATOMIC_ACQUIRE
        )) {
        interrupt_restore(flags);
        return false;
    }
    interrupt_restore(flags);
    return true;
}

bool fast_handoff_unpark(uint64_t handle, uint64_t lapic_id) {
    fast_task_t *task = task_from_handle(handle);
    if (!task) return false;

    for (;;) {
        if (task_state_load(task) == task_zombie) return false;

        uint8_t parked = __atomic_load_n(&task->park_state, __ATOMIC_ACQUIRE);
        if (parked == park_notified) return true;
        if (parked != park_parked) {
            if (__atomic_compare_exchange_n(
                    &task->park_state,
                    &parked,
                    park_notified,
                    false,
                    __ATOMIC_ACQ_REL,
                    __ATOMIC_ACQUIRE
                ))
                return true;
            continue;
        }
        if (!__atomic_compare_exchange_n(
                &task->park_state,
                &parked,
                park_idle,
                false,
                __ATOMIC_ACQ_REL,
                __ATOMIC_ACQUIRE
            ))
            continue;

        uint8_t state = task_blocked;
        if (!__atomic_compare_exchange_n(
                &task->state,
                &state,
                task_ready,
                false,
                __ATOMIC_ACQ_REL,
                __ATOMIC_ACQUIRE
            ))
            return state != task_zombie;

        return fast_handoff_enqueue(handle, lapic_id) ||
            __atomic_load_n(&task->queued, __ATOMIC_ACQUIRE) != 0;
    }
}

uint64_t fast_handoff_wake_sequence(void) {
    fast_cpu_t *cpu = &fast_cpus[current_lapic_id() % cpu_slot_count];
    return __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
}

void fast_handoff_wake_bsp(void) {
    const uint64_t lapic_id = __atomic_load_n(&bsp_lapic_id, __ATOMIC_ACQUIRE);
    if (lapic_id != UINT64_MAX) wake_cpu(lapic_id);
}

/* The Kotlin/Native interop wrapper keeps this entire call in Native state. */
void fast_handoff_park_kotlin(uint64_t deadline_ns, uint64_t wake_sequence) {
    const uint64_t flags = interrupt_save();
    fast_handoff_yield();

    fast_cpu_t *cpu = &fast_cpus[current_lapic_id() % cpu_slot_count];
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

static void wake_idle_worker(fast_cpu_t *cpu) {
    fast_task_t *idle = cpu->idle;
    if (cpu->state != cpu_online || !idle || idle == cpu->current ||
        !__atomic_load_n(&idle->context_valid, __ATOMIC_ACQUIRE))
        return;

    task_state_store(idle, task_ready);
    queue_push(cpu, idle);
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
    initialize_fpu(task);
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
    if (!task) return;
    __builtin_memset(&task->regs, 0, sizeof(task->regs));
    task->regs.rip = entry;
    task->regs.rsp = rsp;
    task->regs.rbp = rsp;
    task->regs.rflags = 0x202;
    task->regs.cs = 0x08;
    task->regs.ss = 0x10;
    task->regs.ds = 0x10;
    task->regs.es = 0x10;
    task->regs.fs_base = fs_base;
    task->kernel_fs_base = fs_base;
    task->regs.rdi = argument;
    task->user_context = 0;
    task_state_store(task, task_ready);
    __atomic_store_n(&task->context_valid, 1, __ATOMIC_RELEASE);
    initialize_fpu(task);
}

void fast_handoff_init_user(
    uint64_t handle,
    uint64_t entry,
    uint64_t rsp,
    uint64_t fs_base
) {
    fast_task_t *task = task_from_handle(handle);
    if (!task) return;
    __builtin_memset(&task->regs, 0, sizeof(task->regs));
    task->regs.rip = entry;
    task->regs.rsp = rsp;
    task->regs.rflags = 0x202;
    task->regs.cs = 0x23;
    task->regs.ss = 0x1b;
    task->regs.ds = 0x1b;
    task->regs.es = 0x1b;
    task->regs.fs_base = fs_base;
    task->user_context = 1;
    task_state_store(task, task_ready);
    __atomic_store_n(&task->context_valid, 1, __ATOMIC_RELEASE);
    initialize_fpu(task);
}

void fast_handoff_init_user_registers(
    uint64_t handle,
    const uint64_t *registers,
    uint64_t rsp,
    uint64_t fs_base
) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !registers || !rsp) return;
    __builtin_memcpy(&task->regs, registers, sizeof(task->regs));
    task->regs.rax = 0;
    task->regs.func = 0;
    task->regs.errcode = 0;
    task->regs.rsp = rsp;
    if (fs_base) task->regs.fs_base = fs_base;
    task->user_context = 1;
    task_state_store(task, task_ready);
    __atomic_store_n(&task->context_valid, 1, __ATOMIC_RELEASE);
    initialize_fpu(task);
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
    if (cpu->is_bsp)
        __atomic_store_n(&bsp_lapic_id, lapic_id, __ATOMIC_RELEASE);
    cpu->state = cpu_bootstrapping;
    if (!task->kernel_fs_base)
        task->kernel_fs_base = rdmsr(ia32_fs_base_msr);
    task_state_store(task, task_running);
    __atomic_store_n(&task->queued, 0, __ATOMIC_RELEASE);
    unlock_cpu(cpu);
    interrupt_restore(flags);
    return true;
}

bool fast_handoff_finish_bootstrap(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    if (!task) return false;

    fast_cpu_t *cpu = &fast_cpus[current_lapic_id() % cpu_slot_count];
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
    if (!task || !__atomic_load_n(&task->context_valid, __ATOMIC_ACQUIRE))
        return false;
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    const uint8_t state = task_state_load(task);
    const bool accepted = task != cpu->current && state != task_running &&
        state != task_zombie && queue_push(cpu, task);
    if (accepted) task_state_store(task, task_ready);
    unlock_cpu(cpu);
    if (accepted) wake_cpu(lapic_id);
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
    if (task && state <= task_zombie)
        __atomic_store_n(&task->state, state, __ATOMIC_RELEASE);
}

uint8_t fast_handoff_task_is_queued(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    return task ? __atomic_load_n(&task->queued, __ATOMIC_ACQUIRE) : 0;
}

uint8_t fast_handoff_task_has_context(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    return task ? __atomic_load_n(&task->context_valid, __ATOMIC_ACQUIRE) : 0;
}

uint64_t fast_handoff_current_task_id(void) {
    fast_cpu_t *cpu = &fast_cpus[current_lapic_id() % cpu_slot_count];
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    const fast_task_t *task = cpu->current;
    const uint64_t id = task ? task->id : UINT64_MAX;
    unlock_cpu(cpu);
    interrupt_restore(flags);
    return id;
}

bool fast_handoff_replace_address_space(uint64_t handle, uint64_t cr3) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !cr3) return false;

    fast_cpu_t *cpu = &fast_cpus[current_lapic_id() % cpu_slot_count];
    const uint64_t flags = interrupt_save();
    lock_cpu(cpu);
    const bool current = cpu->current == task;
    if (current) task->cr3 = cr3;
    unlock_cpu(cpu);
    if (current) write_cr3(cr3);
    interrupt_restore(flags);
    return current;
}

void fast_handoff_irq(pt_regs_t *regs, uint64_t irq_num) {
    const uint64_t lapic_id = current_lapic_id();
    const uint64_t slot = lapic_id % cpu_slot_count;
    fast_cpu_t *cpu = &fast_cpus[slot];

    const fast_task_t *current = cpu->current;
    const bool syscall_in_progress = current && current->user_context &&
        (regs->cs & 3) == 0;

    if (irq_num != timer_irq) {
        __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
        if (fast_handoff_ps2_irq(irq_num)) {
            lock_cpu(cpu);
            wake_idle_worker(cpu);
            unlock_cpu(cpu);
            lapic_eoi(irq_num);
        } else if (irq_num == spurious_irq) {
            lapic_eoi(irq_num);
        } else {
            do_irq(regs, irq_num);
        }
        return;
    }

    const bool voluntary = __atomic_exchange_n(
        &yield_requested[slot], 0, __ATOMIC_ACQ_REL) != 0;
    const bool preemptible = (regs->cs & 3) != 0 || voluntary;

    if (preemptible && (!syscall_in_progress || voluntary) &&
        __atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE) &&
        cpu->state != cpu_offline) {
        lock_cpu(cpu);
        fast_task_t *previous = cpu->current;
        const bool previous_is_idle = cpu->state == cpu_online &&
            previous == cpu->idle;
        if (previous) {
            save_task(previous, regs);
            uint8_t parked = __atomic_load_n(
                &previous->park_state, __ATOMIC_ACQUIRE);
            if (parked == park_requested) {
                task_state_store(previous, task_blocked);
                if (!__atomic_compare_exchange_n(
                        &previous->park_state,
                        &parked,
                        park_parked,
                        false,
                        __ATOMIC_RELEASE,
                        __ATOMIC_ACQUIRE
                    )) {
                    task_state_store(previous, task_running);
                    if (parked == park_notified)
                        __atomic_store_n(
                            &previous->park_state, park_idle, __ATOMIC_RELEASE);
                }
            } else if (parked == park_notified) {
                __atomic_compare_exchange_n(
                    &previous->park_state,
                    &parked,
                    park_idle,
                    false,
                    __ATOMIC_ACQ_REL,
                    __ATOMIC_ACQUIRE
                );
            }
            if (!previous_is_idle &&
                task_state_load(previous) == task_running &&
                queue_push(cpu, previous)) {
                task_state_store(previous, task_ready);
            }
        }

        fast_task_t *next = queue_pop(cpu);
        if (next && __atomic_load_n(&next->context_valid, __ATOMIC_ACQUIRE)) {
            cpu->current = next;
        } else {
            next = previous && task_state_load(previous) == task_running
                ? previous : NULL;
            if (!next && cpu->state == cpu_online) next = cpu->idle;
            if (next) task_state_store(next, task_running);
            cpu->current = next;
        }
        if (previous_is_idle && next != previous &&
            task_state_load(previous) == task_running)
            task_state_store(previous, task_ready);
        unlock_cpu(cpu);

        if (next && __atomic_load_n(&next->context_valid, __ATOMIC_ACQUIRE)) {
            if (!previous || previous->cr3 != next->cr3) write_cr3(next->cr3);
            if (next->kernel_rsp) {
                set_kernel_stack(lapic_id, next->kernel_rsp, cpu->is_bsp);
                locals[lapic_id % cpu_slot_count].syscall.kernel_rsp = next->kernel_rsp;
            }
            locals[slot].syscall.kernel_fs_base = next->kernel_fs_base
                ? next->kernel_fs_base : kernel_runtime_fs_bases[slot];
            restore_task(next, regs);
        }
    }

    update_scheduler_timer(cpu);
    lapic_eoi(irq_num);
}
