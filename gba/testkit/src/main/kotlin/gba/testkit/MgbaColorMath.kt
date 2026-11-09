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
    /** Mask for a 5-bit colour channel. */
    private const val CHANNEL_5BIT_MASK = 0x1F

    /** Bit shift for the red channel in a 16-bit BGR555 value. */
    private const val RED_SHIFT = 0

    /** Bit shift for the green channel in a 16-bit BGR555 value. */
    private const val GREEN_SHIFT = 5

    /** Bit shift for the blue channel in a 16-bit BGR555 value. */
    private const val BLUE_SHIFT = 10

    /** Maximum value of an 8-bit color channel. */
    private const val MAX_CHANNEL_8BIT = 255

    /** Scale factor (denominator) for GBA alpha blending weights (EVA, EVB, EVY in 0..16). */
    private const val BLEND_WEIGHT_SCALE = 16

    /** Per 8-bit channel min(255, (a*eva + b*evb) / 16). */
    override fun alpha(a: Int, b: Int, eva: Int, evb: Int): Int =
        map2(a, b) { channelA, channelB ->
            minOf(MAX_CHANNEL_8BIT, (channelA * eva + channelB * evb) / BLEND_WEIGHT_SCALE)
        }

    /** Per 8-bit channel c + (255 - c) * evy / 16. */
    override fun brighten(c: Int, evy: Int): Int =
        map2(c, 0) { channel, _ ->
            channel + (MAX_CHANNEL_8BIT - channel) * evy / BLEND_WEIGHT_SCALE
        }

    /**
     * Per 8-bit channel: red `c - c * evy / 16`, green and blue `c * (16 - evy) / 16` (= c - ceil(c * evy / 16)).
     * mGBA's `_darken` subtracts on the packed colour, so the truncated product of the higher channels rounds the
     * other way (fits every darkened pixel of op anim frames f1110..2230).
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
     * Applies channel-wise transformation [transform] across red, green, and blue 5-bit channels of
     * [colorA] and [colorB], widening each channel to 8 bits first, and packing the resulting top 5 bits.
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
     * Widens a 5-bit GBA channel (0..31) to an 8-bit channel (0..255) by replicating the top two bits
     * into the bottom two bits: `c << 3 | c >> 2` (matching mGBA's `mColorFrom555`).
     */
    private fun widen(channel5: Int): Int = (channel5 shl 3) or (channel5 shr 2)
}
