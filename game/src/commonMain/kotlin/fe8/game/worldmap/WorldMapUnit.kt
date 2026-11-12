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

/**
 * `SetStandingMuFacingWM(frameId, dst)`: copies the standing (facing) graphics of a world map
 * sprite into OBJ VRAM. A `fun interface` rather than a lambda type, so the per-frame call does
 * not box its arguments.
 */
public fun interface SetStandingMuFacing {
    /**
     * Writes the current idle-animation frame (chosen from the game clock) of the standing
     * sprite at tile offset [frameId] (an entry of `gUnknown_08205F7C`) to the GBA VRAM address
     * [dst].
     */
    public operator fun invoke(frameId: Int, dst: Int)
}

/**
 * `SMS_DisplayOne(class, layer, x, y, oam2, isBlend)`: puts one standing map sprite on screen
 * this frame. A `fun interface` for the same reason as [SetStandingMuFacing].
 */
public fun interface SmsDisplayOne {
    /**
     * Draws the standing sprite of [classId] on OBJ layer [layer] at screen position
     * ([x], [y]) with the OAM attribute 2 word [oam2]; [blend] draws it semi-transparent.
     */
    public operator fun invoke(
        classId: Int,
        layer: Int,
        x: Int,
        y: Int,
        oam2: Int,
        blend: Boolean,
    )
}

/**
 * `struct GMapUnitProc` past `PROC_HEADER`: the state of one unit sprite walking or standing on
 * the world map (a lord, a monster, a ship passenger ...), owned by a [GmapUnitContainerProc].
 */
public class GmapUnitProc {
    /** `flags` (+0x2A, `u16`): `GMAPUNIT_FLAG_*`. */
    public var flags: Int = 0

    /**
     * `unk_2c` (+0x2C, `u16`): the AP OBJ layer (priority node) the standing sprite is drawn on;
     * 8 when created.
     */
    public var layer: Int = 0

    /**
     * `unk_2e` (+0x2E, `u16`): OAM attribute 2 bits added to every tile word; 0x800 when
     * created, which is OBJ priority 2 (`OAM2_LAYER(2)`).
     */
    public var oam2Base: Int = 0

    /**
     * `unk_30` (+0x30): the first OBJ tile of this sprite's graphics (the container's [layer]);
     * the standing sprite is uploaded to `OBJ_VRAM + chr * 0x20`.
     */
    public var chr: Int = 0

    /** `index` (+0x34, `s8`): the slot of this sprite in [GmapUnitContainerProc.units]. */
    public var index: Int = 0

    /** `faction` (+0x35, `u8`): `FACTION_ID_*` (0 blue, 1 green, 2 red); selects the palette. */
    public var faction: Int = 0

    /** `pal` (+0x36, `u8`): the OBJ palette bank used; low 4 bits only reach the OAM. */
    public var pal: Int = 0

    /** `animId` (+0x37, `s8`): the AP animation; 4 is the standing sprite, others walk. */
    public var animId: Int = 0

    /** `unk_38` (+0x38, `u16`): the class the sprite was created for ("class initially"). */
    public var classId: Int = 0

    /**
     * `unk_3a` (+0x3A, `u16`): the class whose animation currently plays (also "class
     * initially" in the decomp; [mapUnitCSwitchClass] changes it).
     */
    public var currentClassId: Int = 0

    /** `x` (+0x3C, `u16`): world map pixel X of the sprite. */
    public var x: Int = 0

    /** `y` (+0x3E, `u16`): world map pixel Y of the sprite. */
    public var y: Int = 0

    /** `ap` (+0x40): the AP animation handle of the walking animation, or null. */
    public var ap: ApHandle? = null
}

/**
 * `struct GMapUnitContainerProc` past `PROC_HEADER`: holds the (up to seven) unit sprites of the
 * world map; there is one, `GM_UNITC`.
 */
public class GmapUnitContainerProc {
    /**
     * `layer` (+0x2C): the first OBJ tile reserved for the map sprites (each slot takes 4
     * tiles after it). The decomp name `layer` is misleading; it is used as a tile index.
     */
    public var layer: Int = 0

    /** `unk_30` (+0x30, `u8`): the `chr` argument of `NewGmapUnitContainer`; meaning unknown. */
    public var chr: Int = 0

    /** `pMapUnitProcs` (+0x34): the seven sprite slots; a null entry is a free slot. */
    public val units: Array<Proc?> = arrayOfNulls(UNIT_SLOTS)

    /** Capacity of the container. */
    public companion object {
        /** Sprite slots per container. */
        public const val UNIT_SLOTS: Int = 7
    }
}

/**
 * `struct GMapUnitFadeProc` past `PROC_HEADER`: a fade of world map unit sprites between two OBJ
 * palette banks, played through OBJ palette 0xA (`FADE_PAL`), used to make a sprite appear from or
 * vanish into black.
 */
public class GmapUnitFadeProc {
    /** `unk_29` (+0x29, `s8`): OBJ palette bank the fade starts from (-1 = black). */
    public var palFrom: Int = 0

    /** `unk_2a` (+0x2A, `s8`): OBJ palette bank the fade ends on (-1 = black). */
    public var palTo: Int = 0

    /** `unk_2c` (+0x2C, `u16`): duration in frames. */
    public var duration: Int = 0

    /** `unk_2e` (+0x2E, `u16`): frames elapsed. */
    public var timer: Int = 0

    /** `unk_30` (+0x30, `u8[7]`): container slots of the sprites being faded. */
    public val units: IntArray = IntArray(GmapUnitContainerProc.UNIT_SLOTS)

    /** `unk_37` (+0x37, `u8`): number of entries of [units] in use. */
    public var count: Int = 0
}

/**
 * The unit sprites that walk and stand on the world map (`src/worldmap_gmapunit.c`):
 * [GmapUnitProc] sprites in a [GmapUnitContainerProc], their fade in and out through a palette
 * blend ([GmapUnitFadeProc]) and the `MapUnitC_*` accessors that `src/worldmap_mapmu.c` and
 * `src/worldmap_automu.c` use.
 *
 * `GM_UNITC` (`WorldMapMainProc.gm_unitc`) is [container] here. The `MapUnitC_*` functions of
 * the decomp take the container as their first argument; here they use [container].
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
    /**
     * The callees of `worldmap_gmapunit.c` that live in other files (the standing sprite code in
     * the battle-map sprite module and the world map screen); each defaults to a no-op.
     */
    public class Hooks(
        /** `GetClassSMSId(classId)`: the standing sprite id of a class. */
        public val classSmsId: (classId: Int) -> Int = { 0 },
        /**
         * `StartWorldMapSMS(smsId, frameId, slot)`: unpacks the standing sprite sheet [smsId] to
         * OBJ tile offset [slot] and records its placement under [frameId] in
         * `gUnitSpriteSlots`. Called here with the container slot as [frameId] and the slot's
         * entry of `gUnknown_08205F7C` as [slot].
         */
        public val startWorldMapSms: (smsId: Int, frameId: Int, slot: Int) -> Unit = { _, _, _ -> },
        /** `SetStandingMuFacingWM(frameId, (u8 *)dst)`: [dst] is a GBA VRAM address. */
        public val setStandingMuFacingWm: SetStandingMuFacing = SetStandingMuFacing { _, _ -> },
        /** `SMS_DisplayOne(class, layer, x, y, oam2, isBlend)`. */
        public val smsDisplayOne: SmsDisplayOne = SmsDisplayOne { _, _, _, _, _, _ -> },
        /**
         * `Decompress(img, GetMapUnitMMSGfxBuffer(index))`: unpacks the walking sprite graphics at
         * ROM address [img] into the sprite buffer of slot [index].
         */
        public val decompressMuGfx: (img: Int, index: Int) -> Unit = { _, _ -> },
        /**
         * `GmapRm_GetPosition`: the camera position while the world map is in its
         * `gGMData.state.bits.state_3` mode (the map screen proc is not the camera then).
         */
        public val rmPosition: () -> Pair<Int, Int> = { 0 to 0 },
        /** `((struct GmScreenProc *)parent)->x/y`: the world map scroll position. */
        public val screenPosition: (parent: Proc) -> Pair<Int, Int> = { 0 to 0 },
    )

    /** `GM_UNITC`: the container created by [newGmapUnitContainer], or null before that. */
    public var container: Proc? = null
        private set

    /** The state of `GM_UNITC`; fails when no container has been created. */
    private fun cont(): GmapUnitContainerProc = containerState(checkNotNull(container))

    /** The [GmapUnitContainerProc] state of the container proc [proc]. */
    private fun containerState(proc: Proc): GmapUnitContainerProc =
        proc.state as GmapUnitContainerProc

    /** The state of the sprite in slot [index] of `GM_UNITC`; the slot must be occupied. */
    private fun unitProc(index: Int): GmapUnitProc =
        cont().units[index]!!.state as GmapUnitProc

    /**
     * `ProcScr_GmapUnit`: the script of one unit sprite; [gmapUnitLoop] draws it every frame
     * until it is ended.
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
     * `ProcScr_GmapUnitContainer`: the script of the container; it only blocks, holding the
     * sprites until it is ended.
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
     * `ProcScr_GmapUnitFade`: the script of a unit sprite fade; one frame of sleep, then
     * [gmapUnitFadeLoop] blends the palette each frame until the fade has run its duration.
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
     *
     * Frees the sprite's animation handle when the sprite proc ends.
     */
    public fun gmapUnitDestruct(proc: Proc) {
        ap.delete((proc.state as GmapUnitProc).ap)
    }

    /**
     * Ported from src/worldmap_gmapunit.c: GmapUnit_Init
     *
     * A new sprite starts hidden (no flags), at world map position (0, 0), on animation 4 (the
     * standing sprite).
     */
    public fun gmapUnitInit(proc: Proc) {
        val s = proc.state as GmapUnitProc
        s.flags = 0
        s.animId = 4
        s.y = 0
        s.x = 0
    }

    /**
     * Ported from src/worldmap_gmapunit.c: GmapUnit_Loop (checked per step against the OAM
     * watch of `ram_trace_prologue_wm_lcd` in WorldMapEventUnitTest: the entries of walking and
     * standing sprites, the tiles at the dumps and the `UPDATEGFX` clear)
     *
     * Draws the sprite (standing sprite for animation 4, else the [ap] animation) when it is
     * displayed and inside the screen. The standing-sprite branch calls
     * [Hooks.setStandingMuFacingWm] and [Hooks.smsDisplayOne].
     *
     * The camera comes from [Hooks.rmPosition] in `state_3` mode, else from the parent screen
     * proc. The visibility test is unsigned so it also rejects negative offsets: a screen-space
     * Y in -0x20 until 0xC0 and X in -0x10 until 0x100 (sprites partly off the 240x160 screen
     * still draw). The result is masked to the OAM attribute 1 X (9 bits) and attribute 0 Y
     * (8 bits) fields.
     */
    public fun gmapUnitLoop(proc: Proc) {
        val s = proc.state as GmapUnitProc
        if (s.flags and FLAG_DISPLAY == 0) return
        val (camX, camY) = if (gmData.state3) {
            hooks.rmPosition()
        } else {
            hooks.screenPosition(checkNotNull(proc.parent))
        }
        // The decomp holds these in `s16`, hence the narrowing to a signed 16-bit value.
        var xOam1 = (s.x - camX).toShort().toInt()
        var yOam0 = (s.y - camY).toShort().toInt()
        if (((yOam0 + 0x20) and 0xFFFF) < 0xE0 && ((xOam1 + 0x10) and 0xFFFF) < 0x110) {
            xOam1 = xOam1 and 0x1FF
            yOam0 = yOam0 and 0xFF
            if (s.animId == 4) {
                // The standing frame is uploaded to the sprite's tiles every frame, then drawn by
                // SMS_DisplayOne.
                val frame = STANDING_FRAME[s.index]
                hooks.setStandingMuFacingWm(frame, OBJ_VRAM + s.chr * 0x20)
                val oam2 = s.chr + ((s.pal and 0xF) shl 12) + frame + s.oam2Base
                hooks.smsDisplayOne(
                    s.classId, s.layer, xOam1, yOam0, oam2, s.flags and FLAG_BLEND != 0,
                )
            } else {
                // Semi-transparency is the OAM attribute 0 mode bit, not a separate step.
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
     * The OBJ palette bank of a world map faction: `FACTION_ID_BLUE` (player) 0xC,
     * `FACTION_ID_GREEN` (NPC) 0xD, `FACTION_ID_RED` (enemy) 0xE; any other faction uses blue's
     * (the decomp's `default`).
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
     *
     * Each slot owns 4 OBJ tiles after the container's [GmapUnitContainerProc.layer] and a
     * 0x2200-byte graphics buffer; the sprite starts on animation 4 (standing).
     *
     * NOTE: on the no-handle path the decomp calls `Proc_End(ProcScr_GmapUnit)`, i.e. with the
     * script rather than the new proc (it looks like a decomp type slip). This port ends the new
     * proc, which is what was evidently meant; whether the original does the same is unverified.
     * The -3 path is not reached in the capture.
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
        s.oam2Base = OAM2_PRIORITY_2
        s.faction = faction and 0xFF
        s.pal = getPaletteIdForAllegience(s.faction)
        s.index = i
        s.classId = classId
        s.currentClassId = classId
        hooks.startWorldMapSms(hooks.classSmsId(classId), s.index, STANDING_FRAME[s.index])
        s.layer = AP_OBJ_LAYER
        val handle = ap.create(mmsRomTcs2(classId), AP_OBJ_LAYER)
        if (handle == null) {
            engine.end(proc)
            return -3
        }
        ap.switchAnimation(handle, s.animId)
        hooks.decompressMuGfx(mmsGfx(classId), i)
        handle.pGraphics = GFX_BUFFER + i * GFX_BUFFER_SIZE
        s.chr = c.layer
        // Tile word: 4 tiles per slot after the container's base, the palette bank in bits 12-15
        // (`OAM2_PAL`) and the priority bits.
        handle.tileBase = i * 4 + c.layer + (s.pal and 0xF) * 0x1000 + s.oam2Base
        s.ap = handle
        c.units[i] = proc
        return i
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MMS_GetGfx (not verified: no @VerifiesPort test
     * names it): the compressed walking graphics (`.img`) of the `gMuInfoTable` entry of class
     * [idx] (the table starts at class 1).
     */
    public fun mmsGfx(idx: Int): Int = rom.u32(Mu.MU_INFO_TABLE + 8 * (idx - 1))

    /**
     * Transcribed from src/worldmap_gmapunit.c: MMS_GetROMTCS2 (not verified: no @VerifiesPort test
     * names it): the AP animation data (`.anim`) of the `gMuInfoTable` entry of class [idx]
     * (the table starts at class 1).
     */
    public fun mmsRomTcs2(idx: Int): Int = rom.u32(Mu.MU_INFO_TABLE + 8 * (idx - 1) + 4)

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_DeleteGmapUnit
     *
     * Removes the sprite in slot [index] (animation freed, proc ended); an empty slot is ignored.
     */
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
     * Moves the sprite in slot [index] to world map pixel ([x], [y]). The capture has no value
     * that needs the `u16` narrowing.
     */
    public fun mapUnitCSetPosition(index: Int, x: Int, y: Int) {
        val s = unitProc(index)
        s.x = x and 0xFFFF
        s.y = y and 0xFFFF
    }

    /** Ported from src/worldmap_gmapunit.c: MapUnitC_GetPosition: the world map `(x, y)`. */
    public fun mapUnitCGetPosition(index: Int): Pair<Int, Int> {
        val s = unitProc(index)
        return s.x to s.y
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: sub_80BAF58 (not verified: never called in the
     * capture)
     *
     * Moves the sprite in slot [index] by ([x], [y]) pixels (`sub_80BAF58` in the decomp; a
     * relative `MapUnitC_SetPosition`), wrapping as `u16`.
     */
    public fun mapUnitCMovePosition(index: Int, x: Int, y: Int) {
        val s = unitProc(index)
        s.x = (s.x + x) and 0xFFFF
        s.y = (s.y + y) and 0xFFFF
    }

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_GetAnimId: the AP animation id (4 = standing).
     */
    public fun mapUnitCGetAnimId(index: Int): Int = unitProc(index).animId

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetAnimId
     *
     * Switches the sprite in slot [index] to AP animation [animId] (4 = standing sprite). The
     * capture has no value that needs the `s8` narrowing.
     */
    public fun mapUnitCSetAnimId(index: Int, animId: Int) {
        val s = unitProc(index)
        s.animId = animId.toByte().toInt()
        ap.switchAnimation(s.ap, s.animId)
    }

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetDisplayEnabled
     *
     * Shows or hides the sprite in slot [index] (`GMAPUNIT_FLAG_DISPLAY`).
     */
    public fun mapUnitCSetDisplayEnabled(index: Int, enabled: Boolean) {
        val s = unitProc(index)
        s.flags = if (enabled) s.flags or FLAG_DISPLAY else s.flags and FLAG_DISPLAY.inv()
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_IsBlendEnabled (not verified: never
     * called in the capture)
     *
     * Whether the sprite in slot [index] is drawn semi-transparent (`GMAPUNIT_FLAG_BLEND`).
     */
    public fun mapUnitCIsBlendEnabled(index: Int): Boolean =
        unitProc(index).flags and FLAG_BLEND != 0

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_SetBlendEnabled (not verified: never
     * called in the capture)
     *
     * Turns semi-transparent drawing of the sprite in slot [index] on or off.
     */
    public fun mapUnitCSetBlendEnabled(index: Int, enabled: Boolean) {
        val s = unitProc(index)
        s.flags = if (enabled) s.flags or FLAG_BLEND else s.flags and FLAG_BLEND.inv()
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_GetLayer (not verified: never called in
     * the capture)
     *
     * The AP OBJ layer (priority node) the sprite's animation is drawn on.
     */
    public fun mapUnitCGetLayer(index: Int): Int = unitProc(index).ap!!.objLayer

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetLayer
     *
     * Sets the AP OBJ layer of the sprite in slot [index], for both the walking animation and
     * the standing sprite.
     */
    public fun mapUnitCSetLayer(index: Int, layer: Int) {
        val s = unitProc(index)
        s.ap!!.objLayer = layer
        s.layer = layer and 0xFFFF
    }

    /**
     * Transcribed from src/worldmap_gmapunit.c: MapUnitC_GetFaction (not verified: never called
     * in the capture)
     *
     * The faction (`FACTION_ID_*`) the sprite's palette was chosen from.
     */
    public fun mapUnitCGetFaction(index: Int): Int = unitProc(index).faction

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetFaction
     *
     * Recolours the sprite in slot [index] to its faction palette; a negative [faction] keeps the
     * stored faction and only reapplies its palette (how a finished fade restores it).
     */
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
     * Sets the OBJ palette bank of the sprite in slot [index] and rewrites its tile word. The
     * capture has no value that needs the `u8` narrowing.
     */
    public fun mapUnitCSetPalette(index: Int, pal: Int) {
        val s = unitProc(index)
        s.pal = pal and 0xFF
        s.ap!!.tileBase = cont().layer + index * 4 + (pal and 0xF) * 0x1000 + s.oam2Base
    }

    /**
     * Ported from src/worldmap_gmapunit.c: MapUnitC_SetGfxNeedsUpdate (the `index < 0` branch is
     * not exercised by the capture)
     *
     * Asks the sprite in slot [index] (every sprite when negative) to re-upload its walking
     * graphics on the next draw (`GMAPUNIT_FLAG_UPDATEGFX`).
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
     * Switches the sprite in slot [index] to the animation of [classId] (0 = the class it was
     * made for) and loads that class's graphics. Returns false when no animation handle is free.
     *
     * NOTE: `sub_80BB0E0` in the decomp. The old handle is freed even when the class is
     * unchanged, and then `s.ap` is left pointing at the freed handle (also on the failure
     * return); the decomp does the same, so this is kept. Likely an original bug, unverified.
     */
    public fun mapUnitCSwitchClass(index: Int, classId: Int): Boolean {
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
     *
     * The class the sprite was created for (`sub_80BB188` in the decomp).
     */
    public fun mapUnitCGetClassId(index: Int): Int = unitProc(index).classId

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB194
     *
     * The class whose animation currently plays (`sub_80BB194` in the decomp).
     */
    public fun mapUnitCGetCurrentClassId(index: Int): Int = unitProc(index).currentClassId

    /**
     * Transcribed from src/worldmap_gmapunit.c: GmapUnitContainer_Destruct (not verified: the
     * container ends after its sprites in the capture's last step)
     *
     * Ends every sprite proc when the container ends. The decomp tests the array pointer for
     * null rather than each slot, so it calls `Proc_End` on empty slots too; ending a null proc
     * is harmless, as here.
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
     *
     * Empties all seven sprite slots.
     */
    public fun gmapUnitContainerInit(proc: Proc) {
        containerState(proc).units.fill(null)
    }

    /**
     * Ported from src/worldmap_gmapunit.c: NewGmapUnitContainer (the `u8` narrowing of `chr` is
     * not exercised by the capture)
     *
     * Creates the container that holds the world map unit sprites, [layer] being the first OBJ
     * tile reserved for them; also makes it `GM_UNITC`.
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
     *
     * When a fade ends, hides the faded sprites if the fade went to black and gives every one
     * back its faction palette (it was switched to the fade palette while fading).
     */
    public fun gmapUnitFadeDestruct(proc: Proc) {
        val s = proc.state as GmapUnitFadeProc
        for (i in 0 until s.count) {
            if (s.palTo < 0) mapUnitCSetDisplayEnabled(s.units[i], false)
            mapUnitCSetFaction(s.units[i], -1)
        }
    }

    /** Ported from src/worldmap_gmapunit.c: GmapUnitFade_Init: restarts the frame counter. */
    public fun gmapUnitFadeInit(proc: Proc) {
        (proc.state as GmapUnitFadeProc).timer = 0
    }

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB26C
     *
     * Named `blendFadeColor` here; `sub_80BB26C` in the decomp.
     *
     * Checked through OBJ palette 0xA of the three unit fades (steps 8185..8450). No fade there
     * starts from palette 0, so that branch of the start palette test is unobserved.
     *
     * One 15-bit colour of a fade: entry [i1] of OBJ palette [pal1] blended towards entry [i2] of
     * [pal2] by [t] (0x1000 = all the way); a negative palette is black.
     *
     * The colour is the GBA's 5-5-5 BGR layout (red bits 0-4, green 5-9, blue 10-14); the
     * `0x100` is the offset of the OBJ palettes in the palette buffer (16 BG palettes first).
     */
    public fun blendFadeColor(pal1: Int, i1: Int, pal2: Int, i2: Int, t: Int): Int {
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
        // Each channel moves (r2 - r1) * t / 0x1000 from its start value.
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
     * Blends OBJ palette 0xA from the start palette to the end palette over `duration` frames;
     * on the last frame it copies the end palette (or black) and ends the fade. Sprites being
     * faded use OBJ palette 0xA (see [fadeInMapUnit]).
     */
    public fun gmapUnitFadeLoop(proc: Proc) {
        val s = proc.state as GmapUnitFadeProc
        s.timer = (s.timer + 1) and 0xFFFF
        val base = FADE_PAL * 0x10 + 0x100
        if (s.timer < s.duration) {
            val t = Spline.divArm1(s.timer, s.duration, 0)
            for (i in 0 until 0x10) {
                hardware.palette[base + i] = blendFadeColor(s.palFrom, i, s.palTo, i, t).toShort()
            }
        } else {
            copyOrClearFadePalette(s.palTo)
            engine.breakProc(proc)
        }
        hardware.enablePaletteSync()
    }

    /**
     * Fills OBJ palette 0xA (the fade palette) with a copy of OBJ palette [pal], or with black
     * when [pal] is negative.
     */
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
     * Returns the fade proc, or null if it could not start.
     *
     * The decomp function returns nothing (its `return proc` is commented out as a bug); this port
     * returns the proc for the callers' convenience.
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
     *
     * Ends all running unit fades at once.
     */
    public fun endGmapUnitFade() {
        engine.endEach(fadeScript)
    }

    /** Ported from src/worldmap_gmapunit.c: GetGmapUnitFade: the running fade, or null. */
    public fun getGmapUnitFade(): Proc? = engine.find(fadeScript)

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB49C
     *
     * Adds the sprite in slot [index] to the running fade, if there is one (`sub_80BB49C` in the
     * decomp).
     *
     * NOTE: the decomp has no bounds check on the seven-entry list of faded slots and neither
     * does this port; more than seven joiners overrun it. Not reached in the capture.
     */
    public fun joinGmapUnitFade(index: Int) {
        val proc = getGmapUnitFade() ?: return
        val s = proc.state as GmapUnitFadeProc
        s.units[s.count] = index
        s.count = (s.count + 1) and 0xFF
    }

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB4C0
     *
     * Fades the sprite in slot [index] in (`sub_80BB4C0` in the decomp): from black to its
     * faction palette over [duration] frames, or joins the running fade. The sprite is switched
     * to fade palette 0xA and shown; [gmapUnitFadeDestruct] restores its own palette.
     */
    public fun fadeInMapUnit(index: Int, duration: Int, parent: Proc?) {
        val s = unitProc(index)
        if (getGmapUnitFade() == null) {
            startGmapUnitFade(index, -1, getPaletteIdForAllegience(s.faction), duration, parent)
        } else {
            joinGmapUnitFade(index)
        }
        mapUnitCSetPalette(index, FADE_PAL)
        mapUnitCSetDisplayEnabled(index, true)
    }

    /**
     * Ported from src/worldmap_gmapunit.c: sub_80BB538 (the final `SetDisplayEnabled(true)` is not
     * checked: every faded-out sprite in the capture is displayed already)
     *
     * Fades the sprite in slot [index] out (`sub_80BB538` in the decomp): from its faction palette
     * to black over [duration] frames, or joins the running fade. The sprite stays displayed
     * until the fade ends ([gmapUnitFadeDestruct] hides it).
     */
    public fun fadeOutMapUnit(index: Int, duration: Int, parent: Proc?) {
        val s = unitProc(index)
        if (getGmapUnitFade() == null) {
            startGmapUnitFade(index, getPaletteIdForAllegience(s.faction), -1, duration, parent)
        } else {
            joinGmapUnitFade(index)
        }
        mapUnitCSetPalette(index, FADE_PAL)
        mapUnitCSetDisplayEnabled(index, true)
    }

    /** Flags and constants of `worldmap_gmapunit.c`. */
    public companion object {
        /** `GMAPUNIT_FLAG_DISPLAY`: the sprite is drawn. */
        public const val FLAG_DISPLAY: Int = 1

        /** `GMAPUNIT_FLAG_BLEND`: the sprite is drawn semi-transparent. */
        public const val FLAG_BLEND: Int = 2

        /** `GMAPUNIT_FLAG_UPDATEGFX`: re-upload the walking graphics on the next draw. */
        public const val FLAG_UPDATEGFX: Int = 4

        /** `gUnknown_08205F7C`: the OBJ tile offset of each standing sprite slot. */
        private val STANDING_FRAME = intArrayOf(0x00, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18)

        /** `PROC_MARK_WMSTUFF` (`include/proc.h`): the proc mark of world map procs. */
        private const val PROC_MARK_WMSTUFF = 8

        /** `PROC_TREE_3` (`include/proc.h`): the root proc tree that has no parent proc. */
        private const val PROC_TREE_3 = 3

        /** `OAM0_BLEND` (`include/ctc.h`): OAM attribute 0 mode bit for semi-transparent. */
        private const val OAM0_BLEND = 0x400

        /** `OAM2_LAYER(2)` (`include/ctc.h`): OBJ priority 2 in OAM attribute 2 bits 10-11. */
        private const val OAM2_PRIORITY_2 = 0x800

        /** The AP OBJ layer (priority node) new sprites are made on (a literal 8 in the decomp). */
        private const val AP_OBJ_LAYER = 8

        /** Start of OBJ tile VRAM (`OBJ_VRAM0`, 0x06010000). */
        private const val OBJ_VRAM = 0x06010000

        /** The OBJ palette bank (`OBPAL_OFFSET(0xA)`) that unit fades are played through. */
        private const val FADE_PAL = 0xA

        /** `gUnknown_0200AF00`: the decompressed map sprite graphics (0x2200 bytes per slot). */
        private const val GFX_BUFFER = 0x0200AF00
        /** Bytes of one slot's graphics buffer (`GetMapUnitMMSGfxBuffer`'s `idx * 0x2200`). */
        private const val GFX_BUFFER_SIZE = 0x2200
    }
}
