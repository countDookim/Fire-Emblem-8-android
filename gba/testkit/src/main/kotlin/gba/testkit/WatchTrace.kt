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

    /** Copy of the record after step [step] (`step` in `firstStep..lastStep`). */
    public fun step(step: Int): ByteArray {
        val offset = base(step)
        return data.copyOfRange(offset, offset + length)
    }

    /** Unsigned byte at [offset] of the record after step [step]. */
    public fun u8(step: Int, offset: Int): Int = data[at(step, offset)].toInt() and 0xFF

    /** Little-endian unsigned 16-bit value at [offset] of the record after step [step]. */
    public fun u16(step: Int, offset: Int): Int = u8(step, offset) or (u8(step, offset + 1) shl 8)

    /** Little-endian 32-bit value at [offset] of the record after step [step]. */
    public fun u32(step: Int, offset: Int): Int = u16(step, offset) or (u16(step, offset + 2) shl 16)

    /** Returns the byte offset in [data] where the memory record for step [step] begins. */
    private fun base(step: Int): Int {
        require(step in firstStep..lastStep) { "step $step outside $firstStep..$lastStep of watch $name" }
        return (step - firstStep) * length
    }

    /** Computes the absolute byte offset in [data] for byte [offset] within the record of step [step]. */
    private fun at(step: Int, offset: Int): Int {
        require(offset in 0 until length) { "offset $offset outside watch $name length $length" }
        return base(step) + offset
    }
}
