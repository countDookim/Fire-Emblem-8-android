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

/** The fields of `struct ProcGmapRm` ported so far. */
public class GmapRmProc {
    /** `flag` (+0x29, `GMAPRM_FLAG_*` bits). */
    public var flag: Int = 0

    /** `timer` (+0x2A). */
    public var timer: Int = 0

    /** `x` (+0x2E, `s16`): the BG1 scroll x. */
    public var x: Int = 0

    /** `y` (+0x30, `s16`): the BG1 scroll y. */
    public var y: Int = 0
}

/** The fields of `struct ProcGmapRmUpdate`. */
public class GmapRmUpdateProc {
    /** `x0` (+0x2A, `s16`). */
    public var x0: Int = 0

    /** `y0` (+0x2C, `s16`). */
    public var y0: Int = 0

    /** `x1` (+0x2E, `s16`). */
    public var x1: Int = 0

    /** `y1` (+0x30, `s16`). */
    public var y1: Int = 0

    /** `delay` (+0x32, `s16`). */
    public var delay: Int = 0

    /** `timer` (+0x34, `s16`). */
    public var timer: Int = 0

    /** `speed` (+0x36, `s16`). */
    public var speed: Int = 0
}

/**
 * The big map BG of the world map (`src/worldmap_rm.c`: `ProcScr_GmapRM`, its BG scroll updaters
 * `ProcScr_GmapRmUpdateDirect` / `ProcScr_GmapRmUpdateExt`). The nation border highlight proc
 * `GmapRmBorder1` is in [WorldMapRmBorder], the blend pulse `GmapRmBaPalAnim1` in
 * [WorldMapRmBaPalAnim].
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
    /** `gGenericBuffer` (0x2000 bytes): scratch for the tilemap decompression. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /** Callees of `worldmap_rm.c` that are not ported yet; each defaults to doing nothing. */
    public class Hooks(
        /** `GM_SCREEN->gmroute->flags = (flags & ~clear) | set`. */
        public val updateRouteFlags: (clear: Int, set: Int) -> Unit = { _, _ -> },
        /** `SkipGmNodeIconDisplay(GM_ICON)`. */
        public val skipNodeIcon: () -> Unit = {},
        /** `UnskipGmNodeIconDisplay(GM_ICON)`. */
        public val unskipNodeIcon: () -> Unit = {},
        /** `SetupGmapNodeGfx()`. */
        public val setupGmapNodeGfx: () -> Unit = {},
        /** `LoadObjUIGfx()`. */
        public val loadObjUiGfx: () -> Unit = {},
        /** `LoadLegacyUiFrameGraphics()`. */
        public val loadLegacyUiFrameGraphics: () -> Unit = {},
        /** `GmMu_RemoveUnit(proc->gm_mu, unit)`. */
        public val removeMuUnit: (unit: Int) -> Unit = {},
        /** `GmHideMuUnit(GM_MU, WM_MU_0)`. */
        public val hideLeaderMu: () -> Unit = {},
        /** `GmShowMuUnit(GM_MU, WM_MU_0)`. */
        public val showLeaderMu: () -> Unit = {},
    )

    /**
     * `ProcScr_GmapRmUpdateDirect`: keeps the BG1 scroll at the `ProcScr_GmapRM` position.
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
     * `ProcScr_GmapRmUpdateExt`: scrolls the `ProcScr_GmapRM` position from `(x0, y0)` to `(x1,
     * y1)`.
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
     * `ProcScr_GmapRM`: shows the big map BG until `GMAPRM_FLAG_UNBLOCK` is set.
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
            call(ProcFunc { gmapRm80C2320(it) }),
            repeat(ProcFunc { gmapRmBlocking(it) }),
            call(ProcFunc { gmapRmGoto1IfFlag1(it) }),
            call(ProcFunc { gmapRm80C214C(it) }),
            sleep(1),
            repeat(ProcFunc { gmapRmBlendOut(it) }),
            label(LABEL_1),
            call(ProcFunc { gmapRm80C2398(it) }),
            end(),
        ),
    ) { GmapRmProc() }

    private fun state(proc: Proc): GmapRmProc = proc.state as GmapRmProc

    /**
     * Ported from src/worldmap_rm.c: GmapRmUpdateDirect_Loop
     *
     * Sets the BG1 scroll to the parent's position (the BG1 offsets at the `f` dumps match).
     */
    public fun gmapRmUpdateDirectLoop(proc: Proc) {
        val parent = state(checkNotNull(proc.parent))
        hardware.lcd.setBgPosition(BG_1, parent.x, parent.y)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmUpdateExt_End (not verified: the update procs end
     * with the
     * position already at the target, so the write cannot be told from a no-op)
     */
    public fun gmapRmUpdateExtEnd(proc: Proc) {
        val s = proc.state as GmapRmUpdateProc
        gmapRmSetPosition(s.x1, s.y1)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRmUpdateExt_Delay (not verified: every captured
     * delay is 0, so
     * `<= 0` cannot be told from `< 0` after the decrement)
     *
     * `PROC_REPEAT` callback: breaks once `--delay <= 0`.
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
     * One frame of the eased scroll (ease-in and ease-out of 0x800 each); ends the repeat at
     * `speed`.
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
     */
    public fun endGmapRmUpdateExt() {
        engine.endEach(updateExtScript)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_OnEnd (not verified: the capture's `GmapRM` ends
     * the
     * frame the trace stops showing its screen, and most callees are hooks)
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

    /** Transcribed from src/worldmap_rm.c: GmapRm_InitNop (not verified: a no-op). */
    public fun gmapRmInitNop() {
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_StartUpdateDirect
     *
     * Loads the big map: BG1 scrolled to the origin, `Img_EventGmap` to BG VRAM, its palette to
     * banks
     * 5-8, `Tsa_EventGmap` into the BG1 tilemap buffer (tile base 0x5000), then starts
     * [updateDirectScript] as a child.
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

    /** Ported from src/worldmap_rm.c: GmapRm_Goto0IfFlag0 */
    public fun gmapRmGoto0IfFlag0(proc: Proc) {
        if (state(proc).flag and FLAG_0 == 0) engine.goto(proc, LABEL_0)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_Goto1IfFlag1 (not verified: runs after the
     * unblock).
     */
    public fun gmapRmGoto1IfFlag1(proc: Proc) {
        if (state(proc).flag and FLAG_1 == 0) engine.goto(proc, LABEL_1)
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_Blocking
     *
     * `PROC_REPEAT` callback: ends the repeat once `GMAPRM_FLAG_UNBLOCK` is set. The capture
     * covers only
     * the blocked branch (the proc stays at the repeat from step 4948 to 9285).
     */
    public fun gmapRmBlocking(proc: Proc) {
        if (state(proc).flag and FLAG_UNBLOCK != 0) engine.breakProc(proc)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_InitBlend (not verified: the capture's flag 0 is
     * never
     * set, so the blend-in branch of the script is skipped)
     */
    public fun gmapRmInitBlend(proc: Proc) {
        val lcd = hardware.lcd
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 1, 1)
        lcd.setBlendConfig(1, 0x10, 0, 0)
        lcd.setDispEnable(1, 1, 1, 1, 1)
        state(proc).timer = 0
    }

    /** Transcribed from src/worldmap_rm.c: GmapRm_BlendIn (not verified: see [gmapRmInitBlend]). */
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
     * unblock, past the
     * frames the capture's screen dumps cover)
     */
    public fun gmapRm80C214C(proc: Proc) {
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

    /** Transcribed from src/worldmap_rm.c: GmapRm_BlendOut (not verified: see [gmapRm80C214C]). */
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
     * Removes every unit slot 2.. that is in use at the leader's location, sets `GMAPRM_FLAG_3`
     * from the
     * leader's `GM_UNIT_STATE_B0` and hides the leader's MU. The capture covers the flag and not
     * the
     * removal loop (no other unit shares the leader's node).
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
     * [gmapRm80C214C]).
     */
    public fun gmapRmDisplayLeaderUnit(proc: Proc) {
        if (state(proc).flag and FLAG_3 != 0) hooks.showLeaderMu()
    }

    /**
     * Ported from src/worldmap_rm.c: GmapRm_80C2320
     *
     * Hides the node icon and units, sets BG priorities 0/3/1/3 and clears the BG2 tilemap.
     */
    public fun gmapRm80C2320(proc: Proc) {
        hooks.unskipNodeIcon()
        hardware.lcd.setBlendConfig(0, 0x10, 0x10, 0)
        gmapRmRemoveUnits(proc)
        setPriorities(0, 3, 1, 3)
        hardware.fill(hardware.mapBuffer(BG_2), 0)
        hardware.enableBgSyncByMask(BG2_SYNC_BIT)
        gmData.state3 = true
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_80C2398 (not verified: see [gmapRm80C214C])
     */
    public fun gmapRm80C2398(proc: Proc) {
        hooks.skipNodeIcon()
        hardware.lcd.setBlendConfig(0, 0x10, 0x10, 0)
        gmapRmDisplayLeaderUnit(proc)
        setPriorities(0, 1, 3, 3)
        hardware.lcd.setDispEnable(1, 1, 1, 1, 1)
        gmData.state3 = false
        engine.endEach(updateDirectScript)
    }

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

    /** Transcribed from src/worldmap_rm.c: EndGmapRM (not verified: not reached in the capture). */
    public fun endGmapRm() {
        engine.endEach(rmScript)
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRMExists (not verified: only the event wait calls
     * it).
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
     * ended
     * by the event engine without the flag)
     *
     * Sets `GMAPRM_FLAG_UNBLOCK` on the first [rmScript] proc, letting [gmapRmBlocking] end.
     */
    public fun gmapRmSetUnblocked() {
        val proc = engine.find(rmScript) ?: return
        state(proc).flag = state(proc).flag or FLAG_UNBLOCK
    }

    /**
     * Transcribed from src/worldmap_rm.c: GmapRm_EndAll (not verified: only the skipping branch of
     * the
     * event commands calls it)
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
        /** `PROC_MARK_WMSTUFF`. */
        public const val PROC_MARK_WMSTUFF: Int = 8

        /** `GMAPRM_FLAG_0`. */
        public const val FLAG_0: Int = 1

        /** `GMAPRM_FLAG_1`. */
        public const val FLAG_1: Int = 2

        /** `GMAPRM_FLAG_UNBLOCK`. */
        public const val FLAG_UNBLOCK: Int = 4

        /** `GMAPRM_FLAG_3`. */
        public const val FLAG_3: Int = 8

        /** `GMAPRM_LABEL_0`. */
        public const val LABEL_0: Int = 0

        /** `GMAPRM_LABEL_1`. */
        public const val LABEL_1: Int = 1

        private const val BG_1 = 1
        private const val BG_2 = 2
        private const val BG1_SYNC_BIT = 2
        private const val BG2_SYNC_BIT = 4
        private const val ROUTE_FLAG_2 = 4
        private const val GM_UNIT_STATE_B0 = 1
        private const val WM_MU_0 = 0
        private const val WM_MU_2 = 2
        private const val WM_MU_MAX = 7
        private const val GENERIC_BUFFER_SIZE = 0x2000
        private const val PATH_VRAM_OFFSET = 0x5000
        private const val IMG_EVENT_GMAP = 0x08A99140
        private const val TSA_EVENT_GMAP = 0x08A9DF74
        private const val PAL_EVENT_GMAP = 0x08A9E4C4
        private const val IMG_GMAP_PATH = 0x08A97ED8
    }
}
