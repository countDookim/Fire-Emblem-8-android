package fe8.game.opanim

import fe8.game.GameControl
import gbafe.data.Decompress
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.startChild
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware

/**
 * The op anim (intro cutscene after the logos), `ProcScr_OpAnim` (decomp `src/opanim-main.c`, `src/opanimfx.c`).
 * Partial: only its start (`OpAnimInit`, the world map setup and its fade-in) is ported; a stand-in `PROC_BLOCK`
 * sits where `OpAnimUpdateScreen1` would run.
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
    /** World map graphics loaded by [setupWorldMap]. */
    public val worldMap: WorldMapGfx,
) {
    /** ROM data used by [setupWorldMap]. */
    public class WorldMapGfx(
        /** `Img_OpAnimWorldMap`. */
        public val img: OpAnimHs.Blob,
        /** `Tsa_OpAnimWorldMap`. */
        public val tsa: OpAnimHs.Blob,
        /** `Img_OpAnimWorldMapFog`. */
        public val fogImg: OpAnimHs.Blob,
        /** `Tsa_OpAnimWorldMapFog`. */
        public val fogTsa: OpAnimHs.Blob,
        /** `Pal_OpAnimWorldMapFog` (16 colours). */
        public val fogPal: ShortArray,
    )

    /** `struct ProcOpAnimBLDALPHA`. */
    public class BldAlphaState {
        /** `timer`. */
        public var timer: Int = 0
    }

    /** `ProcScr_OpAnimBLDALPHA`: fades the BG palette in from black to white, waits, then blends the fog in. */
    public val bldAlphaScript: ProcScript = ProcScript(
        "ProcScr_OpAnimBLDALPHA",
        listOf(
            call(ProcFunc { (it.state as BldAlphaState).timer = 0 }), // OpAnimBldAlphaInit
            repeat(ProcFunc { bldAlphaUpdateBgPalette(it) }),
            repeat(ProcFunc { bldAlphaDelay(it) }),
            repeat(ProcFunc { bldAlphaMain(it) }),
            end(),
        ),
        newState = { BldAlphaState() },
    )

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
            call(ProcFunc { setupWorldMap(it) }),
            startChild { bldAlphaScript },
            whileTrue(ProcBoolFunc { bldAlphaExists() }),
            block(), // not ported: OpAnimUpdateScreen1 and the rest of the cutscene
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
     * Sets up BG2 (world map) and BG1 (fog, palette 2), blending and display. `StartBgmExt` is not modelled.
     *
     * Partially ported from src/opanim-main.c: SetupOpAnimWorldMapfx
     */
    public fun setupWorldMap(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        intArrayOf(0x0000, 0x4000, 0x8000, 0x0000).forEachIndexed { bg, o -> hw.setTileDataOffset(bg, o) }
        intArrayOf(0xD800, 0xE800, 0xF000, 0xF800).forEachIndexed { bg, o -> hw.setMapDataOffset(bg, o) }
        for (bg in 0..3) {
            hw.setScreenSize(bg, 0)
            lcd.setBgPosition(bg, 0, 0)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
        }
        lcd.dispcnt = lcd.dispcnt and 0xE000.inv() // SetWinEnable(0, 0, 0)
        val w = worldMap
        Decompress.into(w.img.data, w.img.offset, hw.vram, hw.tileDataOffset(2))
        Decompress.into(w.tsa.data, w.tsa.offset, hw.vram, 0xF000)
        hw.palette.fill(0, 0, 16)
        hw.fill(hw.mapBuffer(0), 0)
        hw.enableBgSyncByMask(1)
        Decompress.into(w.fogImg.data, w.fogImg.offset, hw.vram, hw.tileDataOffset(1))
        copyToPal(w.fogPal, 0x40, 0x20)
        Decompress.into(w.fogTsa.data, w.fogTsa.offset, hw.vram, 0xE800)
        for (i in 0 until 0x400) {
            val o = 0xE800 + 2 * i
            val v = ((hw.vram[o].toInt() and 0xFF) or ((hw.vram[o + 1].toInt() and 0xFF) shl 8)) + 0x2000
            hw.vram[o] = v.toByte()
            hw.vram[o + 1] = (v shr 8).toByte()
        }
        hw.enablePaletteSync()
        lcd.setBlendConfig(1, 0, 0x10, 8)
        lcd.setBlendTargetA(0, 0, 0, 0, 1)
        lcd.setBlendTargetB(1, 1, 1, 1, 0)
        (proc.state as State).timer = 0
        lcd.setDispEnable(0, 0, 1, 0, 1)
    }

    /**
     * Fills BG palette 0 with a grey interpolated from black to white over 0x3E frames, then sets [pal] to white,
     * keeps only OBJ as first blend target and breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaUpdateBgPalette
     */
    public fun bldAlphaUpdateBgPalette(proc: Proc) {
        val st = proc.state as BldAlphaState
        val v = linear(0, 0x1F, st.timer, 0x3E)
        val color = (v shl 10) or (v shl 5) or v
        hardware.palette.fill(color.toShort(), 0, 16)
        hardware.enablePaletteSync()
        if (st.timer == 0x3E) {
            st.timer = 0
            pal = 0x7FFF
            hardware.lcd.setBlendTargetA(0, 0, 0, 0, 1)
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * Waits 8 frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaDelay
     */
    public fun bldAlphaDelay(proc: Proc) {
        val st = proc.state as BldAlphaState
        if (st.timer == 8) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * Moves the blend coefficients from (0x10, 0) to (0, 0x10) over 0x10 frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaMain
     */
    public fun bldAlphaMain(proc: Proc) {
        val st = proc.state as BldAlphaState
        val v = linear(0x10, 0, st.timer, 0x10)
        hardware.lcd.blendCoeffA = v and 0xFF
        hardware.lcd.blendCoeffB = (0x10 - v) and 0xFF
        if (st.timer == 0x10) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * True while a `ProcScr_OpAnimBLDALPHA` proc exists.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaExists
     */
    public fun bldAlphaExists(): Boolean = engine.find(bldAlphaScript) != null

    /** `Interpolate(INTERPOLATE_LINEAR, ...)` (src/bmlib.c), the only method used here. */
    private fun linear(lo: Int, hi: Int, x: Int, xMax: Int): Int = if (xMax == 0) hi else lo + (hi - lo) * x / xMax

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
