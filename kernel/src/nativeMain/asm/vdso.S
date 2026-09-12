.equ clock_monotonic, 1
.equ clock_monotonic_raw, 4
.equ clock_monotonic_coarse, 6
.equ clock_boottime, 7
.equ supported_clocks, (1 << clock_monotonic) | (1 << clock_monotonic_raw) | (1 << clock_monotonic_coarse) | (1 << clock_boottime)
.equ syscall_clock_gettime, 228
.equ nanoseconds_per_second, 1000000000

.text
.global __vdso_clock_gettime
.type __vdso_clock_gettime, @function
__vdso_clock_gettime:
    cmpl $clock_boottime, %edi
    ja .Lfallback
    movl $supported_clocks, %eax
    btl %edi, %eax
    jnc .Lfallback
    lfence
    rdtsc
    shlq $32, %rdx
    orq %rdx, %rax
    subq __vdso_clock_data(%rip), %rax
    mulq __vdso_clock_data+8(%rip)
    shrdq $32, %rdx, %rax
    xorl %edx, %edx
    movl $nanoseconds_per_second, %ecx
    divq %rcx
    movq %rax, (%rsi)
    movq %rdx, 8(%rsi)
    xorl %eax, %eax
    ret
.Lfallback:
    movl $syscall_clock_gettime, %eax
    syscall
    ret
.size __vdso_clock_gettime, .-__vdso_clock_gettime

.section .vdso_clock_data,"a",@progbits
.balign 8
.hidden __vdso_clock_data
.type __vdso_clock_data, @object
__vdso_clock_data:
    .quad 0, 0
.size __vdso_clock_data, .-__vdso_clock_data

.section .note.GNU-stack,"",@progbits
