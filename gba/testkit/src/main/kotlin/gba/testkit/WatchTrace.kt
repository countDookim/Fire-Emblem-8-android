package gba.testkit

/**
 * A per-step trace of one watched memory range recorded by the mGBA harness (the harness is in the
 * ROM repo; its `--watch` option is not checked here): [length] bytes appended after every
 * emulated step from [firstStep] on. Record k is the state after step `firstStep + k`. Loaded by
 * `ReferenceData.Run.watch` from `watches.txt` and `<name>.watch.bin`.
 */
public class WatchTrace(
    /** Watch name (first column of `watches.txt`). */
    public val name: String,
    /** GBA bus address of the first watched byte (hex second column of `watches.txt`). */
    public val address: Int,
    /** Bytes per record (third column of `watches.txt`). */
    public val length: Int,
    /** Emulated step of the first record (fourth column of `watches.txt`). */
    public val firstStep: Int,
    /** The `.watch.bin` bytes: the records back to back, each [length] bytes. */
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

    /** Little-endian 32-bit word at [offset] of the record after step [step]. */
    public fun u32(step: Int, offset: Int): Int = u16(step, offset) or (u16(step, offset + 2) shl 16)

    /** Offset in [data] where the record after step [step] begins; checks the step range. */
    private fun base(step: Int): Int {
        require(step in firstStep..lastStep) { "step $step outside $firstStep..$lastStep of watch $name" }
        return (step - firstStep) * length
    }

    /** Offset in [data] of byte [offset] of the record after step [step]; checks both ranges. */
    private fun at(step: Int, offset: Int): Int {
        require(offset in 0 until length) { "offset $offset outside watch $name length $length" }
        return base(step) + offset
    }
}
