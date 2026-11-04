package fe8.game.savemenu

import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl

/**
 * Save / main menu drawing, decomp `src/savedraw.c`. So far: the drifting rune "fog" background on BG2
 * (a sine wave of per-line horizontal offsets plus a slow diagonal scroll).
 */
public object SaveDraw {
    /** The `bg_x` / `bg_y` fields of `struct SaveDrawProc` (u16 each) that drive the fog scroll. */
    public class Fog {
        /** `bg_x`: horizontal position in 1/8 pixel (+1 per frame). */
        public var bgX: Int = 0

        /** `bg_y`: vertical position in 1/8 pixel (+2 per frame). */
        public var bgY: Int = 0
    }

    /**
     * One frame of the fog: advances [fog], writes BG2's next-frame line offsets
     * `(sin(angle) / 0x300 + x) & 0x1FF` (angle = y, +12 per line) into [scroll] register 0, sets BG2's
     * position in [lcd] and flips [scroll]'s buffers. [sin] is the game's 256-entry `gSinLookup` (s16, 4.12).
     *
     * Ported from src/savedraw.c: SaveDraw_ScrollFogBG
     */
    public fun scrollFogBg(fog: Fog, scroll: BgVerticalScroll, lcd: LcdControl, sin: ShortArray) {
        fog.bgX = (fog.bgX + 1) and 0xFFFF
        fog.bgY = (fog.bgY + 2) and 0xFFFF
        val x = ((fog.bgX and 0xFFF) shr 3).toShort().toInt()
        val bgY = (fog.bgY / 8) and 0xFF
        val base = scroll.bufferOffset(0, 1)
        var angle = bgY
        for (i in 0 until LINES) {
            val v = sin[angle and 0xFF] / 0x300
            scroll.buf[base + i] = ((v + x) and 0x1FF).toShort()
            angle += 12
        }
        lcd.setBgPosition(2, x, bgY)
        scroll.flip()
    }

    private const val LINES = 160
}
