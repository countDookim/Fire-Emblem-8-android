package fe8.game.worldmap

import fe8.game.RomView
import fe8.game.bm.Mu
import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.ApHandle
import gbafe.render.BgHardware
import gbafe.render.SpriteAnimPlayer

/** `struct GMapUnitProc` past `PROC_HEADER` (one map sprite of the world map). */
public class GmapUnitProc {
    /** `flags` (+0x2A, `u16`): `GMAPUNIT_FLAG_*`. */
    public var flags: Int = 0

    /** `unk_2c` (+0x2C, `u16`): the OBJ layer. */
    public var layer: Int = 0

    /** `unk_2e` (+0x2E, `u16`): OAM2 priority bits. */
    public var oam2Base: Int = 0

    /** `unk_30` (+0x30): the base OBJ tile. */
    public var chr: Int = 0

    /** `index` (+0x34, `s8`): the slot in the container. */
    public var index: Int = 0

    /** `faction` (+0x35, `u8`). */
    public var faction: Int = 0

    /** `pal` (+0x36, `u8`). */
    public var pal: Int = 0

    /** `animId` (+0x37, `s8`). */
    public var animId: Int = 0

    /** `unk_38` (+0x38, `u16`): the class the sprite was created for. */
    public var classId: Int = 0

    /** `unk_3a` (+0x3A, `u16`): the class whose animation currently plays. */
    public var currentClassId: Int = 0

    /** `x` (+0x3C, `u16`). */
    public var x: Int = 0

    /** `y` (+0x3E, `u16`). */
    public var y: Int = 0

    /** `ap` (+0x40): the animation handle, or null. */
    public var ap: ApHandle? = null
}

/** `struct GMapUnitContainerProc` past `PROC_HEADER`. */
public class GmapUnitContainerProc {
    /** `layer` (+0x2C): the base OBJ tile of the map sprites. */
    public var layer: Int = 0

    /** `unk_30` (+0x30). */
    public var chr: Int = 0

    /** `pMapUnitProcs` (+0x34): the seven sprite slots. */
    public val units: Array<Proc?> = arrayOfNulls(UNIT_SLOTS)

    /** Sizes. */
    public companion object {
        /** Sprite slots per container. */
        public const val UNIT_SLOTS: Int = 7
    }
}

/** `struct GMapUnitFadeProc` past `PROC_HEADER`. */
public class GmapUnitFadeProc {
    /** `unk_29` (+0x29, `s8`): palette the fade starts from (-1 = black). */
    public var palFrom: Int = 0

    /** `unk_2a` (+0x2A, `s8`): palette the fade ends on (-1 = black). */
    public var palTo: Int = 0

    /** `unk_2c` (+0x2C, `u16`): duration in frames. */
    public var duration: Int = 0

    /** `unk_2e` (+0x2E, `u16`): frames elapsed. */
    public var timer: Int = 0

    /** `unk_30` (+0x30): the faded slots. */
    public val units: IntArray = IntArray(GmapUnitContainerProc.UNIT_SLOTS)

    /** `unk_37` (+0x37, `u8`): number of entries in [units]. */
    public var count: Int = 0
}

/**
 * The world map sprites (`src/worldmap_gmapunit.c`): [GmapUnitProc] map sprites in a
 * [GmapUnitContainerProc], their palette fades ([GmapUnitFadeProc]) and the accessors
 * `src/worldmap_mapmu.c` and `src/worldmap_automu.c` use.
 *
 * `GM_UNITC` (`WorldMapMainProc.gm_unitc`) is [container] here.
 *
 * @param engine the proc scheduler
 * @param rom the ROM (`gMuInfoTable`)
 * @param hardware the palette buffer (`gPaletteBuffer`)
 * @param ap the sprite animation player (`src/ap.c`)
 * @param gmData `gGMData`
 * @param hooks the callees outside this file
 */
public class WorldMapUnit(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val hardware: BgHardware,
    private val ap: SpriteAnimPlayer,
    private val gmData: GmData,
    private val hooks: Hooks = Hooks(),
) {
    /** The callees of `worldmap_gmapunit.c` that live in other files; each defaults to a no-op. */
    public class Hooks(
        /** `GetClassSMSId(classId)`. */
        public val classSmsId: (classId: Int) -> Int = { 0 },
        /** `StartWorldMapSMS(smsId, frameId, slot)`. */
        public val startWorldMapSms: (smsId: Int, frameId: Int, slot: Int) -> Unit = { _, _, _ -> },
        /** `SetStandingMuFacingWM(frameId, (u8 *)dst)`: [dst] is a GBA VRAM address. */
        public val setStandingMuFacingWm: (frameId: Int, dst: Int) -> Unit = { _, _ -> },
        /** `SMS_DisplayOne(class, layer, x, y, oam2, isBlend)`. */
        public val smsDisplayOne: (
            classId: Int, layer: Int, x: Int, y: Int, oam2: Int, blend: Boolean,
        ) -> Unit = { _, _, _, _, _, _ -> },
        /** `Decompress(img, GetMapUnitMMSGfxBuffer(index))`. */
        public val decompressMuGfx: (img: Int, index: Int) -> Unit = { _, _ -> },
        /** `GmapRm_GetPosition` (used while `gGMData.state.bits.state_3` is set). */
        public val rmPosition: () -> Pair<Int, Int> = { 0 to 0 },
        /** `((struct GmScreenProc *)parent)->x/y`. */
        public val screenPosition: (parent: Proc) -> Pair<Int, Int> = { 0 to 0 },
    )

    /** `GM_UNITC`: the container created by [newGmapUnitContainer], or null. */
    public var container: Proc? = null
        private set

    private fun cont(): GmapUnitContainerProc = containerState(checkNotNull(container))

    private fun containerState(proc: Proc): GmapUnitContainerProc =
        proc.state as GmapUnitContainerProc

    private fun unitProc(index: Int): GmapUnitProc =
        cont().units[index]!!.state as GmapUnitProc

    /**
     * `ProcScr_GmapUnit`: one map sprite.
     *
     * Transcribed from src/worldmap_gmapunit.c: ProcScr_GmapUnit (not verified: no @VerifiesPort
     * test names it)
     */
    public val unitScript: ProcScript = ProcScript(
        "ProcScr_GmapUnit",
        listOf(
            name("GmapUnit"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapUnitDestruct(it) }),
            call(ProcFunc { gmapUnitInit(it) }),
            repeat(ProcFunc { gmapUnitLoop(it) }),
            end(),
        ),
    ) { GmapUnitProc() }

    /**
     * `ProcScr_GmapUnitContainer`.
     *
     * Transcribed from src/worldmap_gmapunit.c: ProcScr_GmapUnitContainer (not verified: no
     * @VerifiesPort test names it)
     */
    public val containerScript: ProcScript = ProcScript(
        "ProcScr_GmapUnitContainer",
        listOf(
            name("GmapUnit"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapUnitContainerDestruct(it) }),
            call(ProcFunc { gmapUnitContainerInit(it) }),
            block(),
            end(),
        ),
    ) { GmapUnitContainerProc() }

    /**
     * `ProcScr_GmapUnitFade`.
     *
     * Transcribed from src/worldmap_gmapunit.c: ProcScr_GmapUnitFade (not verified: no
     * @VerifiesPort test names it)
     */
    public val fadeScript: ProcScript = ProcScript(
        "ProcScr_GmapUnitFade",
        listOf(
            name("Gmap Unit Fade"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapUnitFadeDestruct(it) }),
            sleep(0),
            call(ProcFunc { gmapUnitFadeInit(it) }),
            repeat(ProcFunc { gmapUnitFadeLoop(it) }),
            end(),
        ),
    ) { GmapUnitFadeProc() }

    /**
     * Transcribed from src/worldmap_gmapunit.c: GmapUnit_Destruct (not verified: no @VerifiesPort
     * test names it)
     */
    public fun gmapUnitDestruct(proc: Proc) {
        ap.delete((proc.state as GmapUnitProc).ap)
    }

    /** Ported from src/worldmap_gmapunit.c: GmapUnit_Init */
    public fun gmapUnitInit(proc: Proc) {
        val s = proc.state as GmapUnitProc
        s.flags = 0
        s.animId = 4
        s.y = 0
        s.x = 0
    }

    /**
     * Ported from src/worldmap_gmapunit.c: GmapUnit_Loop (checked: the `UPDATEGFX` clear and the
     * flags; not checked: the screen bounds, which the test makes always true, and the OAM output)
     *
     * Draws the sprite (standing sprite for animation 4, else the [ap] animation) when it is
     * displayed and inside the screen. The standing-sprite branch calls
     * [Hooks.setStandingMuFacingWm] and [Hooks.smsDisplayOne]; the capture shows only animations
     * 0-3 on screen, so that branch is not covered by the OAM check.
     */
    public fun gmapUnitLoop(proc: Proc) {
        val s = proc.state as GmapUnitProc
        if (s.flags and FLAG_DISPLAY == 0) return
        val (camX, camY) = if (gmData.state3) {
            hooks.rmPosition()
        } else {
            hooks.screenPosition(checkNotNull(proc.parent))
        }
        var xOam1 = (s.x - camX).toShort().toInt()
        var yOam0 = (s.y - camY).toShort().toInt()
        if (((yOam0 + 0x20) and 0xFFFF) < 0xE0 && ((xOam1 + 0x10) and 0xFFFF) < 0x110) {
            xOam1 = xOam1 and 0x1FF
            yOam0 = yOam0 and 0xFF
            if (s.animId == 4) {
                val frame = STANDING_FRAME[s.index]
                hooks.setStandingMuFacingWm(frame, OBJ_VRAM + s.chr * 0x20)
                val oam2 = s.chr + ((s.pal and 0xF) shl 12) + frame + s.oam2Base
                hooks.smsDisplayOne(
                    s.classId, s.layer, xOam1, yOam0, oam2, s.flags and FLAG_BLEND != 0,
                )
            } else {
                if (s.flags and FLAG_BLEND != 0) yOam0 = yOam0 or OAM0_BLEND
                if (s.flags and FLAG_UPDATEGFX != 0) {
                    s.ap!!.gfxNeedsUpdate = true
                    s.flags = s.flags and FLAG_UPDATEGFX.inv()
                }
                ap.update(s.ap, xOam1, yOam0)
            }
        }
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: GetPaletteIdForAllegience (not verified: the
     * capture shows factions 0 and 1 only, so the red branch is unchecked)
     *
     * The OBJ palette bank of a world map faction (0 blue, 1 green, 2 red; others use blue's).
     */
    public fun getPaletteIdForAllegience(faction: Int): Int = when (faction) {
        1 -> 0xD
        2 -> 0xE
        else -> 0xC
    }

    /**
     * Ported from src/worldmap_gmapunit.c: NewMapUnit
     *
     * Creates the sprite for [classId] in the first free slot of the container. Returns the slot,
     * or -1 (no free slot), -2 (no proc), -3 (no animation handle).
     */
    public fun newMapUnit(container: Proc, classId: Int, faction: Int): Int {
        val c = containerState(container)
        var i = 0
        while (i < GmapUnitContainerProc.UNIT_SLOTS) {
            if (c.units[i] == null) break
            i++
        }
        if (i == GmapUnitContainerProc.UNIT_SLOTS) return -1
        val proc = engine.start(unitScript, checkNotNull(container.parent))
        val s = proc.state as GmapUnitProc
        s.oam2Base = 0x800
        s.faction = faction and 0xFF
        s.pal = getPaletteIdForAllegience(s.faction)
        s.index = i
        s.classId = classId
        s.currentClassId = classId
        hooks.startWorldMapSms(hooks.classSmsId(classId), s.index, STANDING_FRAME[s.index])
        s.layer = 8
        val handle = ap.create(mmsRomTcs2(classId), 8)
        if (handle == null) {
            engine.end(proc)
            return -3
        }
        ap.switchAnimation(handle, s.animId)
        hooks.decompressMuGfx(mmsGfx(classId), i)
        handle.pGraphics = GFX_BUFFER + i * GFX_BUFFER_SIZE
        s.chr = c.layer
        handle.tileBase = i * 4 + c.layer + (s.pal and 0xF) * 0x1000 + s.oam2Base
        s.ap = handle
        c.units[i] = proc
        return i
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MMS_GetGfx (not verified: no @VerifiesPort test
     * names it): `.img` of the entry
     */
    public fun mmsGfx(idx: Int): Int = rom.u32(Mu.MU_INFO_TABLE + 8 * (idx - 1))

    /**
     * Transcribed from src/worldmap_gmapunit.c: MMS_GetROMTCS2 (not verified: no @VerifiesPort test
     * names it): `.anim` of the entry
     */
    public fun mmsRomTcs2(idx: Int): Int = rom.u32(Mu.MU_INFO_TABLE + 8 * (idx - 1) + 4)

    /** Ported from src/worldmap_gmapunit.c: MapUnitC_DeleteGmapUnit */
    public fun mapUnitCDeleteGmapUnit(index: Int) {
        val c = cont()
        val proc = c.units[index] ?: return
        val s = proc.state as GmapUnitProc
        ap.delete(s.ap)
        s.ap = null
        engine.end(proc)
        c.units[index] = null
    }

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetPosition
     *
     * The capture has no value that needs the `u16`/`s8`/`u8` narrowing.
     */
    public fun mapUnitCSetPosition(index: Int, x: Int, y: Int) {
        val s = unitProc(index)
        s.x = x and 0xFFFF
        s.y = y and 0xFFFF
    }

    /** Ported from src/worldmap_gmapunit.c: MapUnitC_GetPosition: `(x, y)` as `u16`. */
    public fun mapUnitCGetPosition(index: Int): Pair<Int, Int> {
        val s = unitProc(index)
        return s.x to s.y
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: sub_80BAF58 (not verified: never called in the
     * capture)
     */
    public fun sub80BAF58(index: Int, x: Int, y: Int) {
        val s = unitProc(index)
        s.x = (s.x + x) and 0xFFFF
        s.y = (s.y + y) and 0xFFFF
    }

    /** Ported from src/worldmap_gmapunit.c: MapUnitC_GetAnimId */
    public fun mapUnitCGetAnimId(index: Int): Int = unitProc(index).animId

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetAnimId
     *
     * The capture has no value that needs the `u16`/`s8`/`u8` narrowing.
     */
    public fun mapUnitCSetAnimId(index: Int, animId: Int) {
        val s = unitProc(index)
        s.animId = animId.toByte().toInt()
        ap.switchAnimation(s.ap, s.animId)
    }

    /** Ported from src/worldmap_gmapunit.c: MapUnitC_SetDisplayEnabled */
    public fun mapUnitCSetDisplayEnabled(index: Int, enabled: Boolean) {
        val s = unitProc(index)
        s.flags = if (enabled) s.flags or FLAG_DISPLAY else s.flags and FLAG_DISPLAY.inv()
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_IsBlendEnabled (not verified: never
     * called in the capture)
     */
    public fun mapUnitCIsBlendEnabled(index: Int): Boolean =
        unitProc(index).flags and FLAG_BLEND != 0

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_SetBlendEnabled (not verified: never
     * called in the capture)
     */
    public fun mapUnitCSetBlendEnabled(index: Int, enabled: Boolean) {
        val s = unitProc(index)
        s.flags = if (enabled) s.flags or FLAG_BLEND else s.flags and FLAG_BLEND.inv()
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_GetLayer (not verified: never called in
     * the capture)
     */
    public fun mapUnitCGetLayer(index: Int): Int = unitProc(index).ap!!.objLayer

    /** Ported from src/worldmap_gmapunit.c: MapUnitC_SetLayer */
    public fun mapUnitCSetLayer(index: Int, layer: Int) {
        val s = unitProc(index)
        s.ap!!.objLayer = layer
        s.layer = layer and 0xFFFF
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_GetFaction (not verified: never called
     * in the capture)
     */
    public fun mapUnitCGetFaction(index: Int): Int = unitProc(index).faction

    /** Ported from src/worldmap_gmapunit.c: MapUnitC_SetFaction */
    public fun mapUnitCSetFaction(index: Int, faction: Int) {
        val s = unitProc(index)
        if (faction >= 0) s.faction = faction and 0xFF
        val pal = getPaletteIdForAllegience(s.faction)
        s.pal = pal
        s.ap!!.tileBase = cont().layer + index * 4 + (pal and 0xF) * 0x1000 + s.oam2Base
    }

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetPalette
     *
     * The capture has no value that needs the `u16`/`s8`/`u8` narrowing.
     */
    public fun mapUnitCSetPalette(index: Int, pal: Int) {
        val s = unitProc(index)
        s.pal = pal and 0xFF
        s.ap!!.tileBase = cont().layer + index * 4 + (pal and 0xF) * 0x1000 + s.oam2Base
    }

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetGfxNeedsUpdate (the `index < 0` branch is
     * not exercised by the capture)
     */
    public fun mapUnitCSetGfxNeedsUpdate(index: Int) {
        if (index >= 0) {
            val s = unitProc(index)
            s.flags = s.flags or FLAG_UPDATEGFX
            return
        }
        for (proc in cont().units) {
            if (proc != null) {
                val s = proc.state as GmapUnitProc
                s.flags = s.flags or FLAG_UPDATEGFX
            }
        }
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: sub_80BB0E0 (not verified: only ship-travel
     * nodes reach it and the capture has none)
     *
     * Switches the sprite to the animation of [classId] (0 = the class it was made for).
     */
    public fun sub80BB0E0(index: Int, classId: Int): Boolean {
        val s = unitProc(index)
        if (s.ap != null) ap.delete(s.ap)
        var r2 = classId
        if (r2 == 0) r2 = s.classId
        if (r2 != s.currentClassId) {
            s.currentClassId = r2
            val handle = ap.create(mmsRomTcs2(r2), 8) ?: return false
            ap.switchAnimation(handle, s.animId)
            hooks.decompressMuGfx(mmsGfx(r2), index)
            handle.pGraphics = GFX_BUFFER + index * GFX_BUFFER_SIZE
            s.chr = cont().layer
            handle.tileBase = cont().layer + index * 4 + (s.pal and 0xF) * 0x1000 + s.oam2Base
            s.ap = handle
        }
        return true
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: sub_80BB188 (not verified: never called in the
     * capture)
     */
    public fun sub80BB188(index: Int): Int = unitProc(index).classId

    /** Ported from src/worldmap_gmapunit.c: sub_80BB194 (the class whose animation plays). */
    public fun sub80BB194(index: Int): Int = unitProc(index).currentClassId

    /**
     * Transcribed from src/worldmap_gmapunit.c: GmapUnitContainer_Destruct (not verified: the
     * container ends after its sprites in the capture's last step)
     */
    public fun gmapUnitContainerDestruct(proc: Proc) {
        val c = containerState(proc)
        for (i in c.units.indices) {
            engine.end(c.units[i])
            c.units[i] = null
        }
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: GmapUnitContainer_Init (not verified: a fresh
     * proc already has empty slots, so no trace can tell it from a no-op)
     */
    public fun gmapUnitContainerInit(proc: Proc) {
        containerState(proc).units.fill(null)
    }

    /**
     * Ported from src/worldmap_gmapunit.c: NewGmapUnitContainer (the `u8` narrowing of `chr` is
     * not exercised by the capture)
     *
     * Also makes it `GM_UNITC`.
     *
     * @param parent `GM_SCREEN`
     */
    public fun newGmapUnitContainer(parent: Proc, layer: Int, chr: Int): Proc {
        val proc = engine.start(containerScript, parent)
        val c = containerState(proc)
        c.layer = layer
        c.chr = chr and 0xFF
        container = proc
        return proc
    }

    /**
     * Ported from src/worldmap_gmapunit.c: GmapUnitFade_Destruct (checked through the sprite
     * fields: faction palette restored, display cleared when fading out)
     */
    public fun gmapUnitFadeDestruct(proc: Proc) {
        val s = proc.state as GmapUnitFadeProc
        for (i in 0 until s.count) {
            if (s.palTo < 0) mapUnitCSetDisplayEnabled(s.units[i], false)
            mapUnitCSetFaction(s.units[i], -1)
        }
    }

    /** Ported from src/worldmap_gmapunit.c: GmapUnitFade_Init */
    public fun gmapUnitFadeInit(proc: Proc) {
        (proc.state as GmapUnitFadeProc).timer = 0
    }

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB26C
     *
     * Checked through OBJ palette 0xA of the three unit fades (steps 8185..8450). No fade there
     * starts from palette 0, so that branch of the start palette test is unobserved.
     *
     * One 15-bit colour of a fade: entry [i1] of OBJ palette [pal1] blended towards entry [i2] of
     * [pal2] by [t] (0x1000 = all the way); a negative palette is black.
     */
    public fun sub80BB26C(pal1: Int, i1: Int, pal2: Int, i2: Int, t: Int): Int {
        var r1 = 0
        var g1 = 0
        var b1 = 0
        if (pal1 >= 0) {
            val c = hardware.palette[pal1 * 0x10 + i1 + 0x100].toInt() and 0xFFFF
            r1 = c and 0x1F
            g1 = (c and 0x3E0) shr 5
            b1 = (c and 0x7C00) shr 10
        }
        var r2 = 0
        var g2 = 0
        var b2 = 0
        if (pal2 >= 0) {
            val c = hardware.palette[pal2 * 0x10 + i2 + 0x100].toInt() and 0xFFFF
            r2 = c and 0x1F
            g2 = (c and 0x3E0) shr 5
            b2 = (c and 0x7C00) shr 10
        }
        val red = r1 + Spline.divArm(0x1000, (r2 - r1) * t)
        val green = g1 + Spline.divArm(0x1000, (g2 - g1) * t)
        val blue = b1 + Spline.divArm(0x1000, (b2 - b1) * t)
        return ((blue shl 10) + (green shl 5)) + red
    }

    /**
     * Ported from src/worldmap_gmapunit.c: GmapUnitFade_Loop (checked: timer, the end of the
     * fade and the palette written to OBJ palette 0xA each step; the palette buffer is compared,
     * so the `EnablePaletteSync` call itself is not observed)
     *
     * Blends OBJ palette 0xA from the start palette to the end palette over `duration` frames.
     */
    public fun gmapUnitFadeLoop(proc: Proc) {
        val s = proc.state as GmapUnitFadeProc
        s.timer = (s.timer + 1) and 0xFFFF
        val base = FADE_PAL * 0x10 + 0x100
        if (s.timer < s.duration) {
            val t = Spline.divArm1(s.timer, s.duration, 0)
            for (i in 0 until 0x10) {
                hardware.palette[base + i] = sub80BB26C(s.palFrom, i, s.palTo, i, t).toShort()
            }
        } else {
            copyOrClearFadePalette(s.palTo)
            engine.breakProc(proc)
        }
        hardware.enablePaletteSync()
    }

    private fun copyOrClearFadePalette(pal: Int) {
        val base = FADE_PAL * 0x10 + 0x100
        if (pal >= 0) {
            val from = pal * 0x10 + 0x100
            hardware.palette.copyInto(hardware.palette, base, from, from + 0x10)
        } else {
            hardware.palette.fill(0, base, base + 0x10)
        }
    }

    /**
     * Ported from src/worldmap_gmapunit.c: StartGmapUnitFade (checked: the proc fields and the
     * start palette copied to OBJ palette 0xA; the blocking parent is not in the capture)
     *
     * Starts a fade of [index] from palette [palA] to [palB] over [duration] frames, blocking
     * [parent] or in tree 3 when it is null, and copies the start palette to OBJ palette 0xA.
     */
    public fun startGmapUnitFade(
        index: Int,
        palA: Int,
        palB: Int,
        duration: Int,
        parent: Proc?,
    ): Proc? {
        val proc = if (parent != null) {
            engine.startBlocking(fadeScript, parent)
        } else {
            engine.start(fadeScript, PROC_TREE_3)
        } ?: return null
        val s = proc.state as GmapUnitFadeProc
        s.palFrom = palA
        s.palTo = palB
        s.duration = duration and 0xFFFF
        copyOrClearFadePalette(palA)
        hardware.enablePaletteSync()
        s.units[0] = index
        s.count = 1
        return proc
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: EndGmapUnitFade (not verified: runs only on a
     * skipped event in the prologue)
     */
    public fun endGmapUnitFade() {
        engine.endEach(fadeScript)
    }

    /** Ported from src/worldmap_gmapunit.c: GetGmapUnitFade */
    public fun getGmapUnitFade(): Proc? = engine.find(fadeScript)

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB49C: adds [index] to the
     * running fade.
     */
    public fun sub80BB49C(index: Int) {
        val proc = getGmapUnitFade() ?: return
        val s = proc.state as GmapUnitFadeProc
        s.units[s.count] = index
        s.count = (s.count + 1) and 0xFF
    }

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB4C0
     *
     * Fades [index] in: from black to its faction palette, or joins the running fade.
     */
    public fun sub80BB4C0(index: Int, duration: Int, parent: Proc?) {
        val s = unitProc(index)
        if (getGmapUnitFade() == null) {
            startGmapUnitFade(index, -1, getPaletteIdForAllegience(s.faction), duration, parent)
        } else {
            sub80BB49C(index)
        }
        mapUnitCSetPalette(index, FADE_PAL)
        mapUnitCSetDisplayEnabled(index, true)
    }

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB538 (the final `SetDisplayEnabled(true)` is not
     * checked: every faded-out sprite in the capture is displayed already)
     *
     * Fades [index] out: from its faction palette to black, or joins the running fade.
     */
    public fun sub80BB538(index: Int, duration: Int, parent: Proc?) {
        val s = unitProc(index)
        if (getGmapUnitFade() == null) {
            startGmapUnitFade(index, getPaletteIdForAllegience(s.faction), -1, duration, parent)
        } else {
            sub80BB49C(index)
        }
        mapUnitCSetPalette(index, FADE_PAL)
        mapUnitCSetDisplayEnabled(index, true)
    }

    /** Flags and constants of `worldmap_gmapunit.c`. */
    public companion object {
        /** `GMAPUNIT_FLAG_DISPLAY`. */
        public const val FLAG_DISPLAY: Int = 1

        /** `GMAPUNIT_FLAG_BLEND`. */
        public const val FLAG_BLEND: Int = 2

        /** `GMAPUNIT_FLAG_UPDATEGFX`. */
        public const val FLAG_UPDATEGFX: Int = 4

        /** `gUnknown_08205F7C`: the OBJ tile offset of each standing sprite slot. */
        private val STANDING_FRAME = intArrayOf(0x00, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18)

        private const val PROC_MARK_WMSTUFF = 8
        private const val PROC_TREE_3 = 3
        private const val OAM0_BLEND = 0x400
        private const val OBJ_VRAM = 0x06010000
        private const val FADE_PAL = 0xA

        /** `gUnknown_0200AF00`: the decompressed map sprite graphics (0x2200 bytes each). */
        private const val GFX_BUFFER = 0x0200AF00
        private const val GFX_BUFFER_SIZE = 0x2200
    }
}
