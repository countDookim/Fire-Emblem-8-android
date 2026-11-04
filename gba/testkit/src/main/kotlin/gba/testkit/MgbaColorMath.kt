package gba.testkit

import gba.render.ColorMath


/**
 * Colour-effect arithmetic of the pinned reference emulator (mGBA 0.10.5, 32-bit colour build): each 5-bit
 * channel is first widened to 8 bits as `c << 3 | c >> 2` (`mColorFrom555`), then blended or faded on 8-bit
 * values (`mColorMix5Bit`, `_brighten`, `_darken` in `include/mgba/core/interface.h` and
 * `src/gba/renderers/software-private.h`), and the frame keeps 8 bits. Read back at 5 bits this can be one
 * step above the hardware's 5-bit math ([ColorMath.Hardware]), so frame compares against mGBA dumps use this
 * model. Results are BGR555 holding the top 5 bits of mGBA's 8-bit channels (what the compare looks at).
 */
public object MgbaColorMath : ColorMath {
    /** Per 8-bit channel min(255, (a*eva + b*evb) / 16). */
    override fun alpha(a: Int, b: Int, eva: Int, evb: Int): Int = map2(a, b) { x, y -> minOf(255, (x * eva + y * evb) / 16) }

    /** Per 8-bit channel c + (255 - c) * evy / 16. */
    override fun brighten(c: Int, evy: Int): Int = map2(c, 0) { x, _ -> x + (255 - x) * evy / 16 }

    /**
     * Per 8-bit channel: red `c - c * evy / 16`, green and blue `c * (16 - evy) / 16` (= c - ceil(c * evy / 16)).
     * mGBA's `_darken` subtracts on the packed colour, so the truncated product of the higher channels rounds the
     * other way (fits every darkened pixel of op anim frames f1110..2230).
     */
    override fun darken(c: Int, evy: Int): Int {
        val r = widen(c and 0x1F)
        val g = widen((c shr 5) and 0x1F)
        val b = widen((c shr 10) and 0x1F)
        val r2 = r - r * evy / 16
        val g2 = g * (16 - evy) / 16
        val b2 = b * (16 - evy) / 16
        return (r2 shr 3) or ((g2 shr 3) shl 5) or ((b2 shr 3) shl 10)
    }

    private inline fun map2(a: Int, b: Int, f: (Int, Int) -> Int): Int {
        var out = 0
        for (s in intArrayOf(0, 5, 10)) out = out or ((f(widen((a shr s) and 0x1F), widen((b shr s) and 0x1F)) shr 3) shl s)
        return out
    }

    private fun widen(c: Int): Int = (c shl 3) or (c shr 2)
}
