package fe8.game.opanim

import gba.data.Decompress
import gbafe.input.KeyStatus
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.render.LcdControl

/**
 * Per-frame steps of the boot screens shown before the title intro: the health and safety warning,
 * the Nintendo logo and the Intelligent Systems logo (decomp `src/opanim-healthsafetyscreen.c`).
 * Covers the palette fades, the pauses between screens, the warning screen's brightness pulse while
 * it waits for a button, and the A/B skip of the logos. Logo steps return a [StepResult] (what the
 * proc engine does next); the warning-screen steps return true for `Proc_Break`. The screen loaders
 * ([prepareHealthSafety], [prepareNintendofx]) are here too. [EarlyStartUi] strings these steps
 * into the boot script.
 */
public object OpAnimHs {
    /** What a logo `PROC_REPEAT` step asks of the proc engine after this frame. */
    public enum class StepResult {
        /** Keep repeating the step next frame. */
        CONTINUE,

        /** `Proc_Break`: go on to the next script command. */
        BREAK,

        /** `Proc_Goto(proc, 0)`: logos skipped; jump to label 0 (fade to black, then end). */
        SKIP,
    }

    /** `status` of the health and safety screen (skippable only after a software reset). */
    public const val STATUS_HEALTH_SAFETY: Int = 0

    /** `status` of the Nintendo logo (skippable only after a software reset). */
    public const val STATUS_NINTENDO: Int = 1

    /** `status` of the Intelligent Systems logo (the only screen A/B can always skip). */
    public const val STATUS_INTELLIGENT_SYSTEMS: Int = 2

    /** Fields of `struct ProcOpAnimHS` used by the steps. */
    public class Proc {
        /**
         * `status` (+0x2C): which screen is up (0 health and safety, 1 Nintendo, 2 Intelligent
         * Systems). Only [tryTickExit] reads it, to decide whether A/B may skip.
         */
        public var status: Int = 0

        /** `palette_timer` (+0x30): frames into the current fade or brightness pulse. */
        public var paletteTimer: Int = 0

        /** `delay_timer` (+0x34): frames left in the current [earlyStartDelay] pause. */
        public var delayTimer: Int = 0

        /**
         * `unk38` in the decomp (+0x38): the skip fade's brightness counter; [startSkipFade] sets
         * it to 4 and [skipFade] counts it down (`sub_80CBFC0`).
         */
        public var skipFadeTimer: Int = 0
    }

    /**
     * Sets `status` to the health and safety screen (0).
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
     * Decides whether this frame skips the logos: A or B newly pressed (`newKeys & 0xA`), allowed
     * only on the Intelligent Systems logo or after a software reset ([softwareReset], the game's
     * `IsSoftwareReset()`, which lets the player skip every screen). [base] is the result the step
     * reached before the check; a skip overrides it (the decomp does `Proc_Goto` after a possible
     * `Proc_Break`).
     *
     * Ported from src/opanim-healthsafetyscreen.c: TryTickHSScreenExit
     */
    public fun tryTickExit(
        proc: Proc,
        keys: KeyStatus,
        softwareReset: Boolean,
        base: StepResult,
    ): StepResult {
        if (proc.status == STATUS_INTELLIGENT_SYSTEMS || softwareReset) {
            if (keys.newKeys and 0xA != 0) return StepResult.SKIP
        }
        return base
    }

    /**
     * One frame of the pause between screens: counts `delay_timer` down; when it reaches zero,
     * resets `palette_timer` (for the next fade) and breaks. Then checks for a logo skip
     * ([tryTickExit]).
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameEarlyStartDelay
     */
    public fun earlyStartDelay(proc: Proc, keys: KeyStatus, softwareReset: Boolean): StepResult {
        var base = StepResult.CONTINUE
        if (--proc.delayTimer <= 0) {
            proc.paletteTimer = 0
            base = StepResult.BREAK
        }
        return tryTickExit(proc, keys, softwareReset, base)
    }

    /**
     * One frame of the Nintendo logo fade-in: brightens [nintendoPalette] (`Pal_GameIntroNintendo`,
     * 1 bank) into BG palette bank 0 of [paletteBuffer] over 30 frames, showing only BG0 on the
     * first frame. On the last frame it loads a 60-frame pause (`delay_timer`) and breaks. Then
     * checks for a logo skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroNintendoFadeIN
     */
    public fun nintendoFadeIn(
        proc: Proc,
        nintendoPalette: ShortArray,
        paletteBuffer: ShortArray,
        lcd: LcdControl,
        keys: KeyStatus,
        softwareReset: Boolean,
    ): StepResult {
        if (proc.paletteTimer == 0) lcd.setDispEnable(1, 0, 0, 0, 0)
        proc.paletteTimer++
        brightenPalette(nintendoPalette, paletteBuffer, 0, 1, proc.paletteTimer, FADE_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, FADE_FRAMES, 0x3C))
    }

    /**
     * One frame of the Nintendo logo fade-out (the fade-in reversed, 30 frames); on the last frame
     * it loads a 40-frame pause and breaks. Then checks for a logo skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroNintendoFadeOUT
     */
    public fun nintendoFadeOut(
        proc: Proc,
        nintendoPalette: ShortArray,
        paletteBuffer: ShortArray,
        keys: KeyStatus,
        softwareReset: Boolean,
    ): StepResult {
        proc.paletteTimer++
        val cur = FADE_FRAMES - proc.paletteTimer
        brightenPalette(nintendoPalette, paletteBuffer, 0, 1, cur, FADE_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, FADE_FRAMES, 0x28))
    }

    /**
     * One frame of the Intelligent Systems logo fade-in: brightens [isPalette]
     * (`Pal_IntelligentSystems`, 3 banks) into BG palette banks 1 to 3 of [paletteBuffer] over 30
     * frames, showing only BG1 on the first frame. On the last frame it loads a 60-frame pause and
     * breaks. Then checks for a logo skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroIntelligentSystemsFadeIN
     */
    public fun intelligentSystemsFadeIn(
        proc: Proc,
        isPalette: ShortArray,
        paletteBuffer: ShortArray,
        lcd: LcdControl,
        keys: KeyStatus,
        softwareReset: Boolean,
    ): StepResult {
        if (proc.paletteTimer == 0) lcd.setDispEnable(0, 1, 0, 0, 0)
        proc.paletteTimer++
        brightenPalette(isPalette, paletteBuffer, 1, 3, proc.paletteTimer, FADE_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, FADE_FRAMES, 0x3C))
    }

    /**
     * One frame of the Intelligent Systems logo fade-out (40 frames, longer than its 30-frame
     * fade-in); on the last frame it loads a 60-frame pause and breaks. Then checks for a logo
     * skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroIntelligentSystemsFadeOUT
     */
    public fun intelligentSystemsFadeOut(
        proc: Proc,
        isPalette: ShortArray,
        paletteBuffer: ShortArray,
        keys: KeyStatus,
        softwareReset: Boolean,
    ): StepResult {
        proc.paletteTimer++
        val cur = IS_FADE_OUT_FRAMES - proc.paletteTimer
        brightenPalette(isPalette, paletteBuffer, 1, 3, cur, IS_FADE_OUT_FRAMES)
        return tryTickExit(proc, keys, softwareReset, fadeDone(proc, IS_FADE_OUT_FRAMES, 0x3C))
    }

    /**
     * Start of the skip fade (label 0 of the boot script): saves all 512 colours of [paletteBuffer]
     * into [backup] (`gPaletteOpAnimHsBackup`) and sets the brightness counter to 4.
     *
     * Ported from src/opanim-healthsafetyscreen.c: sub_80CBF9C
     */
    public fun startSkipFade(proc: Proc, paletteBuffer: ShortArray, backup: ShortArray) {
        paletteBuffer.copyInto(backup, 0, 0, 0x200)
        proc.skipFadeTimer = 4
    }

    /**
     * One frame of the skip fade to black: on the first three frames scales every colour of
     * [backup] by `n / 4` into [paletteBuffer] (n = 3, 2, 1, the counter after its decrement); on
     * the fourth it blacks the whole palette and returns true (`Proc_Break`). The caller marks the
     * palette for upload, as the decomp does with `EnablePaletteSync` on every call.
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

    /**
     * Shared tail of the logo fades: once the palette timer has run [frames] frames, loads
     * `delay_timer` with the [delay] frames of the pause that follows and returns
     * [StepResult.BREAK]; otherwise [StepResult.CONTINUE]. The decomp spells this out in each fade
     * as `palette_timer > frames - 1`.
     */
    private fun fadeDone(proc: Proc, frames: Int, delay: Int): StepResult {
        if (proc.paletteTimer > frames - 1) {
            proc.delayTimer = delay
            return StepResult.BREAK
        }
        return StepResult.CONTINUE
    }

    /** Length of the Intelligent Systems fade-out in frames (0x28); the other fades use 0x1E. */
    private const val IS_FADE_OUT_FRAMES = 0x28

    /**
     * One frame of the health and safety screen fade-in: brightens [hsPalette]
     * (`Pal_OpAnimHealthSafetyScreen`, 16 colours) into BG palette bank 0 of [paletteBuffer] over
     * 30 frames; on the last frame sets a 90-frame pause and returns true (`Proc_Break`). The
     * caller marks the palette for upload. Unlike the logo fades, this one never checks for a skip.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroHealthSafetyFadeIN
     */
    public fun healthSafetyFadeIn(
        proc: Proc,
        hsPalette: ShortArray,
        paletteBuffer: ShortArray,
    ): Boolean {
        proc.paletteTimer++
        brightenPalette(hsPalette, paletteBuffer, 0, 1, proc.paletteTimer, FADE_FRAMES)
        if (proc.paletteTimer > FADE_FRAMES - 1) {
            proc.delayTimer = 0x5A
            return true
        }
        return false
    }

    /**
     * One frame of the health and safety screen fade-out (the fade-in reversed, 30 frames); on the
     * last frame sets a 40-frame pause and returns true (`Proc_Break`). The caller marks the
     * palette for upload.
     *
     * Ported from src/opanim-healthsafetyscreen.c: GameIntroHealthSafetyFadeOUT
     */
    public fun healthSafetyFadeOut(
        proc: Proc,
        hsPalette: ShortArray,
        paletteBuffer: ShortArray,
    ): Boolean {
        proc.paletteTimer++
        val cur = FADE_FRAMES - proc.paletteTimer
        brightenPalette(hsPalette, paletteBuffer, 0, 1, cur, FADE_FRAMES)
        if (proc.paletteTimer > FADE_FRAMES - 1) {
            proc.delayTimer = 0x28
            return true
        }
        return false
    }

    /** Length of the fades in frames (0x1E), except the Intelligent Systems fade-out. */
    private const val FADE_FRAMES = 0x1E

    /**
     * One frame of the health and safety screen waiting for a button. On its first frame it shows
     * BG0 and BG1, makes BG0 the blend target and stops ignoring keys (they were ignored since
     * [prepareHealthSafety]). Every frame it pulses BG0's brightness (`BLEND_EFFECT_BRIGHTEN` with
     * coefficient |16 - (t mod 64) / 2|, a triangle wave of period 64 frames). Once the timer
     * passes 0xE0F (3600 frames) or any button is newly pressed (`newKeys & 0x3FF`), it clears the
     * blend effect, hides BG0 and returns true (`Proc_Break`).
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
        // The C holds this in an s16 and takes its absolute value below.
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
     * Fades palette banks in: writes [lines] banks of 16 colours from [src] into [paletteBuffer]
     * (the game's `gPaletteBuffer`, 512 colours, BG banks first) from BG bank [palId] onwards, each
     * 5-bit channel scaled by `cur / max` with C integer division. At `cur >= max` the colours are
     * copied unchanged. The caller then marks the palette for upload (the decomp calls
     * `EnablePaletteSync` itself).
     *
     * Ported from src/opanim-healthsafetyscreen.c: OpAnimHS_BrightenPalette
     */
    public fun brightenPalette(
        src: ShortArray,
        paletteBuffer: ShortArray,
        palId: Int,
        lines: Int,
        cur: Int,
        max: Int,
    ) {
        val len = lines * 16
        val dst = palId * 16
        // Below full brightness scale each channel; at or past it copy verbatim, as the C does.
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

    /** A `Decompress`-format graphics blob at [offset] of [data], e.g. a ROM symbol. */
    public class Blob(
        /** Bytes holding the blob (the ROM image, or the blob alone). */
        public val data: ByteArray,
        /** Offset of the blob's header in [data]. */
        public val offset: Int = 0,
    )

    /**
     * Decompresses the tile-and-palette map [tsa] and writes it into background [bg]'s tilemap with
     * [tileBase] added to every tile id (`Decompress` into `gGenericBuffer`, then
     * `CallARM_FillTileRect`).
     */
    private fun tsaInto(hw: BgHardware, bg: Int, tsa: Blob, tileBase: Int) {
        val generic = Decompress.decode(tsa.data, tsa.offset) // gGenericBuffer
        BgTilemap.applyTsa(hw.mapBuffer(bg), 0, generic, 0, tileBase)
    }

    /**
     * Blacks out the palette buffer so the screen fades in from black, and queues the palette and
     * BG0/BG1 tilemap uploads (`EnablePaletteSync`, `BG_EnableSyncByMask`).
     */
    private fun clearPaletteAndSync(hw: BgHardware) {
        hw.palette.fill(0) // CpuFastFill16(0, gPaletteBuffer, 0x400)
        hw.enablePaletteSync()
        hw.enableBgSyncByMask(0x3)
    }

    /**
     * Sets up the logo screens (`GameIntroPrepareNintendofx`): resets the backgrounds, removes the
     * primary HBlank handler, loads the Nintendo logo (tiles at VRAM 0, map on BG0) and the
     * Intelligent Systems logo (tiles at VRAM 0x2000, map on BG1 with tile base 0x1100), clears the
     * palette buffer so both fade in from black, and shows BG0 only. The decomp's first call,
     * `Sound_FadeOutBGM(1)`, is made by [EarlyStartUi] (`Env.fadeOutBgm`) before this.
     *
     * Partially ported from src/opanim-healthsafetyscreen.c: GameIntroPrepareNintendofx
     */
    public fun prepareNintendofx(
        proc: Proc,
        hw: BgHardware,
        nintendoImg: Blob,
        nintendoTsa: Blob,
        isImg: Blob,
        isTsa: Blob,
    ) {
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
     * Sets up the health and safety screen (`PrepareHealthAndSafetyScreen`). After a software reset
     * the screen is skipped: returns false and the caller does `Proc_Goto(proc, 0x3E7)` (the
     * Nintendo logo). Otherwise resets the backgrounds, removes the HBlank handler, loads the
     * screen (tiles at VRAM 0, then the BG1 and BG0 maps), clears the palette buffer, shows BG1
     * only, ignores all keys (`SetKeyStatus_IgnoreMask(0x3FF)`, until [healthSafetyWaitButton]) and
     * returns true. The decomp's `Sound_FadeOutBGM(1)` is made by [EarlyStartUi] after this returns
     * true.
     *
     * Partially ported from src/opanim-healthsafetyscreen.c: PrepareHealthAndSafetyScreen
     */
    public fun prepareHealthSafety(
        proc: Proc,
        hw: BgHardware,
        keys: KeyStatus,
        softwareReset: Boolean,
        img: Blob,
        tsaBg1: Blob,
        tsaBg0: Blob,
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
