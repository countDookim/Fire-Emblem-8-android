package gbafe.render

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep

/**
 * The blend-register screen fades of `src/bmlib.c` (`ProcScr_FadeToBlack`, `FromBlack`,
 * `ToWhite`, `FromWhite`): a proc moves `BLDY` between 0 and 16 in steps of a Q4 speed (a
 * fixed-point number with 4 fraction bits: 0x10 = one `BLDY` step per frame). To black and
 * from black use the darken effect (`BLDCNT` effect 3), to white and from white the brighten
 * effect (2).
 */
public class ScreenFade(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** `gLCDControlBuffer`. */
    public val lcd: LcdControl,
) {
    /** The proc fields the fades use. */
    public class State {
        /** `unk64`: Q4 speed (step per frame). */
        public var speed: Int = 0

        /** `unk66`: Q4 `BLDY` (0..0x100). */
        public var level: Int = 0
    }

    /** Lets colour effects (bit 5 of each `WININ` / `WINOUT` part) apply in every window part. */
    private fun enableBlendInWindows() {
        lcd.setWincntBit(LcdControl.WIN0_ENABLE_BLEND, true)
        lcd.setWincntBit(LcdControl.WIN1_ENABLE_BLEND, true)
        lcd.setWincntBit(LcdControl.WOBJ_ENABLE_BLEND, true)
        lcd.setWincntBit(LcdControl.WOUT_ENABLE_BLEND, true)
    }

    /**
     * Ported from src/bmlib.c: FadeToBlack_OnInit
     */
    public fun fadeToBlackOnInit(proc: Proc) {
        enableBlendInWindows()
        lcd.setBlendConfig(3, 0, 0, 0)
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.setBlendBackdropA(1)
        val state = proc.state as State
        state.speed = 0x10
        state.level = 0
    }

    /**
     * Ported from src/bmlib.c: FadeToCommon_OnLoop
     *
     * Verified for the fade to black at speed 4 (levels 0..0x100 and the `blendY == 0x10` end);
     * the fade-to-white proc shares this loop but is not traced.
     */
    public fun fadeToCommonOnLoop(proc: Proc) {
        if (lcd.blendY == 0x10) {
            engine.end(proc)
            return
        }
        val state = proc.state as State
        state.level += state.speed
        if (state.level >= 0x100) state.level = 0x100
        lcd.blendY = state.level shr 4
    }

    /**
     * Ported from src/bmlib.c: FadeFromBlack_OnInit
     */
    public fun fadeFromBlackOnInit(proc: Proc) {
        enableBlendInWindows()
        lcd.setBlendConfig(3, 0, 0, 0x10)
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.setBlendTargetB(1, 1, 1, 1, 1)
        lcd.setBlendBackdropA(1)
        val state = proc.state as State
        state.speed = 0x10
        state.level = 0x100
    }

    /**
     * Transcribed from src/bmlib.c: FadeFromCommon_OnLoop (not verified: the only traced fade
     * has speed 0x10, so the level is always a multiple of 16 and rounding is unchecked)
     */
    public fun fadeFromCommonOnLoop(proc: Proc) {
        if (lcd.blendY == 0) {
            engine.end(proc)
            return
        }
        val state = proc.state as State
        state.level -= state.speed
        if (state.level <= 0) state.level = 0
        lcd.blendY = state.level shr 4
    }

    /**
     * Transcribed from src/bmlib.c: FadeToWhite_OnInit (not verified:
     *   no reference run fades to white)
     */
    public fun fadeToWhiteOnInit(proc: Proc) {
        fadeToBlackOnInit(proc)
        lcd.setBlendConfig(2, 0, 0, 0)
    }

    /**
     * Transcribed from src/bmlib.c: FadeFromWhite_OnInit (not verified:
     *   no reference run fades from white)
     */
    public fun fadeFromWhiteOnInit(proc: Proc) {
        fadeFromBlackOnInit(proc)
        lcd.setBlendConfig(2, 0, 0, 0x10)
    }

    /** The shared shape of the four fade procs: init, sleep 0, loop until done, then block. */
    private fun script(name: String, init: ProcFunc, loop: ProcFunc) = ProcScript(
        name,
        listOf(call(init), sleep(0), repeat(loop), block()),
        newState = { State() },
    )

    private val toLoop = ProcFunc { fadeToCommonOnLoop(it) }
    private val fromLoop = ProcFunc { fadeFromCommonOnLoop(it) }

    /** `ProcScr_FadeToBlack`. */
    public val toBlack: ProcScript =
        script("ProcScr_FadeToBlack", ProcFunc { fadeToBlackOnInit(it) }, toLoop)

    /** `ProcScr_FadeFromBlack`. */
    public val fromBlack: ProcScript =
        script("ProcScr_FadeFromBlack", ProcFunc { fadeFromBlackOnInit(it) }, fromLoop)

    /** `ProcScr_FadeToWhite`. */
    public val toWhite: ProcScript =
        script("ProcScr_FadeToWhite", ProcFunc { fadeToWhiteOnInit(it) }, toLoop)

    /** `ProcScr_FadeFromWhite`. */
    public val fromWhite: ProcScript =
        script("ProcScr_FadeFromWhite", ProcFunc { fadeFromWhiteOnInit(it) }, fromLoop)

    /**
     * Whether any of the four fade procs exists.
     *
     * Ported from src/bmlib.c: FadeExists
     */
    public fun fadeExists(): Boolean =
        engine.find(fromBlack) != null || engine.find(toBlack) != null ||
            engine.find(fromWhite) != null || engine.find(toWhite) != null

    /** Starts [script] on proc tree 3 with the Q4 [speed] (a signed 16-bit field, `unk64`). */
    private fun begin(script: ProcScript, speed: Int) {
        val proc = engine.start(script, 3)
        (proc.state as State).speed = speed.toShort().toInt()
    }

    /** Starts [script] blocking [parent] until it ends, with the Q4 [speed]. */
    private fun beginBlocking(script: ProcScript, speed: Int, parent: Proc) {
        val proc = engine.startBlocking(script, parent) ?: return
        (proc.state as State).speed = speed.toShort().toInt()
    }

    /**
     * Ported from src/bmlib.c: StartFadeToBlack
     */
    public fun startFadeToBlack(speed: Int): Unit = begin(toBlack, speed)

    /**
     * Transcribed from src/bmlib.c: StartFadeFromBlack (not verified:
     *   only the locking FromBlack start is traced)
     */
    public fun startFadeFromBlack(speed: Int): Unit = begin(fromBlack, speed)

    /** Ported from src/bmlib.c: StartLockingFadeToBlack */
    public fun startLockingFadeToBlack(speed: Int, parent: Proc): Unit =
        beginBlocking(toBlack, speed, parent)

    /** Ported from src/bmlib.c: StartLockingFadeFromBlack */
    public fun startLockingFadeFromBlack(speed: Int, parent: Proc): Unit =
        beginBlocking(fromBlack, speed, parent)

    /**
     * Transcribed from src/bmlib.c: StartLockingFadeToWhite (not verified:
     *   no reference run fades to white)
     */
    public fun startLockingFadeToWhite(speed: Int, parent: Proc): Unit =
        beginBlocking(toWhite, speed, parent)

    /**
     * Transcribed from src/bmlib.c: StartLockingFadeFromWhite (not verified:
     *   no reference run fades from white)
     */
    public fun startLockingFadeFromWhite(speed: Int, parent: Proc): Unit =
        beginBlocking(fromWhite, speed, parent)

    /** Ported from src/bmlib.c: StartSlowFadeToBlack */
    public fun startSlowFadeToBlack(): Unit = startFadeToBlack(0x04)

    /**
     * Transcribed from src/bmlib.c: StartFastFadeToBlack (not verified:
     *   only the slow variant is traced)
     */
    public fun startFastFadeToBlack(): Unit = startFadeToBlack(0x40)

    /**
     * Breaks [proc] once no fade exists.
     *
     * Ported from src/bmlib.c: WaitForFade
     */
    public fun waitForFade(proc: Proc) {
        if (!fadeExists()) engine.breakProc(proc)
    }

    /**
     * Transcribed from src/bmlib.c: StartMidFadeToBlack (not verified:
     *   never called by a traced path)
     */
    public fun startMidFadeToBlack(): Unit = startFadeToBlack(0x10)

    /**
     * Transcribed from src/bmlib.c: StartMidFadeFromBlack (not verified:
     *   never called by a traced path)
     */
    public fun startMidFadeFromBlack(): Unit = startFadeFromBlack(0x10)
}
