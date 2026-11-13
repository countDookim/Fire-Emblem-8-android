package fe8.game.bm

/**
 * Little-endian field access and bitfield helpers shared by [PlaySt], [BmSt] and the save file
 * codecs. The GBA is little-endian and the decomp structs are `packed`, so a field is read at
 * its `/* 0x.. */` byte offset, and bitfields (`u32 x : 3`) fill a word from the lowest bit up.
 * None of the functions allocate.
 */
internal object StructCodec {
    /** Reads the unsigned byte at [offset] in [buffer]. */
    fun u8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt() and 0xFF

    /** Reads the signed byte at [offset] in [buffer]. */
    fun s8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt()

    /** Reads the unsigned little-endian halfword (16-bit) at [offset] in [buffer]. */
    fun u16(buffer: ByteArray, offset: Int): Int =
        u8(buffer, offset) or (u8(buffer, offset + 1) shl 8)

    /** Reads the signed little-endian halfword (16-bit) at [offset] in [buffer]. */
    fun s16(buffer: ByteArray, offset: Int): Int = u16(buffer, offset).toShort().toInt()

    /** Reads the little-endian word at [offset] in [buffer]; the 32 bits are held in the [Int]. */
    fun u32(buffer: ByteArray, offset: Int): Int =
        u16(buffer, offset) or (u16(buffer, offset + 2) shl 16)

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
     * Extracts the [width]-bit bitfield that starts at bit [shift] of [word]; the first
     * declared bitfield of a C struct word is at shift 0 (GCC packs from the least significant
     * bit).
     */
    fun bits(word: Int, shift: Int, width: Int): Int = (word ushr shift) and ((1 shl width) - 1)

    /**
     * The inverse of [bits]: truncates [value] to [width] bits and shifts it to [shift], ready
     * to be or-ed into the word.
     */
    fun field(value: Int, shift: Int, width: Int): Int = (value and ((1 shl width) - 1)) shl shift
}
