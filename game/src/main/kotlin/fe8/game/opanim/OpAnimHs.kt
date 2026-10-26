package fe8.game.opanim

import gbafe.input.KeyStatus
import gbafe.render.LcdControl

/**
 * Boot intro screens (Nintendo / Intelligent Systems logos, health & safety), decomp
 * `src/opanim-healthsafetyscreen.c`. So far: the palette fade helper the logo and warning screens use.
 */
public object OpAnimHs {
    /** Fields of `struct ProcOpAnimHS` used by the steps. */
    public class Proc {
        /** `status` (+0x2C): 0 health & safety, 1 Nintendo, 2 Intelligent Systems. */
        public var status: Int = 0

        /** `palette_timer` (+0x30): frames into the current fade / pulse. */
        public var paletteTimer: Int = 0

        /** `delay_timer` (+0x34): frames left in `GameEarlyStartDelay`. */
        public var delayTimer: Int = 0
    }

    /**
     * One frame of the health & safety fade-in: advances the timer and fades [hsPalette] (`Pal_OpAnimHealthSafetyScreen`,
     * 16 colours) into BG bank 0 of [paletteBuffer] over 30 frames; after the last one sets a 90-frame delay and
     * returns true (`Proc_Break`). The caller marks the palette for upload.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroHealthSafetyFadeIN
     */
    public fun healthSafetyFadeIn(proc: Proc, hsPalette: ShortArray, paletteBuffer: ShortArray): Boolean {
        proc.paletteTimer++
        brightenPalette(hsPalette, paletteBuffer, 0, 1, proc.paletteTimer, FADE_FRAMES)
        if (proc.paletteTimer > FADE_FRAMES - 1) {
            proc.delayTimer = 0x5A
            return true
        }
        return false
    }

    /**
     * One frame of the health & safety fade-out (the fade-in reversed); after the last frame sets a 40-frame
     * delay and returns true (`Proc_Break`).
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroHealthSafetyFadeOUT
     */
    public fun healthSafetyFadeOut(proc: Proc, hsPalette: ShortArray, paletteBuffer: ShortArray): Boolean {
        proc.paletteTimer++
        brightenPalette(hsPalette, paletteBuffer, 0, 1, FADE_FRAMES - proc.paletteTimer, FADE_FRAMES)
        if (proc.paletteTimer > FADE_FRAMES - 1) {
            proc.delayTimer = 0x28
            return true
        }
        return false
    }

    /** Length of the logo / warning fades in frames (0x1E). */
    private const val FADE_FRAMES = 0x1E

    /**
     * One frame of the health & safety screen waiting for a button: on its first frame shows BG0 + BG1, makes
     * BG0 the brighten target and stops ignoring keys; then pulses BG0's brightness (EVY = |16 - (t mod 64) / 2|).
     * After 0xE0F frames or any key press, clears the effect, hides BG0 and returns true (`Proc_Break`).
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroHealthSafetyWaitButton
     */
    public fun healthSafetyWaitButton(proc: Proc, lcd: LcdControl, keys: KeyStatus): Boolean {
        if (proc.paletteTimer == 0) {
            lcd.setDispEnable(1, 1, 0, 0, 0)
            lcd.setBlendTargetA(1, 0, 0, 0, 0)
            keys.ignoreMask = 0
        }
        proc.paletteTimer++
        val t = proc.paletteTimer
        val v = (0x10 - ((t - (t / 64) * 64) shr 1)).toShort().toInt()
        lcd.setBlendConfig(2, 0, 0, if (v >= 0) v else -v)
        if (t > 0xE0F || keys.newKeys and 0x3FF != 0) {
            proc.paletteTimer = 0
            lcd.setDispEnable(0, 1, 0, 0, 0)
            lcd.setBlendTargetA(0, 0, 0, 0, 0)
            lcd.setBlendTargetB(0, 0, 0, 0, 0)
            lcd.setBlendConfig(0, 0, 0, 0)
            return true
        }
        return false
    }

    /**
     * Writes [lines] × 16 colours of [src] into BG palette bank [palId] onwards of [paletteBuffer] (the game's
     * `gPaletteBuffer`, 512 colours, BG banks first), each 5-bit channel scaled by `cur / max` with C integer
     * division; at `cur >= max` the colours are copied unchanged. The caller then marks the palette for upload
     * (`EnablePaletteSync`).
     *
     * Ported from src/opanim-healthsafetyscreen.c: OpAnimHS_BrightenPalette
     */
    public fun brightenPalette(src: ShortArray, paletteBuffer: ShortArray, palId: Int, lines: Int, cur: Int, max: Int) {
        val len = lines * 16
        val dst = palId * 16
        if (cur < max) {
            for (i in 0 until len) {
                val c = src[i].toInt() and 0xFFFF
                val r = (c and 0x1F) * cur / max
                val g = ((c shr 5) and 0x1F) * cur / max
                val b = ((c shr 10) and 0x1F) * cur / max
                paletteBuffer[dst + i] = (r or (g shl 5) or (b shl 10)).toShort()
            }
        } else {
            src.copyInto(paletteBuffer, dst, 0, len)
        }
    }
}
