package fe8.game.bm

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware

/**
 * The per-frame map-display proc `sProc_BMVSync` of src/bmio.c, which runs on the VSync tree
 * while the chapter map is shown (started by `BMapVSync_Start`). Each frame it (1) copies the
 * next frame of the chapter's animated map tile graphics into the tileset area of BG VRAM,
 * (2) copies the next set of animated map palette colours into palette bank 6 (the map's
 * animated colours), (3) swaps the unit sprite sheet frame and (4) runs the weather effect.
 * This class owns (1) and (2); (3) and (4) are the [hooks].
 *
 * Both animations walk a table in the ROM chapter data (`map.objAnimId`, `map.paletteAnimId`):
 * each entry holds a delay in frames and what to copy; after the last entry (a zero delay) the
 * table starts again. Which map tiles or colours animate is chapter data (this file does not
 * know).
 *
 * The state is the proc's own fields (`BMVSyncProc` 0x2C..0x3F); there is one such proc.
 * Nothing is allocated per frame.
 */
public class MapAnimations(
    /** ROM the animation tables are read from. */
    private val rom: RomView,
    /** Chapter table access (`map.objAnimId`, `map.paletteAnimId`). */
    private val chapterData: ChapterData,
    /** BG VRAM and palette buffer. */
    private val hardware: BgHardware,
    /** `gPlaySt` (`chapterIndex`, which chooses the chapter's animation tables). */
    private val playSt: PlaySt,
    /** The proc engine scheduling [procScrBmVSync]. */
    private val engine: ProcEngine? = null,
    /** `gBmSt` (`gameGfxSemaphore`, the count of `BMapDispSuspend` calls without a resume). */
    private val bmSt: BmSt? = null,
    /** External callbacks invoked during VSync proc lifecycle. */
    public val hooks: Hooks = Hooks(),
) {
    /** Callbacks invoked by [procScrBmVSync] and [start]. */
    public class Hooks {
        /** `WfxInit`, called by [start]: sets up the chapter's weather effect (snow, rain, ...). */
        public var wfxInit: () -> Unit = {}

        /**
         * `SyncUnitSpriteSheet`, called by [procScrBmVSync] each frame: copies the map unit
         * sprite sheet frame that the game clock (period 72 frames) selects into OBJ VRAM.
         */
        public var syncUnitSpriteSheet: () -> Unit = {}

        /** `WfxVSync`, called by [procScrBmVSync] each frame: moves the weather effect on. */
        public var wfxVSync: () -> Unit = {}

        /**
         * `BMapVSync_OnEnd`, called when [procScrBmVSync] ends: in the C it removes the
         * secondary HBlank handler (`SetSecondaryHBlankHandler(NULL)`), which the weather
         * effects use.
         */
        public var onEnd: () -> Unit = {}

        /** Alias for [onEnd]. */
        public var bmapVSyncOnEnd: () -> Unit
            get() = onEnd
            set(value) { onEnd = value }
    }

    /**
     * Animation cursors (`BMVSyncProc` fields). `*Start` is the first entry of the chapter's
     * table (a ROM address; 0 means no animation), `*Current` the entry to copy next and
     * `*Clock` the frames left before that copy.
     */
    private var tileGfxAnimStart = 0
    private var tileGfxAnimCurrent = 0
    private var tileGfxAnimClock = 0
    private var tilePalAnimStart = 0
    private var tilePalAnimCurrent = 0
    private var tilePalAnimClock = 0

    /** The tile animation clock (`BMVSyncProc.tileGfxAnimClock`), for differential testing. */
    public val gfxAnimClock: Int get() = tileGfxAnimClock

    /**
     * The current tile animation entry pointer (`BMVSyncProc.tileGfxAnimCurrent`), for
     * differential testing.
     */
    public val gfxAnimCurrent: Int get() = tileGfxAnimCurrent

    /**
     * The start tile animation entry pointer (`BMVSyncProc.tileGfxAnimStart`), for differential
     * testing.
     */
    public val gfxAnimStart: Int get() = tileGfxAnimStart

    /** The palette animation clock (`BMVSyncProc.tilePalAnimClock`), for differential testing. */
    public val palAnimClock: Int get() = tilePalAnimClock

    /**
     * The current palette animation entry pointer (`BMVSyncProc.tilePalAnimCurrent`), for
     * differential testing.
     */
    public val palAnimCurrent: Int get() = tilePalAnimCurrent

    /**
     * The start palette animation entry pointer (`BMVSyncProc.tilePalAnimStart`), for differential
     * testing.
     */
    public val palAnimStart: Int get() = tilePalAnimStart

    /**
     * `sProc_BMVSync` (src/bmio.c:121, ROM 0x0859D8B8, 10 commands): the map display's
     * per-frame proc (`gProc_VBlankHandler` in older notes), started on tree 0. Marked
     * `PROC_MARK_DISP` so that `BMapDispSuspend` (battle animation, menus that take over the
     * display) freezes it and `BMapDispResume` restarts it.
     *
     * Transcribed from src/bmio.c: sProc_BMVSync (not verified: data symbol; script execution
     * verified via
     *   BMapVSync_Start and BMapVSync_OnLoop)
     */
    public val procScrBmVSync: ProcScript = ProcScript(
        "sProc_BMVSync",
        listOf<ProcCmd>(
            mark(PROC_MARK_DISP),
            setEndCb(ProcFunc { onEnd(it) }),
            sleep(0),
            label(0),
            call(ProcFunc { updateMapImgAnimations() }),
            call(ProcFunc { updateMapPalAnimations() }),
            call(ProcFunc { hooks.syncUnitSpriteSheet() }),
            call(ProcFunc { hooks.wfxVSync() }),
            ProcScripts.repeat(ProcFunc { onLoop(it) }),
            ProcScripts.end(),
        ),
    )

    /** Alias matching the C symbol name `sProc_BMVSync`. */
    public val sProc_BMVSync: ProcScript get() = procScrBmVSync

    /**
     * Ported from src/bmio.c: BMapVSync_InitMapAnimations
     *
     * Clears both clocks and points both animations at the first entry of the chapter's tables
     * (looked up through `gChapterDataAssetTable`), so each plays from its start.
     */
    public fun initMapAnimations(proc: Proc? = null) {
        tileGfxAnimClock = 0
        tilePalAnimClock = 0
        val chapter = playSt.chapterIndex
        tileGfxAnimStart = chapterAssetPointer(chapterData.objAnimId(chapter))
        tileGfxAnimCurrent = tileGfxAnimStart
        tilePalAnimStart = chapterAssetPointer(chapterData.paletteAnimId(chapter))
        tilePalAnimCurrent = tilePalAnimStart
    }

    /**
     * Sets the tile animation state (`tileGfxAnimStart`, `tileGfxAnimCurrent`, `tileGfxAnimClock`)
     * directly, as read from a proc dump; for resuming and tests. Not in the C.
     */
    public fun seedTileAnimation(start: Int, current: Int, clock: Int) {
        tileGfxAnimStart = start
        tileGfxAnimCurrent = current
        tileGfxAnimClock = clock
    }

    /**
     * Sets the palette animation state (`tilePalAnimStart`, `tilePalAnimCurrent`,
     * `tilePalAnimClock`) directly, as read from a proc dump; for resuming and tests. Not in
     * the C.
     */
    public fun seedPaletteAnimation(start: Int, current: Int, clock: Int) {
        tilePalAnimStart = start
        tilePalAnimCurrent = current
        tilePalAnimClock = clock
    }

    /**
     * Ported from src/bmio.c: BMapVSync_UpdateMapImgAnimations
     *
     * Animates the map tiles (what the player sees as moving tiles on the map). Waits
     * `tileGfxAnimClock` frames, then copies `size` bytes of the current `TileGfxAnim` entry
     * (u16 `time`, u16 `size`, pointer `data`; src/bmio.h) to `BG_TILE_ADDR(0x140)`, sets the
     * clock to the entry's `time` and advances; when the next entry's `time` is 0 it wraps to
     * the first entry. (`BG_TILE_ADDR` in the decomp counts 0x80-byte units, so this is VRAM
     * offset 0xA000, 4bpp character 0x500.)
     */
    public fun updateMapImgAnimations() {
        if (tileGfxAnimStart == 0) return
        if (tileGfxAnimClock != 0) {
            tileGfxAnimClock--
            return
        }
        val entryAddr = tileGfxAnimCurrent
        tileGfxAnimClock = rom.u16(entryAddr)
        val size = rom.u16(entryAddr + 2)
        val data = rom.u32(entryAddr + 4)
        val vram = hardware.vram
        for (i in 0 until size) vram[TILE_ANIM_VRAM + i] = rom.u8(data + i).toByte()
        tileGfxAnimCurrent += GFX_ENTRY_SIZE
        if (rom.u16(tileGfxAnimCurrent) == 0) tileGfxAnimCurrent = tileGfxAnimStart
    }

    /**
     * Transcribed from src/bmio.c: BMapVSync_UpdateMapPalAnimations (not verified:
     *   only the tile animation is compared against a trace)
     *
     * Animates the map colours. Waits `tilePalAnimClock` frames, then copies `colorCount`
     * colours of the current `TilePalAnim` entry (u32 `data` pointer, u8 `time`, u8 `colorCount`,
     * u8 `colorStart`; src/bmio.h) into `gPaletteBuffer` at colour `colorStart + 0x10 *
     * BM_BGPAL_6` (BG palette bank 6, just below the tileset palettes at `BM_BGPAL_TILESET_BASE`
     * = 7), requests a palette upload and advances; when the next entry's `time` is 0 it wraps
     * to the first entry.
     */
    public fun updateMapPalAnimations() {
        if (tilePalAnimStart == 0) return
        if (tilePalAnimClock != 0) {
            tilePalAnimClock--
            return
        }
        val entryAddr = tilePalAnimCurrent
        tilePalAnimClock = rom.u8(entryAddr + 4)
        val count = rom.u8(entryAddr + 5)
        val dstColor = rom.u8(entryAddr + 6) + 0x10 * BM_BGPAL_6
        val data = rom.u32(entryAddr)
        for (i in 0 until count) hardware.palette[dstColor + i] = rom.u16(data + 2 * i).toShort()
        hardware.enablePaletteSync()
        tilePalAnimCurrent += PAL_ENTRY_SIZE
        if (rom.u8(tilePalAnimCurrent + 4) == 0) tilePalAnimCurrent = tilePalAnimStart
    }

    /**
     * Ported from src/bmio.c: BMapVSync_OnLoop
     *
     * The last command of the script: `Proc_Goto(proc, 0)` back to label 0 each frame, so the
     * four per-frame calls repeat until the proc is ended.
     */
    public fun onLoop(proc: Proc) {
        val procEngine = engine ?: return
        procEngine.goto(proc, 0)
    }

    /**
     * Ported from src/bmio.c: BMapVSync_Start
     *
     * Starts the `sProc_BMVSync` proc on root tree 0 (`PROC_TREE_VSYNC`), loads the chapter's
     * animation tables, initializes the weather effect and clears `gBmSt.gameGfxSemaphore`.
     * `BMapDispResume` also calls this (after ending the old proc) when the last suspend is
     * undone.
     */
    public fun start(): Proc? {
        val proc = engine?.start(procScrBmVSync, PROC_TREE_VSYNC)
        initMapAnimations(proc)
        hooks.wfxInit()
        bmSt?.gameGfxSemaphore = 0
        return proc
    }

    /**
     * Transcribed from src/bmio.c: BMapVSync_End (not verified:
     *   no battle map teardown trace exercises ending the VSync proc)
     *
     * Ends each instance of `sProc_BMVSync`, which stops the map animations and weather.
     */
    public fun end() {
        val procEngine = engine ?: return
        procEngine.endEach(procScrBmVSync)
    }

    /**
     * Transcribed from src/bmio.c: BMapVSync_OnEnd (not verified:
     *   secondary HBlank handler is a stub / weather effect teardown not traced)
     *
     * Runs [Hooks.onEnd] when the VSync proc ends; the teardown itself lives in that hook.
     */
    public fun onEnd(proc: Proc? = null) {
        hooks.onEnd()
    }

    /**
     * Transcribed from src/bmio.c: DisableMapPaletteAnimations (not verified:
     *   no trace exercises disabling map palette animations)
     *
     * Stops the map colour animation (the colours stay as last copied) if the VSync proc
     * exists. Called by the spell animations on the map (`mapanim_spellassoc.c`, before they
     * fade or recolour the palette) and by the chapter title screen
     * (`chapterintrofx_title.c`); `ResetMapPaletteAnimations` is the counterpart.
     */
    public fun disableMapPaletteAnimations() {
        if (engine == null || engine.find(procScrBmVSync) != null) {
            tilePalAnimStart = 0
        }
    }

    /**
     * Transcribed from src/bmio.c: ResetMapPaletteAnimations (not verified:
     *   no trace exercises resetting map palette animations)
     *
     * Restarts the map colour animation from the first entry of the chapter's palette animation
     * table, if the VSync proc exists. Called at the end of each spell animation on the map
     * (`mapanim_spellassoc.c`) after the palette was restored.
     */
    public fun resetMapPaletteAnimations() {
        if (engine == null || engine.find(procScrBmVSync) != null) {
            val chapter = playSt.chapterIndex
            tilePalAnimStart = chapterAssetPointer(chapterData.paletteAnimId(chapter))
            tilePalAnimCurrent = tilePalAnimStart
        }
    }

    /**
     * The ROM pointer stored in `gChapterDataAssetTable` at entry [id] (0 for "none"), which is
     * how the chapter data's `objAnimId` / `paletteAnimId` find their tables.
     */
    private fun chapterAssetPointer(id: Int): Int =
        rom.u32(ChapterData.CHAPTER_DATA_ASSET_TABLE + 4 * id)

    /** Constants. */
    public companion object {
        /** `BG_TILE_ADDR(0x140)` as a VRAM offset (`BG_TILE_ADDR(n)` is `0x80 * n`). */
        public const val TILE_ANIM_VRAM: Int = 0x80 * 0x140

        /** `sizeof(struct TileGfxAnim)`. */
        public const val GFX_ENTRY_SIZE: Int = 8

        /** `sizeof(struct TilePalAnim)` (data pointer plus three bytes, padded). */
        public const val PAL_ENTRY_SIZE: Int = 8

        /** `BM_BGPAL_6`: the BG palette bank the animated map colours are copied to. */
        public const val BM_BGPAL_6: Int = 6

        /** Root tree 0 (`PROC_TREE_VSYNC`). */
        public const val PROC_TREE_VSYNC: Int = 0

        /** Display proc mark (`PROC_MARK_DISP`), the one `BMapDispSuspend` blocks. */
        public const val PROC_MARK_DISP: Int = 1
    }
}
