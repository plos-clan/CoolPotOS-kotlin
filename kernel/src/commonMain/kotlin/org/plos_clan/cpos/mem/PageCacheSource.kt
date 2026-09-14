package org.plos_clan.cpos.mem

import kotlin.jvm.JvmInline

interface PageCacheProvider {
    val cacheSource: PageCacheSource?
}

interface PageCacheSource : PageCacheProvider {
    override val cacheSource: PageCacheSource
        get() = this

    val identity: Any
        get() = this

    val readAheadSize: Int
        get() = 0

    fun read(offset: ULong, destination: ByteArray): Int

    companion object {
        internal const val READ_ERROR = Int.MIN_VALUE
        internal const val READ_INTERRUPTED = Int.MIN_VALUE + 1
    }
}

internal enum class PageCacheFailure {
    OUT_OF_MEMORY,
    IO_ERROR,
    INTERRUPTED,
}

internal class PageCacheAcquireResult private constructor(
    private val value: ULong,
    val validBytes: Int,
) {
    val isSuccess: Boolean
        get() = value < INTERRUPTED

    val frame: ULong
        get() {
            check(isSuccess)
            return value
        }

    val failure: PageCacheFailure
        get() = when (value) {
            OUT_OF_MEMORY -> PageCacheFailure.OUT_OF_MEMORY
            INTERRUPTED -> PageCacheFailure.INTERRUPTED
            else -> PageCacheFailure.IO_ERROR
        }

    companion object {
        private const val IO_ERROR = 0xffff_ffff_ffff_ffffuL
        private const val OUT_OF_MEMORY = 0xffff_ffff_ffff_fffeuL
        private const val INTERRUPTED = 0xffff_ffff_ffff_fffduL

        fun acquired(frame: ULong, validBytes: Int) = PageCacheAcquireResult(frame, validBytes)
        fun failed(failure: PageCacheFailure) = PageCacheAcquireResult(
            when (failure) {
                PageCacheFailure.OUT_OF_MEMORY -> OUT_OF_MEMORY
                PageCacheFailure.IO_ERROR -> IO_ERROR
                PageCacheFailure.INTERRUPTED -> INTERRUPTED
            },
            0,
        )
    }
}

@JvmInline
internal value class PageCacheReadResult private constructor(private val value: Int) {
    val isSuccess: Boolean
        get() = value >= 0

    val bytes: Int
        get() = value.coerceAtLeast(0)

    val failure: PageCacheFailure
        get() = when (value) {
            OUT_OF_MEMORY -> PageCacheFailure.OUT_OF_MEMORY
            INTERRUPTED -> PageCacheFailure.INTERRUPTED
            else -> PageCacheFailure.IO_ERROR
        }

    companion object {
        private const val OUT_OF_MEMORY = -1
        private const val IO_ERROR = -2
        private const val INTERRUPTED = -3

        fun completed(bytes: Int) = PageCacheReadResult(bytes)
        fun failed(failure: PageCacheFailure) = PageCacheReadResult(
            when (failure) {
                PageCacheFailure.OUT_OF_MEMORY -> OUT_OF_MEMORY
                PageCacheFailure.IO_ERROR -> IO_ERROR
                PageCacheFailure.INTERRUPTED -> INTERRUPTED
            },
        )
    }
}

internal data class PageCacheStatistics(
    val cachedBytes: ULong,
    val reclaimableBytes: ULong,
)
