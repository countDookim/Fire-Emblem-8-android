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

/**
 * Per-proc state of the nation highlight pulse `ProcScr_GmapRmBaPalAnim1`: the fields of
 * `struct ProcGmapRmBaPalAnim` (include/worldmap.h).
 */
public class GmapRmBaPalAnimProc {
    /**
     * `flag` (+0x29, `u8`): set to 1 at init and when a full pulse ends (weight back at 0),
     * cleared by the first frame of the fade-in; the nation border proc waits for 1
     * (`CheckGmapRmBaPalAnim1State`, `GmapRmBorder1_80C29F8`).
     */
    public var flag: Int = 0

    /** `timer` (+0x2A, `s16`): frames into the current fade. */
    public var timer: Int = 0
}

/**
 * The pulsing opacity of the nation highlight on the world map's big map (`src/worldmap_rm.c`:
 * `ProcScr_GmapRmBaPalAnim1`): the highlight sprites of `GmapRmBorder1` are drawn translucent
 * (`OBJ_TRANSLUCENT`, `GmapRmBorder1_PutSprite2`) over BG1 (the blend's second target), and this
 * proc ramps the blend weight up and down over 45 frames each way, forever, until ended.
 *
 * @param engine the proc scheduler
 * @param lcd the blend registers
 * @param getWmFaceBlendAmt `GetWMFaceBlendAmt()` (src/worldmap_face.c:278): the face control
 *   proc's blend weight (`blendAmt >> 1`), or 0x10 if there is none; the end callback restores it
 */
public class WorldMapRmBaPalAnim(
    private val engine: ProcEngine,
    private val lcd: LcdControl,
    private val getWmFaceBlendAmt: () -> Int,
) {
    /**
     * `ProcScr_GmapRmBaPalAnim1`: init, then forever fade in (`Loop1`), pause 2 frames, fade out
     * (`Loop2`), pause 2 frames.
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

    /** The [GmapRmBaPalAnimProc] state of a pulse proc. */
    private fun state(proc: Proc) = proc.state as GmapRmBaPalAnimProc

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmBaPalAnim1_End (not verified: the next frame's
     * MergeOut overwrites the restored blend before the registers show it)
     *
     * End callback: hands the blend weights back to the face control (`GetWMFaceBlendAmt`), so
     * the world map face blend is not left at the pulse's value.
     */
    public fun gmapRmBaPalAnim1End(proc: Proc) {
        val value = getWmFaceBlendAmt()
        lcd.setBlendConfig(0, value, 0x10 - value, 0)
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBaPalAnim1_Init
     *
     * Sets no first blend target and BG1 as the second, weights 0 / 0x10, timer 0 and
     * [GmapRmBaPalAnimProc.flag] 1.
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
     *
     * `PROC_REPEAT` callback: the fade-in, 45 frames with the weight rising from 0 to 0x10
     * (`_DivArm2(timer, 45, 0)`, linear), ending at 0x10. Clears [GmapRmBaPalAnimProc.flag].
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
     *
     * `PROC_REPEAT` callback: the fade-out, the weight falling from 0x10 back to 0; sets
     * [GmapRmBaPalAnimProc.flag] again when it ends.
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
     * Starts the pulse (the nation border proc does, when the first highlight appears).
     *
     * @param parent blocked by the new proc; the proc is a root of tree 3 when null
     */
    public fun startGmapRmBaPalAnim1(parent: Proc?): Proc =
        if (parent != null) {
            checkNotNull(engine.startBlocking(script, parent))
        } else {
            engine.start(script, 3)
        }

    /**
     * Transcribed from src/worldmap_rm.c: EndGmapRmBaPalAnim1 (not verified: no traced call).
     *
     * Ends the pulse (the end callback restores the face blend).
     */
    public fun endGmapRmBaPalAnim1() {
        engine.endEach(script)
    }

    /**
     * Transcribed from src/worldmap_rm.c: CheckGmapRmBaPalAnim1Exists (not verified: no traced
     * call)
     *
     * Whether the pulse is running.
     */
    public fun checkGmapRmBaPalAnim1Exists(): Boolean = engine.find(script) != null

    /**
     * Ported from src/worldmap_rm.c: CheckGmapRmBaPalAnim1State
     *
     * @return [GmapRmBaPalAnimProc.flag] of the pulse (1 at the start or after a full pulse), 0
     *   while it is mid-pulse or when there is no pulse
     */
    public fun checkGmapRmBaPalAnim1State(): Int {
        val proc = engine.find(script) ?: return 0
        return state(proc).flag
    }

    private companion object {
        /** Frames of one fade direction (the `45` in `GmapRmBaPalAnim1_Loop1` / `Loop2`). */
        const val FADE_FRAMES = 45
    }
}
