package gba.core.sram

/**
 * Little-endian field access for save structures laid out by GCC for the GBA (ARM, little-endian).
 *
 * Bitfields are addressed as one LSB-first bit stream starting at a byte offset: bit `k` is bit `k
 * % 8` of byte `k / 8`. That is how GCC packs `u32 a : 5; u32 b : 12; ...` including fields that
 * straddle byte or 32-bit unit boundaries in packed structs. None of the functions allocate.
 */
public object SramBits {
    /** Unsigned 16-bit little-endian value at [offset] of [buf]. */
    public fun getU16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    /** Stores the low 16 bits of [value] at [offset] little-endian. */
    public fun putU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
        buf[offset + 1] = (value shr 8).toByte()
    }

    /**
     * 32-bit little-endian value at [offset] of [buf] (a u32 above 0x7FFFFFFF reads as negative).
     */
    public fun getU32(buf: ByteArray, offset: Int): Int =
        getU16(buf, offset) or (getU16(buf, offset + 2) shl 16)

    /** Stores [value] at [offset] little-endian. */
    public fun putU32(buf: ByteArray, offset: Int, value: Int) {
        putU16(buf, offset, value)
        putU16(buf, offset + 2, value shr 16)
    }

    /**
     * Reads the unsigned bitfield of [width] bits (1..32) that starts [bitOffset] bits after byte
     * [base] (bit 0 is the least significant bit of `buf[base]`). A 32-bit field with the top bit
     * set reads negative.
     */
    public fun getBits(buf: ByteArray, base: Int, bitOffset: Int, width: Int): Int {
        require(width in 1..32 && bitOffset >= 0) { "bad bitfield" }
        var value = 0L
        var got = 0
        var bit = bitOffset
        while (got < width) {
            val byte = buf[base + (bit shr 3)].toInt() and 0xFF
            val shift = bit and 7
            val take = minOf(8 - shift, width - got)
            value = value or (((byte shr shift) and ((1 shl take) - 1)).toLong() shl got)
            got += take
            bit += take
        }
        return value.toInt()
    }

    /**
     * Like [getBits] but sign-extends from the field's top bit (a signed bitfield such as `s8 x :
     * 4`).
     */
    public fun getBitsSigned(buf: ByteArray, base: Int, bitOffset: Int, width: Int): Int {
        val v = getBits(buf, base, bitOffset, width)
        val s = 32 - width
        return (v shl s) shr s
    }

    /**
     * Stores the low [width] bits (1..32) of [value] into the bitfield at [bitOffset] after byte
     * [base], keeping all other bits.
     */
    public fun putBits(buf: ByteArray, base: Int, bitOffset: Int, width: Int, value: Int) {
        require(width in 1..32 && bitOffset >= 0) { "bad bitfield" }
        var left = width
        var v = value.toLong() and 0xFFFFFFFFL
        var bit = bitOffset
        while (left > 0) {
            val i = base + (bit shr 3)
            val shift = bit and 7
            val take = minOf(8 - shift, left)
            val mask = ((1 shl take) - 1) shl shift
            val old = buf[i].toInt()
            buf[i] = ((old and mask.inv()) or (((v.toInt() shl shift)) and mask)).toByte()
            v = v shr take
            left -= take
            bit += take
        }
    }
}
