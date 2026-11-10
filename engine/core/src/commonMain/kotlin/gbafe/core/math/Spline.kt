package gbafe.core.math

/**
 * The easing helpers of `src/spline.c` and `src/bmlib.c` that camera and map scrolls use. Results
 * are 12-bit fixed point fractions (0x1000 = 1.0); divisions truncate towards zero (BIOS `Div`)
 * on 32-bit ints.
 */
public object Spline {
    /**
     * `DivArm(divisor, dividend)`: the BIOS division with swapped arguments, `dividend / divisor`.
     */
    public fun divArm(divisor: Int, dividend: Int): Int =
        if (divisor == 0) 0 else dividend / divisor

    /**
     * Ported from src/spline.c: sub_800A42C
     *
     * Eases [value] (0..0x1000) through an ease-in of [start] and an ease-out of [end] (both 12-bit
     * fractions of the whole range) with a linear middle part.
     */
    public fun easeInOut(value: Int, start: Int, end: Int): Int {
        var s = start
        var e = end
        var v = value
        var num1 = s + e
        if (num1 == 0) return v
        if (num1 > 0x1000) {
            s = divArm(num1, s * 0x1000)
            e = divArm(num1, e * 0x1000)
        }
        num1 = divArm(0x2000 - s - e, 0x1000000)
        if (v < s) {
            if (s == 0) s = 1
            return divArm(0x1000, divArm(0x1000, divArm(s, num1 * 0x1000) * v) * v)
        }
        if (v < 0x1000 - e) {
            return divArm(0x1000, num1 * (divArm(0x1000, v * 0x2000) - s))
        }
        v = 0x1000 - v
        if (e == 0) e = 1
        return 0x1000 - divArm(0x1000, divArm(0x1000, divArm(e, num1 * 0x1000) * v) * v)
    }

    /**
     * Ported from src/bmlib.c: sub_8014CA4
     *
     * [easeInOut] of `timer / speed` (as a 0x1000 fraction) with ease-in [start] and ease-out
     * [end].
     */
    public fun sub8014CA4(timer: Int, speed: Int, start: Int, end: Int): Int =
        easeInOut(divArm(speed, timer * 0x1000), start, end)

    /**
     * Transcribed from src/spline.c: _DivArm1 (not verified: no reference capture reaches a caller
     * that passes a non-zero [power] to it)
     *
     * `timer / duration` as a 0x1000 fraction, ease-out raised to [power] (0 = linear).
     */
    public fun divArm1(timer: Int, duration: Int, power: Int): Int {
        if (power == 0) return divArm(duration, timer shl 12)
        val r4 = timer * 0x1000
        var r1 = if (0x1000 - divArm(duration, r4) < 0) {
            divArm(duration, r4) - 0x1000
        } else {
            0x1000 - divArm(duration, r4)
        }
        val r5 = r1
        for (i in 0 until power) r1 = divArm(0x1000, r1 * r5)
        return 0x1000 - r1
    }

    /**
     * Transcribed from src/spline.c: _DivArm2 (not verified: no reference capture reaches a caller)
     *
     * `timer / duration` as a 0x1000 fraction with an ease-in over the first half and an ease-out
     * over the
     * second, each raised to [power] (0 = linear).
     */
    public fun divArm2(timer: Int, duration: Int, power: Int): Int {
        if (power == 0) return divArm(duration, timer shl 12)
        val half = duration / 2
        var r1 = if (timer < half) {
            val v = divArm(half, timer * 0x1000)
            if (v < 0) -v else v
        } else {
            val v = divArm(half, (timer - half) * 0x1000)
            if (0x1000 - v < 0) v - 0x1000 else 0x1000 - v
        }
        val r5 = r1
        for (i in 0 until power) r1 = divArm(0x1000, r1 * r5)
        return if (timer < half) r1 / 2 else (0x1000 - r1) / 2 + 0x800
    }
}
