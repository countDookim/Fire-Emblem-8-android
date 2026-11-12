package gbafe.render

import gba.render.BgAffine

/** The BG2/BG3 affine helpers of src/sysutil.c, operating on [LcdControl.affine]. */
public object BgAffin {
    /** Index of [bg]'s `BgAffineDstData` in [LcdControl.affine]: BG2 first (0), BG3 second (6). */
    private fun at(bg: Int): Int = if (bg == 2) 0 else 6

    /**
     * Sets [bg]'s affine registers so that texture point ([xCenter], [yCenter]) (whole pixels) is
     * shown at screen (0, 0), rotated by [angle] (s16, 0x1000 = one turn) and magnified by
     * [sx]/[sy] (8.8, 0x100 = 1x, clamped to at least 4). Passes `0x10000 / scale` to the BIOS
     * `BgAffineSet`. BG2 uses the BG2 slot, any other BG the BG3 slot.
     *
     * Ported from src/sysutil.c: BgAffinRotScaling
     */
    public fun rotScaling(
        lcd: LcdControl, bg: Int, angle: Int, xCenter: Int, yCenter: Int, sx: Int, sy: Int,
    ) {
        val scaleX = maxOf(sx.toShort().toInt(), 4)
        val scaleY = maxOf(sy.toShort().toInt(), 4)
        BgAffine.set(
            lcd.affine, at(bg),
            xCenter.toShort() * 0x100, yCenter.toShort() * 0x100, 0, 0,
            (0x10000 / scaleX).toShort().toInt(), (0x10000 / scaleY).toShort().toInt(),
            (angle.toShort() * 0x10) and 0xFFFF,
        )
    }

    /**
     * Scales BG2's `pb`/`pd` by [sy] and `pa`/`pc` by [sx] (8.8 fixed point). The original only
     * handles BG2 (any other [bg] makes it dereference NULL); other values are ignored here.
     *
     * Ported from src/sysutil.c: BgAffinScaling
     */
    public fun scaling(lcd: LcdControl, bg: Int, sy: Int, sx: Int) {
        if (bg != 2) return
        val a = lcd.affine
        a[1] = ((a[1] * sy.toShort()) shr 8).toShort().toInt()
        a[3] = ((a[3] * sy.toShort()) shr 8).toShort().toInt()
        a[0] = ((a[0] * sx.toShort()) shr 8).toShort().toInt()
        a[2] = ((a[2] * sx.toShort()) shr 8).toShort().toInt()
    }

    /**
     * Sets BG2's `dx`/`dy` (the `BG2X`/`BG2Y` reference point, 24.8) so screen point (q0X, q0Y)
     * shows texture point (p0X, p0Y) (whole pixels). The original only handles BG2 (any other [bg]
     * makes it dereference NULL); other values are ignored here.
     *
     * Ported from src/sysutil.c: BgAffinAnchoring
     */
    public fun anchoring(lcd: LcdControl, bg: Int, q0X: Int, q0Y: Int, p0X: Int, p0Y: Int) {
        if (bg != 2) return
        val a = lcd.affine
        val qx = q0X.toShort().toInt()
        val qy = q0Y.toShort().toInt()
        a[4] = a[0] * -qx + a[1] * -qy + p0X.toShort() * 0x100
        a[5] = a[2] * -qx + a[3] * -qy + p0Y.toShort() * 0x100
    }
}
