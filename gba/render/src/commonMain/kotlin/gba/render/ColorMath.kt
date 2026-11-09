package gba.render

/**
 * Colour-effect arithmetic used by the compositor (BGR555 in, BGR555 out). [Hardware] is the GBA's 5-bit
 * math and the only one the product uses; tests swap in an emulator's model to compare against its frames.
 */
public interface ColorMath {
    /** BLDALPHA blend of first target [a] over second target [b] with coefficients [eva], [evb] (0..16). */
    public fun alpha(a: Int, b: Int, eva: Int, evb: Int): Int

    /** BLDY brightness increase of [c] by [evy] (0..16). */
    public fun brighten(c: Int, evy: Int): Int

    /** BLDY brightness decrease of [c] by [evy] (0..16). */
    public fun darken(c: Int, evy: Int): Int

    /** GBA hardware: every channel computed on its 5-bit value. */
    public object Hardware : ColorMath {
        /** Per channel min(31, (a*eva + b*evb) / 16). */
        override fun alpha(a: Int, b: Int, eva: Int, evb: Int): Int =
            alphaCh(a, b, 0, eva, evb) or
                alphaCh(a, b, 5, eva, evb) or
                alphaCh(a, b, 10, eva, evb)

        /** Per channel c + (31 - c) * evy / 16. */
        override fun brighten(c: Int, evy: Int): Int =
            brightCh(c, 0, evy) or brightCh(c, 5, evy) or brightCh(c, 10, evy)

        /** Per channel c - c * evy / 16. */
        override fun darken(c: Int, evy: Int): Int =
            darkCh(c, 0, evy) or darkCh(c, 5, evy) or darkCh(c, 10, evy)

        private fun alphaCh(colorA: Int, colorB: Int, shift: Int, coeffA: Int, coeffB: Int): Int =
            minOf(31, (((colorA shr shift) and 0x1F) * coeffA + ((colorB shr shift) and 0x1F) * coeffB) shr 4) shl shift

        private fun brightCh(color: Int, shift: Int, coeffY: Int): Int {
            val channel = (color shr shift) and 0x1F
            return (channel + (((31 - channel) * coeffY) shr 4)) shl shift
        }

        private fun darkCh(color: Int, shift: Int, coeffY: Int): Int {
            val channel = (color shr shift) and 0x1F
            return (channel - ((channel * coeffY) shr 4)) shl shift
        }
    }
}
