package com.mundymorning.couchcast.relay

/** HTTP `Range: bytes=...` handling for files the phone serves. Only a single range is honoured; anything odd is served whole. */
sealed class ByteRange {
    object Full : ByteRange()
    object Unsatisfiable : ByteRange()
    data class Partial(val start: Long, val end: Long) : ByteRange() { val size: Long get() = end - start + 1 }

    companion object {
        fun parse(header: String?, length: Long): ByteRange {
            if (length < 0 || header == null) return Full
            val spec = header.trim().takeIf { it.startsWith("bytes=", true) }?.substring(6)?.trim() ?: return Full
            if (',' in spec) return Full
            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (a.isEmpty()) {                       // suffix: the last N bytes
                val n = b.toLongOrNull() ?: return Full
                if (n < 0) return Full
                if (n == 0L || length == 0L) return Unsatisfiable
                return Partial(maxOf(0, length - n), length - 1)
            }
            val start = a.toLongOrNull() ?: return Full
            if (start < 0) return Full
            val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return Full)
            if (end < start) return Full
            if (start >= length) return Unsatisfiable
            return Partial(start, minOf(end, length - 1))
        }
    }
}
