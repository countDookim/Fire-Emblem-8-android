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
 * `ToWhite`, `FromWhite`): a proc moves `BLDY` between 0 and 16 in steps of a Q4 speed.
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

    private fun enableBlendInWindows() {
        lcd.setWincntBit(5, true)
        lcd.setWincntBit(13, true)
        lcd.setWincntBit(29, true)
        lcd.setWincntBit(21, true)
    }

    /**
     * Ported from src/bmlib.c: FadeToBlack_OnInit
     */
    public fun fadeToBlackOnInit(proc: Proc) {
        enableBlendInWindows()
        lcd.setBlendConfig(3, 0, 0, 0)
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.setBlendBackdropA(1)
        val st = proc.state as State
        st.speed = 0x10
        st.level = 0
    }

    /**
     * Ported from src/bmlib.c: FadeToCommon_OnLoop
     */
    public fun fadeToCommonOnLoop(proc: Proc) {
        if (lcd.blendY == 0x10) {
            engine.end(proc)
            return
        }
        val st = proc.state as State
        st.level += st.speed
        if (st.level >= 0x100) st.level = 0x100
        lcd.blendY = st.level shr 4
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
        val st = proc.state as State
        st.speed = 0x10
        st.level = 0x100
    }

    /**
     * Ported from src/bmlib.c: FadeFromCommon_OnLoop
     */
    public fun fadeFromCommonOnLoop(proc: Proc) {
        if (lcd.blendY == 0) {
            engine.end(proc)
            return
        }
        val st = proc.state as State
        st.level -= st.speed
        if (st.level <= 0) st.level = 0
        lcd.blendY = st.level shr 4
    }

    /**
     * Ported from src/bmlib.c: FadeToWhite_OnInit
     */
    public fun fadeToWhiteOnInit(proc: Proc) {
        fadeToBlackOnInit(proc)
        lcd.setBlendConfig(2, 0, 0, 0)
    }

    /**
     * Ported from src/bmlib.c: FadeFromWhite_OnInit
     */
    public fun fadeFromWhiteOnInit(proc: Proc) {
        fadeFromBlackOnInit(proc)
        lcd.setBlendConfig(2, 0, 0, 0x10)
    }

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

    private fun begin(script: ProcScript, speed: Int) {
        val proc = engine.start(script, 3)
        (proc.state as State).speed = speed.toShort().toInt()
    }

    private fun beginBlocking(script: ProcScript, speed: Int, parent: Proc) {
        val proc = engine.startBlocking(script, parent) ?: return
        (proc.state as State).speed = speed.toShort().toInt()
    }

    /** Ported from src/bmlib.c: StartFadeToBlack */
    public fun startFadeToBlack(speed: Int): Unit = begin(toBlack, speed)

    /** Ported from src/bmlib.c: StartFadeFromBlack */
    public fun startFadeFromBlack(speed: Int): Unit = begin(fromBlack, speed)

    /** Ported from src/bmlib.c: StartLockingFadeToBlack */
    public fun startLockingFadeToBlack(speed: Int, parent: Proc): Unit =
        beginBlocking(toBlack, speed, parent)

    /** Ported from src/bmlib.c: StartLockingFadeFromBlack */
    public fun startLockingFadeFromBlack(speed: Int, parent: Proc): Unit =
        beginBlocking(fromBlack, speed, parent)

    /** Ported from src/bmlib.c: StartLockingFadeToWhite */
    public fun startLockingFadeToWhite(speed: Int, parent: Proc): Unit =
        beginBlocking(toWhite, speed, parent)

    /** Ported from src/bmlib.c: StartLockingFadeFromWhite */
    public fun startLockingFadeFromWhite(speed: Int, parent: Proc): Unit =
        beginBlocking(fromWhite, speed, parent)

    /** Ported from src/bmlib.c: StartMidFadeToBlack */
    public fun startMidFadeToBlack(): Unit = startFadeToBlack(0x10)

    /** Ported from src/bmlib.c: StartMidFadeFromBlack */
    public fun startMidFadeFromBlack(): Unit = startFadeFromBlack(0x10)
}
