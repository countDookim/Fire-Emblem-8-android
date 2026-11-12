package gba.audio

/**
 * The GBA's PSG (CGB) sound registers as `CgbSound` sees them: one byte per IO address from
 * `0x04000060` (`NR10`) to `0x0400009F` (last byte of wave RAM), indexed by
 * `address - 0x04000060`.
 * Only a register file (what the driver writes and reads back); the synth that turns it into
 * samples is separate. Not a port.
 */
public class M4aPsg {
    /**
     * Register bytes, offset = IO address - `0x04000060`. Starts as `MPlayExtender` / `SoundInit`
     * leave them: NR50 = 0x77 (full left and right master volume), SOUNDCNT_H PSG ratio 100%
     * (`SOUND_ALL_MIX_FULL`).
     */
    public val regs: IntArray = IntArray(0x40).also {
        it[NR50 - BASE] = 0x77
        it[SOUNDCNT_H - BASE] = 0x02
    }

    /**
     * High byte of `SOUNDBIAS` (`0x04000089`): sampling-cycle bits 6..7 (`SoundInit` sets 0x40;
     * `m4aSoundMode`'s `SOUND_MODE_DA_BIT_*` rewrites them), read by `CgbSound` to adjust the
     * frequency of voices with `TONEDATA_TYPE_FIX`. 0x42 = those 0x40 plus the hardware's
     * reset bias bit.
     */
    public var soundBiasH: Int = 0x42

    /** Reads the byte at IO [address]. */
    public fun read(address: Int): Int = regs[address - BASE]

    /** Bit per channel 1..4 (bit n-1): triggered (NRx4 bit 7 written) since [takeTriggers]. */
    public var triggers: Int = 0
        private set

    /** Bit per channel 1..4: length counter loaded (NRx1 written) since [takeLengthLoads]. */
    public var lengthLoads: Int = 0
        private set

    /** Bit per channel 1, 2, 4 (bit n-1): `NRx2` (envelope) written since [takeEnvelopeWrites]. */
    public var envelopeWrites: Int = 0
        private set

    /**
     * Writes the byte [value] at IO [address]; an NRx4 write with bit 7 set triggers a channel,
     * an NRx1 write loads its length counter.
     */
    public fun write(address: Int, value: Int) {
        regs[address - BASE] = value and 0xFF
        when (address) {
            0x04000062 -> lengthLoads = lengthLoads or 1
            0x04000068 -> lengthLoads = lengthLoads or 2
            0x04000072 -> lengthLoads = lengthLoads or 4
            0x04000078 -> lengthLoads = lengthLoads or 8
            0x04000063 -> envelopeWrites = envelopeWrites or 1
            0x04000069 -> envelopeWrites = envelopeWrites or 2
            0x04000079 -> envelopeWrites = envelopeWrites or 8
        }
        if (value and 0x80 != 0) {
            when (address) {
                0x04000065 -> triggers = triggers or 1
                0x0400006D -> triggers = triggers or 2
                0x04000075 -> triggers = triggers or 4
                0x0400007D -> triggers = triggers or 8
            }
        }
    }

    /** Returns and clears [lengthLoads] (read by the synth). */
    public fun takeLengthLoads(): Int = lengthLoads.also { lengthLoads = 0 }

    /** Returns and clears [envelopeWrites] (read by the synth). */
    public fun takeEnvelopeWrites(): Int = envelopeWrites.also { envelopeWrites = 0 }

    /** Returns and clears [triggers] (read by the synth). */
    public fun takeTriggers(): Int = triggers.also { triggers = 0 }

    /** Register addresses. */
    public companion object {
        /** `REG_NR10` (square 1 sweep), the first PSG register. */
        public const val BASE: Int = 0x04000060

        /** `REG_NR50`: PSG master volume (left bits 4..6, right bits 0..2). */
        public const val NR50: Int = 0x04000080

        /** Low byte of `REG_SOUNDCNT_H`: PSG mix ratio in bits 0..1 (25/50/100%). */
        public const val SOUNDCNT_H: Int = 0x04000082

        /** `REG_NR51`: PSG pan (left/right enable per channel). */
        public const val NR51: Int = 0x04000081

        /** `REG_WAVE_RAM0`: the first of the 16 bytes of channel 3's wave pattern. */
        public const val WAVE_RAM: Int = 0x04000090
    }
}
