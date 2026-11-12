package fe8.game.worldmap

import fe8.game.RomView
import fe8.game.scene.FaceState
import fe8.game.scene.TalkFace
import fe8.game.scene.TalkFaceHost
import fe8.game.scene.TalkHost
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware

/**
 * `struct WMFaceWrapper`: one of the two portrait slots shown over the world map (one on each side
 * of the screen) and the state of its slide-in / slide-out animation.
 */
public class WmFaceWrapper {
    /** `x` (+0, s16): resting x pixel position of the portrait sprite (low byte of `config`). */
    public var x: Int = 0

    /**
     * `y` (+2, s16): not a coordinate but packed slide state. Low byte: frame counter of the slide
     * (0..15); 0x100: slides in from the right of [WmFaceWrapper.x] and out to the left, 0x200 the
     * opposite way (see [WorldMapFace.wmFaceCtrlLoopExt]); 0x400: portrait flipped;
     * 0x800: sliding in; 0x1000: sliding out.
     */
    public var y: Int = 0

    /** `faceProc` (+4): the `E_FACE` proc of the sprite on this slot, or null when free. */
    public var faceProc: Proc? = null

    /** `faceId` (+8, u16): the portrait shown here; 0xFFFF when never used. */
    public var faceId: Int = 0xFFFF

    /**
     * `unk_0a` (+0xA, s8): +1 while the portrait fades in, -1 while it fades out, 0 otherwise.
     * The decomp only guesses "fade direction"; the values are those written by `WmDrawFace`,
     * `WmClearFace` and `HideWmFace`, and read by `HandleWMFaceFade`.
     */
    public var unk0a: Int = 0
}

/** `struct WMFaceHolderProc`: state of the child proc that owns the two portrait slots. */
public class WmFaceHolderState {
    /** `faceWrapper[2]` (+0x2C, +0x38). */
    public val faceWrapper: Array<WmFaceWrapper> = arrayOf(WmFaceWrapper(), WmFaceWrapper())
}

/** `struct WMFaceCtrlProc`: state of the proc that animates the world map portraits. */
public class WmFaceCtrlState {
    /** `timer` (+0x2A, u16): index into an unused table in the decomp; has no effect. */
    public var timer: Int = 0

    /** `faceHolderProc` (+0x2C): the child holding both [WmFaceWrapper]s. */
    public var faceHolderProc: Proc? = null

    /** `increment` (+0x30, s8): per-frame step of [blendAmt]: +2 fading in, -2 out, 0 idle. */
    public var increment: Int = 0

    /**
     * `blendAmt` (+0x31, u8): fade progress 0..0x20; half of it is the blend coefficient (0..16)
     * given to `SetBlendConfig`.
     */
    public var blendAmt: Int = 0
}

/**
 * The world map portraits of `src/worldmap_face.c`: `ProcScr_WorldMapFaceCtrl` and its child
 * `ProcScr_WorldMapFaceHolder`. While a portrait slides in or out (16 frames) it is drawn as a tile
 * map on BG1 (BG2 when `state_3`) and cross-faded with alpha blending; the `E_FACE` sprite
 * beneath it is hidden during the slide and shown once it ends. Reference comparisons cover only
 * the observed state-3 branch; other states and continuous event timing remain unverified.
 *
 * @param engine the proc scheduler
 * @param hw BG hardware (map buffers, VRAM, palette, LCD registers)
 * @param gmData `gGMData` (`state_3`)
 * @param rom the ROM (the `PutFaceOnBackGround` tile table)
 * @param face the face procs of `src/face.c`
 * @param talkHost decompression and palette services
 * @param faceHost portrait table access
 */
public class WorldMapFace(
    private val engine: ProcEngine,
    private val hw: BgHardware,
    private val gmData: GmData,
    private val rom: RomView,
    private val face: TalkFace,
    private val talkHost: TalkHost,
    private val faceHost: TalkFaceHost,
) {
    /** Receives the sub-tile scroll from [faceTileColumn] (the C passes `&bgX`). */
    private val scrollScratch = IntArray(1)

    /** `gUnknown_080D77FC` of src/face.c: 12x10 tile ids of a 96x80 portrait, 0xFFFF = empty. */
    private val tileTable = IntArray(120) { rom.u16(TILE_TABLE + 2 * it) }

    /** `ProcScr_WorldMapFaceHolder`: initialises the slots, then blocks until its parent ends. */
    public val holderScript: ProcScript = ProcScript(
        "ProcScr_WorldMapFaceHolder",
        listOf(
            mark(PROC_MARK_WMSTUFF),
            call(ProcFunc { worldMapFaceHolderInit(it) }),
            block(),
            end(),
        ),
    ) { WmFaceHolderState() }

    /** `ProcScr_WorldMapFaceCtrl`: starts the holder child, then runs [wmFaceCtrlLoop]. */
    public val ctrlScript: ProcScript = ProcScript(
        "ProcScr_WorldMapFaceCtrl",
        listOf(
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { }),
            call(ProcFunc { wmFaceCtrlInit(it) }),
            repeat(ProcFunc { wmFaceCtrlLoop(it) }),
            end(),
        ),
    ) { WmFaceCtrlState() }

    /**
     * `gProcScr_E_FACE_ExtraFrame`: the `E_FACE` portrait sprite proc used for world map faces,
     * without the camera-move wait (that wait is unverified).
     */
    public val extraFrameScript: ProcScript = ProcScript(
        "gProcScr_E_FACE_ExtraFrame",
        listOf(
            sleep(1),
            call(ProcFunc { face.faceOnInit(it) }),
            call(ProcFunc { face.faceOnIdle(it) }),
            repeat(ProcFunc { face.faceOnIdle(it) }),
            end(),
        ),
    ) { FaceState() }

    /**
     * `SetupFaceGfxData(gUnknown_08A3D728)` (src/worldmap_main.c, called from `WorldMap_Init`,
     * which is not ported yet): reads the four face slots' VRAM tile and palette targets from the
     * ROM (eight bytes per `FaceVramEntry`).
     */
    public fun setupFaceConfig() {
        val tiles = IntArray(4) { rom.u32(FACE_CONFIG + 8 * it) }
        val pals = IntArray(4) { rom.u32(FACE_CONFIG + 8 * it + 4) }
        face.setupFaceGfxData(tiles, pals)
    }

    /**
     * Transcribed from src/worldmap_face.c: WorldMapFaceHolder_Init (not verified: fresh-state
     * trace only)
     *
     * Both wrappers: no face, zero position, `faceId` 0xFFFF (the decomp ORs 0xFFFF in).
     */
    public fun worldMapFaceHolderInit(proc: Proc) {
        for (w in (proc.state as WmFaceHolderState).faceWrapper) {
            w.faceProc = null
            w.unk0a = 0
            w.y = 0
            w.x = 0
            w.faceId = 0xFFFF
        }
    }

    /**
     * Transcribed from src/worldmap_face.c: WMFaceCtrl_Init (not verified: fresh-state trace only)
     *
     * Starts the holder child and zeroes the fade state (the decomp also zeroes the unused
     * `unk_33` and `unk_34`).
     */
    public fun wmFaceCtrlInit(proc: Proc) {
        val s = proc.state as WmFaceCtrlState
        s.timer = 0
        s.faceHolderProc = engine.start(holderScript, proc)
        s.increment = 0
        s.blendAmt = 0
    }

    /**
     * Ported from src/worldmap_face.c: sub_80B826C
     *
     * Splits the pixel x of a portrait into whole BG tiles and the leftover pixels: the tile
     * map is drawn at the tile column and the BG scroll register makes up the remainder, so the
     * slide moves smoothly in pixels. Portraits are positioned relative to x = 0x30 (the decomp
     * gives no name for this offset); negative inputs round toward the left tile.
     *
     * `sub_80B826C` in the decomp.
     *
     * @return the BG tile column of [xIn]; `out[0]` receives the sub-tile scroll
     */
    public fun faceTileColumn(xIn: Int, out: IntArray): Int {
        val a = xIn - 0x30
        val b = if (a < 0) xIn - 0x29 else a
        val x = b shr 3
        out[0] = x * 8 - a
        return x
    }

    /**
     * Ported from src/worldmap_face.c: GetWMFaceBg
     *
     * @return BG2 when `gGMData.state` bit 3 (`state_3`) is set, else BG1
     */
    public fun getWmFaceBg(): Int = if (state3()) 2 else 1

    /**
     * Ported from src/worldmap_face.c: GetWMFaceVramOffset
     *
     * @return the VRAM byte offset of the portrait tiles: 0x5000 with `state_3`, else 0x1800
     */
    public fun getWmFaceVramOffset(): Int = if (state3()) 0x5000 else 0x1800

    /** `gGMData.state.bits.state_3`; the meaning of that flag is unknown. */
    private fun state3() = (gmData.stateRaw shr 3) and 1 != 0

    /**
     * Ported from src/face.c: PutFaceOnBackGround
     *
     * Writes the 12x10 tile block of a portrait into [map] at [at] (rows 0x20 apart), tile ids
     * offset by [base] (mirrored with the h-flip bit when [flip]). Cells outside [map] are skipped.
     */
    public fun putFaceOnBackGround(map: ShortArray, at: Int, base: Int, flip: Boolean) {
        for (i in 0 until 10) {
            for (j in 0 until 12) {
                val src = tileTable[i * 12 + if (flip) 11 - j else j]
                val value = when {
                    src == 0xFFFF -> 0
                    flip -> src + base + 0x400
                    else -> src + base
                }
                val index = at + i * 0x20 + j
                if (index in map.indices) map[index] = value.toShort()
            }
        }
    }

    /**
     * Ported from src/worldmap_face.c: sub_80B82C8
     *
     * Puts the portrait on BG [bg] at pixel position ([xIn], [yIn]) and scrolls the sub-tile part.
     * [offset] is the VRAM offset the tiles were decompressed to; `offset shr 5` is the first
     * tile number (one tile is 0x20 bytes) and 0x4000 selects palette bank 4 in the map entries.
     *
     * `sub_80B82C8` in the decomp.
     */
    public fun drawFaceOnBg(bg: Int, offset: Int, xIn: Int, yIn: Int, flip: Int) {
        val x = faceTileColumn(xIn, scrollScratch)
        val y = yIn / 8
        val yRest = y * 8 - yIn
        putFaceOnBackGround(hw.mapBuffer(bg), y * 0x20 + x, (offset shr 5) + 0x4000, flip != 0)
        hw.enableBgSyncByMask(1 shl bg)
        hw.lcd.setBgPosition(bg, scrollScratch[0], yRest)
    }

    /**
     * Ported from src/worldmap_face.c: HandleWMFaceFade
     *
     * Steps the cross-fade: sets BLDALPHA from [WmFaceCtrlState.blendAmt], then adds
     * `increment`. At 0 the faces that were fading out (`unk_0a == -1`) are ended and their slots
     * freed; at 0x20 the faces that were fading in (`unk_0a == 1`) lose their `FACE_DISP_BLEND`
     * flag, so they are drawn opaque. Either end stops the fade.
     */
    public fun handleWmFaceFade(proc: Proc) {
        val s = proc.state as WmFaceCtrlState
        val holder = s.faceHolderProc!!.state as WmFaceHolderState
        hw.lcd.setBlendConfig(1, s.blendAmt shr 1, 0x10 - (s.blendAmt shr 1), 0)
        s.blendAmt = (s.blendAmt + s.increment) and 0xFF
        if (s.blendAmt == 0) {
            for (i in 0 until 2) {
                val w = holder.faceWrapper[i]
                if (w.faceProc != null && w.unk0a == -1) {
                    endFaceById(i)
                    w.unk0a = 0
                    w.faceProc = null
                }
            }
            s.increment = 0
        }
        if (s.blendAmt == 0x20) {
            for (i in 0 until 2) {
                val w = holder.faceWrapper[i]
                val faceProc = w.faceProc
                if (faceProc != null && w.unk0a == 1) {
                    val state = faceProc.state as? FaceState ?: continue
                    face.setFaceDisplayBits(faceProc, state.displayBits and DISP_BLEND.inv())
                    w.unk0a = 0
                }
            }
            s.increment = 0
        }
    }

    /**
     * Ported from src/worldmap_face.c: WMFaceCtrl_LoopExt
     *
     * Slides each face in (0x800 phase) or out (0x1000 phase) over 16 frames on a quadratic
     * ease (offset `d * d * 0x20 / 256` pixels, `d` counting down from 16), redrawing the BG tile
     * map each frame (`action` 1) and clearing it when the slide ends (`action` 2). The sprite
     * (`E_FACE`) stays hidden during the slide and is un-hidden when the slide in ends; the
     * slide out hides it on its first frame.
     */
    public fun wmFaceCtrlLoopExt(proc: Proc) {
        val holder = (proc.state as WmFaceCtrlState).faceHolderProc!!.state as WmFaceHolderState
        val bg = getWmFaceBg()
        for (i in 0 until 2) {
            val w = holder.faceWrapper[i]
            val faceProc = w.faceProc ?: continue
            // A skipped talk ends the face procs but leaves `faceWrapper.faceProc` stale; the
            // freed slot may already run another proc (the original writes into it unchecked).
            val state = faceProc.state as? FaceState ?: continue
            var action = 0
            val x = w.x
            if (w.y and 0x800 != 0 && (w.y and 0xFF) < 0x10) {
                val d = 0x10 - (w.y and 0xFF)
                val shift = d * 0x20 * d / 256
                if (w.y and 0x100 != 0) {
                    state.xPos = x + shift
                    action = 1
                }
                if (w.y and 0x200 != 0) {
                    state.xPos = x - shift
                    action = 1
                }
                w.y = (w.y + 1) and 0xFFFF
                if ((w.y and 0xFF) > 0xF) {
                    action = 2
                    face.setFaceDisplayBits(faceProc, state.displayBits and DISP_HIDDEN.inv())
                    w.y = w.y and 0x800.inv()
                }
            }
            if (w.y and 0x1000 != 0 && (w.y and 0xFF) < 0x10) {
                if ((w.y and 0xFF) == 0) {
                    face.setFaceDisplayBits(faceProc, state.displayBits or DISP_HIDDEN)
                }
                val d = 0x10 - (w.y and 0xFF)
                val shift = d * 0x20 * d / 256
                if (w.y and 0x100 != 0) {
                    state.xPos = (x - 0x20) + shift
                    action = 1
                }
                if (w.y and 0x200 != 0) {
                    state.xPos = (x + 0x20) - shift
                    action = 1
                }
                w.y = (w.y + 1) and 0xFFFF
                if ((w.y and 0xFF) > 0xF) action = 2
            }
            when (action) {
                1 -> {
                    hw.fill(hw.mapBuffer(bg), 0)
                    drawFaceOnBg(
                        getWmFaceBg(), getWmFaceVramOffset(), state.xPos, 0x1C,
                        (w.y shr 10) and 1,
                    )
                    hw.enableBgSyncByMask(1 shl bg)
                }
                2 -> {
                    hw.fill(hw.mapBuffer(bg), 0)
                    hw.enableBgSyncByMask(1 shl bg)
                }
            }
        }
    }

    /**
     * Ported from src/worldmap_face.c: WMFaceCtrl_Loop
     *
     * Advances the unused timer (the decomp wraps it at the 0xFF terminator of a 54-entry table
     * whose values are never read), slides the faces and steps a running fade.
     */
    public fun wmFaceCtrlLoop(proc: Proc) {
        val s = proc.state as WmFaceCtrlState
        s.timer++
        if (s.timer >= TIMER_END) s.timer = 0
        wmFaceCtrlLoopExt(proc)
        if (s.increment != 0) handleWmFaceFade(proc)
    }

    /**
     * Transcribed from src/worldmap_face.c: StartWMFaceCtrl (not verified: the proc fields of its
     * init are compared with the trace, the parent link is not)
     *
     * @param parent the world map main proc; a root of tree 0 when null
     */
    public fun startWmFaceCtrl(parent: Proc?): Proc =
        if (parent != null) engine.start(ctrlScript, parent) else engine.start(ctrlScript, 0)

    /** Ported from src/worldmap_face.c: EndWMFaceCtrl */
    public fun endWmFaceCtrl() {
        engine.endEach(ctrlScript)
    }

    /**
     * Ported from src/worldmap_face.c: GetWMFaceBlendAmt
     *
     * @return the current blend coefficient (0..16), 16 when no controller runs
     */
    public fun getWmFaceBlendAmt(): Int {
        val proc = engine.find(ctrlScript) ?: return 0x10
        return (proc.state as WmFaceCtrlState).blendAmt shr 1
    }

    /**
     * The BLDCNT targets used by [wmDrawFace] and [wmClearFace]: the portrait BG (BG2 with
     * `state_3`, else BG1) is blended against the other of BG1 / BG2 plus BG3 and sprites. The
     * argument order of `SetBlendTargetA/B` (src/hardware.c) is BG0..BG3, then OBJ.
     */
    private fun setBlendTargets() {
        if (state3()) {
            hw.lcd.setBlendTargetA(0, 0, 1, 0, 0)
            hw.lcd.setBlendTargetB(0, 1, 0, 1, 1)
        } else {
            hw.lcd.setBlendTargetA(0, 1, 0, 0, 0)
            hw.lcd.setBlendTargetB(0, 0, 1, 1, 1)
        }
    }

    /**
     * Transcribed from src/face.c: EndFaceById (not verified: checked through the faded-out faces
     * of [handleWmFaceFade]): frees the slot and ends the face proc.
     */
    public fun endFaceById(slot: Int) {
        val proc = face.gFaces[slot] ?: return
        face.gFaces[slot] = null
        engine.end(proc)
    }

    /**
     * `StartFace2` of src/face.c (transcribed, checked through the proc fields of the faces
     * [wmDrawFace] starts): a face without mouth and blink children on
     * `gProcScr_E_FACE_ExtraFrame`. Returns null when the slot already holds a face. [disp] is the
     * `FACE_DISP_*` bit set; with `FACE_DISP_BIT_13` the portrait palette is cleared instead of
     * loaded.
     */
    private fun startFace2(slot: Int, fid: Int, x: Int, y: Int, disp: Int): Proc? {
        if (face.gFaces[slot] != null) return null
        val proc = engine.start(extraFrameScript, 5)
        face.gFaces[slot] = proc
        if (disp and DISP_BIT_13 != 0) {
            val base = (face.paletteIdOf(slot) + 0x10) * 16
            hw.palette.fill(0, base, base + 16)
            hw.enablePaletteSync()
        } else {
            talkHost.applyPalette(faceHost.portraitPalette(fid), face.paletteIdOf(slot) + 0x10)
        }
        val state = proc.state as FaceState
        state.faceSlot = slot
        state.faceId = fid
        state.spriteLayer = 5
        state.xPos = x
        state.yPos = y
        face.setFaceDisplayBits(proc, disp)
        return proc
    }

    /**
     * Ported from src/worldmap_face.c: WmDrawFace
     *
     * Starts the portrait of [faceId] in [faceSlot] (if empty): tiles to the BG, palette 4, a
     * hidden blended `E_FACE` and the slide state in the wrapper. Used by the world map event
     * commands that show a speaker's portrait.
     *
     * @param config low byte: x; 0x100 / 0x200: slide direction; 0x400: flipped; 0x2000 / 0x4000:
     *   sprite layer (3 by default, 4, 5 or, with both, 6); 0x8000: `FACE_DISP_BIT_13`, which
     *   leaves the sprite palette empty
     */
    public fun wmDrawFace(faceSlot: Int, faceId: Int, config: Int) {
        val proc = engine.find(ctrlScript)!!
        val s = proc.state as WmFaceCtrlState
        val w = (s.faceHolderProc!!.state as WmFaceHolderState).faceWrapper[faceSlot]
        setBlendTargets()
        val bg = getWmFaceBg()
        val offset = getWmFaceVramOffset()
        if (w.faceProc == null) {
            w.faceId = faceId
            w.x = config and 0xFF
            w.y = ((config and 0xFF00) + 0x800) and 0xFFFF
            talkHost.decompressToVram(faceHost.portraitImage(faceId), VRAM_BASE + offset)
            talkHost.applyPalette(faceHost.portraitPalette(faceId), 4)
            hw.fill(hw.mapBuffer(bg), 0)
            var disp = DISP_HIDDEN or DISP_BLEND or KIND_96X80 or HLAYER_1
            if (config and 0x400 != 0) disp = disp or DISP_FLIPPED
            if (config and 0x8000 != 0) disp = disp or DISP_BIT_13
            val faceProc = startFace2(faceSlot, faceId, w.x, 28, disp)
            w.faceProc = faceProc
            val layer = when {
                config and 0x6000 == 0x6000 -> 6
                config and 0x4000 != 0 -> 5
                config and 0x2000 != 0 -> 4
                else -> 3
            }
            (faceProc!!.state as FaceState).spriteLayer = layer
            face.setFaceBlinkControl(faceProc, 5)
            w.unk0a = 1
            s.increment = 2
            if (s.blendAmt == 0x20) {
                s.blendAmt = 0
                hw.lcd.setBlendConfig(1, 0, 0x10, 0)
            }
        }
    }

    /**
     * Ported from src/worldmap_face.c: WmClearFace
     *
     * Starts the slide out of the portrait in [faceSlot] (if shown and not already leaving):
     * reloads the tiles and palette so the BG copy can be drawn again, sets the 0x1000 phase and
     * starts a fade out.
     *
     * @param config 0x100 / 0x200: slide direction (cleared when 0)
     */
    public fun wmClearFace(faceSlot: Int, config: Int) {
        val proc = engine.find(ctrlScript)!!
        val s = proc.state as WmFaceCtrlState
        val w = (s.faceHolderProc!!.state as WmFaceHolderState).faceWrapper[faceSlot]
        setBlendTargets()
        val bg = getWmFaceBg()
        val offset = getWmFaceVramOffset()
        if (w.faceProc != null && w.y and 0x1000 == 0) {
            val id = w.faceId.toShort().toInt()
            talkHost.decompressToVram(faceHost.portraitImage(id), VRAM_BASE + offset)
            talkHost.applyPalette(faceHost.portraitPalette(id), 4)
            hw.fill(hw.mapBuffer(bg), 0)
            var tmp = w.y and 0xFCFF
            if (config and 0x300 != 0) tmp = tmp or (config and 0x300)
            tmp = tmp and 0xFF00
            w.y = (tmp + 0x1000) and 0xFFFF
            w.unk0a = -1
            s.increment = -2
            if (s.blendAmt == 0) {
                s.blendAmt = 0x20
                hw.lcd.setBlendConfig(1, 0x10, 0, 0)
            }
        }
    }

    /**
     * Transcribed from src/worldmap_face.c: HideWmFace (not verified: the world map exit checks
     * only the blend targets; no face is shown there, so the loop over the wrappers never runs)
     */
    public fun hideWmFace() {
        val proc = engine.find(ctrlScript)!!
        val s = proc.state as WmFaceCtrlState
        hw.lcd.setBlendTargetA(0, 0, 0, 0, 0)
        hw.lcd.setBlendTargetB(0, 0, 0, 1, 0)
        for (w in (s.faceHolderProc!!.state as WmFaceHolderState).faceWrapper) {
            val faceProc = w.faceProc
            if (faceProc != null && w.y and 0x1000 == 0) {
                // Stale after a skipped talk ended the face (see wmFaceCtrlLoopExt).
                val state = faceProc.state as? FaceState ?: continue
                face.setFaceDisplayBits(faceProc, state.displayBits or DISP_HIDDEN)
                w.y = ((w.y and 0xFF.inv()) + 0x1000) and 0xFFFF
                w.unk0a = -1
                s.increment = -2
                if (s.blendAmt == 0) {
                    s.blendAmt = 0x20
                    hw.lcd.setBlendConfig(0, 0x10, 0, 0)
                }
            }
        }
    }

    /**
     * Constants of the world map faces. The `DISP_*` ones are the `FACE_DISP_*` bits of
     * include/face.h.
     */
    public companion object {
        /** `PROC_MARK_WMSTUFF` (include/proc.h): proc mark of world map procs. */
        public const val PROC_MARK_WMSTUFF: Int = 8

        /** Start of VRAM (the BG tile data is at its start). */
        private const val VRAM_BASE = 0x06000000

        /** ROM address of `gUnknown_08A3D728`, the world map's four `FaceVramEntry`s. */
        private const val FACE_CONFIG = 0x08A3D728

        /** ROM address of `gUnknown_080D77FC`, the portrait tile layout. */
        private const val TILE_TABLE = 0x080D77FC

        /** Index of the 0xFF terminator in the decomp's unused timer table. */
        private const val TIMER_END = 54

        /** `FACE_DISP_FLIPPED`. */
        private const val DISP_FLIPPED = 1

        /** `FACE_DISP_KIND(FACE_96x80)`: `FACE_96x80` is 2. */
        private const val KIND_96X80 = 2

        /** `FACE_DISP_HLAYER(FACE_HLAYER_1)`: `FACE_HLAYER_1` is `1 << 1`. */
        private const val HLAYER_1 = 2 shl 6

        /** `FACE_DISP_BLEND`. */
        private const val DISP_BLEND = 1 shl 10

        /** `FACE_DISP_BIT_13`: meaning unknown beyond clearing the palette in `StartFace2`. */
        private const val DISP_BIT_13 = 1 shl 13

        /** `FACE_DISP_HIDDEN`. */
        private const val DISP_HIDDEN = 1 shl 14
    }
}
