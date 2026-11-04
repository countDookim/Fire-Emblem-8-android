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
 * `ProcScr_GameEarlyStartUI` (src/opanim-healthsafetyscreen.c): health & safety screen, Nintendo and
 * Intelligent Systems logos, with the A/B logo skip. Same 28 commands as the ROM script, so command indices match
 * `proc_scrCur`. Screen loads use [Env.gfx] and [Env.hardware].
 */
public class EarlyStartUi(
    /** The proc engine the script runs on. */
    public val engine: ProcEngine,
    /** Game state the steps read and write. */
    public val env: Env,
) {
    /** What the steps touch outside the proc. */
    public class Env(
        /** `gPaletteBuffer` (512 colours). */
        public val paletteBuffer: ShortArray,
        /** `gPaletteOpAnimHsBackup` (512 colours). */
        public val paletteBackup: ShortArray,
        /** `gLCDControlBuffer`. */
        public val lcd: LcdControl,
        /** `gKeyStatusPtr`. */
        public val keys: KeyStatus,
        /** `Pal_OpAnimHealthSafetyScreen` (16 colours). */
        public val hsPalette: ShortArray,
        /** `Pal_GameIntroNintendo` (16 colours). */
        public val nintendoPalette: ShortArray,
        /** `Pal_IntelligentSystems` (48 colours). */
        public val isPalette: ShortArray,
        /** `IsSoftwareReset()`. */
        public val softwareReset: () -> Boolean = { false },
        /** The compressed screen graphics `PrepareHealthAndSafetyScreen` and `GameIntroPrepareNintendofx` load. */
        public val gfx: Gfx,
        /** `gKeyComboResetEN` (`EnableKeyComboResetEN`). */
        public val keyComboReset: KeyComboReset = KeyComboReset(),
        /** BG buffers, VRAM and sync flags; shares [lcd] and [paletteBuffer]. */
        public val hardware: BgHardware = BgHardware(lcd, paletteBuffer),
        /** `Sound_FadeOutBGM(speed)` (both screens' setup calls it with speed 1). */
        public val fadeOutBgm: (Int) -> Unit = {},
    )

    /** ROM graphics of the three screens (`Decompress` blobs). */
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

    private fun st(p: Proc) = p.state as OpAnimHs.Proc

    private fun apply(p: Proc, r: StepResult) {
        when (r) {
            StepResult.CONTINUE -> Unit
            StepResult.BREAK -> engine.breakProc(p)
            StepResult.SKIP -> engine.goto(p, 0)
        }
    }

    private fun bool(p: Proc, broke: Boolean) {
        if (broke) engine.breakProc(p)
    }

    private val e = env

    /** `EnablePaletteSync` after a fade wrote [Env.paletteBuffer] (`OpAnimHS_BrightenPalette`, `sub_80CBFC0`). */
    private fun <T> synced(result: T): T {
        e.hardware.enablePaletteSync()
        return result
    }
    private val delay = ProcFunc { p -> apply(p, OpAnimHs.earlyStartDelay(st(p), e.keys, e.softwareReset())) }

    /** The script; [ProcScript.newState] creates an [OpAnimHs.Proc]. */
    public val script: ProcScript = ProcScript("ProcScr_GameEarlyStartUI", listOf(
        yield(),
        call { OpAnimHs.setStatus0(st(it)) },
        call {
            val g = e.gfx
            if (!OpAnimHs.prepareHealthSafety(st(it), e.hardware, e.keys, e.softwareReset(), g.hsImg, g.hsTsaBg1, g.hsTsaBg0)) {
                engine.goto(it, 0x3E7)
            } else {
                e.fadeOutBgm(1) // after the software-reset check, as in the original
            }
        },
        repeat { bool(it, synced(OpAnimHs.healthSafetyFadeIn(st(it), e.hsPalette, e.paletteBuffer))) },
        repeat(delay),
        repeat { bool(it, OpAnimHs.healthSafetyWaitButton(st(it), e.lcd, e.keys)) },
        repeat { bool(it, synced(OpAnimHs.healthSafetyFadeOut(st(it), e.hsPalette, e.paletteBuffer))) },
        repeat(delay),
        label(0x3E7),
        call {
            val g = e.gfx
            e.fadeOutBgm(1)
            OpAnimHs.prepareNintendofx(st(it), e.hardware, g.nintendoImg, g.nintendoTsa, g.isImg, g.isTsa)
        },
        call { OpAnimHs.setStatus1(st(it)) },
        repeat { apply(it, synced(OpAnimHs.nintendoFadeIn(st(it), e.nintendoPalette, e.paletteBuffer, e.lcd, e.keys, e.softwareReset()))) },
        repeat(delay),
        repeat { apply(it, synced(OpAnimHs.nintendoFadeOut(st(it), e.nintendoPalette, e.paletteBuffer, e.keys, e.softwareReset()))) },
        repeat(delay),
        call { e.keyComboReset.enable() },
        call { OpAnimHs.setStatus2(st(it)) },
        repeat { apply(it, synced(OpAnimHs.intelligentSystemsFadeIn(st(it), e.isPalette, e.paletteBuffer, e.lcd, e.keys, e.softwareReset()))) },
        repeat(delay),
        repeat { apply(it, synced(OpAnimHs.intelligentSystemsFadeOut(st(it), e.isPalette, e.paletteBuffer, e.keys, e.softwareReset()))) },
        repeat(delay),
        goto(1),
        label(0),
        call { e.keyComboReset.enable() },
        call { OpAnimHs.startSkipFade(st(it), e.paletteBuffer, e.paletteBackup) },
        repeat { bool(it, synced(OpAnimHs.skipFade(st(it), e.paletteBuffer, e.paletteBackup))) },
        label(1),
        end(),
    ), newState = { OpAnimHs.Proc() })
}
