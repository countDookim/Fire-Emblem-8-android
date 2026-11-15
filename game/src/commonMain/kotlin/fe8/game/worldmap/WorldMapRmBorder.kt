package fe8.game.worldmap

import fe8.game.RomView
import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.render.ApHandle
import gbafe.render.BgHardware
import gbafe.render.SpriteAnimPlayer

/**
 * The fields of `struct ProcGmapRmBorder1`: one highlighted nation on the world map (the
 * glowing outline and the slide-in picture of the nation's territory).
 */
public class GmapRmBorderProc {
    /**
     * `flag` (+0x29, `bool`): set by `sub_80C2C10` ([WorldMapRmBorder.sub80C2C10], event command
     * `EventB9_WmRemoveHighlightNationPart1`) to start the highlight's removal; read by
     * `GmapRmBorder1_80C2A1C`.
     */
    public var flag: Int = 0

    /** `index` (+0x2A, `u8`): the nation (`WM_NATION_Frelia` 0 .. `WM_NATION_Renais` 7). */
    public var index: Int = 0

    /**
     * `unk_2b` (+0x2B, `s8`): the graphics slot (0..2), i.e. which 0x1000-byte chunk of OBJ
     * VRAM from `0x06010000` this highlight's tiles use so up to three can coexist.
     */
    public var slot: Int = 0

    /** `sprite1` (+0x2C): the translucent outline (animation 0), drawn every frame. */
    public var sprite1: ApHandle? = null

    /** `timer` (+0x30, `s16`): frames into the merge in / merge out slide. */
    public var timer: Int = 0

    /** `sprite2` (+0x34): the nation picture (animation 1) that slides and fades in / out. */
    public var sprite2: ApHandle? = null
}

/**
 * The nation highlight on the world map (`src/worldmap_rm.c`: `ProcScr_GmapRmBorder1`), shown
 * by `EventB8_WmShowBigMapHighlight` and removed by `EventB9` / `EventBA`. The proc slides and
 * fades the nation's picture in, draws it with a translucent outline whose opacity pulses
 * ([WorldMapRmBaPalAnim]) until it is told to end, then slides the picture out. The per-nation
 * graphics and animation pointers (`GfxSet_WmNationMap`) are read from [rom].
 *
 * @param engine the proc scheduler
 * @param hardware blend registers, VRAM and palette
 * @param rom the ROM image
 * @param player the sprite animation player
 * @param baPalAnim the blend pulse this proc starts and ends
 * @param getPosition `GmapRm_GetPosition` as `(x, y)`, null without a map proc
 */
public class WorldMapRmBorder(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val player: SpriteAnimPlayer,
    private val baPalAnim: WorldMapRmBaPalAnim,
    private val getPosition: () -> Pair<Int, Int>?,
) {
    private val lcd get() = hardware.lcd

    /**
     * `ProcScr_GmapRmBorder1`: init, merge in, start the blend pulse ([WorldMapRmBaPalAnim]).
     * The first highlight jumps straight to label 1 when it starts the pulse; a later one, which
     * finds the pulse already running, first draws until the pulse is at rest. From label 1 it
     * draws until `flag` is set and then until the pulse is at rest again, ends the pulse (last
     * highlight only) and merges out.
     *
     * Transcribed from src/worldmap_rm.c: ProcScr_GmapRmBorder1 (not verified: the script order
     * is checked through the proc cursor, but no unit of it is ported here)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GmapRmBorder1",
        listOf(
            name("Gmap RM border"),
            mark(WorldMapRm.PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapRmBorder1End(it) }),
            call(ProcFunc { gmapRmBorderInit(it) }),
            repeat(ProcFunc { gmapRmBorder1NationMergeIn(it) }),
            call(ProcFunc { gmapRmBorderStartPulse(it) }),
            repeat(ProcFunc { gmapRmBorderDrawUntilPulseRests(it) }),
            label(1),
            repeat(ProcFunc { gmapRmBorderDrawUntilFlag(it) }),
            repeat(ProcFunc { gmapRmBorderDrawUntilPulseRests(it) }),
            call(ProcFunc { gmapRmBorderEndPulse(it) }),
            repeat(ProcFunc { gmapRmBorder1NationMergeOut(it) }),
            end(),
        ),
    ) { GmapRmBorderProc() }

    /** The highlight state of a [script] proc. */
    private fun state(proc: Proc) = proc.state as GmapRmBorderProc

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmBorder1_End (not verified: removing either sprite
     * delete or the pulse end leaves the traced OAM, blend and palette unchanged)
     *
     * End callback: deletes both sprites and, when this is the last highlight, ends the blend
     * pulse.
     */
    public fun gmapRmBorder1End(proc: Proc) {
        val s = state(proc)
        player.delete(s.sprite2)
        player.delete(s.sprite1)
        if (engine.count(script) == 1) baPalAnim.endGmapRmBaPalAnim1()
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C2750 (`gmapRmBorderInit` here)
     *
     * Prepares the blend the translucent outline sprite (`OBJ_TRANSLUCENT`) needs: no first
     * target, BG1 as the only second target, blend effect none (semi-transparent OBJs blend
     * regardless) with EVA 0 and EVB 0x10. Resets the timer and, for the first highlight only,
     * loads `Pal_WmHighLightNationMap` into palette slot 0x19 (OBJ palette 9, the bank
     * [wmShowNationHighlightedMap] gives both sprites).
     *
     * Checked per step against `ram_trace_prologue_wm_lcd`: BLDCNT/BLDALPHA/BLDY and palette 0x19.
     */
    public fun gmapRmBorderInit(proc: Proc) {
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(0, 1, 0, 0, 0)
        lcd.setBlendConfig(0, 0, 0x10, 0)
        state(proc).timer = 0
        if (engine.count(script) <= 1) {
            rom.pal(PAL_NATION_MAP, 16).copyInto(hardware.palette, 0x19 * 16)
            hardware.enablePaletteSync()
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_PutSprite2
     *
     * Draws the nation picture ([GmapRmBorderProc.sprite2]) at the big map position ([x], [y])
     * minus the big map scroll (`GmapRm_GetPosition`), as a translucent sprite (`OBJ_TRANSLUCENT`
     * in the OAM attribute 0 bits that ride along with y) when [transparency] is set.
     *
     * Checked per step against the OAM watch of `ram_trace_prologue_wm_lcd`.
     */
    public fun gmapRmBorder1PutSprite2(proc: Proc, x: Int, y: Int, transparency: Boolean) {
        val (ix, iy) = position()
        val dx = x - ix
        val dy = y - iy
        player.update(state(proc).sprite2, dx, if (transparency) OBJ_TRANSLUCENT or dy else dy)
    }

    /**
     * `GmapRm_GetPosition` (the big map's scroll position) as signed halfwords (the decomp reads
     * them into `s16`); (0, 0) without a map proc.
     */
    private fun position(): Pair<Int, Int> =
        getPosition()?.let { it.first.toShort().toInt() to it.second.toShort().toInt() } ?: (0 to 0)

    /** `GfxSet_WmNationMap[index].x` (`s16`): the nation picture's big map x. */
    private fun setX(index: Int) =
        rom.u16(GFX_SET_NATION_MAP + SET_SIZE * index + SET_X).toShort().toInt()

    /** `GfxSet_WmNationMap[index].y` (`s16`): the nation picture's big map y. */
    private fun setY(index: Int) =
        rom.u16(GFX_SET_NATION_MAP + SET_SIZE * index + SET_Y).toShort().toInt()

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_NationMergeIn
     *
     * Slides the nation picture in from 18 pixels to the right of its place while its blend
     * weight (EVA) rises and EVB falls, over 22 frames ([MERGE_FRAMES]), then sets both weights
     * to 0x10, draws it opaque in place and breaks.
     */
    public fun gmapRmBorder1NationMergeIn(proc: Proc) {
        val s = state(proc)
        if (++s.timer < MERGE_FRAMES) {
            val coeff = Spline.divArm1(s.timer, MERGE_FRAMES, 2)
            val ret = Spline.divArm(0x1000, coeff * 0x10)
            lcd.setBlendConfig(0, ret, 0x10 - ret, 0)
            val offset = Spline.divArm(0x1000, (0x1000 - coeff) * 18)
            gmapRmBorder1PutSprite2(proc, setX(s.index) + offset, setY(s.index), true)
        } else {
            lcd.setBlendConfig(0, 0x10, 0x10, 0)
            s.timer = 0
            gmapRmBorder1PutSprite2(proc, setX(s.index), setY(s.index), false)
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C28C4 (`gmapRmBorderEndPulse` here)
     *
     * Before merging out, the last remaining highlight ends the blend pulse
     * (`EndGmapRmBaPalAnim1`), which restores the blend weights.
     */
    public fun gmapRmBorderEndPulse(proc: Proc) {
        if (engine.count(script) == 1) baPalAnim.endGmapRmBaPalAnim1()
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_NationMergeOut
     *
     * Slides the nation picture out to the left by 18 pixels while it fades (EVA falls, EVB
     * rises), over 22 frames ([MERGE_FRAMES]), then restores the default blend
     * (`SetDefaultColorEffects`: effect none, EVA 0x10, EVB 0) and breaks.
     */
    public fun gmapRmBorder1NationMergeOut(proc: Proc) {
        val s = state(proc)
        if (++s.timer < MERGE_FRAMES) {
            val coeff = Spline.divArm1(s.timer, MERGE_FRAMES, 2)
            val ret = Spline.divArm(0x1000, coeff * 0x10)
            lcd.setBlendConfig(0, 0x10 - ret, ret, 0)
            val offset = Spline.divArm(0x1000, coeff * 18)
            gmapRmBorder1PutSprite2(proc, setX(s.index) - offset, setY(s.index), true)
        } else {
            lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C2964 (`gmapRmBorderStartPulse` here)
     *
     * When no other border proc exists, starts the blend pulse and jumps to label 1, skipping the
     * wait for the pulse to be at rest. With another highlight on screen the pulse is already
     * running and this does nothing. The traced run has one highlight and only observes the
     * pulse start.
     */
    public fun gmapRmBorderStartPulse(proc: Proc) {
        if (engine.count(script) <= 1) {
            baPalAnim.startGmapRmBaPalAnim1(null)
            engine.goto(proc, 1)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_PutSpriteAll
     *
     * Draws both sprites at the nation's `GfxSet_WmNationMap` position minus the big map scroll:
     * the translucent outline ([GmapRmBorderProc.sprite1]) and the opaque nation picture.
     */
    public fun gmapRmBorder1PutSpriteAll(proc: Proc) {
        val s = state(proc)
        val (ix, iy) = position()
        player.update(
            s.sprite1, setX(s.index) - ix, OBJ_TRANSLUCENT or (setY(s.index) - iy),
        )
        gmapRmBorder1PutSprite2(proc, setX(s.index), setY(s.index), false)
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C29F8 (`gmapRmBorderDrawUntilPulseRests`
     * here)
     *
     * Draws the sprites until the blend pulse is at rest, its blend weight 0
     * (`CheckGmapRmBaPalAnim1State`, the pulse proc's `flag`), then resets the timer and breaks.
     * Keeps drawing while no pulse proc exists (the state is then 0).
     */
    public fun gmapRmBorderDrawUntilPulseRests(proc: Proc) {
        gmapRmBorder1PutSpriteAll(proc)
        if (baPalAnim.checkGmapRmBaPalAnim1State() != 0) {
            state(proc).timer = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C2A1C (`gmapRmBorderDrawUntilFlag` here)
     *
     * Draws the sprites until [GmapRmBorderProc.flag] is set (the highlight is to be removed).
     */
    public fun gmapRmBorderDrawUntilFlag(proc: Proc) {
        gmapRmBorder1PutSpriteAll(proc)
        if (state(proc).flag != 0) engine.breakProc(proc)
    }

    /**
     * Ported from src/worldmap_rm.c: StartGmapRmBorder1
     *
     * Picks the graphics slot for a new highlight (the decomp's `StartGmapRmBorder1` returns the
     * result as `countMaybe`). Checked with the one border the capture runs
     * at a time (slot 0, so the tile base and the OBJ tiles at `0x06010000`); the slots of
     * concurrent borders stay unobserved.
     *
     * @return the lowest graphics slot (0..2) no border proc uses, or -1 if all are taken
     */
    public fun startGmapRmBorder1(): Int {
        val used = ArrayList<Int>()
        var proc = engine.findAfter(script, null)
        while (proc != null) {
            used.add(state(proc).slot)
            proc = engine.findAfter(script, proc)
        }
        if (used.isEmpty()) return 0
        for (i in 0 until 3) if (i !in used) return i
        return -1
    }

    /**
     * Ported from src/worldmap_rm.c: DrawWmNationHighLightMapGfx
     *
     * Decompresses the nation's highlight tiles (`GfxSet_WmNationMap[index].img`) into OBJ VRAM
     * slot [chr] (`0x06010000 + chr * 0x1000`).
     *
     * Checked against the OBJ VRAM of the full dumps inside the runs (slot 0, nation 7).
     */
    public fun drawWmNationHighLightMapGfx(chr: Int, index: Int) {
        rom.decompressInto(
            rom.u32(GFX_SET_NATION_MAP + SET_SIZE * index), hardware.vram,
            OBJ_VRAM_OFFSET + chr * 0x1000,
        )
    }

    /**
     * Ported from src/worldmap_rm.c: WmShowNationHighlightedMap
     *
     * Starts the highlight of nation [index] (`WM_NATION_*`): picks a free graphics slot, loads
     * its tiles and creates the two sprites (animation 0 the outline, animation 1 the nation
     * picture) on OBJ layer 0xD. The sprites' `tileBase` (OAM attribute 2 added to every
     * object) is `0x9C00 | slot * 0x80`: palette bank 9 (bits 12..15, the one the init loads),
     * priority 3 (bits 10..11) and the tile number of the slot's 0x1000 bytes of OBJ VRAM
     * (`slot * 0x1000 / 0x20`).
     *
     * Checked: proc fields, tile base, both animations, OBJ tiles and OAM (nation 7).
     *
     * @param parent blocked by the new proc; the proc is a root of tree 3 when null
     */
    public fun wmShowNationHighlightedMap(index: Int, parent: Proc?): Proc {
        val slot = startGmapRmBorder1() // before the new proc exists, so it is not counted
        val proc = if (parent != null) {
            checkNotNull(engine.startBlocking(script, parent))
        } else {
            engine.start(script, 3)
        }
        drawWmNationHighLightMapGfx(slot, index)
        val definition = rom.u32(GFX_SET_NATION_MAP + SET_SIZE * index + SET_SPRITE)
        val tileBase = ((slot shl 12) shr 5) or 0x9C00
        val s = state(proc)
        s.sprite1 = player.create(definition, 0xD)?.also {
            it.tileBase = tileBase
            player.switchAnimation(it, 0)
        }
        s.sprite2 = player.create(definition, 0xD)?.also {
            it.tileBase = tileBase
            player.switchAnimation(it, 1)
        }
        s.index = index
        s.slot = slot
        s.flag = 0
        return proc
    }

    /**
     * Transcribed from src/worldmap_rm.c: EndGmapRmBorder1 (not verified: no traced call)
     *
     * Ends a highlight at once (the skipping branch of `EventB9`, with -1, and of `EventBA`).
     * The decomp's search loop dereferences its iterator before the NULL check, so an absent
     * nation would read through NULL there; this returns instead.
     *
     * @param index the nation to end, or negative for every border proc
     */
    public fun endGmapRmBorder1(index: Int) {
        if (index < 0) {
            engine.endEach(script)
            return
        }
        var proc = engine.findAfter(script, null)
        while (proc != null) {
            if (state(proc).index == index) {
                engine.end(proc)
                return
            }
            proc = engine.findAfter(script, proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: sub_80C2BC4
     *
     * Whether a highlight proc exists; `EventBA_WmRemoveHighlightNationPart2` waits while it
     * does (and its skipping branch ends the proc if it does).
     * (Not renamed: [WorldMapBigMapEvents] and tests call it by this name.)
     *
     * Verified through `EventBA` in WorldMapEventCameraTest (the `index >= 0` path); the
     * negative-index path is not run by the capture.
     *
     * @param index the nation, or negative for any
     * @return whether a border proc for the nation exists
     */
    public fun sub80C2BC4(index: Int): Boolean {
        if (index < 0) return engine.find(script) != null
        var proc = engine.findAfter(script, null)
        while (proc != null) {
            if (state(proc).index == index) return true
            proc = engine.findAfter(script, proc)
        }
        return false
    }

    /**
     * Ported from src/worldmap_rm.c: sub_80C2C10
     *
     * Asks the highlight to be removed (`EventB9_WmRemoveHighlightNationPart1`). (Not renamed:
     * [WorldMapBigMapEvents] and tests call it by this name.)
     *
     * Verified through `EventB9` in WorldMapEventCameraTest (the `index >= 0` path); the
     * negative-index path (every border) is not run by the capture.
     *
     * Sets `flag` on the border proc of nation [index], or on every one if [index] is negative.
     */
    public fun sub80C2C10(index: Int) {
        var proc = engine.findAfter(script, null)
        while (proc != null) {
            val s = state(proc)
            if (index < 0) {
                s.flag = 1
            } else if (s.index == index) {
                s.flag = 1
                return
            }
            proc = engine.findAfter(script, proc)
        }
    }

    private companion object {
        /** `GfxSet_WmNationMap` (FE8U): `WM_NATION_MAX` entries of `struct GmMapRmBorderSet`. */
        const val GFX_SET_NATION_MAP = 0x08206AF0

        /** `sizeof(struct GmMapRmBorderSet)`: `img` +0, `sprite` +4, `x` +8, `y` +10. */
        const val SET_SIZE = 12
        const val SET_SPRITE = 4
        const val SET_X = 8
        const val SET_Y = 10

        /** `Pal_WmHighLightNationMap` (FE8U). */
        const val PAL_NATION_MAP = 0x08A9E688

        /** `0x06010000 - 0x06000000`: OBJ VRAM within VRAM. */
        const val OBJ_VRAM_OFFSET = 0x10000

        /** `OBJ_TRANSLUCENT` (`OBJ_MODE(1)`): OAM attribute 0 mode bits (semi-transparent OBJ). */
        const val OBJ_TRANSLUCENT = 0x400

        /** Frames of the merge in and out. */
        const val MERGE_FRAMES = 22
    }
}
