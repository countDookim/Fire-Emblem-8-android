package gbafe.save

/**
 * The two checksum algorithms the original save code (`src/bmsave.c`, `src/bmsave-lib.c`) stores
 * in SRAM:
 * 1. [checksum16]: a 16-bit `(sum + xor)` checksum. FE8 uses it for the global save info, the
 *    rank, sound room and bonus-claim records and one more small record (`bmsave_unkstruct2`).
 * 2. [checksum32]: a 32-bit checksum (halfword sum in the low half, halfword xor in the high
 *    half), stored in each `SaveBlockInfo` for its save block (`VerifySaveBlockChecksum`).
 *
 * Both routines read little-endian halfwords sequentially and allocate nothing.
 */
public object SaveChecksums {
    /** 16-bit truncation mask. */
    private const val MASK_16BIT = 0xFFFF

    /** Number of bytes per GBA 16-bit halfword. */
    private const val BYTES_PER_HALFWORD = 2

    /**
     * Checksum of the [size] bytes of [data] starting at [offset], as stored in the global save
     * info (over its first `GLOBALSIZEINFO_SIZE_FOR_CHECKSUM` = 0x50 bytes), the bonus-claim data
     * and similar small blocks: `(sum + xor)` of the `size / 2` little-endian halfwords,
     * truncated to 16 bits (the C function returns `u16`). A trailing odd byte is ignored.
     *
     * Ported from src/bmsave-lib.c: Checksum16
     *
     * @return the checksum in `0..0xFFFF`.
     */
    public fun checksum16(data: ByteArray, offset: Int, size: Int): Int {
        var sum = 0
        var xorSum = 0
        var cursor = offset
        var halfwordIndex = 0
        val halfwordCount = size / BYTES_PER_HALFWORD
        while (halfwordIndex < halfwordCount) {
            val halfword = (data[cursor].toInt() and 0xFF) or ((data[cursor + 1].toInt() and 0xFF) shl 8)
            sum += halfword
            xorSum = xorSum xor halfword
            cursor += BYTES_PER_HALFWORD
            halfwordIndex++
        }
        return (sum + xorSum) and MASK_16BIT
    }

    /**
     * Checksum of a save block (the value kept in `SaveBlockInfo.checksum32`): the low 16 bits of
     * the halfword sum in the low half and the xor of the halfwords in the high half,
     * `(sum & 0xFFFF) + (xor << 16)`. It is what the ARM routine does (the C comment above it says
     * `u32` words, but the loop loads `ldrh`). The routine is a do-while: it always reads at
     * least one halfword, so any [size] below 4 reads exactly one, otherwise it reads
     * `size / 2` halfwords.
     *
     * Ported from asm/arm.s: Checksum32
     *
     * @return the 32-bit checksum pattern (the high bit may be set, so the value can be negative).
     */
    public fun checksum32(data: ByteArray, offset: Int, size: Int): Int {
        var sum = 0
        var xorSum = 0
        var cursor = offset
        var bytesRemaining = size - BYTES_PER_HALFWORD
        do {
            val halfword = (data[cursor].toInt() and 0xFF) or ((data[cursor + 1].toInt() and 0xFF) shl 8)
            sum += halfword
            xorSum = xorSum xor halfword
            cursor += BYTES_PER_HALFWORD
            bytesRemaining -= BYTES_PER_HALFWORD
        } while (bytesRemaining >= 0)
        return (sum and MASK_16BIT) + (xorSum shl 16)
    }

    /**
     * Thumb veneer that `bx pc`-switches to the ARM [checksum32]; the game calls it as
     * `ComputeChecksum32(buf, size)` from `SramChecksum32`, after copying the SRAM bytes into
     * `gGenericBuffer`.
     *
     * Ported from asm/arm_call.s: ComputeChecksum32
     */
    public fun computeChecksum32(data: ByteArray, offset: Int, size: Int): Int =
        checksum32(data, offset, size)
}
