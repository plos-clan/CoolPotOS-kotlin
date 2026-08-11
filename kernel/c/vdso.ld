OUTPUT_FORMAT(elf64-x86-64)
OUTPUT_ARCH(i386:x86-64)

PHDRS
{
    image PT_LOAD FILEHDR PHDRS FLAGS(5);
    dynamic PT_DYNAMIC FLAGS(4);
}

SECTIONS
{
    . = SIZEOF_HEADERS;
    .hash : { *(.hash) } :image
    .dynsym : { *(.dynsym) } :image
    .dynstr : { *(.dynstr) } :image
    .text : { *(.text .text.*) } :image
    .rodata : { *(.rodata .rodata.*) } :image
    .dynamic : { *(.dynamic) } :image :dynamic

    /* Must remain last: the kernel initializes this from the image end. */
    .vdso_clock_data : { KEEP(*(.vdso_clock_data)) } :image

    .symtab 0 : { *(.symtab) }
    .strtab 0 : { *(.strtab) }
    .shstrtab 0 : { *(.shstrtab) }

    /DISCARD/ : {
        *(.comment)
        *(.eh_frame .eh_frame_hdr)
        *(.note .note.*)
        *(.gnu.hash)
    }
}
