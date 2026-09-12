.equ task_cr3, 8
.equ task_kernel_rsp, 16
.equ task_kernel_fs_base, 24
.equ task_id, 32
.equ task_elapsed, 64
.equ task_started, 72
.equ task_sequence, 80
.equ task_size, 88
.equ scheduler_cpu, 32

.text
.global fast_handoff_create_task
.type fast_handoff_create_task, @function
fast_handoff_create_task:
    pushq %rdi
    pushq %rsi
    pushq %rdx
    pushq %rcx
    subq $8, %rsp
    movl $1, %edi
    movl $task_size, %esi
    call calloc
    addq $8, %rsp
    popq %rcx
    popq %rdx
    popq %rsi
    popq %rdi
    testq %rax, %rax
    jz .Lcreated
    movq %rdi, task_id(%rax)
    movq %rsi, task_cr3(%rax)
    movq %rdx, task_kernel_rsp(%rax)
    movq %rcx, task_kernel_fs_base(%rax)
.Lcreated:
    ret
.size fast_handoff_create_task, .-fast_handoff_create_task

.global fast_switch_to
.type fast_switch_to, @function
fast_switch_to:
    pushq %rbp
    pushq %rbx
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15
    movq %rdi, %r12
    movq %rsi, %r13
    subq $8, %rsp
    call runtime_clock_nanos
    addq $8, %rsp
    incq task_sequence(%r12)
    movq task_started(%r12), %rdx
    testq %rdx, %rdx
    jz .Lstopped
    movq %rax, %rcx
    subq %rdx, %rcx
    addq %rcx, task_elapsed(%r12)
.Lstopped:
    movq $0, task_started(%r12)
    incq task_sequence(%r12)
    movq %gs:scheduler_cpu, %rdx
    movq (%rdx), %rdx
    incq task_sequence(%rdx)
    movq %rax, task_started(%rdx)
    incq task_sequence(%rdx)
    movq %rsp, (%r12)
    movq %r13, %rsp
    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %rbx
    popq %rbp
    ret
.size fast_switch_to, .-fast_switch_to

.global fast_handoff_task_cpu_time
.type fast_handoff_task_cpu_time, @function
fast_handoff_task_cpu_time:
    pushq %rbx
    pushq %rbp
    pushq %r12
    pushq %r13
    subq $8, %rsp
    movq %rdi, %rbx
.Lsample:
    movq task_sequence(%rbx), %rbp
    testb $1, %bpl
    jnz .Lretry
    movq task_elapsed(%rbx), %r12
    movq task_started(%rbx), %r13
    testq %r13, %r13
    jz .Lvalidate
    call runtime_clock_nanos
    subq %r13, %rax
    addq %rax, %r12
.Lvalidate:
    cmpq task_sequence(%rbx), %rbp
    jne .Lretry
    movq %r12, %rax
    addq $8, %rsp
    popq %r13
    popq %r12
    popq %rbp
    popq %rbx
    ret
.Lretry:
    pause
    jmp .Lsample
.size fast_handoff_task_cpu_time, .-fast_handoff_task_cpu_time

.section .note.GNU-stack,"",@progbits
