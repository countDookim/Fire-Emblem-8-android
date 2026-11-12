package gba.render

/**
 * Colour-effect arithmetic used by the compositor (BGR555 in, BGR555 out): the three `BLDCNT`
 * effects alpha blend, brightness increase and brightness decrease. Coefficients are sixteenths,
 * 0..16, from
 * `BLDALPHA` (EVA, EVB) and `BLDY` (EVY); 16 means 1.0. [Hardware] is the GBA's 5-bit-per-channel
 * math and the only one the product uses; tests swap in an emulator's model to compare against its
 * frames.
 */
public interface ColorMath {
    /**
     * `BLDCNT` effect 1: `BLDALPHA` blend of first target [a] with second target [b], weighted by
     * the coefficients [eva] and [evb] (0..16 each, 16 = 1.0).
     */
    public fun alpha(a: Int, b: Int, eva: Int, evb: Int): Int

    /** `BLDCNT` effect 2: moves [c] towards white by [evy]/16 (`REG_BLDY`, 0..16). */
    public fun brighten(c: Int, evy: Int): Int

    /** `BLDCNT` effect 3: moves [c] towards black by [evy]/16 (`REG_BLDY`, 0..16). */
    public fun darken(c: Int, evy: Int): Int

    /** GBA hardware: every channel computed on its 5-bit value. */
    public object Hardware : ColorMath {
        /** Per 5-bit channel `min(31, (a * eva + b * evb) / 16)`, rounded down. */
        override fun alpha(a: Int, b: Int, eva: Int, evb: Int): Int =
            alphaCh(a, b, 0, eva, evb) or
                alphaCh(a, b, 5, eva, evb) or
                alphaCh(a, b, 10, eva, evb)

        /** Per 5-bit channel `c + (31 - c) * evy / 16`, rounded down. */
        override fun brighten(c: Int, evy: Int): Int =
            brightCh(c, 0, evy) or brightCh(c, 5, evy) or brightCh(c, 10, evy)

        /** Per 5-bit channel `c - c * evy / 16`, rounded down. */
        override fun darken(c: Int, evy: Int): Int =
            darkCh(c, 0, evy) or darkCh(c, 5, evy) or darkCh(c, 10, evy)

        /** One channel (at bit [shift]) of [alpha]: `shr 4` is the divide by 16. */
        private fun alphaCh(colorA: Int, colorB: Int, shift: Int, coeffA: Int, coeffB: Int): Int =
            minOf(31, (((colorA shr shift) and 0x1F) * coeffA + ((colorB shr shift) and 0x1F) * coeffB) shr 4) shl shift

        /** One channel (at bit [shift]) of [brighten]. */
        private fun brightCh(color: Int, shift: Int, coeffY: Int): Int {
            val channel = (color shr shift) and 0x1F
            return (channel + (((31 - channel) * coeffY) shr 4)) shl shift
        }

        /** One channel (at bit [shift]) of [darken]. */
        private fun darkCh(color: Int, shift: Int, coeffY: Int): Int {
            val channel = (color shr shift) and 0x1F
            return (channel - ((channel * coeffY) shr 4)) shl shift
        }
    }
}
