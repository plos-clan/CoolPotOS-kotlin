@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar

abstract class NativeBuffer : BufferSource {
    final override fun copyTo(sourceOffset: Int, destination: BufferDestination, destinationOffset: Int, count: Int): Int =
        destination.copyFrom(destinationOffset, this, sourceOffset, count)

    internal abstract fun copyToNative(sourceOffset: Int, destination: CPointer<UByteVar>, count: Int): Int
}
