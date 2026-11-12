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

/** The fields of `struct ProcGmapRmBorder1`. */
public class GmapRmBorderProc {
    /** `flag` (+0x29, `bool`): set by the sibling blend pulse; read by the repeat functions. */
    public var flag: Int = 0

    /** `index` (+0x2A, `u8`): the nation. */
    public var index: Int = 0

    /** `unk_2b` (+0x2B, `s8`): the graphics slot (0..2). */
    public var slot: Int = 0

    /** `sprite1` (+0x2C): the pulsing outline. */
    public var sprite1: ApHandle? = null

    /** `timer` (+0x30, `s16`). */
    public var timer: Int = 0

    /** `sprite2` (+0x34): the merge in/out sprite. */
    public var sprite2: ApHandle? = null
}

/**
 * The nation highlight on the world map (`src/worldmap_rm.c`: `ProcScr_GmapRmBorder1`). The
 * per-nation graphics and animation pointers (`GfxSet_WmNationMap`) are read from [rom].
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
     * `ProcScr_GmapRmBorder1`.
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
            call(ProcFunc { gmapRmBorder180C2750(it) }),
            repeat(ProcFunc { gmapRmBorder1NationMergeIn(it) }),
            call(ProcFunc { gmapRmBorder180C2964(it) }),
            repeat(ProcFunc { gmapRmBorder180C29F8(it) }),
            label(1),
            repeat(ProcFunc { gmapRmBorder180C2A1C(it) }),
            repeat(ProcFunc { gmapRmBorder180C29F8(it) }),
            call(ProcFunc { gmapRmBorder180C28C4(it) }),
            repeat(ProcFunc { gmapRmBorder1NationMergeOut(it) }),
            end(),
        ),
    ) { GmapRmBorderProc() }

    private fun state(proc: Proc) = proc.state as GmapRmBorderProc

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmBorder1_End (not verified: removing either sprite
     * delete or the pulse end leaves the traced OAM, blend and palette unchanged)
     */
    public fun gmapRmBorder1End(proc: Proc) {
        val s = state(proc)
        player.delete(s.sprite2)
        player.delete(s.sprite1)
        if (engine.count(script) == 1) baPalAnim.endGmapRmBaPalAnim1()
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C2750
     *
     * Checked per step against `ram_trace_prologue_wm_lcd`: BLDCNT/BLDALPHA/BLDY and palette 0x19.
     */
    public fun gmapRmBorder180C2750(proc: Proc) {
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
     * Checked per step against the OAM watch of `ram_trace_prologue_wm_lcd`.
     */
    public fun gmapRmBorder1PutSprite2(proc: Proc, x: Int, y: Int, transparency: Boolean) {
        val (ix, iy) = position()
        val dx = x - ix
        val dy = y - iy
        player.update(state(proc).sprite2, dx, if (transparency) OBJ_TRANSLUCENT or dy else dy)
    }

    /** `GmapRm_GetPosition` as signed halfwords; (0, 0) without a map proc. */
    private fun position(): Pair<Int, Int> =
        getPosition()?.let { it.first.toShort().toInt() to it.second.toShort().toInt() } ?: (0 to 0)

    private fun setX(index: Int) = rom.u16(GFX_SET_NATION_MAP + 12 * index + 8).toShort().toInt()

    private fun setY(index: Int) = rom.u16(GFX_SET_NATION_MAP + 12 * index + 10).toShort().toInt()

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_NationMergeIn
     *
     * Slides and fades the merge sprite in over 22 frames, then breaks.
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
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C28C4
     */
    public fun gmapRmBorder180C28C4(proc: Proc) {
        if (engine.count(script) == 1) baPalAnim.endGmapRmBaPalAnim1()
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_NationMergeOut
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
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C2964
     *
     * Starts the blend pulse unless another border proc exists; the `Proc_Goto` is redundant in
     * the traced run (label 1 follows), so only the pulse start is observed.
     */
    public fun gmapRmBorder180C2964(proc: Proc) {
        if (engine.count(script) <= 1) {
            baPalAnim.startGmapRmBaPalAnim1(null)
            engine.goto(proc, 1)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_PutSpriteAll
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
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C29F8
     */
    public fun gmapRmBorder180C29F8(proc: Proc) {
        gmapRmBorder1PutSpriteAll(proc)
        if (baPalAnim.checkGmapRmBaPalAnim1State() != 0) {
            state(proc).timer = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmBorder1_80C2A1C
     */
    public fun gmapRmBorder180C2A1C(proc: Proc) {
        gmapRmBorder1PutSpriteAll(proc)
        if (state(proc).flag != 0) engine.breakProc(proc)
    }

    /**
     * Ported from src/worldmap_rm.c: StartGmapRmBorder1
     *
     * Checked with the one border the capture runs at a time (slot 0, so the tile base and the
     * OBJ tiles at `0x06010000`); the slots of concurrent borders stay unobserved.
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
     * Checked against the OBJ VRAM of the full dumps inside the runs (slot 0, nation 7).
     */
    public fun drawWmNationHighLightMapGfx(chr: Int, index: Int) {
        rom.decompressInto(
            rom.u32(GFX_SET_NATION_MAP + 12 * index), hardware.vram,
            OBJ_VRAM_OFFSET + chr * 0x1000,
        )
    }

    /**
     * Ported from src/worldmap_rm.c: WmShowNationHighlightedMap
     *
     * Checked: proc fields, tile base, both animations, OBJ tiles and OAM (nation 7).
     *
     * @param parent blocked by the new proc; the proc is a root of tree 3 when null
     */
    public fun wmShowNationHighlightedMap(index: Int, parent: Proc?): Proc {
        val slot = startGmapRmBorder1()
        val proc = if (parent != null) {
            checkNotNull(engine.startBlocking(script, parent))
        } else {
            engine.start(script, 3)
        }
        drawWmNationHighLightMapGfx(slot, index)
        val definition = rom.u32(GFX_SET_NATION_MAP + 12 * index + 4)
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
     * Transcribed from src/worldmap_rm.c: sub_80C2BC4 (not verified: no traced call)
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
     * Transcribed from src/worldmap_rm.c: sub_80C2C10 (not verified: the event that calls it is
     * not ported; the test replays it at the step the traced flag is first set)
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
        /** `GfxSet_WmNationMap` (FE8U). */
        const val GFX_SET_NATION_MAP = 0x08206AF0

        /** `Pal_WmHighLightNationMap` (FE8U). */
        const val PAL_NATION_MAP = 0x08A9E688

        /** `0x06010000 - 0x06000000`. */
        const val OBJ_VRAM_OFFSET = 0x10000

        /** `OBJ_TRANSLUCENT`: OAM0 mode bits. */
        const val OBJ_TRANSLUCENT = 0x400

        /** Frames of the merge in and out. */
        const val MERGE_FRAMES = 22
    }
}
