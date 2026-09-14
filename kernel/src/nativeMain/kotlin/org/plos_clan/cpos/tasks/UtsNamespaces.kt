package org.plos_clan.cpos.tasks

import KERNEL_NAME

internal object UtsNamespaces {
    val initial = UtsNamespace(
        sysName = "CoolPotOS",
        nodeName = "localhost",
        release = KERNEL_NAME,
        version = "v0.0.1",
        machine = "x86_64",
        domainName = "",
    )
}
