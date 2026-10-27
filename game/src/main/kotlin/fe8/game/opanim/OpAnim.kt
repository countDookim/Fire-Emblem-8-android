package fe8.game.opanim

import fe8.game.GameControl
import gbafe.data.Decompress
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware

/**
 * The op anim (intro cutscene after the logos), `ProcScr_OpAnim` (decomp `src/opanim-main.c`, `src/opanimfx.c`).
 * Partial: only its start (`OpAnimInit`) is ported; a stand-in `PROC_BLOCK` sits where `SetupOpAnimWorldMapfx`
 * would run.
 */
public class OpAnim(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** Display hardware (palette buffer, VRAM, LCD control). */
    public val hardware: BgHardware,
    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus,
    /** The game control proc's owner, for `SetNextGameActionId`. */
    public val gameControl: GameControl,
    /** Graphics and palettes loaded by [init]. */
    public val gfx: Gfx,
) {
    /** ROM data used by [init]: four compressed OBJ images and their 16-colour palettes. */
    public class Gfx(
        /** `Img_OpAnimSplitLine`, `Img_opanim_unk1`, `Img_OpAnimEphEirikaName`, `Img_OpAnimDivlEye`. */
        public val images: List<OpAnimHs.Blob>,
        /** The matching `Pal_*` (16 colours each). */
        public val palettes: List<ShortArray>,
    )

    /** `struct ProcOpAnim` fields used so far. */
    public class State {
        /** `timer`. */
        public var timer: Int = 0
    }

    /** `gOpAnimSt.pal`: colour forced into palette entry 0 by [copyToPal]. */
    public var pal: Int = 0

    /** `ProcScr_OpAnimfxTerminator`: ends the op anim on A, B or Start. */
    public val terminatorScript: ProcScript = ProcScript(
        "ProcScr_OpAnimfxTerminator",
        listOf(yield(), repeat(ProcFunc { terminatorMain(it) }), end()),
    )

    /** The script (partial, see the class doc); [ProcScript.newState] creates a [State]. */
    public val script: ProcScript = ProcScript(
        "ProcScr_OpAnim",
        listOf(
            yield(),
            call(ProcFunc { init(it) }),
            yield(),
            block(), // not ported: SetupOpAnimWorldMapfx and the rest of the cutscene
            end(),
        ),
        newState = { State() },
    )

    /**
     * Resets the display, loads the cutscene's OBJ graphics and palettes, sets the next game action to "played
     * through" and starts the terminator. `Sound_FadeOutBGM` is not modelled.
     *
     * Partially ported from src/opanim-main.c: OpAnimInit
     */
    public fun init(proc: Proc) {
        val hw = hardware
        hw.setupBackgrounds()
        hw.hblank.setPrimary(null)
        (proc.state as State).timer = 0
        pal = 0
        hw.lcd.dispcnt = hw.lcd.dispcnt or DISPCNT_OBJ_1D
        hw.lcd.setDispEnable(0, 0, 0, 0, 0)
        val objOffsets = intArrayOf(0x0000, 0x0100, 0x08C0, 0x1140)
        for (i in 0..3) {
            val img = gfx.images[i]
            Decompress.into(img.data, img.offset, hw.vram, OBJ_VRAM + objOffsets[i])
        }
        for (i in 0..3) copyToPal(gfx.palettes[i], 0x200 + 0x20 * i, 0x20)
        hw.vramFill16(0xFFFF, OBJ_VRAM + 0x7800, 0x800)
        gameControl.setNextGameActionId(GameControl.GAME_ACTION_PLAYED_THROUGH)
        engine.start(terminatorScript, proc)
    }

    /**
     * Copies [size] bytes of [src] to the palette buffer at byte [offset], requests a palette sync and forces entry
     * 0 to [pal].
     *
     * Ported from src/opanimfx.c: CopyToPalOpAnim
     */
    public fun copyToPal(src: ShortArray, offset: Int, size: Int) {
        src.copyInto(hardware.palette, offset shr 1, 0, size shr 1)
        hardware.enablePaletteSync()
        hardware.palette[0] = pal.toShort()
    }

    /**
     * On A, B or Start: sets the next game action to "user skipped", clears [pal] and jumps the op anim to label 99.
     *
     * Partially ported from src/opanimfx.c: OpAnimfxTerminatorMain (label 99 not ported yet: the jump is a no-op)
     */
    public fun terminatorMain(proc: Proc) {
        if (keys.newKeys and (A_BUTTON or B_BUTTON or START_BUTTON) != 0) {
            gameControl.setNextGameActionId(GameControl.GAME_ACTION_USR_SKIPPED)
            pal = 0
            engine.goto(proc.parent!!, 99)
        }
    }

    private companion object {
        const val OBJ_VRAM = 0x10000
        const val DISPCNT_OBJ_1D = 0x40
        const val A_BUTTON = 0x1
        const val B_BUTTON = 0x2
        const val START_BUTTON = 0x8
    }
}
