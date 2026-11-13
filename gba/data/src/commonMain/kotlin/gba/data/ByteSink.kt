package gba.data

/**
 * A growable byte buffer and streaming byte sink (multiplatform common replacement for
 * `ByteArrayOutputStream` and `DataOutputStream`).
 *
 * @param initialCapacity initial backing-array size in bytes (at least [MIN_CAPACITY] is used).
 */
public class ByteSink(initialCapacity: Int = DEFAULT_INITIAL_CAPACITY) {
    /** Backing array; only the first [count] bytes are valid. Grown by [ensure]. */
    private var buf = ByteArray(maxOf(initialCapacity, MIN_CAPACITY))

    /** Number of valid bytes at the start of [buf]. */
    private var count = 0

    /** Number of bytes written so far. */
    public val size: Int get() = count

    /** Grows [buf] so [extra] more bytes fit: to double its size, or to exactly fit if larger. */
    private fun ensure(extra: Int) {
        val need = count + extra
        if (need > buf.size) {
            buf = buf.copyOf(maxOf(need, buf.size * 2))
        }
    }

    /** Appends the low 8 bits of integer [b] as a byte. */
    public fun write(b: Int) {
        ensure(1)
        buf[count++] = b.toByte()
    }

    /** Appends [length] bytes of [bytes] starting at [offset]. */
    public fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        ensure(length)
        bytes.copyInto(buf, count, offset, offset + length)
        count += length
    }

    /** Writes 32-bit integer [v] as 4 big-endian bytes (most significant byte first). */
    public fun writeInt(v: Int) {
        write(v ushr 24)
        write(v ushr 16)
        write(v ushr 8)
        write(v)
    }

    /** Returns a newly-allocated copy of the bytes written to the sink so far. */
    public fun toByteArray(): ByteArray = buf.copyOf(count)

    /** Constants for [ByteSink] buffer sizing. */
    public companion object {
        /** Default initial capacity for newly allocated sinks. */
        public const val DEFAULT_INITIAL_CAPACITY: Int = 256

        /** Smallest backing array allocated, even if a smaller initial capacity is requested. */
        public const val MIN_CAPACITY: Int = 16
    }
}
