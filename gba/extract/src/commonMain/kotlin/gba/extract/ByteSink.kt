package gba.extract

/**
 * A growable byte buffer (common-code replacement for `ByteArrayOutputStream` +
 * `DataOutputStream`).
 *
 * @param initialCapacity initial backing-array size in bytes (at least 16 is used).
 */
public class ByteSink(initialCapacity: Int = 256) {
    private var buf = ByteArray(maxOf(initialCapacity, 16))
    private var count = 0

    /** Number of bytes written so far. */
    public val size: Int get() = count

    private fun ensure(extra: Int) {
        val need = count + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
    }

    /** Appends the low 8 bits of [b]. */
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

    /** Writes [v] as 4 big-endian bytes. */
    public fun writeInt(v: Int) {
        write(v ushr 24)
        write(v ushr 16)
        write(v ushr 8)
        write(v)
    }

    /** A copy of the bytes written so far. */
    public fun toByteArray(): ByteArray = buf.copyOf(count)
}
