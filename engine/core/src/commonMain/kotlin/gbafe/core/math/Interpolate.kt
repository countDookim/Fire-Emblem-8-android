package gbafe.core.math

/**
 * Easing curves for animations and camera moves: `Interpolate` and its
 * `enum interpolate_method` (src/bmlib.c).
 */
public object Interpolate {
    /** `INTERPOLATE_LINEAR`. */
    public const val LINEAR: Int = 0

    /** `INTERPOLATE_SQUARE`. */
    public const val SQUARE: Int = 1

    /** `INTERPOLATE_CUBIC`. */
    public const val CUBIC: Int = 2

    /** `INTERPOLATE_POW4`. */
    public const val POW4: Int = 3

    /** `INTERPOLATE_RSQUARE`. */
    public const val RSQUARE: Int = 4

    /** `INTERPOLATE_RCUBIC`. */
    public const val RCUBIC: Int = 5

    /**
     * Interpolates from [lo] (at [x] 0) to [hi] (at [xMax]) by [method]; [hi] if [xMax] is 0, 0
     * for an unknown method. Divisions truncate towards zero (BIOS `Div`), on 32-bit ints.
     *
     * Ported from src/bmlib.c: Interpolate
     */
    public fun interpolate(method: Int, lo: Int, hi: Int, x: Int, xMax: Int): Int {
        if (xMax == 0) return hi
        // Total change over the whole range; each curve scales it by a fraction of x / xMax.
        val span = hi - lo
        return when (method) {
            LINEAR -> lo + span * x / xMax
            SQUARE -> lo + x * x * span / (xMax * xMax)
            CUBIC -> lo + x * x * x * span / (xMax * xMax * xMax)
            POW4 -> lo + x * x * x * x * span / (xMax * xMax * xMax * xMax)
            RSQUARE -> {
                val remaining = xMax - x
                lo + span - remaining * remaining * span / (xMax * xMax)
            }
            RCUBIC -> {
                val remaining = xMax - x
                lo + span - remaining * remaining * remaining * span / (xMax * xMax * xMax)
            }
            else -> 0
        }
    }
}
