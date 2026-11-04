package gba.testkit

/**
 * A per-step trace of one watched memory range (harness `--watch`): [length] bytes appended after
 * every emulated step from [firstStep] on. Record k is the state after step `firstStep + k`.
 */
public class WatchTrace(
    /** Watch name (`watches.txt` first column). */
    public val name: String,
    /** GBA bus address of the first watched byte. */
    public val address: Int,
    /** Bytes per record. */
    public val length: Int,
    /** Step of the first record. */
    public val firstStep: Int,
    private val data: ByteArray,
) {
    /** Step of the last record. */
    public val lastStep: Int = firstStep + data.size / length - 1

    /** Copy of the record after step [n] (`n` in `firstStep..lastStep`). */
    public fun step(n: Int): ByteArray {
        val o = base(n)
        return data.copyOfRange(o, o + length)
    }

    /** Unsigned byte at [offset] of the record after step [n]. */
    public fun u8(n: Int, offset: Int): Int = data[at(n, offset)].toInt() and 0xFF

    /** Little-endian unsigned 16-bit value at [offset] of the record after step [n]. */
    public fun u16(n: Int, offset: Int): Int = u8(n, offset) or (u8(n, offset + 1) shl 8)

    /** Little-endian 32-bit value at [offset] of the record after step [n]. */
    public fun u32(n: Int, offset: Int): Int = u16(n, offset) or (u16(n, offset + 2) shl 16)

    private fun base(n: Int): Int {
        require(n in firstStep..lastStep) { "step $n outside $firstStep..$lastStep of watch $name" }
        return (n - firstStep) * length
    }

    private fun at(n: Int, offset: Int): Int {
        require(offset in 0 until length) { "offset $offset outside watch $name length $length" }
        return base(n) + offset
    }
}
