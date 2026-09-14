package org.plos_clan.cpos.module.elf

import org.plos_clan.cpos.fs.vfs.OpenFileDescription

internal data class ElfFile(
    val file: OpenFileDescription,
    val image: ElfImage,
)
