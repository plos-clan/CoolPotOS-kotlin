package org.plos_clan.cpos.fs.sock

internal class SocketTimestampQueue(bufferedBytes: Int = 0) {
    private data class Span(
        val start: ULong,
        var end: ULong,
        val receivedAtNanos: ULong,
    )

    private val spans = ArrayDeque<Span>()
    private var readSequence = 0uL
    private var writeSequence: ULong

    init {
        require(bufferedBytes >= 0)
        writeSequence = bufferedBytes.toULong()
    }

    fun append(byteCount: Int, receivedAtNanos: ULong?) {
        require(byteCount >= 0)
        if (byteCount == 0) return
        val start = writeSequence
        val end = start + byteCount.toULong()
        writeSequence = end
        if (receivedAtNanos == null) return
        val last = spans.lastOrNull()
        if (last?.end == start && last.receivedAtNanos == receivedAtNanos) {
            last.end = end
        } else {
            spans += Span(start, end, receivedAtNanos)
        }
    }

    fun read(byteCount: Int, consume: Boolean): ULong? {
        require(byteCount >= 0 && byteCount.toULong() <= writeSequence - readSequence)
        if (byteCount == 0) return null
        val end = readSequence + byteCount.toULong()
        if (!consume) {
            for (span in spans) {
                if (end <= span.start) break
                if (end <= span.end) return span.receivedAtNanos
            }
            return null
        }

        var timestamp: ULong? = null
        while (spans.firstOrNull()?.end?.let { it <= end } == true) {
            val span = spans.removeFirst()
            if (span.end == end) timestamp = span.receivedAtNanos
        }
        spans.firstOrNull()?.takeIf { it.start < end }?.let {
            timestamp = it.receivedAtNanos
        }
        readSequence = end
        if (readSequence == writeSequence) {
            spans.clear()
            readSequence = 0uL
            writeSequence = 0uL
        }
        return timestamp
    }
}
