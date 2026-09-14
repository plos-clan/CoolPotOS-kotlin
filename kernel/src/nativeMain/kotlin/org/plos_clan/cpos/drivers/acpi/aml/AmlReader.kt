@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get

internal class AmlPointerSource(
    private val pointer: CPointer<UByteVar>,
    override val size: Int,
) : AmlByteSource {
    override fun readByte(offset: Int): UByte = pointer[offset]
}
