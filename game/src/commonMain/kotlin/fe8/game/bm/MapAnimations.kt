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
 * The map tile animations of the `sProc_BMVSync` proc (src/bmio.c): the animated tile graphics
 * copied to BG VRAM and the animated palette colours copied to palette bank 6 onwards, both
 * cycling through a ROM table entry by entry.
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
    /** `gPlaySt` (`chapterIndex`). */
    private val playSt: PlaySt,
    /** The proc engine scheduling [procScrBmVSync]. */
    private val engine: ProcEngine? = null,
    /** `gBmSt` (`gameGfxSemaphore`). */
    private val bmSt: BmSt? = null,
    /** External callbacks invoked during VSync proc lifecycle. */
    public val hooks: Hooks = Hooks(),
) {
    /** Callbacks invoked by [procScrBmVSync] and [start]. */
    public class Hooks {
        /** `WfxInit`, called by [start]. */
        public var wfxInit: () -> Unit = {}

        /** `SyncUnitSpriteSheet`, called by [procScrBmVSync]. */
        public var syncUnitSpriteSheet: () -> Unit = {}

        /** `WfxVSync`, called by [procScrBmVSync]. */
        public var wfxVSync: () -> Unit = {}

        /** `BMapVSync_OnEnd`, called when [procScrBmVSync] ends. */
        public var onEnd: () -> Unit = {}

        /** Alias for [onEnd]. */
        public var bmapVSyncOnEnd: () -> Unit
            get() = onEnd
            set(value) { onEnd = value }
    }

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
     * `sProc_BMVSync` (src/bmio.c:121, ROM 0x0859D8B8, 10 commands): the VBlank proc, started on
     * tree 0.
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
     * Clears both clocks and points both animations at the first entry of the chapter's tables.
     */
    public fun initMapAnimations(proc: Proc? = null) {
        tileGfxAnimClock = 0
        tilePalAnimClock = 0
        val chapter = playSt.chapterIndex
        tileGfxAnimStart = asset(chapterData.objAnimId(chapter))
        tileGfxAnimCurrent = tileGfxAnimStart
        tilePalAnimStart = asset(chapterData.paletteAnimId(chapter))
        tilePalAnimCurrent = tilePalAnimStart
    }

    /**
     * Sets the tile animation state (`tileGfxAnimStart`, `tileGfxAnimCurrent`, `tileGfxAnimClock`)
     * directly, as read from a proc dump; for resuming and tests.
     */
    public fun seedTileAnimation(start: Int, current: Int, clock: Int) {
        tileGfxAnimStart = start
        tileGfxAnimCurrent = current
        tileGfxAnimClock = clock
    }

    /**
     * Sets the palette animation state (`tilePalAnimStart`, `tilePalAnimCurrent`,
     * `tilePalAnimClock`) directly, as read from a proc dump; for resuming and tests.
     */
    public fun seedPaletteAnimation(start: Int, current: Int, clock: Int) {
        tilePalAnimStart = start
        tilePalAnimCurrent = current
        tilePalAnimClock = clock
    }

    /**
     * Ported from src/bmio.c: BMapVSync_UpdateMapImgAnimations
     *
     * Every `time` frames copies the current `TileGfxAnim` entry (u16 time, u16 size, data
     * pointer) to BG tile 0x140 (VRAM 0xA000) and advances, wrapping at a zero `time`.
     */
    public fun updateMapImgAnimations() {
        if (tileGfxAnimStart == 0) return
        if (tileGfxAnimClock != 0) {
            tileGfxAnimClock--
            return
        }
        val cur = tileGfxAnimCurrent
        tileGfxAnimClock = rom.u16(cur)
        val size = rom.u16(cur + 2)
        val data = rom.u32(cur + 4)
        val vram = hardware.vram
        for (i in 0 until size) vram[TILE_ANIM_VRAM + i] = rom.u8(data + i).toByte()
        tileGfxAnimCurrent += GFX_ENTRY_SIZE
        if (rom.u16(tileGfxAnimCurrent) == 0) tileGfxAnimCurrent = tileGfxAnimStart
    }

    /**
     * Transcribed from src/bmio.c: BMapVSync_UpdateMapPalAnimations (not verified:
     *   only the tile animation is compared against a trace)
     *
     * Every `time` frames copies the current `TilePalAnim` entry (u32 data pointer, u8 time, u8
     * colorCount, u8 colorStart) into `gPaletteBuffer` at colour `colorStart + 0x10 * 6`, enables
     * the palette sync and advances, wrapping at a zero `time`.
     */
    public fun updateMapPalAnimations() {
        if (tilePalAnimStart == 0) return
        if (tilePalAnimClock != 0) {
            tilePalAnimClock--
            return
        }
        val cur = tilePalAnimCurrent
        tilePalAnimClock = rom.u8(cur + 4)
        val count = rom.u8(cur + 5)
        val dst = rom.u8(cur + 6) + 0x10 * BM_BGPAL_6
        val data = rom.u32(cur)
        for (i in 0 until count) hardware.palette[dst + i] = rom.u16(data + 2 * i).toShort()
        hardware.enablePaletteSync()
        tilePalAnimCurrent += PAL_ENTRY_SIZE
        if (rom.u8(tilePalAnimCurrent + 4) == 0) tilePalAnimCurrent = tilePalAnimStart
    }

    /**
     * Ported from src/bmio.c: BMapVSync_OnLoop
     *
     * Jumps the proc back to label 0 to loop every frame.
     */
    public fun onLoop(proc: Proc) {
        val eng = engine ?: return
        eng.goto(proc, 0)
    }

    /**
     * Ported from src/bmio.c: BMapVSync_Start
     *
     * Starts the `sProc_BMVSync` proc on root tree 0 (PROC_TREE_VSYNC), initializes weather
     * effects, and clears `bmSt.gameGfxSemaphore`.
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
     * Ends each instance of `sProc_BMVSync`.
     */
    public fun end() {
        val eng = engine ?: return
        eng.endEach(procScrBmVSync)
    }

    /**
     * Transcribed from src/bmio.c: BMapVSync_OnEnd (not verified:
     *   secondary HBlank handler is a stub / weather effect teardown not traced)
     *
     * Clears secondary HBlank handler when the VSync proc ends.
     */
    public fun onEnd(proc: Proc? = null) {
        hooks.onEnd()
    }

    /**
     * Transcribed from src/bmio.c: DisableMapPaletteAnimations (not verified:
     *   no trace exercises disabling map palette animations)
     *
     * Clears `tilePalAnimStart` if the VSync proc is active.
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
     * Re-points `tilePalAnimStart` and `tilePalAnimCurrent` at the chapter's palette animation
     * table.
     */
    public fun resetMapPaletteAnimations() {
        if (engine == null || engine.find(procScrBmVSync) != null) {
            val chapter = playSt.chapterIndex
            tilePalAnimStart = asset(chapterData.paletteAnimId(chapter))
            tilePalAnimCurrent = tilePalAnimStart
        }
    }

    private fun asset(id: Int): Int = rom.u32(ChapterData.CHAPTER_DATA_ASSET_TABLE + 4 * id)

    /** Constants. */
    public companion object {
        /** `BG_TILE_ADDR(0x140)` as a VRAM offset. */
        public const val TILE_ANIM_VRAM: Int = 0x80 * 0x140

        /** `sizeof(struct TileGfxAnim)`. */
        public const val GFX_ENTRY_SIZE: Int = 8

        /** `sizeof(struct TilePalAnim)` (data pointer plus three bytes, padded). */
        public const val PAL_ENTRY_SIZE: Int = 8

        /** `BM_BGPAL_6`. */
        public const val BM_BGPAL_6: Int = 6

        /** Root tree 0 (`PROC_TREE_VSYNC`). */
        public const val PROC_TREE_VSYNC: Int = 0

        /** Display proc mark (`PROC_MARK_DISP`). */
        public const val PROC_MARK_DISP: Int = 1
    }
}
