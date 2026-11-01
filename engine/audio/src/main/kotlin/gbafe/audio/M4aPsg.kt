package gbafe.audio

/**
 * The GBA's PSG (CGB) sound registers as `CgbSound` sees them: one byte per IO address from
 * `0x04000060` (`NR10`) to `0x040000A0` (end of wave RAM), indexed by `address - 0x04000060`.
 * Only a register file (what the driver writes and reads back); the synth that turns it into
 * samples is separate (task M3-13). Not a port.
 */
public class M4aPsg {
    /** Register bytes, offset = IO address - `0x04000060`. */
    public val regs: IntArray = IntArray(0x40)

    /** High byte of `SOUNDBIAS` (`0x04000089`): sampling-cycle bits 6..7 (FE8: 0x40 set by init). */
    public var soundBiasH: Int = 0x42

    /** Reads the byte at IO [address]. */
    public fun read(address: Int): Int = regs[address - BASE]

    /** Writes the byte [value] at IO [address]. */
    public fun write(address: Int, value: Int) {
        regs[address - BASE] = value and 0xFF
    }

    /** Register addresses. */
    public companion object {
        /** `REG_NR10`, first PSG register. */
        public const val BASE: Int = 0x04000060

        /** `REG_NR51`: PSG pan (left/right enable per channel). */
        public const val NR51: Int = 0x04000081

        /** `REG_WAVE_RAM0`. */
        public const val WAVE_RAM: Int = 0x04000090
    }
}
