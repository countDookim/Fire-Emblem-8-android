package fe8.game.bm

/**
 * Little-endian field access and bitfield helpers shared by [PlaySt], [BmSt], and save file codecs.
 *
 * Provides decoding and encoding primitives for unsigned/signed bytes, halfwords (16-bit),
 * words (32-bit), and GCC-packed bitfields.
 */
internal object StructCodec {
    /** Reads the unsigned byte at [offset] in [buffer]. */
    fun u8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt() and 0xFF

    /** Reads the signed byte at [offset] in [buffer]. */
    fun s8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt()

    /** Reads the unsigned little-endian halfword (16-bit) at [offset] in [buffer]. */
    fun u16(buffer: ByteArray, offset: Int): Int = u8(buffer, offset) or (u8(buffer, offset + 1) shl 8)

    /** Reads the signed little-endian halfword (16-bit) at [offset] in [buffer]. */
    fun s16(buffer: ByteArray, offset: Int): Int = u16(buffer, offset).toShort().toInt()

    /** Reads the little-endian word (32-bit unsigned bits represented as [Int]) at [offset] in [buffer]. */
    fun u32(buffer: ByteArray, offset: Int): Int = u16(buffer, offset) or (u16(buffer, offset + 2) shl 16)

    /** Writes the low byte of [value] at [offset] in [buffer]. */
    fun put8(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
    }

    /** Writes the low 16-bit halfword of [value] at [offset] in [buffer], little-endian. */
    fun put16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value shr 8).toByte()
    }

    /** Writes the 32-bit [value] at [offset] in [buffer], little-endian. */
    fun put32(buffer: ByteArray, offset: Int, value: Int) {
        put16(buffer, offset, value)
        put16(buffer, offset + 2, value shr 16)
    }

    /**
     * Extracts a [width]-bit field from [word] starting at bit [shift] (LSB first, matching GCC bitfield packing).
     */
    fun bits(word: Int, shift: Int, width: Int): Int = (word ushr shift) and ((1 shl width) - 1)

    /**
     * Truncates [value] to [width] bits and shifts it to bit position [shift].
     */
    fun field(value: Int, shift: Int, width: Int): Int = (value and ((1 shl width) - 1)) shl shift
}
