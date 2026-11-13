package fe8.game.opanim

import fe8.game.opanim.OpAnimHs.StepResult
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield
import gbafe.input.KeyComboReset
import gbafe.render.BgHardware
import gbafe.render.LcdControl

/**
 * `ProcScr_GameEarlyStartUI` (src/opanim-healthsafetyscreen.c): the boot sequence the game control
 * proc starts before the opening animation: the health and safety screen, then the Nintendo and
 * Intelligent Systems logos, with the A/B logo skip. Same 28 commands as the decomp script, so
 * command indices match `proc_scrCur`. Screen loads use [Env.gfx] and [Env.hardware]. The per-frame
 * work lives in [OpAnimHs]; this class only wires those steps into the script and turns each step's
 * result into proc control (break, skip, goto).
 */
public class EarlyStartUi(
    /** The proc engine the script runs on. */
    public val engine: ProcEngine,
    /** Game state the steps read and write. */
    public val env: Env,
) {
    /** The game state the steps read and write besides the proc: palettes, LCD, keys, graphics. */
    public class Env(
        /** `gPaletteBuffer` (512 colours). */
        public val paletteBuffer: ShortArray,
        /** `gPaletteOpAnimHsBackup` (512 colours). */
        public val paletteBackup: ShortArray,
        /** `gLCDControlBuffer`: display enable and blend registers. */
        public val lcd: LcdControl,
        /** `gKeyStatusPtr`: the key state the skip and button-wait checks read. */
        public val keys: KeyStatus,
        /** `Pal_OpAnimHealthSafetyScreen` (16 colours). */
        public val hsPalette: ShortArray,
        /** `Pal_GameIntroNintendo` (16 colours). */
        public val nintendoPalette: ShortArray,
        /** `Pal_IntelligentSystems` (48 colours). */
        public val isPalette: ShortArray,
        /** `IsSoftwareReset()`: true after a soft reset (skips the warning, A/B skip any logo). */
        public val softwareReset: () -> Boolean = { false },
        /**
         * The compressed graphics that `PrepareHealthAndSafetyScreen` and
         * `GameIntroPrepareNintendofx` load.
         */
        public val gfx: Gfx,
        /**
         * `gKeyComboResetEN`, set by `EnableKeyComboResetEN`: while set, the soft-reset key combos
         * (L+R+A+B, A+B+Select+Start) work. The script sets it after the Nintendo logo, or on a
         * skip.
         */
        public val keyComboReset: KeyComboReset = KeyComboReset(),
        /** BG tilemap buffers, VRAM and sync flags; shares [lcd] and [paletteBuffer]. */
        public val hardware: BgHardware = BgHardware(lcd, paletteBuffer),
        /** `Sound_FadeOutBGM(speed)`: both screens' setup code calls it with speed 1. */
        public val fadeOutBgm: (Int) -> Unit = {},
    )

    /** ROM graphics of the three screens: tiles and tilemaps as `Decompress` blobs. */
    public class Gfx(
        /** `Img_OpAnimHealthSafetyScreen`. */
        public val hsImg: OpAnimHs.Blob,
        /** `Tsa_OpAnimHealthSafetyScreenBG1`. */
        public val hsTsaBg1: OpAnimHs.Blob,
        /** `Tsa_OpAnimHealthSafetyScreenBG0`. */
        public val hsTsaBg0: OpAnimHs.Blob,
        /** `Img_GameIntroNintendo`. */
        public val nintendoImg: OpAnimHs.Blob,
        /** `Tsa_GameIntroNintendo`. */
        public val nintendoTsa: OpAnimHs.Blob,
        /** `Img_IntelligentSystems`. */
        public val isImg: OpAnimHs.Blob,
        /** `Tsa_IntelligentSystems`. */
        public val isTsa: OpAnimHs.Blob,
    )

    /** The [OpAnimHs.Proc] state that [script]'s `newState` attached to [p]. */
    private fun hsState(p: Proc) = p.state as OpAnimHs.Proc

    /**
     * Turns a logo step's [result] into proc control: [StepResult.BREAK] ends the current repeat
     * command, [StepResult.SKIP] (A/B pressed) jumps to the skip-fade block at label 0.
     */
    private fun applyResult(p: Proc, result: StepResult) {
        when (result) {
            StepResult.CONTINUE -> Unit
            StepResult.BREAK -> engine.breakProc(p)
            StepResult.SKIP -> engine.goto(p, 0)
        }
    }

    /** Ends [p]'s current repeat command once the warning-screen step reports it [finished]. */
    private fun breakIfFinished(p: Proc, finished: Boolean) {
        if (finished) engine.breakProc(p)
    }

    /** Short alias for [env], used throughout the script table below. */
    private val e = env

    /**
     * Queues the palette upload (`EnablePaletteSync`) after a fade step wrote [Env.paletteBuffer];
     * the decomp does this inside `OpAnimHS_BrightenPalette` and `sub_80CBFC0`. Returns [result]
     * unchanged.
     */
    private fun <T> synced(result: T): T {
        e.hardware.enablePaletteSync()
        return result
    }

    /**
     * Repeat command body for the pauses between screens ([OpAnimHs.earlyStartDelay]); it also
     * checks for the logo skip.
     */
    private val delay = ProcFunc { p ->
        applyResult(p, OpAnimHs.earlyStartDelay(hsState(p), e.keys, e.softwareReset()))
    }

    /** The script; [ProcScript.newState] creates an [OpAnimHs.Proc]. */
    public val script: ProcScript = ProcScript("ProcScr_GameEarlyStartUI", listOf(
        yield(),
        call { OpAnimHs.setStatus0(hsState(it)) },
        call {
            val g = e.gfx
            val loaded = OpAnimHs.prepareHealthSafety(
                hsState(it), e.hardware, e.keys, e.softwareReset(), g.hsImg, g.hsTsaBg1, g.hsTsaBg0,
            )
            if (!loaded) {
                engine.goto(it, 0x3E7) // soft reset: skip the health and safety screen
            } else {
                e.fadeOutBgm(1) // after the software-reset check, as in the decomp
            }
        },
        repeat {
            val done = OpAnimHs.healthSafetyFadeIn(hsState(it), e.hsPalette, e.paletteBuffer)
            breakIfFinished(it, synced(done))
        },
        repeat(delay),
        repeat { breakIfFinished(it, OpAnimHs.healthSafetyWaitButton(hsState(it), e.lcd, e.keys)) },
        repeat {
            val done = OpAnimHs.healthSafetyFadeOut(hsState(it), e.hsPalette, e.paletteBuffer)
            breakIfFinished(it, synced(done))
        },
        repeat(delay),
        label(0x3E7),
        call {
            val g = e.gfx
            e.fadeOutBgm(1)
            OpAnimHs.prepareNintendofx(
                hsState(it), e.hardware, g.nintendoImg, g.nintendoTsa, g.isImg, g.isTsa,
            )
        },
        call { OpAnimHs.setStatus1(hsState(it)) },
        repeat {
            val result = OpAnimHs.nintendoFadeIn(
                hsState(it), e.nintendoPalette, e.paletteBuffer, e.lcd, e.keys, e.softwareReset(),
            )
            applyResult(it, synced(result))
        },
        repeat(delay),
        repeat {
            val result = OpAnimHs.nintendoFadeOut(
                hsState(it), e.nintendoPalette, e.paletteBuffer, e.keys, e.softwareReset(),
            )
            applyResult(it, synced(result))
        },
        repeat(delay),
        call { e.keyComboReset.enable() },
        call { OpAnimHs.setStatus2(hsState(it)) },
        repeat {
            val result = OpAnimHs.intelligentSystemsFadeIn(
                hsState(it), e.isPalette, e.paletteBuffer, e.lcd, e.keys, e.softwareReset(),
            )
            applyResult(it, synced(result))
        },
        repeat(delay),
        repeat {
            val result = OpAnimHs.intelligentSystemsFadeOut(
                hsState(it), e.isPalette, e.paletteBuffer, e.keys, e.softwareReset(),
            )
            applyResult(it, synced(result))
        },
        repeat(delay),
        goto(1),
        label(0),
        call { e.keyComboReset.enable() },
        call { OpAnimHs.startSkipFade(hsState(it), e.paletteBuffer, e.paletteBackup) },
        repeat {
            val done = OpAnimHs.skipFade(hsState(it), e.paletteBuffer, e.paletteBackup)
            breakIfFinished(it, synced(done))
        },
        label(1),
        end(),
    ), newState = { OpAnimHs.Proc() })
}
