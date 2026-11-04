package gba.extract

/** A growable byte buffer (common-code replacement for `ByteArrayOutputStream` + `DataOutputStream`). */
internal class ByteSink(initialCapacity: Int = 256) {
    private var buf = ByteArray(maxOf(initialCapacity, 16))
    private var size = 0

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
    }

    fun write(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        ensure(length)
        bytes.copyInto(buf, size, offset, offset + length)
        size += length
    }

    /** Writes [v] as 4 big-endian bytes. */
    fun writeInt(v: Int) {
        write(v ushr 24)
        write(v ushr 16)
        write(v ushr 8)
        write(v)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
