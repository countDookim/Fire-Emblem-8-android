package fe8.game.worldmap

import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.LcdControl

/** The fields of `struct ProcGmapRmBaPalAnim`. */
public class GmapRmBaPalAnimProc {
    /** `flag` (+0x29, `u8`): 1 while the blend is at its start value, 0 while it fades in. */
    public var flag: Int = 0

    /** `timer` (+0x2A, `s16`): frames into the current fade. */
    public var timer: Int = 0
}

/**
 * The blend pulse of the world map's big map BG (`src/worldmap_rm.c`: `ProcScr_GmapRmBaPalAnim1`).
 *
 * @param engine the proc scheduler
 * @param lcd the blend registers
 * @param getWmFaceBlendAmt `GetWMFaceBlendAmt()`, the blend the end callback restores
 */
public class WorldMapRmBaPalAnim(
    private val engine: ProcEngine,
    private val lcd: LcdControl,
    private val getWmFaceBlendAmt: () -> Int,
) {
    /**
     * `ProcScr_GmapRmBaPalAnim1`.
     *
     * Transcribed from src/worldmap_rm.c: ProcScr_GmapRmBaPalAnim1 (not verified: the script order
     * is checked through the proc cursor, but no unit of it is ported here)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GmapRmBaPalAnim1",
        listOf(
            name("Gmap RM ba pal anim"),
            mark(WorldMapRm.PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapRmBaPalAnim1End(it) }),
            call(ProcFunc { gmapRmBaPalAnim1Init(it) }),
            sleep(1),
            label(0),
            repeat(ProcFunc { gmapRmBaPalAnim1Loop1(it) }),
            sleep(2),
            repeat(ProcFunc { gmapRmBaPalAnim1Loop2(it) }),
            sleep(2),
            goto(0),
            end(),
        ),
    ) { GmapRmBaPalAnimProc() }

    private fun state(proc: Proc) = proc.state as GmapRmBaPalAnimProc

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmBaPalAnim1_End (not verified: the next frame's
     * MergeOut overwrites the restored blend before the registers show it)
     */
    public fun gmapRmBaPalAnim1End(proc: Proc) {
        val value = getWmFaceBlendAmt()
        lcd.setBlendConfig(0, value, 0x10 - value, 0)
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBaPalAnim1_Init
     */
    public fun gmapRmBaPalAnim1Init(proc: Proc) {
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(0, 1, 0, 0, 0)
        lcd.setBlendConfig(0, 0, 0x10, 0)
        val s = state(proc)
        s.timer = 0
        s.flag = 1
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBaPalAnim1_Loop1
     */
    public fun gmapRmBaPalAnim1Loop1(proc: Proc) {
        val s = state(proc)
        if (++s.timer < FADE_FRAMES) {
            val coeff = Spline.divArm2(s.timer, FADE_FRAMES, 0)
            lcd.setBlendConfig(0, Spline.divArm(0x1000, coeff * 0x10), 0x10, 0)
            s.flag = 0
        } else {
            lcd.setBlendConfig(0, 0x10, 0x10, 0)
            s.timer = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBaPalAnim1_Loop2
     */
    public fun gmapRmBaPalAnim1Loop2(proc: Proc) {
        val s = state(proc)
        if (++s.timer < FADE_FRAMES) {
            val coeff = Spline.divArm2(s.timer, FADE_FRAMES, 0)
            lcd.setBlendConfig(0, 0x10 - Spline.divArm(0x1000, coeff * 0x10), 0x10, 0)
        } else {
            lcd.setBlendConfig(0, 0, 0x10, 0)
            s.timer = 0
            s.flag = 1
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: StartGmapRmBaPalAnim1
     *
     * @param parent blocked by the new proc; the proc is a root of tree 3 when null
     */
    public fun startGmapRmBaPalAnim1(parent: Proc?): Proc =
        if (parent != null) {
            checkNotNull(engine.startBlocking(script, parent))
        } else {
            engine.start(script, 3)
        }

    /** Transcribed from src/worldmap_rm.c: EndGmapRmBaPalAnim1 (not verified: no traced call). */
    public fun endGmapRmBaPalAnim1() {
        engine.endEach(script)
    }

    /**
     * Transcribed from src/worldmap_rm.c: CheckGmapRmBaPalAnim1Exists (not verified: no traced
     * call)
     */
    public fun checkGmapRmBaPalAnim1Exists(): Boolean = engine.find(script) != null

    /**
     * Ported from src/worldmap_rm.c: CheckGmapRmBaPalAnim1State
     */
    public fun checkGmapRmBaPalAnim1State(): Int {
        val proc = engine.find(script) ?: return 0
        return state(proc).flag
    }

    private companion object {
        /** Frames of one fade direction. */
        const val FADE_FRAMES = 45
    }
}
