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
import gbafe.render.LcdControl

/**
 * `ProcScr_GameEarlyStartUI` (src/opanim-healthsafetyscreen.c): health & safety screen, Nintendo and
 * Intelligent Systems logos, with the A/B logo skip. Same 28 commands as the ROM script, so command indices match
 * `proc_scrCur`. Screen loading and other not-yet-ported calls are the [Env] hooks.
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
        /** `PrepareHealthAndSafetyScreen` (not ported yet). */
        public val prepareHealthSafety: ProcFunc = ProcFunc { },
        /** `GameIntroPrepareNintendofx` (not ported yet). */
        public val prepareLogos: ProcFunc = ProcFunc { },
        /** `EnableKeyComboResetEN` (not ported yet). */
        public val enableKeyComboReset: ProcFunc = ProcFunc { },
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
    private val delay = ProcFunc { p -> apply(p, OpAnimHs.earlyStartDelay(st(p), e.keys, e.softwareReset())) }

    /** The script; [ProcScript.newState] creates an [OpAnimHs.Proc]. */
    public val script: ProcScript = ProcScript("ProcScr_GameEarlyStartUI", listOf(
        yield(),
        call { OpAnimHs.setStatus0(st(it)) },
        call(e.prepareHealthSafety),
        repeat { bool(it, OpAnimHs.healthSafetyFadeIn(st(it), e.hsPalette, e.paletteBuffer)) },
        repeat(delay),
        repeat { bool(it, OpAnimHs.healthSafetyWaitButton(st(it), e.lcd, e.keys)) },
        repeat { bool(it, OpAnimHs.healthSafetyFadeOut(st(it), e.hsPalette, e.paletteBuffer)) },
        repeat(delay),
        label(0x3E7),
        call(e.prepareLogos),
        call { OpAnimHs.setStatus1(st(it)) },
        repeat { apply(it, OpAnimHs.nintendoFadeIn(st(it), e.nintendoPalette, e.paletteBuffer, e.lcd, e.keys, e.softwareReset())) },
        repeat(delay),
        repeat { apply(it, OpAnimHs.nintendoFadeOut(st(it), e.nintendoPalette, e.paletteBuffer, e.keys, e.softwareReset())) },
        repeat(delay),
        call(e.enableKeyComboReset),
        call { OpAnimHs.setStatus2(st(it)) },
        repeat { apply(it, OpAnimHs.intelligentSystemsFadeIn(st(it), e.isPalette, e.paletteBuffer, e.lcd, e.keys, e.softwareReset())) },
        repeat(delay),
        repeat { apply(it, OpAnimHs.intelligentSystemsFadeOut(st(it), e.isPalette, e.paletteBuffer, e.keys, e.softwareReset())) },
        repeat(delay),
        goto(1),
        label(0),
        call(e.enableKeyComboReset),
        call { OpAnimHs.startSkipFade(st(it), e.paletteBuffer, e.paletteBackup) },
        repeat { bool(it, OpAnimHs.skipFade(st(it), e.paletteBuffer, e.paletteBackup)) },
        label(1),
        end(),
    ), newState = { OpAnimHs.Proc() })
}
