#include "bridge.h"
#include "native.h"
#include <stdlib.h>

extern void do_irq(uint64_t irq_num);

enum {
    irq_vector_base = 0x20,
    device_vector_limit = 0xef,
    scheduler_vector = 0xfe,
    device_irq_limit = device_vector_limit - irq_vector_base + 1,
    device_irq_service_budget = 64,
    timer_irq = scheduler_vector - irq_vector_base + 1,
    spurious_irq = 0xff - irq_vector_base + 1,
    lapic_id_register = 0x20,
    lapic_eoi_register = 0xb0,
    lapic_isr_base_register = 0x100,
    lapic_register_stride = 0x10,
    lapic_icr_register = 0x300,
    lapic_icr_high_register = 0x310,
    lapic_timer_register = 0x320,
    lapic_delivery_pending = 1u << 12,
    lapic_timer_tsc_deadline = 1u << 18,
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

struct fast_cpu {
    fast_task_t *current;
    fast_task_t *idle;
    fast_node_t *head;
    fast_cpu_t *next;
    uint64_t virtual_time, slice_started, worker_deadline;
    uint32_t logical_id;
    uint8_t reschedule, worker_pending, balance;
    uint64_t queue_size;
    uint8_t is_bsp;
    enum fast_cpu_state state;
    uint8_t lock;
    uint64_t lapic_id;
    uint64_t timer_deadline;
    uint64_t quantum_deadline;
    uint64_t wake_sequence;
    fast_node_t *sleepers;
} __attribute__((aligned(64)));

static fast_cpu_t fast_cpus[cpu_slot_count];
static fast_cpu_t *online_cpus;
static fast_task_t *runtime_tasks;
static fast_task_t *exited_runtime;
static uint8_t handoff_enabled;
static uint8_t lapic_x2apic;
static uint64_t lapic_mmio_base;
static uint64_t bsp_lapic_id = UINT64_MAX;
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
    fast_cpu_t *cpu;
    __asm__ volatile("movq %%gs:32, %0" : "=r"(cpu));
    return cpu;
}

static fast_cpu_t *current_schedulable_cpu(void) {
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE)) return NULL;
    fast_cpu_t *cpu = current_cpu();
    if (!cpu || __atomic_load_n(&cpu->state, __ATOMIC_ACQUIRE) == cpu_offline)
        return NULL;
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

static void dispatch_device_irqs(void) {
    const uint32_t last_register = device_vector_limit / 32;
    const uint32_t last_mask = UINT32_MAX >>
        (31 - device_vector_limit % 32);
    uint32_t serviced = 0;

    for (uint32_t index = last_register; index > 0; index--) {
        const uint32_t register_address =
            lapic_isr_base_register + index * lapic_register_stride;
        const uint32_t mask = index == last_register
            ? last_mask : UINT32_MAX;
        uint32_t pending = lapic_read(register_address) & mask;
        while (pending && serviced < device_irq_service_budget) {
            const uint32_t bit = 31 - __builtin_clz(pending);
            const uint32_t vector = index * 32 + bit;
            pending &= ~(1u << bit);
            do_irq(vector - irq_vector_base + 1);
            lapic_write(lapic_eoi_register, 0);
            serviced++;
        }
        if (serviced == device_irq_service_budget) break;
    }
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

static fast_node_t *heap_meld(fast_node_t *first, fast_node_t *second) {
    if (!first) return second;
    if (!second) return first;
    if (first->key > second->key) {
        fast_node_t *swap = first;
        first = second;
        second = swap;
    }
    second->previous = first;
    second->next = first->child;
    if (second->next) second->next->previous = second;
    first->child = second;
    return first;
}

static void heap_remove(fast_node_t **root, fast_node_t *node) {
    fast_node_t *previous = node->previous;
    if (previous) {
        if (previous->child == node) previous->child = node->next;
        else previous->next = node->next;
        if (node->next) node->next->previous = previous;
    } else *root = NULL;
    fast_node_t *pairs = NULL, *child = node->child;
    while (child) {
        fast_node_t *first = child, *second = first->next;
        child = second ? second->next : NULL;
        first->next = first->previous = NULL;
        if (second) second->next = second->previous = NULL;
        fast_node_t *pair = heap_meld(first, second);
        pair->next = pairs;
        pairs = pair;
    }
    fast_node_t *merged = NULL;
    while (pairs) {
        fast_node_t *pair = pairs;
        pairs = pair->next;
        pair->next = NULL;
        merged = heap_meld(pair, merged);
    }
    *root = heap_meld(*root, merged);
    node->next = node->previous = node->child = NULL;
}

static void sleep_remove_locked(fast_task_t *task) {
    if (!task->sleeping) return;
    heap_remove(&task->cpu->sleepers, &task->sleep);
    task->sleeping = false;
}

static void update_scheduler_timer(fast_cpu_t *cpu) {
    uint64_t deadline = cpu->quantum_deadline;
    if (cpu->sleepers && (!deadline || cpu->sleepers->key < deadline))
        deadline = cpu->sleepers->key;
    if (cpu->worker_deadline && (!deadline || cpu->worker_deadline < deadline))
        deadline = cpu->worker_deadline;
    if (deadline != cpu->timer_deadline) set_timer_deadline(cpu, deadline);
}

static void wake_cpu(fast_cpu_t *cpu) {
    __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
    __atomic_store_n(&cpu->reschedule, true, __ATOMIC_RELEASE);
    const enum fast_cpu_state state = __atomic_load_n(&cpu->state, __ATOMIC_ACQUIRE);
    if (state == cpu_offline) return;
    bool notify = cpu != current_cpu() || state == cpu_bootstrapping;
    if (!notify) {
        const fast_task_t *current = __atomic_load_n(&cpu->current, __ATOMIC_ACQUIRE);
        notify = current == cpu->idle;
    }
    if (notify) lapic_send_reschedule(cpu->lapic_id);
    fast_cpu_t *idle = __atomic_load_n(&online_cpus, __ATOMIC_ACQUIRE);
    for (; idle; idle = idle->next) {
        if (idle == cpu || idle->state != cpu_online) continue;
        const fast_task_t *current = __atomic_load_n(&idle->current, __ATOMIC_ACQUIRE);
        if (current == idle->idle) lapic_send_reschedule(idle->lapic_id);
    }
}

static bool queue_push(fast_cpu_t *cpu, fast_task_t *task) {
    if (!task || task == cpu->idle || task->queued) return false;
    if ((int64_t)(task->run.key - cpu->virtual_time) < 0) task->run.key = cpu->virtual_time;
    task->queued = true;
    cpu->head = heap_meld(cpu->head, &task->run);
    __atomic_add_fetch(&cpu->queue_size, 1, __ATOMIC_RELAXED);
    return true;
}

static void queue_remove(fast_cpu_t *cpu, fast_task_t *task) {
    if (!task->queued) return;
    heap_remove(&cpu->head, &task->run);
    __atomic_sub_fetch(&cpu->queue_size, 1, __ATOMIC_RELAXED);
    task->queued = false;
}

static bool cpu_allowed(fast_task_t *task, fast_cpu_t *cpu) {
    const size_t byte = cpu->logical_id / 8;
    return !task->affinity || (byte < task->affinity_size &&
        (task->affinity[byte] & (1u << (cpu->logical_id % 8))));
}

static void request_balance(fast_cpu_t *exclude) {
    fast_cpu_t *target = __atomic_load_n(&online_cpus, __ATOMIC_ACQUIRE);
    for (; target; target = target->next) {
        __atomic_store_n(&target->balance, true, __ATOMIC_RELEASE);
        if (target != exclude) lapic_send_reschedule(target->lapic_id);
    }
}

static fast_task_t *queue_pop(fast_cpu_t *cpu) {
    while (cpu->head) {
        fast_task_t *task = cpu->head->task;
        if (!cpu_allowed(task, cpu)) {
            request_balance(cpu);
            return NULL;
        }
        queue_remove(cpu, task);
        if (task_state_load(task) != task_ready) continue;
        const int64_t advance = task->run.key - cpu->virtual_time;
        if (advance > 0) cpu->virtual_time = task->run.key;
        task_state_store(task, task_running);
        return task;
    }
    return NULL;
}

static void balance_cpu(fast_cpu_t *cpu) {
    fast_cpu_t *source = __atomic_load_n(&online_cpus, __ATOMIC_ACQUIRE);
    for (; source; source = source->next) {
        if (source == cpu) continue;
        const uint64_t queued = __atomic_load_n(&source->queue_size, __ATOMIC_RELAXED);
        if (!queued) continue;
        fast_cpu_t *first = source->lapic_id < cpu->lapic_id ? source : cpu;
        fast_cpu_t *second = first == cpu ? source : cpu;
        lock_cpu(first);
        lock_cpu(second);
        fast_task_t *task = source->head ? source->head->task : NULL;
        const bool movable = task && (!cpu->head || !cpu_allowed(task, source)) &&
            task->kernel_rsp && task->id != UINT32_MAX &&
            !__atomic_load_n(&task->on_cpu, __ATOMIC_ACQUIRE) && cpu_allowed(task, cpu);
        if (movable) {
            queue_remove(source, task);
            task->run.key = cpu->virtual_time;
            __atomic_store_n(&task->cpu, cpu, __ATOMIC_RELEASE);
            queue_push(cpu, task);
        }
        unlock_cpu(second);
        unlock_cpu(first);
        if (movable) return;
    }
}

static void account_task(fast_cpu_t *cpu, fast_task_t *task, uint64_t now) {
    const uint64_t started = task->clock.started;
    if (!started) return;
    __atomic_add_fetch(&task->clock.sequence, 1, __ATOMIC_ACQ_REL);
    const uint64_t elapsed = now - started;
    __atomic_add_fetch(&task->clock.elapsed, elapsed, __ATOMIC_RELAXED);
    const size_t slot = cpu->logical_id * cpu_account_stride + !task->user_mode;
    for (cpu_account_t *account = task->account; account; account = account->parent) {
        uint64_t *counter = &account->time[slot];
        __atomic_add_fetch(counter, elapsed, __ATOMIC_RELAXED);
    }
    __atomic_store_n(&task->clock.started, now, __ATOMIC_RELAXED);
    __atomic_add_fetch(&task->clock.sequence, 1, __ATOMIC_RELEASE);
}

void fast_handoff_account_mode(uint8_t user) {
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = current_schedulable_cpu();
    if (!cpu) {
        irq_restore(flags);
        return;
    }
    lock_cpu(cpu);
    account_task(cpu, cpu->current, runtime_clock_nanos());
    cpu->current->user_mode = user;
    unlock_cpu(cpu);
    irq_restore(flags);
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

uint64_t fast_handoff_account_time(cpu_account_t *account, size_t index) {
    return __atomic_load_n(&account->time[index], __ATOMIC_RELAXED);
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
        cpu->reschedule |= migrate;
        unlock_cpu(cpu);
    }
    if (migrate) request_balance(NULL);
    irq_restore(flags);
}

void fast_handoff_set_account(uint64_t handle, cpu_account_t *account) {
    fast_task_t *task = task_from_handle(handle);
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = lock_task(task);
    if (cpu) account_task(cpu, task, runtime_clock_nanos());
    task->account = account;
    if (cpu) unlock_cpu(cpu);
    irq_restore(flags);
}

static enum fast_schedule_result fast_handoff_schedule(
    fast_cpu_t *cpu,
    xstate_t *interrupted_xstate,
    enum fast_schedule_request request,
    uint64_t deadline, uint64_t sequence
) {
    const uint64_t flags = irq_save();
    if (!__atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE) ||
        cpu->state == cpu_offline || !cpu->current) {
        irq_restore(flags);
        return schedule_rejected;
    }

    if (cpu->state == cpu_online) {
        const uint64_t queued = __atomic_load_n(&cpu->queue_size, __ATOMIC_RELAXED);
        bool balance = !queued;
        if (queued) balance = __atomic_exchange_n(&cpu->balance, false, __ATOMIC_ACQ_REL);
        if (balance) balance_cpu(cpu);
    }
    lock_cpu(cpu);
    fast_task_t *runtime = __atomic_load_n(&runtime_tasks, __ATOMIC_ACQUIRE);
    if (runtime) runtime = __atomic_exchange_n(&runtime_tasks, NULL, __ATOMIC_ACQ_REL);
    while (runtime) {
        fast_task_t *next = runtime->next;
        runtime->cr3 = cpu->idle->cr3;
        runtime->quantum_cycles = cpu->idle->quantum_cycles;
        runtime->cpu = cpu;
        queue_push(cpu, runtime);
        runtime = next;
    }
    const uint64_t now = read_tsc();
    while (cpu->sleepers && cpu->sleepers->key <= now) {
        fast_task_t *task = cpu->sleepers->task;
        sleep_remove_locked(task);
        if (task_state_load(task) != task_blocked) continue;
        task_state_store(task, task_ready);
        queue_push(cpu, task);
        cpu->reschedule = true;
    }
    if (cpu->worker_deadline && cpu->worker_deadline <= now) {
        cpu->worker_pending = true;
        cpu->worker_deadline = 0;
    }
    fast_task_t *previous = cpu->current;
    const bool expired = !cpu->quantum_deadline || now >= cpu->quantum_deadline;
    bool running = task_state_load(previous) == task_running;
    bool select_next = request != schedule_tick || cpu->reschedule ||
        cpu->worker_pending || expired || !running;
    cpu->reschedule = false;
    account_task(cpu, previous, runtime_clock_nanos());
    if (previous != cpu->idle && cpu->slice_started) {
        if (previous->queued) heap_remove(&cpu->head, &previous->run);
        const uint32_t weight = __atomic_load_n(&previous->weight, __ATOMIC_RELAXED);
        const __uint128_t weighted_time = (__uint128_t)(now - cpu->slice_started) * 1024;
        previous->run.key += (uint64_t)(weighted_time / weight);
        if (previous->queued) cpu->head = heap_meld(cpu->head, &previous->run);
        uint64_t floor = previous->run.key;
        if (cpu->head && cpu->head->key < floor) floor = cpu->head->key;
        if (floor > cpu->virtual_time) cpu->virtual_time = floor;
    }
    cpu->slice_started = now;
    const bool parking = request == schedule_park;
    if (parking && (previous == cpu->idle || !running)) {
        unlock_cpu(cpu);
        irq_restore(flags);
        return schedule_rejected;
    }
    const bool awakened = previous->wake_sequence != sequence;
    if (parking && (awakened || (deadline && deadline <= now))) {
        select_next = false;
    } else if (parking) {
        task_state_store(previous, task_blocked);
        running = false;
        if (deadline) {
            previous->sleep.key = deadline;
            previous->sleeping = true;
            cpu->sleepers = heap_meld(cpu->sleepers, &previous->sleep);
        }
    }

    fast_task_t *next = previous;
    const bool can_continue = running && cpu_allowed(previous, cpu);
    if (select_next && (cpu->head || cpu->worker_pending || !can_continue)) {
        if (running) {
            task_state_store(previous, task_ready);
            if (previous != cpu->idle) queue_push(cpu, previous);
        }

        const bool run_worker = cpu->worker_pending && previous != cpu->idle && cpu->idle->rsp;
        next = run_worker ? cpu->idle : queue_pop(cpu);
        if (!next && cpu->idle && cpu->idle->rsp) next = cpu->idle;
        if (next) task_state_store(next, task_running);
        if (!next && parking) {
            sleep_remove_locked(previous);
            task_state_store(previous, task_running);
        }
        if (!next) next = previous;
    }
    if (select_next) {
        cpu->quantum_deadline = 0;
        if (cpu->state == cpu_online && next != cpu->idle) {
            const uint64_t quantum = __atomic_load_n(&next->quantum_cycles, __ATOMIC_RELAXED);
            cpu->quantum_deadline = now > UINT64_MAX - quantum ? UINT64_MAX : now + quantum;
        }
    }
    if (next != previous) {
        __atomic_add_fetch(&previous->clock.sequence, 1, __ATOMIC_ACQ_REL);
        __atomic_store_n(&previous->clock.started, 0, __ATOMIC_RELAXED);
        __atomic_add_fetch(&previous->clock.sequence, 1, __ATOMIC_RELEASE);
        const uint64_t started = runtime_clock_nanos();
        __atomic_store_n(&next->clock.started, started, __ATOMIC_RELEASE);
    }
    __atomic_store_n(&next->on_cpu, true, __ATOMIC_RELEASE);
    cpu->current = next;
    update_scheduler_timer(cpu);
    unlock_cpu(cpu);

    if (next == previous) {
        irq_restore(flags);
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
    if (previous_fs_base != kernel_fs_base)
        wrmsr(ia32_fs_base_msr, kernel_fs_base);
    fast_switch_to(previous, next->rsp);
    irq_restore(flags);
    return schedule_switched;
}

static enum fast_schedule_result fast_handoff_schedule_current(
    enum fast_schedule_request request,
    uint64_t deadline, uint64_t sequence
) {
    xstate_t xstate;
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = current_schedulable_cpu();
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
    lapic_send_reschedule(cpu->lapic_id);
    irq_restore(flags);
}

bool fast_handoff_configure_timer(uint8_t vector) {
    if (vector != scheduler_vector) return false;
    lapic_write(lapic_timer_register, vector | lapic_timer_tsc_deadline);
    const uint32_t id = lapic_read(lapic_id_register);
    const uint32_t lapic_id = lapic_x2apic ? id : id >> 24;
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    set_timer_deadline(cpu, 0);
    return true;
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
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = current_cpu();
    __atomic_store_n(&cpu->worker_pending, true, __ATOMIC_RELEASE);
    dispatch_device_irqs();
    const uint64_t sequence = __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
    irq_restore(flags);
    return sequence;
}

void fast_handoff_wake_bsp(void) {
    const uint64_t lapic_id = __atomic_load_n(&bsp_lapic_id, __ATOMIC_ACQUIRE);
    if (lapic_id == UINT64_MAX) return;

    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    __atomic_store_n(&cpu->worker_pending, true, __ATOMIC_RELEASE);
    wake_cpu(cpu);
}

void fast_handoff_park_kotlin(uint64_t deadline_ns, uint64_t wake_sequence, bool may_sleep) {
    const uint64_t flags = irq_save();
    fast_cpu_t *cpu = current_cpu();
    lock_cpu(cpu);
    const uint64_t observed = __atomic_load_n(&cpu->wake_sequence, __ATOMIC_ACQUIRE);
    cpu->worker_pending = !may_sleep || wake_sequence != observed;
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
    irq_restore(flags);
}

_Noreturn void fast_handoff_idle(void) {
    for (;;) {
        const uint64_t sequence = fast_handoff_service();
        fast_handoff_park_kotlin(0, sequence, true);
    }
}

uint64_t fast_handoff_create_task(
    uint32_t id,
    uint64_t cr3,
    uint64_t kernel_rsp,
    uint64_t kernel_fs_base,
    uint64_t quantum_cycles, uint32_t weight
) {
    fast_task_t *task = malloc(sizeof(*task));
    if (!task) return 0;
    *task = (fast_task_t){.id = id, .cr3 = cr3, .kernel_rsp = kernel_rsp,
        .kernel_fs_base = kernel_fs_base, .quantum_cycles = quantum_cycles, .weight = weight,
        .user_mode = kernel_rsp != 0};
    task->run.task = task->sleep.task = task;
    return (uintptr_t)task;
}

void fast_handoff_set_weight(uint64_t handle, uint32_t weight) {
    fast_task_t *task = task_from_handle(handle);
    __atomic_store_n(&task->weight, weight, __ATOMIC_RELAXED);
}

bool fast_handoff_task_has_exited(uint64_t handle) {
    const fast_task_t *task = task_from_handle(handle);
    return task_state_load(task) == task_zombie &&
        !__atomic_load_n(&task->on_cpu, __ATOMIC_ACQUIRE) &&
        !__atomic_load_n(&task->rsp, __ATOMIC_ACQUIRE);
}

uint64_t fast_handoff_task_cpu_time(uint64_t handle) {
    const fast_task_t *task = task_from_handle(handle);
    for (;;) {
        const uint64_t sequence = __atomic_load_n(&task->clock.sequence, __ATOMIC_ACQUIRE);
        if (sequence & 1) goto retry;
        uint64_t elapsed = __atomic_load_n(&task->clock.elapsed, __ATOMIC_RELAXED);
        const uint64_t started = __atomic_load_n(&task->clock.started, __ATOMIC_ACQUIRE);
        if (started) elapsed += runtime_clock_nanos() - started;
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        const uint64_t observed = __atomic_load_n(&task->clock.sequence, __ATOMIC_RELAXED);
        if (sequence == observed) return elapsed;
retry:
        __asm__ volatile("pause");
    }
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

uint64_t fast_handoff_queue_size(uint64_t lapic_id) {
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
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
    fast_handoff_yield();
    fast_handoff_idle();
}

bool fast_handoff_prepare_runtime(uint64_t rsp, uint64_t fs_base) {
    const uint64_t handle = fast_handoff_create_task(UINT32_MAX, 0, rsp, fs_base, 0, 1024);
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

bool fast_handoff_bind_current(
    uint64_t handle,
    uint64_t lapic_id,
    uint8_t is_bsp, uint32_t logical_id
) {
    fast_task_t *task = task_from_handle(handle);
    if (!task) return false;
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
    const uint64_t flags = irq_save();
    lock_cpu(cpu);
    if (cpu->state != cpu_offline) {
        unlock_cpu(cpu);
        irq_restore(flags);
        return false;
    }
    cpu->current = task;
    cpu->idle = task;
    cpu->is_bsp = is_bsp != 0;
    cpu->lapic_id = lapic_id;
    cpu->logical_id = logical_id;
    task->on_cpu = true;
    fast_cpu_t *head = __atomic_load_n(&online_cpus, __ATOMIC_RELAXED);
    do {
        cpu->next = head;
    } while (!__atomic_compare_exchange_n(
        &online_cpus, &head, cpu, false, __ATOMIC_RELEASE, __ATOMIC_RELAXED
    ));
    locals[lapic_id % cpu_slot_count].syscall.scheduler_cpu = (uintptr_t)cpu;
    __atomic_store_n(&task->cpu, cpu, __ATOMIC_RELEASE);
    if (cpu->is_bsp)
        __atomic_store_n(&bsp_lapic_id, lapic_id, __ATOMIC_RELEASE);
    __atomic_store_n(&cpu->state, cpu_bootstrapping, __ATOMIC_RELEASE);
    if (!task->kernel_fs_base)
        task->kernel_fs_base = rdmsr(ia32_fs_base_msr);
    task_state_store(task, task_running);
    task->queued = false;
    unlock_cpu(cpu);
    if (cpu->is_bsp) __atomic_store_n(&handoff_enabled, true, __ATOMIC_RELEASE);
    irq_restore(flags);
    return true;
}

bool fast_handoff_finish_bootstrap(uint64_t handle) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !task->cpu) return false;

    fast_cpu_t *cpu = task->cpu;
    const uint64_t flags = irq_save();
    lock_cpu(cpu);
    const bool finished = cpu->state == cpu_bootstrapping &&
        cpu->current == task && cpu->idle == task;
    if (finished) __atomic_store_n(&cpu->state, cpu_online, __ATOMIC_RELEASE);
    unlock_cpu(cpu);
    irq_restore(flags);
    return finished;
}

bool fast_handoff_enqueue(uint64_t handle, uint64_t lapic_id) {
    fast_task_t *task = task_from_handle(handle);
    if (!task || !__atomic_load_n(&task->rsp, __ATOMIC_ACQUIRE))
        return false;
    fast_cpu_t *cpu = &fast_cpus[lapic_id % cpu_slot_count];
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
    bool accepted = owner == cpu && task != cpu->current &&
        state != task_running &&
        state != task_zombie;
    if (accepted) {
        if (state == task_blocked) sleep_remove_locked(task);
        task_state_store(task, task_ready);
        accepted = queue_push(cpu, task) || task->queued;
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
    const fast_cpu_t *cpu = current_schedulable_cpu();
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
    fast_cpu_t *cpu = current_cpu();
    const bool user = (regs->cs & 3u) != 0;
    bool restore = false;
    if (user) fast_handoff_account_mode(false);
    if (irq_num <= device_irq_limit) {
        __atomic_add_fetch(&cpu->wake_sequence, 1, __ATOMIC_RELEASE);
        __atomic_store_n(&cpu->worker_pending, true, __ATOMIC_RELEASE);
        lapic_send_reschedule(cpu->lapic_id);
        goto finish;
    }
    if (irq_num == spurious_irq) goto finish;
    lapic_write(lapic_eoi_register, 0);
    if (irq_num != timer_irq) goto finish;
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
    const bool enabled = __atomic_load_n(&handoff_enabled, __ATOMIC_ACQUIRE);
    if (enabled && cpu->state != cpu_offline) {
        xstate_t *interrupted = deliver ? NULL : xstate;
        const enum fast_schedule_result result =
            fast_handoff_schedule(cpu, interrupted, schedule_tick, 0, 0);
        restore = result == schedule_switched;
    } else update_scheduler_timer(cpu);
    restore |= deliver;
finish:
    if (user) fast_handoff_account_mode(true);
    return restore;
}
