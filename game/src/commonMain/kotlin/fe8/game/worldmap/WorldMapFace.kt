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

/** `struct WMFaceWrapper`: one portrait slot of the world map. */
public class WmFaceWrapper {
    /** `x` (+0, s16). */
    public var x: Int = 0

    /**
     * `y` (+2): slide counter (low byte), direction (0x100 / 0x200), flip (0x400), phase (0x800
     * in, 0x1000 out).
     */
    public var y: Int = 0

    /** `faceProc` (+4): the `E_FACE` proc, or null. */
    public var faceProc: Proc? = null

    /** `faceId` (+8). */
    public var faceId: Int = 0xFFFF

    /** `unk_0a` (+0xA, s8): +1 fading in, -1 fading out. */
    public var unk0a: Int = 0
}

/** `struct WMFaceHolderProc`: the two wrappers. */
public class WmFaceHolderState {
    /** `faceWrapper[2]` (+0x2C, +0x38). */
    public val faceWrapper: Array<WmFaceWrapper> = arrayOf(WmFaceWrapper(), WmFaceWrapper())
}

/** `struct WMFaceCtrlProc`. */
public class WmFaceCtrlState {
    /** `timer` (+0x2A). */
    public var timer: Int = 0

    /** `faceHolderProc` (+0x2C). */
    public var faceHolderProc: Proc? = null

    /** `increment` (+0x30, s8). */
    public var increment: Int = 0

    /** `blendAmt` (+0x31, u8). */
    public var blendAmt: Int = 0
}

/**
 * The world map portraits of `src/worldmap_face.c`: `ProcScr_WorldMapFaceCtrl` and its child
 * `ProcScr_WorldMapFaceHolder`. Portraits slide in over BG1 (BG2 when `state_3`) as tile maps and
 * become `E_FACE` sprites afterwards. Reference comparisons cover only the observed state-3
 * branch; other states and continuous event timing remain unverified.
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
    private val scrollScratch = IntArray(1)
    private val tileTable = IntArray(120) { rom.u16(TILE_TABLE + 2 * it) }

    /** `ProcScr_WorldMapFaceHolder`. */
    public val holderScript: ProcScript = ProcScript(
        "ProcScr_WorldMapFaceHolder",
        listOf(
            mark(PROC_MARK_WMSTUFF),
            call(ProcFunc { worldMapFaceHolderInit(it) }),
            block(),
            end(),
        ),
    ) { WmFaceHolderState() }

    /** `ProcScr_WorldMapFaceCtrl`. */
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
     * `gProcScr_E_FACE_ExtraFrame` without the camera-move wait; that wait is unverified.
     * The original's proc name is `E_FACE`.
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
     * `SetupFaceGfxData(gUnknown_08A3D728)`: the world map's slot tiles and palettes, read from the
     * ROM (a call of `WorldMap_Init`, which is not ported yet).
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
     * Both wrappers: no face, zero position, `faceId` 0xFFFF.
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
     * Starts the holder child and zeroes the fade state.
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
     * @return the BG tile column of [xIn]; `out[0]` receives the sub-tile scroll
     */
    public fun sub80B826C(xIn: Int, out: IntArray): Int {
        val a = xIn - 0x30
        val b = if (a < 0) xIn - 0x29 else a
        val x = b shr 3
        out[0] = x * 8 - a
        return x
    }

    /** Ported from src/worldmap_face.c: GetWMFaceBg */
    public fun getWmFaceBg(): Int = if (state3()) 2 else 1

    /** Ported from src/worldmap_face.c: GetWMFaceVramOffset */
    public fun getWmFaceVramOffset(): Int = if (state3()) 0x5000 else 0x1800

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
     */
    public fun sub80B82C8(bg: Int, offset: Int, xIn: Int, yIn: Int, flip: Int) {
        val x = sub80B826C(xIn, scrollScratch)
        val y = yIn / 8
        val yRest = y * 8 - yIn
        putFaceOnBackGround(hw.mapBuffer(bg), y * 0x20 + x, (offset shr 5) + 0x4000, flip != 0)
        hw.enableBgSyncByMask(1 shl bg)
        hw.lcd.setBgPosition(bg, scrollScratch[0], yRest)
    }

    /**
     * Ported from src/worldmap_face.c: HandleWMFaceFade
     *
     * Steps the blend and, when it reaches an end, ends faded-out faces or drops the blend flag of
     * faded-in ones.
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
                    val state = faceProc.state as FaceState
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
     * ease, redrawing the BG tile map each frame and un-hiding the sprite at the end of the
     * slide in.
     */
    public fun wmFaceCtrlLoopExt(proc: Proc) {
        val holder = (proc.state as WmFaceCtrlState).faceHolderProc!!.state as WmFaceHolderState
        val bg = getWmFaceBg()
        for (i in 0 until 2) {
            val w = holder.faceWrapper[i]
            val faceProc = w.faceProc ?: continue
            val state = faceProc.state as FaceState
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
                    sub80B82C8(
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
     * Advances the unused timer (wrapping at 54), slides the faces and steps a running fade.
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

    /** Transcribed from src/worldmap_face.c: EndWMFaceCtrl (not verified: no traced call). */
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
     * `gProcScr_E_FACE_ExtraFrame`.
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
     * hidden blended `E_FACE` and the slide state in the wrapper.
     *
     * @param config low byte: x; 0x100 / 0x200: slide direction; 0x400: flipped; 0x2000 / 0x4000:
     *   sprite layer; 0x8000: no palette load
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
     * Starts the slide out of the portrait in [faceSlot] (if shown and not already leaving).
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
     * Transcribed from src/worldmap_face.c: HideWmFace (not verified: the prologue trace never
     * calls it)
     */
    public fun hideWmFace() {
        val proc = engine.find(ctrlScript)!!
        val s = proc.state as WmFaceCtrlState
        hw.lcd.setBlendTargetA(0, 0, 0, 0, 0)
        hw.lcd.setBlendTargetB(0, 0, 0, 1, 0)
        for (w in (s.faceHolderProc!!.state as WmFaceHolderState).faceWrapper) {
            val faceProc = w.faceProc
            if (faceProc != null && w.y and 0x1000 == 0) {
                val state = faceProc.state as FaceState
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

    /** Constants of the world map faces. */
    public companion object {
        /** `PROC_MARK_WMSTUFF`. */
        public const val PROC_MARK_WMSTUFF: Int = 8

        private const val VRAM_BASE = 0x06000000
        private const val FACE_CONFIG = 0x08A3D728
        private const val TILE_TABLE = 0x080D77FC
        private const val TIMER_END = 54
        private const val DISP_FLIPPED = 1
        private const val KIND_96X80 = 2
        private const val HLAYER_1 = 2 shl 6
        private const val DISP_BLEND = 1 shl 10
        private const val DISP_BIT_13 = 1 shl 13
        private const val DISP_HIDDEN = 1 shl 14
    }
}
