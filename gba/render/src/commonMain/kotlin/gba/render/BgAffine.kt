package gba.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The BIOS call `BgAffineSet` (SWI 0x0E) as mGBA (the reference emulator) computes it, in float.
 * Matches mGBA on the title screen's BG2 zoom and turn (`TitleScreenDifferentialTest`).
 */
public object BgAffine {
    /**
     * Writes `pa, pb, pc, pd, dx, dy` for one BG into [dst] at [at] (the `LcdControl.affine`
     * layout) from texture centre [texX]/[texY] (24.8), screen centre [scrX]/[scrY], scales
     * [scaleX]/[scaleY] (8.8) and angle [angle] (u16, only the high byte is used).
     */
    public fun set(
        dst: IntArray,
        at: Int,
        texX: Int,
        texY: Int,
        scrX: Int,
        scrY: Int,
        scaleX: Int,
        scaleY: Int,
        angle: Int,
    ) {
        val origX = texX / 256f
        val origY = texY / 256f
        val fScaleX = scaleX.toShort() / 256f
        val fScaleY = scaleY.toShort() / 256f
        val theta = ((angle and 0xFFFF) shr 8) / 128f * PI.toFloat()
        val cosVal = cos(theta)
        val sinVal = sin(theta)
        val pa = cosVal * fScaleX
        val pb = -sinVal * fScaleX
        val pc = sinVal * fScaleY
        val pd = cosVal * fScaleY
        val refX = origX - (pa * scrX.toShort() + pb * scrY.toShort())
        val refY = origY - (pc * scrX.toShort() + pd * scrY.toShort())
        dst[at] = (pa * 256).toInt().toShort().toInt()
        dst[at + 1] = (pb * 256).toInt().toShort().toInt()
        dst[at + 2] = (pc * 256).toInt().toShort().toInt()
        dst[at + 3] = (pd * 256).toInt().toShort().toInt()
        dst[at + 4] = (refX * 256).toInt()
        dst[at + 5] = (refY * 256).toInt()
    }
}
