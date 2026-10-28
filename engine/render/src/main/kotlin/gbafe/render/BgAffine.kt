package gbafe.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The BIOS call `BgAffineSet` (SWI 0x0E) as mGBA (the reference emulator) computes it, in float.
 * Matches mGBA on the title screen's BG2 zoom and turn (`TitleScreenDifferentialTest`).
 */
public object BgAffine {
    /**
     * Writes `pa, pb, pc, pd, dx, dy` for one BG into [dst] at [at] (the [LcdControl.affine]
     * layout) from texture centre [texX]/[texY] (24.8), screen centre [scrX]/[scrY], scales
     * [sx]/[sy] (8.8) and angle [alpha] (u16, only the high byte is used).
     */
    public fun set(
        dst: IntArray, at: Int, texX: Int, texY: Int, scrX: Int, scrY: Int, sx: Int, sy: Int, alpha: Int,
    ) {
        val ox = texX / 256f
        val oy = texY / 256f
        val fsx = sx.toShort() / 256f
        val fsy = sy.toShort() / 256f
        val theta = ((alpha and 0xFFFF) shr 8) / 128f * PI.toFloat()
        val cs = cos(theta)
        val sn = sin(theta)
        val a = cs * fsx
        val b = -sn * fsx
        val c = sn * fsy
        val d = cs * fsy
        val rx = ox - (a * scrX.toShort() + b * scrY.toShort())
        val ry = oy - (c * scrX.toShort() + d * scrY.toShort())
        dst[at] = (a * 256).toInt().toShort().toInt()
        dst[at + 1] = (b * 256).toInt().toShort().toInt()
        dst[at + 2] = (c * 256).toInt().toShort().toInt()
        dst[at + 3] = (d * 256).toInt().toShort().toInt()
        dst[at + 4] = (rx * 256).toInt()
        dst[at + 5] = (ry * 256).toInt()
    }
}
