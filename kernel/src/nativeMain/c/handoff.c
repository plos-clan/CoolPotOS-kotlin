#include "bridge.h"
#include "native.h"
#include <stdlib.h>

enum {
    irq_vector_base = 0x20,
    device_vector_limit = 0xef,
    scheduler_vector = 0xfe,
    device_irq_limit = device_vector_limit - irq_vector_base + 1,
    timer_irq = scheduler_vector - irq_vector_base + 1,
    spurious_irq = 0xff - irq_vector_base + 1,
    lapic_id_register = 0x20,
    lapic_eoi_register = 0xb0,
    lapic_icr_register = 0x300,
    lapic_icr_high_register = 0x310,
    lapic_delivery_pending = 1u << 12,
    x2apic_msr_base = 0x800,
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
    schedule_tick,
    schedule_reschedule,
    schedule_park,
};

enum fast_schedule_result {
    schedule_rejected,
    schedule_same_task,
    schedule_switched,
};

static fast_task_t *runtime_tasks;
static fast_task_t *exited_runtime;
static uint8_t lapic_x2apic;
static uint64_t lapic_mmio_base;
static fast_cpu_t *bsp_cpu;
static void (*user_interrupt_handler)(void *frame);

__attribute__((naked))
static void fast_switch_to(fast_task_t *, uint64_t) {
    __asm__ volatile(
        "pushq %%rbp\n"
        "pushq %%rbx\n"
        "pushq %%r12\n"
        "pushq %%r13\n"
        "pushq %%r14\n"
        "pushq %%r15\n"
        "movq %%rsp, %c[rsp](%%rdi)\n"
        "movq %%rsi, %%rsp\n"
        "cmpb $%c[zombie], %c[state](%%rdi)\n"
        "jne 1f\n"
        "movq $0, %c[rsp](%%rdi)\n"
        "1:\n"
        "movb $0, %c[on_cpu](%%rdi)\n"
        "popq %%r15\n"
        "popq %%r14\n"
        "popq %%r13\n"
        "popq %%r12\n"
        "popq %%rbx\n"
        "popq %%rbp\n"
        "ret\n"
        :
        : [rsp] "i"(offsetof(fast_task_t, rsp)),
          [on_cpu] "i"(offsetof(fast_task_t, on_cpu)),
          [state] "i"(offsetof(fast_task_t, state)),
          [zombie] "i"(task_zombie)
        : "memory"
    );
}

static inline fast_task_t *task_from_handle(uint64_t handle) {
    return (fast_task_t *)(uintptr_t)handle;
}

static inline void lock_cpu(fast_cpu_t *cpu) {
    while (__atomic_test_and_set(&cpu->lock, __ATOMIC_ACQUIRE))
        __asm__ volatile("pause");
}

static inline void unlock_cpu(fast_cpu_t *cpu) {
    __atomic_clear(&cpu->lock, __ATOMIC_RELEASE);
}

static inline enum fast_task_state task_state_load(const fast_task_t *task) {
    return (enum fast_task_state)__atomic_load_n(&task->state, __ATOMIC_ACQUIRE);
}

static inline void task_state_store(fast_task_t *task, enum fast_task_state state) {
    __atomic_store_n(&task->state, state, __ATOMIC_RELEASE);
}

static fast_cpu_t *current_cpu(void) {
    return (fast_cpu_t *)CPOS_CPU_STATE->scheduler_cpu;
}

static fast_cpu_t *bound_cpu(void) {
    return __atomic_load_n(&bsp_cpu, __ATOMIC_ACQUIRE) ? current_cpu() : NULL;
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

static void lapic_send_interrupt(uint64_t lapic_id, uint32_t command) {
    if (lapic_x2apic) {
        lapic_write(lapic_icr_register, lapic_id << 32 | command);
        return;
    }

    uint32_t pending = lapic_read(lapic_icr_register) & lapic_delivery_pending;
    while (pending) {
        __asm__ volatile("pause");
        pending = lapic_read(lapic_icr_register) & lapic_delivery_pending;
    }
    lapic_write(lapic_icr_high_register, (lapic_id & 0xffu) << 24);
    lapic_write(lapic_icr_register, command);
}

static uint64_t tlb_directory, tlb_target;

void tlb_invalidate_remote(uint64_t directory, uint64_t lapic_id) {
    tlb_directory = directory;
    __atomic_store_n(&tlb_target, lapic_id + 1, __ATOMIC_RELEASE);
    lapic_send_interrupt(lapic_id, 4u << 8);
    uint64_t pending = __atomic_load_n(&tlb_target, __ATOMIC_ACQUIRE);
    while (pending) {
        __asm__ volatile("pause");
        pending = __atomic_load_n(&tlb_target, __ATOMIC_ACQUIRE);
    }
}

bool tlb_handle_nmi(void) {
    const uint64_t target = __atomic_load_n(&tlb_target, __ATOMIC_ACQUIRE);
    uint32_t id = lapic_read(lapic_id_register);
    if (!lapic_x2apic) id >>= 24;
    if (target != (uint64_t)id + 1) return false;
    const uint64_t current = read_cr3();
    if (!tlb_directory || current == tlb_directory) write_cr3(current);
    __atomic_store_n(&tlb_target, 0, __ATOMIC_RELEASE);
    return true;
}

static void set_timer_deadline(fast_cpu_t *cpu, uint64_t deadline) {
    cpu->timer_deadline = deadline;
    wrmsr(ia32_tsc_deadline_msr, deadline);
}

static fast_task_t *heap_meld(fast_task_t *first, fast_task_t *second) {
    if (!first) return second;
    if (!second) return first;
    if (first->deadline > second->deadline) {
        fast_task_t *swap = first;
        first = second;
        second = swap;
    }
    second->previous = first;
    second->next = first->child;
    if (second->next) second->next->previous = second;
    first->child = second;
    return first;
}

static void sleep_remove_locked(fast_task_t *node) {
    if (!node->deadline) return;
    fast_task_t **root = &node->cpu->sleepers;
    fast_task_t *previous = node->previous;
    if (previous) {
        if (previous->child == node) previous->child = node->next;
        else previous->next = node->next;
        if (node->next) node->next->previous = previous;
    } else *root = NULL;
    fast_task_t *pairs = NULL, *child = node->child;
    while (child) {
        fast_task_t *first = child, *second = first->next;
        child = second ? second->next : NULL;
        first->next = first->previous = NULL;
        if (second) second->next = second->previous = NULL;
        fast_task_t *pair = heap_meld(first, second);
        pair->next = pairs;
        pairs = pair;
    }
    fast_task_t *merged = NULL;
    while (pairs) {
        fast_task_t *pair = pairs;
        pairs = pair->next;
        pair->next = NULL;
        merged = heap_meld(pair, merged);
    }
    *root = heap_meld(*root, merged);
    node->next = node->previous = node->child = NULL;
    node->deadline = 0;
}

static void update_scheduler_timer(fast_cpu_t *cpu) {
    uint64_t deadline = cpu->quantum_deadline;
    if (cpu->sleepers && (!deadline || cpu->sleepers->deadline < deadline))
        deadline = cpu->sleepers->deadline;
    if (cpu->worker_deadline && (!deadline || cpu->worker_deadline < deadline))
        deadline = cpu->worker_deadline;
    if (deadline != cpu->timer_deadline) set_timer_deadline(cpu, deadline);
}

static void wake_cpu(fast_cpu_t *cpu) {
    const enum fast_cpu_state state = __atomic_load_n(&cpu->state, __ATOMIC_ACQUIRE);
    if (state == cpu_offline) return;
    const fast_task_t *running = __atomic_load_n(&cpu->current, __ATOMIC_ACQUIRE);
    const uint64_t queued = __atomic_load_n(&cpu->queue_size, __ATOMIC_ACQUIRE);
    const bool busy = queued > 1 || (queued && running != cpu->idle);
    if (busy) __atomic_store_n(&cpu->balance, true, __ATOMIC_RELEASE);
    __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
    __atomic_store_n(&cpu->reschedule, true, __ATOMIC_RELEASE);
    if (running == cpu->idle || cpu != current_cpu()) lapic_send_interrupt(cpu->lapic_id, scheduler_vector);
}

static void queue_push(fast_cpu_t *cpu, fast_task_t *task) {
    if (task == cpu->idle || task->queued) return;
    task->next = NULL;
    task->previous = cpu->tail;
    if (cpu->tail) cpu->tail->next = task;
    else cpu->head = task;
    cpu->tail = task;
    cpu->counts[task->priority]++;
    if (task->priority < cpu->strongest) cpu->strongest = task->priority;
    task->queued = true;
    __atomic_store_n(&cpu->queue_size, cpu->queue_size + 1, __ATOMIC_RELEASE);
}

static void queue_remove(fast_cpu_t *cpu, fast_task_t *task) {
    if (!task->queued) return;
    if (task->previous) task->previous->next = task->next;
    else cpu->head = task->next;
    if (task->next) task->next->previous = task->previous;
    else cpu->tail = task->previous;
    task->previous = task->next = NULL;
    cpu->counts[task->priority]--;
    if (!cpu->head) cpu->strongest = cpu->levels - 1;
    while (cpu->strongest + 1 < cpu->levels) {
        if (cpu->counts[cpu->strongest]) break;
        cpu->strongest++;
    }
    __atomic_store_n(&cpu->queue_size, cpu->queue_size - 1, __ATOMIC_RELEASE);
    task->queued = false;
}

static bool cpu_allowed(fast_task_t *task, fast_cpu_t *cpu) {
    const size_t byte = cpu->logical_id / 8;
    return !task->affinity || (byte < task->affinity_size &&
        (task->affinity[byte] & (1u << (cpu->logical_id % 8))));
}

static fast_task_t *queue_pop(fast_cpu_t *cpu) {
    fast_task_t *candidate = cpu->head;
    while (candidate) {
        fast_task_t *task = candidate;
        candidate = task->next;
        if (!cpu_allowed(task, cpu)) {
            __atomic_store_n(&cpu->balance, true, __ATOMIC_RELEASE);
            continue;
        }
        queue_remove(cpu, task);
        if (task_state_load(task) != task_ready) continue;
        return task;
    }
    return NULL;
}

bool fast_handoff_transfer(fast_cpu_t *source, fast_cpu_t *cpu) {
    if (cpu == source) return false;
    if (__atomic_load_n(&cpu->state, __ATOMIC_ACQUIRE) != cpu_online) return false;
    const uint64_t flags = irq_save();
    fast_cpu_t *first = source->logical_id < cpu->logical_id ? source : cpu;
    fast_cpu_t *second = first == cpu ? source : cpu;
    lock_cpu(first);
    lock_cpu(second);
    const uint64_t supply = source->queue_size + (source->current != source->idle);
    const uint64_t demand = cpu->queue_size + (cpu->current != cpu->idle);
    fast_task_t *candidate = source->tail;
    bool moved = false;
    while (candidate) {
        fast_task_t *task = candidate;
        candidate = task->previous;
        if (!task->kernel_rsp || !cpu_allowed(task, cpu)) continue;
        if (__atomic_load_n(&task->on_cpu, __ATOMIC_ACQUIRE)) continue;
        if (supply <= demand + 1 && cpu_allowed(task, source)) continue;
        queue_remove(source, task);
        __atomic_store_n(&task->cpu, cpu, __ATOMIC_RELEASE);
        queue_push(cpu, task);
        moved = true;
        if (source->head) __atomic_store_n(&source->balance, true, __ATOMIC_RELEASE);
        break;
    }
    unlock_cpu(second);
    unlock_cpu(first);
    if (moved) wake_cpu(cpu);
    irq_restore(flags);
    return moved;
}

static void account_until(fast_cpu_t *cpu, uint64_t now) {
    if (now <= cpu->accounted_at) return;
    *cpu->active_clock += now - cpu->accounted_at;
    cpu->accounted_at = now;
}

void fast_handoff_account_mode(bool user) {
    fast_cpu_t *cpu = current_cpu();
    lock_cpu(cpu);
    account_until(cpu, read_tsc());
    cpu_times_t *clock = &cpu->current->clock;
    cpu->active_clock = user ? &clock->user : &clock->system;
    unlock_cpu(cpu);
}

static fast_cpu_t *lock_task(fast_task_t *task) {
    for (;;) {
        fast_cpu_t *cpu = __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE);
        if (!cpu) return NULL;
        lock_cpu(cpu);
        if (cpu == __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE)) return cpu;
        unlock_cpu(cpu);
    }
}

bool fast_handoff_affinity_settled(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    fast_cpu_t *cpu = __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE);
    return !cpu || !__atomic_load_n(&task->on_cpu, __ATOMIC_ACQUIRE) || cpu_allowed(task, cpu);
}

void fast_handoff_set_affinity(uint64_t handle, uint8_t *mask, size_t size) {
    fast_task_t *task = task_from_handle(handle);
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = lock_task(task);
    task->affinity = mask;
    task->affinity_size = size;
    const bool migrate = cpu && !cpu_allowed(task, cpu);
    if (cpu) {
        if (migrate) __atomic_store_n(&cpu->balance, true, __ATOMIC_RELEASE);
        unlock_cpu(cpu);
    }
    if (migrate) wake_cpu(cpu);
    irq_restore(flags);
}

cpu_times_t fast_handoff_clock(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = lock_task(task);
    if (cpu && cpu->current == task) account_until(cpu, read_tsc());
    const cpu_times_t clock = task->clock;
    if (cpu) unlock_cpu(cpu);
    irq_restore(flags);
    return clock;
}

static enum fast_schedule_result fast_handoff_schedule(
    fast_cpu_t *cpu,
    xstate_t *interrupted_xstate,
    enum fast_schedule_request request,
    uint64_t deadline, uint64_t sequence
) {
    lock_cpu(cpu);
    fast_task_t *runtime = __atomic_load_n(&runtime_tasks, __ATOMIC_ACQUIRE);
    if (runtime) runtime = __atomic_exchange_n(&runtime_tasks, NULL, __ATOMIC_ACQ_REL);
    bool added = runtime != NULL;
    while (runtime) {
        fast_task_t *next = runtime->next;
        runtime->cr3 = cpu->idle->cr3;
        runtime->priority = cpu->idle->priority;
        __atomic_store_n(&runtime->cpu, cpu, __ATOMIC_RELEASE);
        queue_push(cpu, runtime);
        runtime = next;
    }
    const uint64_t now = read_tsc();
    while (cpu->sleepers) {
        fast_task_t *task = cpu->sleepers;
        if (task->deadline > now) break;
        sleep_remove_locked(task);
        if (task_state_load(task) != task_blocked) continue;
        task_state_store(task, task_ready);
        queue_push(cpu, task);
        added = true;
    }
    if (added && cpu->queue_size > 1) __atomic_store_n(&cpu->balance, true, __ATOMIC_RELEASE);
    if (cpu->worker_deadline && cpu->worker_deadline <= now) {
        cpu->worker_pending = true;
        cpu->worker_deadline = 0;
    }
    fast_task_t *previous = cpu->current;
    const bool expired = !cpu->quantum_deadline || now >= cpu->quantum_deadline;
    if (__atomic_load_n(&cpu->balance, __ATOMIC_ACQUIRE)) cpu->worker_pending = true;
    const bool running = task_state_load(previous) == task_running;
    const bool requested = __atomic_load_n(&cpu->reschedule, __ATOMIC_ACQUIRE) &&
        __atomic_exchange_n(&cpu->reschedule, false, __ATOMIC_ACQ_REL);
    const bool reschedule = request != schedule_tick || requested || added ||
        cpu->worker_pending || expired || !running;
    account_until(cpu, now);
    const bool parking = request == schedule_park;
    if (parking && (previous == cpu->idle || !running)) {
        unlock_cpu(cpu);
        return schedule_rejected;
    }
    const bool block = parking && previous->wake_sequence == sequence && (!deadline || deadline > now);
    if (block) task_state_store(previous, task_blocked);
    if (block && deadline) {
        previous->deadline = deadline;
        cpu->sleepers = heap_meld(cpu->sleepers, previous);
    }

    fast_task_t *next = previous;
    const bool select_next = parking ? block : reschedule;
    const bool can_continue = running && !block && cpu_allowed(previous, cpu);
    const bool rotate = select_next && (cpu->head || cpu->worker_pending || !can_continue);
    if (rotate && running && !block) {
        task_state_store(previous, task_ready);
        queue_push(cpu, previous);
    }
    if (rotate) {
        const bool run_worker = cpu->worker_pending && previous != cpu->idle && cpu->idle->rsp;
        next = run_worker ? cpu->idle : queue_pop(cpu);
        if (!next && cpu->idle->rsp) next = cpu->idle;
        if (!next && block) {
            sleep_remove_locked(previous);
            next = previous;
        }
        if (next) task_state_store(next, task_running);
        else next = previous;
    }
    if (select_next) cpu->quantum_deadline = 0;
    const bool active = next != cpu->idle || cpu->head;
    if (select_next && active && cpu->state == cpu_online) {
        const size_t row = (size_t)next->priority * cpu->levels;
        const uint64_t quantum = cpu->slices[row + cpu->strongest];
        cpu->quantum_deadline = now > UINT64_MAX - quantum ? UINT64_MAX : now + quantum;
    }
    __atomic_store_n(&next->on_cpu, true, __ATOMIC_RELEASE);
    __atomic_store_n(&cpu->current, next, __ATOMIC_RELEASE);
    cpu->active_clock = &next->clock.system;
    update_scheduler_timer(cpu);
    unlock_cpu(cpu);

    if (next == previous) {
        return parking && select_next ? schedule_rejected : schedule_same_task;
    }
    if (interrupted_xstate) {
        initialize_xstate_header(interrupted_xstate);
        save_xstate(interrupted_xstate);
        restore_xstate(&initial_xstate);
    }
    if (previous->cr3 != next->cr3) write_cr3(next->cr3);
    const size_t slot = cpu->lapic_id % cpu_slot_count;
    syscall_cpu_state_t *syscall = &locals[slot].syscall;
    if (next->kernel_rsp) {
        set_kernel_stack(cpu->lapic_id, next->kernel_rsp, cpu->is_bsp);
        syscall->kernel_rsp = next->kernel_rsp;
    }
    const uint64_t runtime_fs_base = kernel_runtime_fs_bases[slot];
    const uint64_t previous_fs_base = previous->kernel_fs_base
        ? previous->kernel_fs_base : runtime_fs_base;
    const uint64_t kernel_fs_base = next->kernel_fs_base
        ? next->kernel_fs_base
        : runtime_fs_base;
    syscall->kernel_fs_base = kernel_fs_base;
    previous->user_gs_base = rdmsr(ia32_kernel_gs_base_msr);
    if (previous->user_gs_base != next->user_gs_base)
        wrmsr(ia32_kernel_gs_base_msr, next->user_gs_base);
    if (previous_fs_base != kernel_fs_base)
        wrmsr(ia32_fs_base_msr, kernel_fs_base);
    fast_switch_to(previous, next->rsp);
    return schedule_switched;
}

static enum fast_schedule_result fast_handoff_schedule_current(
    enum fast_schedule_request request,
    uint64_t deadline, uint64_t sequence
) {
    xstate_t xstate;
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = bound_cpu();
    if (!cpu) {
        irq_restore(flags);
        return schedule_rejected;
    }
    const enum fast_schedule_result result =
        fast_handoff_schedule(cpu, &xstate, request, deadline, sequence);
    if (result == schedule_switched) restore_xstate(&xstate);
    irq_restore(flags);
    return result;
}

void fast_handoff_configure_lapic(uint8_t x2apic, uint64_t mmio_base) {
    lapic_x2apic = x2apic != 0;
    lapic_mmio_base = mmio_base;
}

void fast_handoff_set_user_interrupt_handler(void (*handler)(void *frame)) {
    __atomic_store_n(&user_interrupt_handler, handler, __ATOMIC_RELEASE);
}

void fast_handoff_request_user_interrupt(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || task_state_load(task) == task_zombie) return;
    if (__atomic_exchange_n(&task->user_interrupt_pending, true, __ATOMIC_ACQ_REL))
        return;
    fast_cpu_t *cpu = __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE);
    if (!cpu) return;
    const uint64_t flags = irq_save();
    lapic_send_interrupt(cpu->lapic_id, scheduler_vector);
    irq_restore(flags);
}

bool fast_handoff_yield(void) {
    return fast_handoff_schedule_current(schedule_reschedule, 0, 0) == schedule_switched;
}
uint64_t fast_handoff_prepare_park(void) {
    fast_task_t *task = task_from_handle(fast_handoff_current_task_handle());
    return task ? __atomic_load_n(&task->wake_sequence, __ATOMIC_ACQUIRE) : 0;
}

bool fast_handoff_park_current(uint64_t deadline_ns, uint64_t sequence) {
    const uint64_t deadline = runtime_clock_deadline(deadline_ns);
    if (deadline_ns && !deadline) return false;
    return fast_handoff_schedule_current(schedule_park, deadline, sequence) != schedule_rejected;
}

bool fast_handoff_unpark(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    if (!task) return false;
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = lock_task(task);
    if (!cpu) {
        irq_restore(flags);
        return false;
    }
    const enum fast_task_state state = task_state_load(task);
    const bool success = state != task_zombie;
    if (success) __atomic_add_fetch(&task->wake_sequence, 1, __ATOMIC_RELEASE);
    if (state == task_blocked) {
        sleep_remove_locked(task);
        task_state_store(task, task_ready);
        queue_push(cpu, task);
    }
    unlock_cpu(cpu);
    if (state == task_blocked) wake_cpu(cpu);
    irq_restore(flags);
    return success;
}

uint64_t fast_handoff_service(void) {
    fast_cpu_t *cpu = current_cpu();
    __atomic_store_n(&cpu->worker_pending, true, __ATOMIC_RELEASE);
    return __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
}

void fast_handoff_wake_bsp(void) {
    fast_cpu_t *cpu = __atomic_load_n(&bsp_cpu, __ATOMIC_ACQUIRE);
    if (!cpu) return;
    const uint64_t flags = irq_save();
    lock_cpu(cpu);
    __atomic_store_n(&cpu->worker_pending, true, __ATOMIC_RELEASE);
    wake_cpu(cpu);
    unlock_cpu(cpu);
    irq_restore(flags);
}

void fast_handoff_park_kotlin(uint64_t deadline_ns, uint64_t wake_sequence, bool may_sleep) {
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = current_cpu();
    lock_cpu(cpu);
    const uint64_t observed = __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
    cpu->worker_pending = !may_sleep || cpu->device_pending || wake_sequence != observed;
    cpu->worker_deadline = runtime_clock_deadline(deadline_ns);
    unlock_cpu(cpu);
    fast_handoff_schedule_current(schedule_reschedule, 0, 0);
    lock_cpu(cpu);
    const uint64_t latest = __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
    const bool idle = !cpu->head && !cpu->worker_pending;
    const bool sleep = may_sleep && idle && wake_sequence == latest;
    update_scheduler_timer(cpu);
    unlock_cpu(cpu);

    if (sleep && (flags & (1u << 9)))
        __asm__ volatile("sti; hlt; cli" : : : "memory");
    __atomic_store_n(&cpu->worker_pending, true, __ATOMIC_RELEASE);
    irq_restore(flags);
}

bool fast_handoff_needs_balance(void) {
    fast_cpu_t *cpu = current_cpu();
    if (cpu->state != cpu_online) return false;
    return __atomic_load_n(&cpu->balance, __ATOMIC_ACQUIRE) &&
        __atomic_exchange_n(&cpu->balance, false, __ATOMIC_ACQ_REL);
}

uint64_t fast_handoff_create_task(
    uint32_t id,
    uint64_t cr3,
    uint64_t kernel_rsp,
    uint64_t kernel_fs_base,
    uint32_t priority
) {
    fast_task_t *task = malloc(sizeof(*task));
    if (!task) return 0;
    *task = (fast_task_t){.id = id, .cr3 = cr3, .kernel_rsp = kernel_rsp,
        .kernel_fs_base = kernel_fs_base, .priority = priority};
    return (uintptr_t)task;
}

void fast_handoff_set_priority(uint64_t handle, uint32_t priority) {
    fast_task_t *task = task_from_handle(handle);
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = lock_task(task);
    const bool queued = cpu && task->queued;
    if (queued) queue_remove(cpu, task);
    task->priority = priority;
    if (queued) queue_push(cpu, task);
    if (cpu) unlock_cpu(cpu);
    irq_restore(flags);
}

bool fast_handoff_task_has_exited(uint64_t handle) {
    const fast_task_t *task = task_from_handle(handle);
    return task_state_load(task) == task_zombie &&
        !__atomic_load_n(&task->on_cpu, __ATOMIC_ACQUIRE) &&
        !__atomic_load_n(&task->rsp, __ATOMIC_ACQUIRE);
}

static void publish_runtime(fast_task_t **queue, fast_task_t *task) {
    fast_task_t *head = __atomic_load_n(queue, __ATOMIC_RELAXED);
    do {
        task->next = head;
    } while (!__atomic_compare_exchange_n(
        queue, &head, task, false, __ATOMIC_RELEASE, __ATOMIC_RELAXED
    ));
    fast_handoff_wake_bsp();
}

uint64_t fast_handoff_take_exited_runtime(void) {
    if (!__atomic_load_n(&exited_runtime, __ATOMIC_ACQUIRE)) return 0;
    return (uintptr_t)__atomic_exchange_n(&exited_runtime, NULL, __ATOMIC_ACQ_REL);
}

uint64_t fast_handoff_load(fast_cpu_t *cpu) {
    const uint64_t queued = __atomic_load_n(&cpu->queue_size, __ATOMIC_RELAXED);
    const fast_task_t *current = __atomic_load_n(&cpu->current, __ATOMIC_ACQUIRE);
    return queued + (current != cpu->idle);
}

_Noreturn void fast_handoff_exit_current(void) {
    irq_save();
    fast_cpu_t *cpu = current_cpu();
    lock_cpu(cpu);
    fast_task_t *task = cpu->current;
    task_state_store(task, task_zombie);
    queue_remove(cpu, task);
    sleep_remove_locked(task);
    unlock_cpu(cpu);
    if (task->id == UINT32_MAX) publish_runtime(&exited_runtime, task);
    for (;;) fast_handoff_yield();
}

bool fast_handoff_prepare_runtime(uint64_t rsp, uint64_t fs_base) {
    const uint64_t handle = fast_handoff_create_task(UINT32_MAX, 0, rsp, fs_base, 0);
    fast_task_t *task = task_from_handle(handle);
    if (!task) return false;
    runtime_tls_owner(fs_base)->task = (uintptr_t)task;
    switch_frame_t *frame = (switch_frame_t *)(uintptr_t)
        (rsp - sizeof(switch_frame_t));
    *frame = (switch_frame_t){.rip = (uintptr_t)&kernel_clone_thread_entry};
    task->rsp = (uintptr_t)frame;
    return true;
}

void fast_handoff_publish_runtime(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    publish_runtime(&runtime_tasks, task);
}

bool fast_handoff_bind_current(uint64_t handle, fast_cpu_t *cpu) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || current_cpu() || task->priority >= cpu->levels) return false;
    const uint64_t flags = irq_save();
    cpu->idle = task;
    __atomic_store_n(&cpu->current, task, __ATOMIC_RELEASE);
    cpu->worker_pending = true;
    cpu->accounted_at = read_tsc();
    cpu->active_clock = &task->clock.system;
    task->on_cpu = true;
    task->kernel_fs_base = rdmsr(ia32_fs_base_msr);
    task_state_store(task, task_running);
    __atomic_store_n(&task->cpu, cpu, __ATOMIC_RELEASE);
    CPOS_CPU_STATE->scheduler_cpu = (uintptr_t)cpu;
    __atomic_store_n(&cpu->state, cpu_bootstrapping, __ATOMIC_RELEASE);
    if (cpu->is_bsp) __atomic_store_n(&bsp_cpu, cpu, __ATOMIC_RELEASE);
    irq_restore(flags);
    return true;
}

bool fast_handoff_finish_bootstrap(void) {
    fast_cpu_t *cpu = current_cpu();
    if (!cpu || cpu->state != cpu_bootstrapping || cpu->current != cpu->idle) return false;
    __atomic_store_n(&cpu->state, cpu_online, __ATOMIC_RELEASE);
    return true;
}

bool fast_handoff_enqueue(uint64_t handle, fast_cpu_t *cpu) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !__atomic_load_n(&task->rsp, __ATOMIC_ACQUIRE)) return false;
    if (__atomic_load_n(&cpu->state, __ATOMIC_ACQUIRE) == cpu_offline) return false;
    const uint64_t flags = irq_save();
    lock_cpu(cpu);
    fast_cpu_t *owner = __atomic_load_n(&task->cpu, __ATOMIC_ACQUIRE);
    if (!owner) {
        const bool assigned = __atomic_compare_exchange_n(
            &task->cpu, &owner, cpu, false, __ATOMIC_RELEASE, __ATOMIC_ACQUIRE
        );
        if (assigned) owner = cpu;
    }
    const enum fast_task_state state = task_state_load(task);
    const bool runnable = state == task_ready || state == task_blocked;
    const bool accepted = owner == cpu && task != cpu->current && task != cpu->idle && runnable;
    if (accepted) {
        if (state == task_blocked) sleep_remove_locked(task);
        task_state_store(task, task_ready);
        queue_push(cpu, task);
    }
    unlock_cpu(cpu);
    if (accepted) wake_cpu(cpu);
    irq_restore(flags);
    return accepted;
}

uint8_t fast_handoff_task_state(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    return task ? task_state_load(task) : task_zombie;
}

void fast_handoff_set_task_state(uint64_t handle, uint8_t state) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || state > task_zombie) return;
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = lock_task(task);
    if (!cpu) {
        task_state_store(task, (enum fast_task_state)state);
        irq_restore(flags);
        return;
    }
    const enum fast_task_state previous = task_state_load(task);
    if (previous == task_blocked && state != task_blocked)
        sleep_remove_locked(task);
    task_state_store(task, (enum fast_task_state)state);
    if (state == task_zombie || state == task_blocked) queue_remove(cpu, task);
    unlock_cpu(cpu);
    irq_restore(flags);
}

uint64_t fast_handoff_current_task_handle(void) {
    const uint64_t flags = irq_save();
    const fast_cpu_t *cpu = bound_cpu();
    const uint64_t handle = cpu ? (uintptr_t)cpu->current : 0;
    irq_restore(flags);
    return handle;
}

bool fast_handoff_replace_address_space(uint64_t handle, uint64_t cr3) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !cr3) return false;

    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = current_cpu();
    lock_cpu(cpu);
    const bool current = cpu->current == task;
    if (current) task->cr3 = cr3;
    unlock_cpu(cpu);
    if (current) write_cr3(cr3);
    irq_restore(flags);
    return current;
}

__attribute__((used)) bool fast_handoff_irq(pt_regs_t *regs, uint64_t irq_num) {
    fast_cpu_t *cpu = bound_cpu();
    if (!cpu) {
        if (irq_num != spurious_irq) lapic_write(lapic_eoi_register, 0);
        return false;
    }
    const bool user = (regs->cs & 3u) != 0;
    if (irq_num <= device_irq_limit) {
        cpu->device_pending = true;
        __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
        __atomic_store_n(&cpu->worker_pending, true, __ATOMIC_RELEASE);
        lapic_send_interrupt(cpu->lapic_id, scheduler_vector);
        return false;
    }
    if (irq_num == spurious_irq) return false;
    lapic_write(lapic_eoi_register, 0);
    if (irq_num != timer_irq) return false;
    cpu->timer_deadline = 0;
    xstate_t *xstate = &((kernel_entry_frame_t *)regs)->xstate;
    void (*handler)(void *) = __atomic_load_n(&user_interrupt_handler, __ATOMIC_ACQUIRE);
    const bool deliver = user && cpu->current && handler &&
        __atomic_exchange_n(&cpu->current->user_interrupt_pending, false, __ATOMIC_ACQ_REL);
    if (deliver) {
        initialize_xstate_header(xstate);
        save_xstate(xstate);
        restore_xstate(&initial_xstate);
        handler(&regs->rip);
        cpu = current_cpu();
    }
    xstate_t *interrupted = deliver ? NULL : xstate;
    const enum fast_schedule_result result = fast_handoff_schedule(cpu, interrupted, schedule_tick, 0, 0);
    return deliver || result == schedule_switched;
}
