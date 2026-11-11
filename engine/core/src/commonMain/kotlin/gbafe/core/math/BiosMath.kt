package gbafe.core.math

/**
 * The GBA BIOS math calls `Sqrt` (SWI 0x08) and `ArcTan2` (SWI 0x0A) as the BIOS computes them.
 *
 * Transcribed from the BIOS algorithm (the same polynomial as mGBA's high-level emulation); not
 * verified against ground truth, so nothing here counts as ported.
 */
public object BiosMath {
    /** `Sqrt(num)`: the integer square root of the unsigned 32-bit [num], rounded down. */
    public fun sqrt(num: Int): Int {
        val n = num.toLong() and 0xFFFFFFFFL
        var result = 0L
        var bit = 1L shl 30
        var rest = n
        while (bit > rest) bit = bit shr 2
        while (bit != 0L) {
            if (rest >= result + bit) {
                rest -= result + bit
                result = (result shr 1) + bit
            } else {
                result = result shr 1
            }
            bit = bit shr 2
        }
        return result.toInt()
    }

    /**
     * The BIOS `ArcTan` polynomial: the angle for slope [i] (a signed 2.14 fixed-point ratio,
     * 0x4000 = 1.0), as an angle scaled 4x too large (0x8000 = 45 degrees), which [arcTan2]
     * shifts right by 2.
     */
    private fun arcTan(i: Int): Int {
        var a = -((i * i) shr 14)
        var b = ((0xA9 * a) shr 14) + 0x390
        b = ((b * a) shr 14) + 0x91C
        b = ((b * a) shr 14) + 0xFB6
        b = ((b * a) shr 14) + 0x16AA
        b = ((b * a) shr 14) + 0x2081
        b = ((b * a) shr 14) + 0x3651
        b = ((b * a) shr 14) + 0xA2F9
        a = (i * b) shr 16
        return a
    }

    /** `ArcTan2(x, y)`: the angle of `(x, y)` as a `u16` (0x10000 = a full turn). */
    public fun arcTan2(x: Int, y: Int): Int {
        val r: Int
        if (y == 0) {
            r = if (x >= 0) 0 else 0x8000
        } else if (x == 0) {
            r = if (y >= 0) 0x4000 else 0xC000
        } else if (y >= 0) {
            r = if (x >= 0) {
                if (x >= y) arcTan((y shl 14) / x) shr 2
                else 0x4000 - (arcTan((x shl 14) / y) shr 2)
            } else {
                if (-x >= y) (arcTan((y shl 14) / x) shr 2) + 0x8000
                else 0x4000 - (arcTan((x shl 14) / y) shr 2)
            }
        } else {
            r = if (x <= 0) {
                if (-x > -y) (arcTan((y shl 14) / x) shr 2) + 0x8000
                else 0xC000 - (arcTan((x shl 14) / y) shr 2)
            } else if (x >= -y) {
                (arcTan((y shl 14) / x) shr 2) + 0x10000
            } else {
                0xC000 - (arcTan((x shl 14) / y) shr 2)
            }
        }
        return r and 0xFFFF
    }
}
