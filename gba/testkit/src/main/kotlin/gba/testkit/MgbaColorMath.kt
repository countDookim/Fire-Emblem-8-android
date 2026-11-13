package gba.testkit

import gba.render.ColorMath

/**
 * Colour-effect arithmetic modelled on the pinned reference emulator, mGBA 0.10.5 (the version
 * named in ARCHITECTURE.md and docs/gba-harness-reuse.md). The model, from this file's author: each
 * 5-bit channel is widened to 8 bits as `c << 3 | c >> 2` (mGBA's `mColorFrom555`), blended or
 * faded on 8-bit values (`mColorMix5Bit`, `_brighten`, `_darken`; files
 * `include/mgba/core/interface.h` and `src/gba/renderers/software-private.h`), and the frame keeps
 * 8 bits. All of that, and "32-bit colour build", is about mGBA internals and is not checked here:
 * mGBA's source is not in this repo. What the repo does state: mGBA's frame dumps have only their
 * top 5 bits valid, and frame compares are made at 5-bit precision (FrameCompareTest), where this
 * can differ by one step from the hardware's 5-bit math ([ColorMath.Hardware]), so those compares
 * use this model instead. Results are BGR555 holding the top 5 bits of the 8-bit channels.
 */
public object MgbaColorMath : ColorMath {
    /** Mask for a 5-bit colour channel. */
    private const val CHANNEL_5BIT_MASK = 0x1F

    /** Bit shift for the red channel in a 16-bit BGR555 value. */
    private const val RED_SHIFT = 0

    /** Bit shift for the green channel in a 16-bit BGR555 value. */
    private const val GREEN_SHIFT = 5

    /** Bit shift for the blue channel in a 16-bit BGR555 value. */
    private const val BLUE_SHIFT = 10

    /** Largest 8-bit channel value (white). */
    private const val MAX_CHANNEL_8BIT = 255

    /** Denominator of the blend coefficients (EVA, EVB, EVY are 0..16, 16 = 1.0). */
    private const val BLEND_WEIGHT_SCALE = 16

    /** `BLDCNT` alpha blend: per widened 8-bit channel `min(255, (a*eva + b*evb) / 16)`. */
    override fun alpha(a: Int, b: Int, eva: Int, evb: Int): Int =
        map2(a, b) { channelA, channelB ->
            minOf(MAX_CHANNEL_8BIT, (channelA * eva + channelB * evb) / BLEND_WEIGHT_SCALE)
        }

    /** `BLDCNT` brighten: per widened 8-bit channel `c + (255 - c) * evy / 16`. */
    override fun brighten(c: Int, evy: Int): Int =
        map2(c, 0) { channel, _ ->
            channel + (MAX_CHANNEL_8BIT - channel) * evy / BLEND_WEIGHT_SCALE
        }

    /**
     * `BLDCNT` darken, per widened 8-bit channel: red `c - c * evy / 16`, green and blue
     * `c * (16 - evy) / 16`, which equals `c - ceil(c * evy / 16)`, so green and blue round the
     * other way from red. The stated reason is that mGBA's `_darken` subtracts on the packed
     * colour (not checked here); what is recorded is that this fit every darkened pixel of the
     * opening-animation frames f1110..2230 (the f1110..1140 part is also cited in OpAnim.kt; the
     * rest is not re-checked here).
     */
    override fun darken(c: Int, evy: Int): Int {
        val red8 = widen((c shr RED_SHIFT) and CHANNEL_5BIT_MASK)
        val green8 = widen((c shr GREEN_SHIFT) and CHANNEL_5BIT_MASK)
        val blue8 = widen((c shr BLUE_SHIFT) and CHANNEL_5BIT_MASK)
        val darkenedRed = red8 - red8 * evy / BLEND_WEIGHT_SCALE
        val darkenedGreen = green8 * (BLEND_WEIGHT_SCALE - evy) / BLEND_WEIGHT_SCALE
        val darkenedBlue = blue8 * (BLEND_WEIGHT_SCALE - evy) / BLEND_WEIGHT_SCALE
        return ((darkenedRed shr 3) shl RED_SHIFT) or
            ((darkenedGreen shr 3) shl GREEN_SHIFT) or
            ((darkenedBlue shr 3) shl BLUE_SHIFT)
    }

    /**
     * Applies [transform] to each widened 8-bit red, green and blue channel pair of the BGR555
     * colours [colorA] and [colorB] and packs the top 5 bits of each result back into BGR555.
     */
    private inline fun map2(colorA: Int, colorB: Int, transform: (Int, Int) -> Int): Int {
        var packed = 0
        val shifts = intArrayOf(RED_SHIFT, GREEN_SHIFT, BLUE_SHIFT)
        for (shift in shifts) {
            val chanA = widen((colorA shr shift) and CHANNEL_5BIT_MASK)
            val chanB = widen((colorB shr shift) and CHANNEL_5BIT_MASK)
            val result8 = transform(chanA, chanB)
            packed = packed or ((result8 shr 3) shl shift)
        }
        return packed
    }

    /**
     * Widens a 5-bit channel (0..31) to 8 bits (0..255) by repeating its top 3 bits in the low
     * bits: `c << 3 | c >> 2` (as mGBA's `mColorFrom555`; not checked here).
     */
    private fun widen(channel5: Int): Int = (channel5 shl 3) or (channel5 shr 2)
}
