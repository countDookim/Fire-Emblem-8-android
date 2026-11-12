package gba.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The BIOS call `BgAffineSet` (SWI 0x0E; `BgAffineSet(src, dest, count)` in the decomp's
 * `include/gba/syscall.h`) for one entry, as mGBA (the reference emulator) computes it, in float.
 * It turns a source (`struct BgAffineSrcData`: texture and screen origin, scale, angle) into the
 * `pa, pb, pc, pd, dx, dy` registers of a rotating/scaling background (`struct BgAffineDstData`).
 * Matches mGBA on the title screen's BG2 zoom and turn (`TitleScreenDifferentialTest`).
 */
public object BgAffine {
    /**
     * Writes six ints into [dst] starting at [at], in the order of `struct BgAffineDstData`:
     * `pa, pb, pc, pd` (s16 values, 8.8 fixed point, the 2x2 matrix mapping screen to texture
     * offsets) then `dx, dy` (the texture point shown at screen (0, 0), 24.8 fixed point; the
     * `BG2X`/`BG2Y` reference point). Inputs are the BIOS source fields: texture origin
     * [texX]/[texY] (24.8), the screen point [scrX]/[scrY] (s16 pixels) that shows it, scales
     * [scaleX]/[scaleY] (s16, 8.8: the texture step per screen pixel, so a larger value shrinks
     * the picture) and rotation [angle] (u16, 0x10000 = one turn; only the high byte is used).
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
