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
        var bitsRead = 0
        var bit = bitOffset
        // Consume the field one source byte at a time, LSB first, so fields that straddle
        // bytes work.
        while (bitsRead < width) {
            val byte = buf[base + (bit shr 3)].toInt() and 0xFF
            val shift = bit and 7
            val take = minOf(8 - shift, width - bitsRead)
            value = value or (((byte shr shift) and ((1 shl take) - 1)).toLong() shl bitsRead)
            bitsRead += take
            bit += take
        }
        return value.toInt()
    }

    /**
     * Like [getBits] but sign-extends from the field's top bit (a signed bitfield such as `s8 x :
     * 4`).
     */
    public fun getBitsSigned(buf: ByteArray, base: Int, bitOffset: Int, width: Int): Int {
        val unsigned = getBits(buf, base, bitOffset, width)
        val unusedHighBits = 32 - width
        // Shift the field's top bit up to bit 31, then arithmetic-shift back to replicate it.
        return (unsigned shl unusedHighBits) shr unusedHighBits
    }

    /**
     * Stores the low [width] bits (1..32) of [value] into the bitfield at [bitOffset] after byte
     * [base], keeping all other bits.
     */
    public fun putBits(buf: ByteArray, base: Int, bitOffset: Int, width: Int, value: Int) {
        require(width in 1..32 && bitOffset >= 0) { "bad bitfield" }
        var bitsLeft = width
        var remaining = value.toLong() and 0xFFFFFFFFL
        var bit = bitOffset
        // Write the field one destination byte at a time, touching only the bits it covers.
        while (bitsLeft > 0) {
            val byteIndex = base + (bit shr 3)
            val shift = bit and 7
            val take = minOf(8 - shift, bitsLeft)
            val mask = ((1 shl take) - 1) shl shift
            val old = buf[byteIndex].toInt()
            buf[byteIndex] =
                ((old and mask.inv()) or (((remaining.toInt() shl shift)) and mask)).toByte()
            remaining = remaining shr take
            bitsLeft -= take
            bit += take
        }
    }
}
