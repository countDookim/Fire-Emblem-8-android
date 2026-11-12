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
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.BgTilemap

/**
 * Per-proc state of the full-screen "big map" proc `ProcScr_GmapRM`: the fields of
 * `struct ProcGmapRm` (include/worldmap.h) ported so far. The decomp's `gm_mu` (+0x54) is not
 * kept here; [WorldMapRm.Hooks.removeMuUnit] stands in for the call that uses it.
 */
public class GmapRmProc {
    /**
     * `flag` (+0x29, `GMAPRM_FLAG_*` bits). [WorldMapRm.FLAG_0] set: fade the big map in when it
     * appears. [WorldMapRm.FLAG_1] set: fade it back out to the normal world map when it is
     * unblocked. [WorldMapRm.FLAG_UNBLOCK] set: stop waiting and leave. [WorldMapRm.FLAG_3] set:
     * the party leader's map unit was visible before the big map and is shown again on leaving.
     */
    public var flag: Int = 0

    /** `timer` (+0x2A): frames into the current blend-in or blend-out. */
    public var timer: Int = 0

    /** `x` (+0x2E, `s16`): the BG1 scroll x. */
    public var x: Int = 0

    /** `y` (+0x30, `s16`): the BG1 scroll y. */
    public var y: Int = 0
}

/**
 * Per-proc state of the big map scroll `ProcScr_GmapRmUpdateExt`: the fields of
 * `struct ProcGmapRmUpdate` (include/worldmap.h), the scroll from `(x0, y0)` to `(x1, y1)` that
 * the event command `EventB6_WmMoveBigMap` starts.
 */
public class GmapRmUpdateProc {
    /** `x0` (+0x2A, `s16`): start scroll x. */
    public var x0: Int = 0

    /** `y0` (+0x2C, `s16`): start scroll y. */
    public var y0: Int = 0

    /** `x1` (+0x2E, `s16`): end scroll x. */
    public var x1: Int = 0

    /** `y1` (+0x30, `s16`): end scroll y. */
    public var y1: Int = 0

    /** `delay` (+0x32, `s16`): frames to wait before the scroll starts. */
    public var delay: Int = 0

    /** `timer` (+0x34, `s16`): frames scrolled so far. */
    public var timer: Int = 0

    /** `speed` (+0x36, `s16`): length of the scroll in frames. */
    public var speed: Int = 0
}

/**
 * The full-screen "big map" the world map events show over the normal world map (the event
 * commands `EventB4_WmDisplayBigMap`, `EventB5_WmHideBigMap`, `EventB6_WmMoveBigMap` and
 * `EventB7_WmBigMapWait`, `src/eventscr_gmap.c`): `src/worldmap_rm.c` `ProcScr_GmapRM`, its BG
 * scroll updaters `ProcScr_GmapRmUpdateDirect` / `ProcScr_GmapRmUpdateExt`. While it shows, the
 * big map image is on BG1, the node icons are not drawn and the leader's map unit is hidden. The
 * nation border highlight proc `GmapRmBorder1` is in [WorldMapRmBorder], the blend pulse
 * `GmapRmBaPalAnim1` in [WorldMapRmBaPalAnim].
 *
 * @param engine the proc scheduler
 * @param hardware BG registers, tilemap buffers, VRAM and palette
 * @param rom the ROM image (map graphics)
 * @param gmData `gGMData`
 * @param hooks the unported callees
 */
public class WorldMapRm(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val gmData: GmData,
    private val hooks: Hooks = Hooks(),
) {
    /** `gGenericBuffer` (0x2000 bytes): scratch the big map's tile map is decompressed into. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /**
     * Callees of `worldmap_rm.c` that are not ported yet; each defaults to doing nothing. Note
     * the decomp's `GmNodeIconDisplayProc.skip` is inverted in effect: `GmapScreen2_Loop` draws
     * the node icons only while `skip` is 1 (src/worldmap_screen2.c:180), so [skipNodeIcon]
     * turns the icons on and [unskipNodeIcon] turns them off.
     */
    public class Hooks(
        /**
         * `GM_SCREEN->gmroute->flags = (flags & ~clear) | set` on the route state's
         * `GM_ROUTE_FLAG_*` bits (their meaning is not documented in the decomp).
         */
        public val updateRouteFlags: (clear: Int, set: Int) -> Unit = { _, _ -> },
        /** `SkipGmNodeIconDisplay(GM_ICON)`: sets `skip = 1`, so the node icons are drawn. */
        public val skipNodeIcon: () -> Unit = {},
        /** `UnskipGmNodeIconDisplay(GM_ICON)`: sets `skip = 0`, so no node icons are drawn. */
        public val unskipNodeIcon: () -> Unit = {},
        /** `SetupGmapNodeGfx()`: reloads the node icon graphics. */
        public val setupGmapNodeGfx: () -> Unit = {},
        /** `LoadObjUIGfx()`: reloads the shared OBJ UI graphics. */
        public val loadObjUiGfx: () -> Unit = {},
        /** `LoadLegacyUiFrameGraphics()`: reloads the UI window frame graphics. */
        public val loadLegacyUiFrameGraphics: () -> Unit = {},
        /** `GmMu_RemoveUnit(proc->gm_mu, unit)`: removes one world map unit's map sprite. */
        public val removeMuUnit: (unit: Int) -> Unit = {},
        /** `GmHideMuUnit(GM_MU, WM_MU_0)`: hides the party leader's map sprite. */
        public val hideLeaderMu: () -> Unit = {},
        /** `GmShowMuUnit(GM_MU, WM_MU_0)`: shows the party leader's map sprite again. */
        public val showLeaderMu: () -> Unit = {},
    )

    /**
     * `ProcScr_GmapRmUpdateDirect`: child of the big map proc that copies its position to the
     * BG1 scroll every frame, so the big map scrolls as [gmapRmSetPosition] moves it.
     *
     * Transcribed from src/worldmap_rm.c: ProcScr_GmapRmUpdateDirect (not verified: script order
     * checked against the `procs` capture in WorldMapRmTest)
     */
    public val updateDirectScript: ProcScript = ProcScript(
        "ProcScr_GmapRmUpdateDirect",
        listOf(
            name("Gmap RM update"),
            mark(PROC_MARK_WMSTUFF),
            repeat(ProcFunc { gmapRmUpdateDirectLoop(it) }),
            end(),
        ),
    )

    /**
     * `ProcScr_GmapRmUpdateExt`: waits `delay` frames, then eases the big map's position from
     * `(x0, y0)` to `(x1, y1)` over `speed` frames (the scroll of `EventB6_WmMoveBigMap`).
     *
     * Transcribed from src/worldmap_rm.c: ProcScr_GmapRmUpdateExt (not verified: script order
     * checked against the `procs` capture in WorldMapRmTest)
     */
    public val updateExtScript: ProcScript = ProcScript(
        "ProcScr_GmapRmUpdateExt",
        listOf(
            name("Gmap RM update"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapRmUpdateExtEnd(it) }),
            yield(),
            repeat(ProcFunc { gmapRmUpdateExtDelay(it) }),
            call(ProcFunc { gmapRmUpdateExtInitPosition(it) }),
            repeat(ProcFunc { gmapRmUpdateExtScrollPosition(it) }),
            end(),
        ),
    ) { GmapRmUpdateProc() }

    /**
     * `ProcScr_GmapRM`: loads and shows the big map (fading it in if [FLAG_0]), waits until
     * `GMAPRM_FLAG_UNBLOCK` is set (`EventB5_WmHideBigMap`), then (if [FLAG_1]) fades back to
     * the normal world map; the end callback restores the world map screen.
     *
     * Transcribed from src/worldmap_rm.c: ProcScr_GmapRM (not verified: script order checked
     * against the `procs` capture in WorldMapRmTest)
     */
    public val rmScript: ProcScript = ProcScript(
        "ProcScr_GmapRM",
        listOf(
            name("Gmap RM"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapRmOnEnd(it) }),
            call(ProcFunc { gmapRmInitNop() }),
            sleep(1),
            call(ProcFunc { gmapRmStartUpdateDirect(it) }),
            call(ProcFunc { gmapRmGoto0IfFlag0(it) }),
            call(ProcFunc { gmapRmInitBlend(it) }),
            repeat(ProcFunc { gmapRmBlendIn(it) }),
            label(LABEL_0),
            call(ProcFunc { gmapRmEnterBigMapView(it) }),
            repeat(ProcFunc { gmapRmBlocking(it) }),
            call(ProcFunc { gmapRmGoto1IfFlag1(it) }),
            call(ProcFunc { gmapRmReturnToWorldMap(it) }),
            sleep(1),
            repeat(ProcFunc { gmapRmBlendOut(it) }),
            label(LABEL_1),
            call(ProcFunc { gmapRmFinish(it) }),
            end(),
        ),
    ) { GmapRmProc() }

    /** The [GmapRmProc] state of a `ProcScr_GmapRM` proc. */
    private fun state(proc: Proc): GmapRmProc = proc.state as GmapRmProc

    /**
     * Ported from src/worldmap_rm.c: GmapRmUpdateDirect_Loop
     *
     * Sets the BG1 scroll to the parent big map proc's position (the BG1 offsets at the `f`
     * dumps match).
     */
    public fun gmapRmUpdateDirectLoop(proc: Proc) {
        val parent = state(checkNotNull(proc.parent))
        hardware.lcd.setBgPosition(BG_1, parent.x, parent.y)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmUpdateExt_End (not verified: the update procs end
     * with the position already at the target, so the write cannot be told from a no-op)
     *
     * End callback of [updateExtScript]: snaps the big map to `(x1, y1)`, so ending the scroll
     * early (`EndGmapRmUpdateExt`) still leaves the map at its target.
     */
    public fun gmapRmUpdateExtEnd(proc: Proc) {
        val s = proc.state as GmapRmUpdateProc
        gmapRmSetPosition(s.x1, s.y1)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmUpdateExt_Delay (not verified: every captured
     * delay is 0, so `<= 0` cannot be told from `< 0` after the decrement)
     *
     * `PROC_REPEAT` callback: the wait before the scroll; breaks once `--delay <= 0`.
     */
    public fun gmapRmUpdateExtDelay(proc: Proc) {
        val s = proc.state as GmapRmUpdateProc
        s.delay = (s.delay - 1).toShort().toInt()
        if (s.delay <= 0) engine.breakProc(proc)
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmUpdateExt_InitPosition
     *
     * Puts the map at `(x0, y0)` and clears the timer.
     */
    public fun gmapRmUpdateExtInitPosition(proc: Proc) {
        val s = proc.state as GmapRmUpdateProc
        gmapRmSetPosition(s.x0, s.y0)
        s.timer = 0
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRmUpdateExt_ScrollPosition
     *
     * One frame of the eased scroll: moves the position the fraction `sub_8014CA4(timer, speed,
     * 0x800, 0x800)` (0x1000 = whole way, ease-in and ease-out of 0x800 each) of the way from
     * `(x0, y0)` to `(x1, y1)`; at frame `speed` it lands on `(x1, y1)` and ends the repeat.
     */
    public fun gmapRmUpdateExtScrollPosition(proc: Proc) {
        val s = proc.state as GmapRmUpdateProc
        s.timer = (s.timer + 1).toShort().toInt()
        if (s.timer < s.speed) {
            val ret = Spline.sub8014CA4(s.timer, s.speed, 0x800, 0x800)
            val x = s.x0 + Spline.divArm(0x1000, (s.x1 - s.x0) * ret)
            val y = s.y0 + Spline.divArm(0x1000, (s.y1 - s.y0) * ret)
            gmapRmSetPosition(x.toShort().toInt(), y.toShort().toInt())
        } else {
            gmapRmSetPosition(s.x1, s.y1)
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: StartGmapRmUpdateExt
     *
     * Starts the big map scroll of `EventB6_WmMoveBigMap`. Values are truncated to `s16` like
     * the proc fields.
     *
     * @param x0 start scroll x
     * @param y0 start scroll y
     * @param x1 end scroll x
     * @param y1 end scroll y
     * @param speed length of the scroll in frames
     * @param delay frames to wait before it starts
     * @param parent blocked until the scroll ends, or null for a tree 3 root
     */
    public fun startGmapRmUpdateExt(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        speed: Int,
        delay: Int,
        parent: Proc?,
    ): Proc {
        val proc = if (parent != null) {
            checkNotNull(engine.startBlocking(updateExtScript, parent))
        } else {
            engine.start(updateExtScript, 3)
        }
        val s = proc.state as GmapRmUpdateProc
        s.x0 = x0.toShort().toInt()
        s.y0 = y0.toShort().toInt()
        s.x1 = x1.toShort().toInt()
        s.y1 = y1.toShort().toInt()
        s.speed = speed.toShort().toInt()
        s.delay = delay.toShort().toInt()
        return proc
    }

    /**
     * Transcribed from src/worldmap_rm.c: EndGmapRmUpdateExt (not verified: never called in the
     * capture).
     *
     * Ends every running big map scroll (each end callback snaps to its target).
     */
    public fun endGmapRmUpdateExt() {
        engine.endEach(updateExtScript)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_OnEnd (not verified: the capture's `GmapRM` ends
     * the frame the trace stops showing its screen, and most callees are hooks)
     *
     * End callback of [rmScript]: puts the normal world map screen back (route flags 4 and 3 set,
     * node icons drawn, node and OBJ UI graphics reloaded, BG1 cleared, UI frame graphics
     * reloaded, `state_3` off).
     */
    public fun gmapRmOnEnd(proc: Proc) {
        hooks.updateRouteFlags(0, 4)
        hooks.updateRouteFlags(0, 3)
        hooks.skipNodeIcon()
        hooks.setupGmapNodeGfx()
        hooks.loadObjUiGfx()
        hardware.fill(hardware.mapBuffer(BG_1), 0)
        hardware.enableBgSyncByMask(BG1_SYNC_BIT)
        hooks.loadLegacyUiFrameGraphics()
        gmData.state3 = false
    }

    /** Transcribed from src/worldmap_rm.c: GmapRm_InitNop (not verified: an empty function). */
    public fun gmapRmInitNop() {
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_StartUpdateDirect
     *
     * Loads the big map: clears `GM_ROUTE_FLAG_2` (via the hook), BG1 scrolled to the origin,
     * `Img_EventGmap` to BG VRAM, its palette `Pal_EventGmap` to palette banks 5-8,
     * `Tsa_EventGmap` into the BG1 tilemap buffer, then starts [updateDirectScript] as a child.
     * The tile map entries get 0x5000 added (`CallARM_FillTileRect`'s base), which sets palette
     * bank 5 in bits 12-15 of every entry so they use the palette loaded above.
     */
    public fun gmapRmStartUpdateDirect(proc: Proc) {
        hooks.updateRouteFlags(ROUTE_FLAG_2, 0)
        hardware.lcd.setBgPosition(BG_1, 0, 0)
        rom.decompressInto(IMG_EVENT_GMAP, hardware.vram, 0)
        rom.pal(PAL_EVENT_GMAP, 16 * 4).copyInto(hardware.palette, 5 * 16)
        hardware.enablePaletteSync()
        rom.decompressInto(TSA_EVENT_GMAP, genericBuffer, 0)
        BgTilemap.applyTsa(hardware.mapBuffer(BG_1), 0, genericBuffer, 0, 0x5000)
        hardware.enableBgSyncByMask(BG1_SYNC_BIT)
        engine.start(updateDirectScript, proc)
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_Goto0IfFlag0
     *
     * Skips the blend-in (jumps to [LABEL_0]) unless [FLAG_0] is set.
     */
    public fun gmapRmGoto0IfFlag0(proc: Proc) {
        if (state(proc).flag and FLAG_0 == 0) engine.goto(proc, LABEL_0)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_Goto1IfFlag1 (not verified: runs after the
     * unblock).
     *
     * Skips the blend-out back to the world map (jumps to [LABEL_1]) unless [FLAG_1] is set.
     */
    public fun gmapRmGoto1IfFlag1(proc: Proc) {
        if (state(proc).flag and FLAG_1 == 0) engine.goto(proc, LABEL_1)
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_Blocking
     *
     * `PROC_REPEAT` callback: the wait while the big map is shown; ends the repeat once
     * `GMAPRM_FLAG_UNBLOCK` is set. The capture covers only the blocked branch (the proc stays
     * at the repeat from step 4948 to 9285).
     */
    public fun gmapRmBlocking(proc: Proc) {
        if (state(proc).flag and FLAG_UNBLOCK != 0) engine.breakProc(proc)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_InitBlend (not verified: the capture's flag 0 is
     * never set, so the blend-in branch of the script is skipped)
     *
     * Starts the big map blend-in: alpha blending (`SetBlendConfig` effect 1) with BG1 (the big
     * map) as the first target and BG2, BG3 and OBJ as the second, weights 0x10 / 0, all layers
     * enabled, timer 0.
     */
    public fun gmapRmInitBlend(proc: Proc) {
        val lcd = hardware.lcd
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 1, 1)
        lcd.setBlendConfig(1, 0x10, 0, 0)
        lcd.setDispEnable(1, 1, 1, 1, 1)
        state(proc).timer = 0
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_BlendIn (not verified: see [gmapRmInitBlend]).
     *
     * `PROC_REPEAT` callback: 75 frames (`timer < 75`) of the blend ramp: the BG1 (big map) weight
     * is `timer * 0x10 / 75` and the second target's `0x10` minus that, then ends the repeat.
     */
    public fun gmapRmBlendIn(proc: Proc) {
        val s = state(proc)
        s.timer = (s.timer + 1) and 0xFFFF
        if (s.timer < 75) {
            val value = (s.timer * 0x10) / 75
            hardware.lcd.setBlendConfig(1, value, 0x10 - value, 0)
        } else {
            engine.breakProc(proc)
        }
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_80C214C (not verified: only run after the
     * unblock, past the frames the capture's screen dumps cover)
     *
     * Start of the way back to the normal world map (only with [FLAG_1]): reloads `Img_GmapPath`
     * into BG VRAM at byte 0x5000 (where `worldmap_main.c:365` loads it for the world map;
     * presumably because the big map image overwrote it, which the decomp does not state), sets
     * `GM_ROUTE_FLAG_2`, `GM_ROUTE_FLAG_0` and `GM_ROUTE_FLAG_1`, draws the node icons, shows the
     * leader's map unit again if [FLAG_3], restores BG priorities 0/1/3/3 and sets alpha blending
     * (BG1 against BG2, BG3 and OBJ, BG1 weight 0x10) for [gmapRmBlendOut].
     *
     * Named `GmapRm_80C214C` in the decomp (placeholder).
     */
    public fun gmapRmReturnToWorldMap(proc: Proc) {
        rom.decompressInto(IMG_GMAP_PATH, hardware.vram, PATH_VRAM_OFFSET)
        hooks.updateRouteFlags(0, ROUTE_FLAG_2)
        hooks.updateRouteFlags(0, 3)
        hooks.skipNodeIcon()
        hardware.lcd.setBlendConfig(1, 0x10, 0, 0)
        gmapRmDisplayLeaderUnit(proc)
        setPriorities(0, 1, 3, 3)
        hardware.lcd.setBlendTargetA(0, 1, 0, 0, 0)
        hardware.lcd.setBlendTargetB(0, 0, 1, 1, 1)
        hardware.lcd.setDispEnable(1, 1, 1, 1, 1)
        gmData.state3 = false
        hooks.setupGmapNodeGfx()
        hooks.loadObjUiGfx()
        state(proc).timer = 0
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_BlendOut (not verified: see
     * [gmapRmReturnToWorldMap]).
     *
     * `PROC_REPEAT` callback: the 75-frame reverse of [gmapRmBlendIn] (the BG1 weight is
     * `0x10 - timer * 0x10 / 75`), then ends the repeat.
     */
    public fun gmapRmBlendOut(proc: Proc) {
        val s = state(proc)
        s.timer = (s.timer + 1) and 0xFFFF
        if (s.timer < 75) {
            val value = (s.timer * 0x10) / 75
            hardware.lcd.setBlendConfig(1, 0x10 - value, value, 0)
        } else {
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_RemoveUnits
     *
     * Removes the map sprite of every other world map unit (slots `WM_MU_2` until `WM_MU_MAX`)
     * that is in use and standing at the leader's (`WM_MU_0`) location, sets `GMAPRM_FLAG_3`
     * from the leader's `GM_UNIT_STATE_B0` (meaning of that unit state bit unknown) and hides the
     * leader's MU. The capture covers the flag and not the removal loop (no other unit shares
     * the leader's node).
     */
    public fun gmapRmRemoveUnits(proc: Proc) {
        for (i in WM_MU_2 until WM_MU_MAX) {
            if (gmData.unitId(i) == 0) continue
            if (gmData.unitLocation(WM_MU_0) != gmData.unitLocation(i)) continue
            hooks.removeMuUnit(i)
        }
        val s = state(proc)
        s.flag = if (gmData.unitState(WM_MU_0) and GM_UNIT_STATE_B0 != 0) {
            s.flag or FLAG_3
        } else {
            s.flag and FLAG_3.inv()
        }
        hooks.hideLeaderMu()
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_DisplayLeaderUnit (not verified: see
     * [gmapRmReturnToWorldMap]).
     *
     * Shows the leader's map unit again if [FLAG_3] recorded it was visible.
     */
    public fun gmapRmDisplayLeaderUnit(proc: Proc) {
        if (state(proc).flag and FLAG_3 != 0) hooks.showLeaderMu()
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_80C2320
     *
     * Switches the screen to "big map shown" once the fade-in is done: no node icons, other
     * units removed and leader hidden ([gmapRmRemoveUnits]), BG priorities 0/3/1/3 (the cleared
     * BG2 above the big map on BG1) and `state_3` set, which makes the world map face and effect
     * code use BG2 (`worldmap_face.c:59`).
     *
     * Named `GmapRm_80C2320` in the decomp (placeholder).
     */
    public fun gmapRmEnterBigMapView(proc: Proc) {
        hooks.unskipNodeIcon()
        hardware.lcd.setBlendConfig(0, 0x10, 0x10, 0)
        gmapRmRemoveUnits(proc)
        setPriorities(0, 3, 1, 3)
        hardware.fill(hardware.mapBuffer(BG_2), 0)
        hardware.enableBgSyncByMask(BG2_SYNC_BIT)
        gmData.state3 = true
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_80C2398 (not verified: see
     * [gmapRmReturnToWorldMap])
     *
     * Last step of [rmScript]: draws the node icons, shows the leader's map unit again if
     * [FLAG_3], restores BG priorities 0/1/3/3, `state_3` off, and ends the scroll updater child.
     *
     * Named `GmapRm_80C2398` in the decomp (placeholder).
     */
    public fun gmapRmFinish(proc: Proc) {
        hooks.skipNodeIcon()
        hardware.lcd.setBlendConfig(0, 0x10, 0x10, 0)
        gmapRmDisplayLeaderUnit(proc)
        setPriorities(0, 1, 3, 3)
        hardware.lcd.setDispEnable(1, 1, 1, 1, 1)
        gmData.state3 = false
        engine.endEach(updateDirectScript)
    }

    /**
     * Sets the draw priority (the low 2 bits of BGxCNT, `gLCDControlBuffer.bgNcnt.priority`; 0 is
     * on top) of the four BGs.
     */
    private fun setPriorities(bg0: Int, bg1: Int, bg2: Int, bg3: Int) {
        val cnt = hardware.lcd.bgCnt
        cnt[0] = (cnt[0] and 3.inv()) or bg0
        cnt[1] = (cnt[1] and 3.inv()) or bg1
        cnt[2] = (cnt[2] and 3.inv()) or bg2
        cnt[3] = (cnt[3] and 3.inv()) or bg3
    }

    /**
     * Ported from src/worldmap_rm.c: StartGmapRm
     *
     * Starts the big map of `EventB4_WmDisplayBigMap`.
     *
     * @param x initial scroll x (`s16`)
     * @param y initial scroll y (`s16`)
     * @param mask the initial flag bits (`GMAPRM_FLAG_UNBLOCK` is always cleared)
     * @param parent blocked until the map ends, or null for a tree 3 root
     */
    public fun startGmapRm(x: Int, y: Int, mask: Int, parent: Proc?): Proc {
        val proc = if (parent != null) {
            checkNotNull(engine.startBlocking(rmScript, parent))
        } else {
            engine.start(rmScript, 3)
        }
        val s = state(proc)
        s.flag = FLAG_UNBLOCK.inv() and mask and 0xFF
        s.x = x.toShort().toInt()
        s.y = y.toShort().toInt()
        return proc
    }

    /** Ported from src/worldmap_rm.c: EndGmapRM: ends the big map proc (its end callback runs). */
    public fun endGmapRm() {
        engine.endEach(rmScript)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRMExists (not verified: only the event wait calls
     * it).
     *
     * Whether the big map is showing; `EventB7_WmBigMapWait` waits on it.
     */
    public fun gmapRmExists(): Boolean = engine.find(rmScript) != null

    /**
     * Ported from src/worldmap_rm.c: GmapRm_SetPosition
     *
     * Sets the position of the first [rmScript] proc, if any.
     */
    public fun gmapRmSetPosition(x: Int, y: Int) {
        val proc = engine.find(rmScript) ?: return
        state(proc).x = x.toShort().toInt()
        state(proc).y = y.toShort().toInt()
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_GetPosition
     *
     * Verified through the border sprites' OAM positions in WorldMapEventCameraTest (the map
     * is at y 24 for the first highlight and y 0 later).
     *
     * @return the position of the first [rmScript] proc as `(x, y)`, or null if there is none
     */
    public fun gmapRmGetPosition(): Pair<Int, Int>? {
        val proc = engine.find(rmScript) ?: return null
        return state(proc).x to state(proc).y
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_SetUnblocked (not verified: the capture's map is
     * ended by the event engine without the flag)
     *
     * Sets `GMAPRM_FLAG_UNBLOCK` on the first [rmScript] proc, letting [gmapRmBlocking] end.
     */
    public fun gmapRmSetUnblocked() {
        val proc = engine.find(rmScript) ?: return
        state(proc).flag = state(proc).flag or FLAG_UNBLOCK
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_EndAll (not verified: only the skipping branch of
     * the event commands calls it)
     *
     * Ends the big map at once (event skip): restores the world map layer state like
     * [gmapRmFinish], turns every layer off with `SetDispEnable(0, 0, 0, 0, 0)` and ends the proc.
     */
    public fun gmapRmEndAll() {
        val proc = engine.find(rmScript) ?: return
        hooks.skipNodeIcon()
        hardware.lcd.setBlendConfig(0, 0x10, 0x10, 0)
        gmapRmDisplayLeaderUnit(proc)
        setPriorities(0, 1, 3, 3)
        gmData.state3 = false
        hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
        engine.endEach(updateDirectScript)
        engine.end(proc)
    }

    /** `worldmap_rm.c` constants. */
    public companion object {
        /** `PROC_MARK_WMSTUFF`: proc mark shared by the world map procs (include/constants). */
        public const val PROC_MARK_WMSTUFF: Int = 8

        /** `GMAPRM_FLAG_0`: fade the big map in when it starts. */
        public const val FLAG_0: Int = 1

        /** `GMAPRM_FLAG_1`: fade back to the world map after the unblock. */
        public const val FLAG_1: Int = 2

        /** `GMAPRM_FLAG_UNBLOCK`: set by [WorldMapRm.gmapRmSetUnblocked] to leave the big map. */
        public const val FLAG_UNBLOCK: Int = 4

        /** `GMAPRM_FLAG_3`: the leader's map unit is shown again on leaving. */
        public const val FLAG_3: Int = 8

        /** `GMAPRM_LABEL_0`: the wait, past the blend-in. */
        public const val LABEL_0: Int = 0

        /** `GMAPRM_LABEL_1`: the end, past the blend-out. */
        public const val LABEL_1: Int = 1

        /** `BG_1` / `BG_2` (include/bg.h). */
        private const val BG_1 = 1
        private const val BG_2 = 2

        /** `BG1_SYNC_BIT` / `BG2_SYNC_BIT` (include/hardware.h): `1 << bg`. */
        private const val BG1_SYNC_BIT = 2
        private const val BG2_SYNC_BIT = 4

        /** `GM_ROUTE_FLAG_2` (include/worldmap.h). */
        private const val ROUTE_FLAG_2 = 4

        /** `GM_UNIT_STATE_B0`: bit 0 of a world map unit's state. */
        private const val GM_UNIT_STATE_B0 = 1

        /** `WM_MU_0` (the party leader), `WM_MU_2`, `WM_MU_MAX` (constants/worldmap.h). */
        private const val WM_MU_0 = 0
        private const val WM_MU_2 = 2
        private const val WM_MU_MAX = 7

        /** Size of `gGenericBuffer`. */
        private const val GENERIC_BUFFER_SIZE = 0x2000

        /** Byte offset in BG VRAM where `Img_GmapPath` is loaded (`BG_VRAM + 0x5000`). */
        private const val PATH_VRAM_OFFSET = 0x5000

        /** ROM addresses of `Img_EventGmap`, `Tsa_EventGmap`, `Pal_EventGmap`, `Img_GmapPath`. */
        private const val IMG_EVENT_GMAP = 0x08A99140
        private const val TSA_EVENT_GMAP = 0x08A9DF74
        private const val PAL_EVENT_GMAP = 0x08A9E4C4
        private const val IMG_GMAP_PATH = 0x08A97ED8
    }
}
