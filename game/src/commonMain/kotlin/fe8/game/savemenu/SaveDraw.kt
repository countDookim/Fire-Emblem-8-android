package fe8.game.savemenu

import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl

/**
 * The drifting fog background of the save / main menu, ported from the
 * `SaveDraw_ScrollFogBG` part of `src/savedraw.c` (the rest of that file, the menu sprites, is
 * not ported here).
 *
 * The fog is background layer BG2 (tile graphics `BGCHR_SAVEMENU_BGFOG` in the decomp). Each
 * frame it drifts diagonally, and each scanline gets its own horizontal offset from `gSinLookup`
 * so the fog ripples.
 */
public object SaveDraw {
    /**
     * Drift position of the fog, in 1/8 pixels on both axes.
     *
     * Ported from `struct SaveDrawProc::bg_x` and `bg_y` (u16 each in decomp).
     */
    public class Fog {
        /**
         * Horizontal position in 1/8 pixels (`bg_x`): +1 per frame, kept to 16 bits. Only the low
         * 12 bits are used for display (0..511 pixels).
         */
        public var bgX: Int = 0

        /**
         * Vertical position in 1/8 pixels (`bg_y`): +2 per frame, kept to 16 bits. Only
         * `(bgY / 8) & 0xFF` is used for display, which also seeds the sine angle.
         */
        public var bgY: Int = 0
    }

    /**
     * Moves the fog one frame: advances [fog], fills the scroll buffer's other half with one
     * horizontal offset per scanline, sets BG2's scroll in [lcd], and flips the buffer so the
     * new table is used from the next frame's HBlank handler.
     *
     * Per scanline the offset is `(sin(angle) / 0x300 + x) & 0x1FF`; `angle` starts at the
     * vertical pixel position and grows by 12 per scanline, so 160 lines cover 1908 angle
     * steps, wrapping the 256-entry table about 7.5 times (the ripple density).
     *
     * Ported from src/savedraw.c: SaveDraw_ScrollFogBG
     *
     * @param fog the persistent subpixel position state of the fog background.
     * @param scroll the per-scanline scroll buffer; register 0 is BG2's horizontal offset.
     * @param lcd receives the whole-frame BG2 position (`BG_SetPosition(BG_2, ...)`).
     * @param sin the first 256 entries of `gSinLookup` (s16, 4.12 fixed point; `SIN(a)` indexes
     *   it with `a & 0xFF`).
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

    /** GBA screen height in scanlines (`DISPLAY_HEIGHT`, include/gba/defines.h). */
    private const val SCREEN_HEIGHT_LINES = 160

    /** Background layer index for the drifting menu fog (`BG_2`). */
    private const val BG_LAYER_2 = 2

    /** Scroll register 0 of the buffer: BG2's horizontal offset (`REG_ADDR_BG2HOFS`). */
    private const val SCROLL_REGISTER_0 = 0

    /** `flip` argument true: the buffer half not currently shown, filled before the flip. */
    private const val NEXT_FRAME_BUFFER = 1

    /** Fog position units per screen pixel. */
    private const val SUBPIXELS_PER_PIXEL = 8

    /** Shift converting 1/8 pixels to pixels (`>> 3`); same as dividing by 8. */
    private const val SUBPIXEL_SHIFT = 3

    /** Keeps `bgX` in 0..4095, i.e. 0..511 pixels, the 9-bit scroll range (`& 0xfff`). */
    private const val SUBPIXEL_WRAP_MASK = 0xFFF

    /** Sine angle increment per scanline (`angle += 12`). */
    private const val ANGLE_STEP_PER_LINE = 12

    /**
     * Divisor turning the 4.12 sine into a pixel offset (`/ 0x300`): with a peak of 0x1000 the
     * offset ranges about -5..5 pixels (4096 / 768 = 5 1/3, truncated toward zero).
     */
    private const val SINE_AMPLITUDE_DIVISOR = 0x300

    /** Wrap of a horizontal scroll value to the hardware's 9 bits (`& 0x1ff`, 0..511). */
    private const val SCROLL_MASK_9BIT = 0x1FF

    /** Wrap to 0..255, used for the vertical pixel position and the sine index (`& 0xff`). */
    private const val BYTE_MASK = 0xFF

    /** Wrap of `bgX` / `bgY` to the decomp's u16 fields. */
    private const val UINT16_MASK = 0xFFFF
}
