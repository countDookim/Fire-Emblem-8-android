package fe8.game.savemenu

import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl

/**
 * Save and main menu graphical rendering, ported from `src/savedraw.c`.
 *
 * Implements the animated background fog effect: an undulating sine wave scroll applied to the
 * background layer BG2 behind the menu windows, combining scanline-by-scanline horizontal
 * displacement from `gSinLookup` with diagonal drift across frames.
 */
public object SaveDraw {
    /**
     * Subpixel position coordinates of the drifting background fog.
     *
     * Ported from `struct SaveDrawProc::bg_x` and `bg_y` (u16 each in decomp).
     */
    public class Fog {
        /**
         * Horizontal background position in 1/8 subpixels (`bg_x`), incremented by 1 per frame
         * and wrapping within 16-bit range.
         */
        public var bgX: Int = 0

        /**
         * Vertical background position in 1/8 subpixels (`bg_y`), incremented by 2 per frame
         * and wrapping within 16-bit range.
         */
        public var bgY: Int = 0
    }

    /**
     * Advances the drifting rune fog background by one frame on BG2.
     *
     * Updates subpixel position counters in [fog], calculates per-scanline horizontal sine wave
     * offsets `(sin(angle) / 0x300 + x) & 0x1FF` (where `angle` starts at `bgY` and steps by 12
     * per scanline), populates the next-frame scanline table in [scroll], positions BG2 in [lcd],
     * and flips the vertical scroll buffers for VBlank display.
     *
     * Ported from src/savedraw.c: SaveDraw_ScrollFogBG
     *
     * @param fog the persistent subpixel position state of the fog background.
     * @param scroll the vertical scanline offset buffer manager for background rendering.
     * @param lcd LCD controller managing hardware background position registers.
     * @param sin the game's 256-entry sine lookup table (`gSinLookup`, s16 values in 4.12 fixed point).
     */
    public fun scrollFogBg(fog: Fog, scroll: BgVerticalScroll, lcd: LcdControl, sin: ShortArray) {
        fog.bgX = (fog.bgX + 1) and UINT16_MASK
        fog.bgY = (fog.bgY + 2) and UINT16_MASK
        val pixelX = ((fog.bgX and SUBPIXEL_WRAP_MASK) shr SUBPIXEL_SHIFT).toShort().toInt()
        val bgY = (fog.bgY / SUBPIXELS_PER_PIXEL) and BYTE_MASK
        val bufferBase = scroll.bufferOffset(SCROLL_REGISTER_0, NEXT_FRAME_BUFFER)
        var scanlineAngle = bgY
        for (line in 0 until SCREEN_HEIGHT_LINES) {
            val waveOffset = sin[scanlineAngle and BYTE_MASK] / SINE_AMPLITUDE_DIVISOR
            scroll.buf[bufferBase + line] = ((waveOffset + pixelX) and SCROLL_MASK_9BIT).toShort()
            scanlineAngle += ANGLE_STEP_PER_LINE
        }
        lcd.setBgPosition(BG_LAYER_2, pixelX, bgY)
        scroll.flip()
    }

    /** GBA screen height in scanlines (`DISPLAY_HEIGHT` in decomp). */
    private const val SCREEN_HEIGHT_LINES = 160

    /** Background layer index for the drifting menu fog (`BG_2`). */
    private const val BG_LAYER_2 = 2

    /** Register index 0 for BG vertical/horizontal scroll buffers. */
    private const val SCROLL_REGISTER_0 = 0

    /** Buffer index 1 used for staging the next frame's scanline table prior to flip. */
    private const val NEXT_FRAME_BUFFER = 1

    /** Number of subpixels per whole screen pixel (1/8th pixel resolution). */
    private const val SUBPIXELS_PER_PIXEL = 8

    /** Bit shift converting 1/8th subpixels to whole screen pixels (`>> 3`). */
    private const val SUBPIXEL_SHIFT = 3

    /** Mask constraining subpixel accumulator before division (`& 0xFFF`). */
    private const val SUBPIXEL_WRAP_MASK = 0xFFF

    /** Sine table angle increment per scanline. */
    private const val ANGLE_STEP_PER_LINE = 12

    /** Divisor scaling 4.12 fixed-point sine values to horizontal pixel displacement (`/ 0x300`). */
    private const val SINE_AMPLITUDE_DIVISOR = 0x300

    /** GBA 9-bit horizontal scroll coordinate wrap mask (`& 0x1FF`, 0..511). */
    private const val SCROLL_MASK_9BIT = 0x1FF

    /** 8-bit wrap mask for sine lookup table indices and tile Y coordinates (`& 0xFF`). */
    private const val BYTE_MASK = 0xFF

    /** 16-bit wrap mask for subpixel position counters (`& 0xFFFF`). */
    private const val UINT16_MASK = 0xFFFF
}
