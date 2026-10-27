package fe8.game.opanim

import gbafe.input.KeyStatus
import gbafe.data.Decompress
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.render.LcdControl

/**
 * Boot intro screens (Nintendo / Intelligent Systems logos, health & safety), decomp
 * `src/opanim-healthsafetyscreen.c`: the `ProcScr_GameEarlyStartUI` steps (palette fades, delays, the
 * health & safety brightness pulse and the logo skip). Step functions return a [StepResult] (what the proc
 * engine does next); screen loading (`PrepareHealthAndSafetyScreen`, `GameIntroPrepareNintendofx`) is not here yet.
 */
public object OpAnimHs {
    /** What a `PROC_REPEAT` step asks of the proc engine after this frame. */
    public enum class StepResult {
        /** Keep repeating the step next frame. */
        CONTINUE,

        /** `Proc_Break`: go on to the next script command. */
        BREAK,

        /** `Proc_Goto(proc, 0)`: the logos were skipped; jump to label 0 (palette fade to black, then end). */
        SKIP,
    }

    /** `status` of the health & safety screen. */
    public const val STATUS_HEALTH_SAFETY: Int = 0

    /** `status` of the Nintendo logo. */
    public const val STATUS_NINTENDO: Int = 1

    /** `status` of the Intelligent Systems logo. */
    public const val STATUS_INTELLIGENT_SYSTEMS: Int = 2

    /** Fields of `struct ProcOpAnimHS` used by the steps. */
    public class Proc {
        /** `status` (+0x2C): 0 health & safety, 1 Nintendo, 2 Intelligent Systems. */
        public var status: Int = 0

        /** `palette_timer` (+0x30): frames into the current fade / pulse. */
        public var paletteTimer: Int = 0

        /** `delay_timer` (+0x34): frames left in `GameEarlyStartDelay`. */
        public var delayTimer: Int = 0

        /** `unk38` (+0x38): frames left in the skip fade to black (`sub_80CBFC0`). */
        public var skipFadeTimer: Int = 0
    }

    /**
     * Sets `status` to health & safety (0).
     *
     * Ported from src/opanim-healthsafetyscreen.c: SetOpAnimHsStatus0
     */
    public fun setStatus0(proc: Proc) {
        proc.status = STATUS_HEALTH_SAFETY
    }

    /**
     * Sets `status` to the Nintendo logo (1).
     *
     * Ported from src/opanim-healthsafetyscreen.c: SetOpAnimHsStatus1
     */
    public fun setStatus1(proc: Proc) {
        proc.status = STATUS_NINTENDO
    }

    /**
     * Sets `status` to the Intelligent Systems logo (2).
     *
     * Ported from src/opanim-healthsafetyscreen.c: SetOpAnimHsStatus2
     */
    public fun setStatus2(proc: Proc) {
        proc.status = STATUS_INTELLIGENT_SYSTEMS
    }

    /**
     * Whether this frame skips the logos: A or B newly pressed (`newKeys & 0xA`), allowed only on the
     * Intelligent Systems logo or after a software reset ([softwareReset], the game's `IsSoftwareReset()`).
     * [base] is what the step decided before the check; a skip overrides it (`Proc_Goto` after `Proc_Break`).
     *
     * Ported from src/opanim-healthsafetyscreen.c: TryTickHSScreenExit
     */
    public fun tryTickExit(proc: Proc, keys: KeyStatus, softwareReset: Boolean, base: StepResult): StepResult {
        if (proc.status == STATUS_INTELLIGENT_SYSTEMS || softwareReset) {
            if (keys.newKeys and 0xA != 0) return StepResult.SKIP
        }
        return base
    }

    /**
     * One frame of the pause between screens: counts `delay_timer` down; at zero resets `palette_timer` and
     * breaks. Then checks for a logo skip ([tryTickExit]).
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameEarlyStartDelay
     */
    public fun earlyStartDelay(proc: Proc, keys: KeyStatus, softwareReset: Boolean): StepResult {
        var r = StepResult.CONTINUE
        if (--proc.delayTimer <= 0) {
            proc.paletteTimer = 0
            r = StepResult.BREAK
        }
        return tryTickExit(proc, keys, softwareReset, r)
    }

    /**
     * One frame of the Nintendo logo fade-in ([nintendoPalette] = `Pal_GameIntroNintendo` into BG bank 0 over
     * 30 frames; shows BG0 on the first frame); then a 60-frame delay. Checks for a logo skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroNintendoFadeIN
     */
    public fun nintendoFadeIn(
        proc: Proc, nintendoPalette: ShortArray, paletteBuffer: ShortArray, lcd: LcdControl, keys: KeyStatus, softwareReset: Boolean,
    ): StepResult {
        if (proc.paletteTimer == 0) lcd.setDispEnable(1, 0, 0, 0, 0)
        proc.paletteTimer++
        brightenPalette(nintendoPalette, paletteBuffer, 0, 1, proc.paletteTimer, FADE_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, FADE_FRAMES, 0x3C))
    }

    /**
     * One frame of the Nintendo logo fade-out (30 frames); then a 40-frame delay. Checks for a logo skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroNintendoFadeOUT
     */
    public fun nintendoFadeOut(proc: Proc, nintendoPalette: ShortArray, paletteBuffer: ShortArray, keys: KeyStatus, softwareReset: Boolean): StepResult {
        proc.paletteTimer++
        brightenPalette(nintendoPalette, paletteBuffer, 0, 1, FADE_FRAMES - proc.paletteTimer, FADE_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, FADE_FRAMES, 0x28))
    }

    /**
     * One frame of the Intelligent Systems logo fade-in ([isPalette] = `Pal_IntelligentSystems`, 3 banks into BG
     * banks 1..3 over 30 frames; shows BG1 on the first frame); then a 60-frame delay. Checks for a logo skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroIntelligentSystemsFadeIN
     */
    public fun intelligentSystemsFadeIn(
        proc: Proc, isPalette: ShortArray, paletteBuffer: ShortArray, lcd: LcdControl, keys: KeyStatus, softwareReset: Boolean,
    ): StepResult {
        if (proc.paletteTimer == 0) lcd.setDispEnable(0, 1, 0, 0, 0)
        proc.paletteTimer++
        brightenPalette(isPalette, paletteBuffer, 1, 3, proc.paletteTimer, FADE_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, FADE_FRAMES, 0x3C))
    }

    /**
     * One frame of the Intelligent Systems logo fade-out (40 frames, longer than the others); then a 60-frame
     * delay. Checks for a logo skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroIntelligentSystemsFadeOUT
     */
    public fun intelligentSystemsFadeOut(proc: Proc, isPalette: ShortArray, paletteBuffer: ShortArray, keys: KeyStatus, softwareReset: Boolean): StepResult {
        proc.paletteTimer++
        brightenPalette(isPalette, paletteBuffer, 1, 3, IS_FADE_OUT_FRAMES - proc.paletteTimer, IS_FADE_OUT_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, IS_FADE_OUT_FRAMES, 0x3C))
    }

    /**
     * Start of the skip fade: saves all 512 colours of [paletteBuffer] into [backup] (`gPaletteOpAnimHsBackup`)
     * and starts a 4-frame countdown.
     *
     * Ported from src/opanim-healthsafetyscreen.c: sub_80CBF9C
     */
    public fun startSkipFade(proc: Proc, paletteBuffer: ShortArray, backup: ShortArray) {
        paletteBuffer.copyInto(backup, 0, 0, 0x200)
        proc.skipFadeTimer = 4
    }

    /**
     * One frame of the skip fade: scales every colour of [backup] by `n / 4` into [paletteBuffer] for n = 3, 2, 1;
     * then blacks the whole palette and returns true (`Proc_Break`). The caller marks the palette for upload.
     *
     * Ported from src/opanim-healthsafetyscreen.c: sub_80CBFC0
     */
    public fun skipFade(proc: Proc, paletteBuffer: ShortArray, backup: ShortArray): Boolean {
        if (--proc.skipFadeTimer > 0) {
            val n = proc.skipFadeTimer
            for (i in 0 until 0x200) {
                val c = backup[i].toInt() and 0xFFFF
                val r = (c and 0x1F) * n / 4
                val g = ((c shr 5) and 0x1F) * n / 4
                val b = ((c shr 10) and 0x1F) * n / 4
                paletteBuffer[i] = (r or (g shl 5) or (b shl 10)).toShort()
            }
            return false
        }
        paletteBuffer.fill(0, 0, 0x200)
        return true
    }

    private fun fadeDone(proc: Proc, frames: Int, delay: Int): StepResult {
        if (proc.paletteTimer > frames - 1) {
            proc.delayTimer = delay
            return StepResult.BREAK
        }
        return StepResult.CONTINUE
    }

    /** Length of the Intelligent Systems fade-out in frames (0x28). */
    private const val IS_FADE_OUT_FRAMES = 0x28

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

    /** A compressed graphics blob (`Decompress` format) at [offset] of [data], e.g. a ROM symbol. */
    public class Blob(
        /** Bytes holding the blob (the ROM image, or the blob alone). */
        public val data: ByteArray,
        /** Offset of the blob's header in [data]. */
        public val offset: Int = 0,
    )

    private fun tsaInto(hw: BgHardware, bg: Int, tsa: Blob, tileBase: Int) {
        val generic = Decompress.decode(tsa.data, tsa.offset) // gGenericBuffer
        BgTilemap.applyTsa(hw.mapBuffer(bg), 0, generic, 0, tileBase)
    }

    private fun clearPaletteAndSync(hw: BgHardware) {
        hw.palette.fill(0) // CpuFastFill16(0, gPaletteBuffer, 0x400)
        hw.enablePaletteSync()
        hw.enableBgSyncByMask(0x3)
    }

    /**
     * `GameIntroPrepareNintendofx`: loads the Nintendo logo (tiles at VRAM 0, TSA on BG0) and the Intelligent
     * Systems logo (tiles at VRAM 0x2000, TSA on BG1 with tile base 0x1100), clears the palette buffer, shows BG0
     * only, removes the primary HBlank handler. `Sound_FadeOutBGM(1)` is not modelled yet (no BGM plays at this
     * point of the boot).
     *
     * Partially ported from src/opanim-healthsafetyscreen.c: GameIntroPrepareNintendofx
     */
    public fun prepareNintendofx(proc: Proc, hw: BgHardware, nintendoImg: Blob, nintendoTsa: Blob, isImg: Blob, isTsa: Blob) {
        hw.setupBackgrounds()
        hw.hblank.setPrimary(null)
        hw.lcd.setDispEnable(0, 0, 0, 0, 0)
        Decompress.into(nintendoImg.data, nintendoImg.offset, hw.vram, 0)
        tsaInto(hw, 0, nintendoTsa, 0)
        Decompress.into(isImg.data, isImg.offset, hw.vram, 0x2000)
        tsaInto(hw, 1, isTsa, 0x1100)
        clearPaletteAndSync(hw)
        hw.lcd.setDispEnable(1, 0, 0, 0, 0)
        proc.paletteTimer = 0
    }

    /**
     * `PrepareHealthAndSafetyScreen`: on a software reset returns false (the caller does `Proc_Goto(proc, 0x3E7)`);
     * else loads the screen (tiles at VRAM 0, BG1 then BG0 TSA), clears the palette buffer, shows BG1 only and
     * ignores all keys (`SetKeyStatus_IgnoreMask(0x3FF)`), and returns true. HBlank and sound as in
     * [prepareNintendofx].
     *
     * Partially ported from src/opanim-healthsafetyscreen.c: PrepareHealthAndSafetyScreen
     */
    public fun prepareHealthSafety(
        proc: Proc, hw: BgHardware, keys: KeyStatus, softwareReset: Boolean, img: Blob, tsaBg1: Blob, tsaBg0: Blob,
    ): Boolean {
        if (softwareReset) return false
        hw.setupBackgrounds()
        hw.hblank.setPrimary(null)
        hw.lcd.setDispEnable(0, 0, 0, 0, 0)
        Decompress.into(img.data, img.offset, hw.vram, 0)
        tsaInto(hw, 1, tsaBg1, 0)
        tsaInto(hw, 0, tsaBg0, 0)
        clearPaletteAndSync(hw)
        hw.lcd.setDispEnable(0, 1, 0, 0, 0)
        proc.paletteTimer = 0
        keys.applyIgnoreMask(0x3FF)
        return true
    }

}
