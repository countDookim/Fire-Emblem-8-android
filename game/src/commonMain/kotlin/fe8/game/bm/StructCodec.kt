package fe8.game.bm

/** Little-endian field access and bitfield helpers shared by [PlaySt] and [BmSt]. */
internal object StructCodec {
    /** Reads the unsigned byte at [o]. */
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    /** Reads the signed byte at [o]. */
    fun s8(b: ByteArray, o: Int): Int = b[o].toInt()

    /** Reads the unsigned little-endian halfword at [o]. */
    fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

    /** Reads the signed little-endian halfword at [o]. */
    fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()

    /** Reads the little-endian word at [o] (the unsigned bits as an [Int]). */
    fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)

    /** Writes the low byte of [v] at [o]. */
    fun put8(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
    }

    /** Writes the low halfword of [v] at [o], little-endian. */
    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
    }

    /** Writes [v] at [o], little-endian. */
    fun put32(b: ByteArray, o: Int, v: Int) {
        put16(b, o, v)
        put16(b, o + 2, v shr 16)
    }

    /** The [width]-bit field of [word] starting at bit [shift] (LSB first, as GCC packs them). */
    fun bits(word: Int, shift: Int, width: Int): Int = (word ushr shift) and ((1 shl width) - 1)

    /** [v] truncated to [width] bits and moved to bit [shift]. */
    fun field(v: Int, shift: Int, width: Int): Int = (v and ((1 shl width) - 1)) shl shift
}
