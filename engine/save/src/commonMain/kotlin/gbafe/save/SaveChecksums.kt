package gbafe.save

/**
 * The two checksums FE8 stores in save RAM. Both read little-endian halfwords, mix a running sum
 * with a running xor, and allocate nothing.
 */
public object SaveChecksums {
    /**
     * Checksum of the [size] bytes of [data] starting at [offset], as stored in the global save
     * info, the bonus-claim data and similar small blocks: `(sum + xor)` of the `size / 2`
     * little-endian halfwords, truncated to 16 bits. A trailing odd byte is ignored.
     *
     * Ported from src/bmsave-lib.c: Checksum16
     *
     * @return the checksum in `0..0xFFFF`.
     */
    public fun checksum16(data: ByteArray, offset: Int, size: Int): Int {
        var add = 0
        var xor = 0
        var p = offset
        var i = 0
        while (i < size / 2) {
            val v = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
            add += v
            xor = xor xor v
            p += 2
            i++
        }
        return (add + xor) and 0xFFFF
    }

    /**
     * Checksum of a save block: the low 16 bits of the halfword sum in the low half and the xor of
     * the halfwords in the high half, `(sum & 0xFFFF) + (xor << 16)`. It is what the ARM routine
     * does (the C comment above it says `u32` words, but the loop loads `ldrh`). The routine is a
     * do-while: it always reads at least one halfword, so any [size] below 4 reads exactly one,
     * otherwise it reads `size / 2` halfwords.
     *
     * Ported from asm/arm.s: Checksum32
     *
     * @return the 32-bit checksum pattern (the high bit may be set, so the value can be negative).
     */
    public fun checksum32(data: ByteArray, offset: Int, size: Int): Int {
        var add = 0
        var xor = 0
        var p = offset
        var left = size - 2
        do {
            val v = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
            add += v
            xor = xor xor v
            p += 2
            left -= 2
        } while (left >= 0)
        return (add and 0xFFFF) + (xor shl 16)
    }

    /**
     * Thumb veneer that `bx pc`-switches to [checksum32]; the game calls it as
     * `ComputeChecksum32(buf, size)` from `SramChecksum32`.
     *
     * Ported from asm/arm_call.s: ComputeChecksum32
     */
    public fun computeChecksum32(data: ByteArray, offset: Int, size: Int): Int =
        checksum32(data, offset, size)
}
