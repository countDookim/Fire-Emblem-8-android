package fe8.game.bm

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.startChild
import gbafe.render.BgHardware

/**
 * The blue, red and green movement and attack range squares of src/playerphase.c and
 * src/bmmap.c: [displayMoveRangeGraphics] starts `sProcScr_MoveLimitView` ([viewScript], a BG2
 * overlay over the map: a tile per map tile that is in `gBmMapMovement` (blue) or `gBmMapRange`
 * (red), whose palette cycles with the game clock) and its child `sProcScr_MoveLimitViewChange`
 * ([changeScript], which opens the squares by copying six frames of tile graphics into VRAM, one
 * per frame); [hideMoveRangeGraphics] ends it.
 *
 * Deviations: `RegisterDataMove` (queued until the next VBlank in the C) is [registerDataMove],
 * which copies at once by default; `GetGameClock` is [gameClock]. The proc fields past
 * `PROC_HEADER` (`flags` at +0x4A of the view proc, `unk_4C` at +0x4C of the change proc) are
 * [State]. Nothing allocates per frame.
 */
public class MoveLimitView(
    /** The proc engine the procs run on (the view is a root proc of tree 4). */
    private val engine: ProcEngine,
    /** `gBmSt`. */
    private val bm: BmSt,
    /** `gBmMapMovement` and `gBmMapRange`. */
    private val map: BmMap,
    /** `gBG2TilemapBuffer`, `gPaletteBuffer`, the LCD control buffer and VRAM. */
    private val hardware: BgHardware,
    /** The ROM the square graphics and the palette cycles are read from. */
    private val rom: RomView,
    /** `RenderBmMap`, called by [moveLimitViewOnInit]. */
    private val render: BmMapRender,
) {
    /** The fields of the two procs past `PROC_HEADER`. */
    public class State {
        /** `MoveLimitViewProc.flags` (+0x4A): the `LIMITVIEW_*` bits of the view proc. */
        public var flags: Int = 0

        /** `MoveLimitViewProc.unk_4C`: the frame (2 to 7) of the next opening graphics copy. */
        public var unk4C: Int = 0
    }

    /** `GetGameClock`: the frame counter that drives the palette cycle. */
    public var gameClock: () -> Int = { 0 }

    /**
     * `RegisterDataMove(src, dst, size)`: copy [size] bytes from the ROM address [romSrc] to
     * VRAM offset [vramOffset]. The default copies at once instead of queueing for `FlushTiles`.
     */
    public var registerDataMove: (romSrc: Int, vramOffset: Int, size: Int) -> Unit =
        { romSrc, vramOffset, size ->
            for (i in 0 until size) hardware.vram[vramOffset + i] = rom.u8(romSrc + i).toByte()
        }

    /**
     * `sProcScr_MoveLimitViewChange` (0x0859AD28, transcribed 1:1, checked against the ROM by
     * `PlayerPhaseScriptRomTest`).
     */
    public val changeScript: ProcScript = ProcScript(
        "sProcScr_MoveLimitViewChange",
        listOf(
            name("MLVCHC"),
            mark(PROC_MARK_DISP),
            call(ProcFunc { moveLimitViewChangeOnInit(it) }),
            repeat(ProcFunc { moveLimitViewChangeOnLoop(it) }),
            end(),
        ),
        newState = { State() },
    )

    /**
     * `sProcScr_MoveLimitView` (0x0859AD50, transcribed 1:1, checked against the ROM by
     * `PlayerPhaseScriptRomTest`).
     */
    public val viewScript: ProcScript = ProcScript(
        "sProcScr_MoveLimitView",
        listOf(
            name("E_MOVELIMITVIEW"),
            mark(PROC_MARK_DISP),
            setEndCb(ProcFunc { moveLimitViewOnEnd(it) }),
            startChild { changeScript },
            call(ProcFunc { moveLimitViewOnInit(it) }),
            repeat(ProcFunc { moveLimitViewOnLoop(it) }),
            end(),
        ),
        newState = { State() },
    )

    init {
        render.movementView = BmMapRender.MovementViewTile { bg, xBm, yBm, xTile, yTile ->
            displayMovementViewTile(bg, xBm, yBm, xTile, yTile)
        }
    }

    /**
     * Ported from src/playerphase.c: DisplayMoveRangeGraphics
     *
     * Shows the range squares with the `LIMITVIEW_*` bits [flags]: starts the view proc in tree 4
     * with those flags. If the view already exists it is redrawn instead
     * ([moveLimitViewOnInit], [moveLimitViewChangeOnInit] without a proc) and keeps its old flags
     * (not verified: no capture displays the range while the squares are up).
     */
    public fun displayMoveRangeGraphics(flags: Int) {
        val view = engine.find(viewScript)
        if (view != null) {
            moveLimitViewOnInit(view)
            moveLimitViewChangeOnInit(null)
            return
        }
        val started = engine.start(viewScript, VIEW_TREE)
        (started.state as State).flags = flags
    }

    /**
     * Ported from src/playerphase.c: HideMoveRangeGraphics
     *
     * Ends the view proc (and with it the change proc); [moveLimitViewOnEnd] clears the squares.
     */
    public fun hideMoveRangeGraphics() {
        engine.endEach(viewScript)
    }

    /**
     * Ported from src/playerphase.c: MoveLimitViewChange_OnInit
     *
     * Copies the base square graphics (the image after the six opening frames) to VRAM
     * 0x5080. With `BM_FLAG_0` not yet set it starts the opening at frame 2 ([proc]'s `unk_4C`);
     * with it set (the view is redrawn) it also copies that image to 0x5000 and ends [proc]
     * (null when called from [displayMoveRangeGraphics]).
     */
    public fun moveLimitViewChangeOnInit(proc: Proc?) {
        registerDataMove(BASE_IMAGE, VRAM_SQUARES + 0x80, 0x80)
        if ((bm.gameStateBits and BM_FLAG_0) == 0) {
            (proc!!.state as State).unk4C = 2
        } else {
            registerDataMove(BASE_IMAGE, VRAM_SQUARES, 0x80)
            engine.end(proc)
        }
    }

    /**
     * Ported from src/playerphase.c: MoveLimitViewChange_OnLoop
     *
     * Copies the opening frame `unk_4C` (a 0x80 byte image of `gOpenLimitViewImgLut`) to VRAM
     * 0x5000 and advances it; after frame 7 the proc moves on (and ends).
     */
    public fun moveLimitViewChangeOnLoop(proc: Proc) {
        val state = proc.state as State
        registerDataMove(OPEN_IMAGES + (state.unk4C - 2) * 0x80, VRAM_SQUARES, 0x80)
        state.unk4C++
        if (state.unk4C == 8) engine.breakProc(proc)
    }

    /**
     * Ported from src/playerphase.c: MoveLimitView_OnInit
     *
     * Turns the windows off, sets `BM_FLAG_0`, redraws the map ([BmMapRender.renderBmMap]) and
     * fills the 15x10 visible tiles of BG2 with [displayMovementViewTile], requests the BG2
     * sync, resets its scroll, sets alpha blending of BG2 (first target) over BG3 and the
     * sprites (second target) with coefficients 10 and 6, and resets the BG priorities.
     */
    @Suppress("UNUSED_PARAMETER")
    public fun moveLimitViewOnInit(proc: Proc) {
        val lcd = hardware.lcd
        lcd.setWinEnable(0, 0, 0)
        bm.gameStateBits = bm.gameStateBits or BM_FLAG_0
        render.renderBmMap()
        for (iy in 9 downTo 0) {
            for (ix in 14 downTo 0) {
                val xOrigin = bm.mapRenderOriginX.toShort().toInt()
                val yOrigin = bm.mapRenderOriginY.toShort().toInt()
                displayMovementViewTile(
                    hardware.mapBuffer(BmMapRender.BG2), xOrigin + ix, yOrigin + iy, ix, iy,
                )
            }
        }
        hardware.enableBgSyncByMask(BmMapRender.BG2_SYNC_BIT)
        lcd.setBgPosition(BmMapRender.BG2, 0, 0)
        lcd.setBlendConfig(BLEND_EFFECT_ALPHA, 10, 6, 0)
        lcd.setBlendTargetA(0, 0, 1, 0, 0)
        lcd.setBlendBackdropA(0)
        lcd.setBlendTargetB(0, 0, 0, 1, 1)
        lcd.setBlendBackdropB(1)
        hardware.initBmBgLayers(false)
    }

    /**
     * Ported from src/playerphase.c: MoveLimitView_OnLoop
     *
     * Each frame cycles the square colours: with `frame = (GetGameClock() / 2) & 31`, copies 16
     * colours of the blue table from `frame` to palette colour 0x41 (`LIMITVIEW_BLUE`; the word
     * alignment of the copy is in [copyToPaletteBuffer]), the red table to 0x51
     * (`LIMITVIEW_RED`), the green table to 0x51 (`LIMITVIEW_GREEN`) and the blue table to 0x51
     * (`LIMITVIEW_UNK`), and flags the palette for sync. The green and unknown tables are (not
     * verified: the captures show blue and red only).
     */
    public fun moveLimitViewOnLoop(proc: Proc) {
        val flags = (proc.state as State).flags
        val frame = (gameClock() / 2) and 31
        if ((flags and LIMITVIEW_BLUE) != 0) copyToPaletteBuffer(PAL_BLUE + frame * 2, 0x82)
        if ((flags and LIMITVIEW_RED) != 0) copyToPaletteBuffer(PAL_RED + frame * 2, 0xA2)
        if ((flags and LIMITVIEW_GREEN) != 0) copyToPaletteBuffer(PAL_GREEN + frame * 2, 0xA2)
        if ((flags and LIMITVIEW_UNK) != 0) copyToPaletteBuffer(PAL_BLUE + frame * 2, 0xA2)
    }

    /**
     * Ported from src/playerphase.c: MoveLimitView_OnEnd
     *
     * The end callback of the view: when the flags have the blue (or unknown) bit, clears BG2 and
     * requests its sync; clears `BM_FLAG_0` and `BM_FLAG_1` and resets the BG priorities.
     */
    public fun moveLimitViewOnEnd(proc: Proc) {
        if (((proc.state as State).flags and (LIMITVIEW_BLUE or LIMITVIEW_UNK)) != 0) {
            hardware.fill(hardware.mapBuffer(BmMapRender.BG2), 0)
            hardware.enableBgSyncByMask(BmMapRender.BG2_SYNC_BIT)
        }
        bm.gameStateBits = bm.gameStateBits and (BM_FLAG_0 or BM_FLAG_1).inv()
        hardware.initBmBgLayers(false)
    }

    /**
     * Ported from src/bmmap.c: DisplayMovementViewTile
     *
     * Writes the 2x2 BG2 entries at tile ([xTileMap], [yTileMap]) of [bg] for map tile
     * ([xBmMap], [yBmMap]): the blue square (0x4280..0x4283) where `gBmMapMovement` is not
     * negative, else the red square (0x5280..0x5283) where `gBmMapRange` is set, else nothing
     * (0). A red tile whose first entry is already set gets the variant 0x5284..0x5287.
     */
    public fun displayMovementViewTile(
        bg: ShortArray,
        xBmMap: Int,
        yBmMap: Int,
        xTileMap: Int,
        yTileMap: Int,
    ) {
        val at = yTileMap * 0x40 + xTileMap * 2
        if (map.movement[yBmMap, xBmMap] < 0x80) {
            bg[at] = 0x4280
            bg[at + 1] = 0x4281
            bg[at + 0x20] = 0x4282
            bg[at + 0x21] = 0x4283
            return
        }
        if (map.range[yBmMap, xBmMap] != 0) {
            val base = if (bg[at].toInt() != 0) 0x5284 else 0x5280
            bg[at] = base.toShort()
            bg[at + 1] = (base + 1).toShort()
            bg[at + 0x20] = (base + 2).toShort()
            bg[at + 0x21] = (base + 3).toShort()
            return
        }
        bg[at] = 0
        bg[at + 1] = 0
        bg[at + 0x20] = 0
        bg[at + 0x21] = 0
    }

    /**
     * `CopyToPaletteBuffer(src, byteOffset, 0x20)`: as the size is a multiple of 32 the C copies
     * with `CpuFastCopy`, which works on word-aligned addresses: the source and the destination
     * (`gPaletteBuffer + byteOffset / 2` halfwords) are rounded down to a word, so the 16 colours
     * land one colour lower when the destination colour or the source colour is odd (here the
     * destinations 0x41 and 0x51 both land on 0x40 and 0x50). Flags the palette for sync.
     */
    private fun copyToPaletteBuffer(romSrc: Int, byteOffset: Int) {
        val colours = hardware.palette
        val first = (byteOffset shr 1) and 1.inv()
        val from = romSrc and 3.inv()
        for (i in 0 until 16) colours[first + i] = rom.u16(from + i * 2).toShort()
        hardware.enablePaletteSync()
    }

    /** Constants. */
    public companion object {
        /** `LIMITVIEW_BLUE`. */
        public const val LIMITVIEW_BLUE: Int = 1 shl 0

        /** `LIMITVIEW_RED`. */
        public const val LIMITVIEW_RED: Int = 1 shl 1

        /** `LIMITVIEW_GREEN`. */
        public const val LIMITVIEW_GREEN: Int = 1 shl 2

        /** `LIMITVIEW_UNK`. */
        public const val LIMITVIEW_UNK: Int = 1 shl 4

        /** `BM_FLAG_0`: the range squares show. */
        public const val BM_FLAG_0: Int = 1 shl 0

        /** `BM_FLAG_1`: the movement range is displayed (set by the player phase). */
        public const val BM_FLAG_1: Int = 1 shl 1

        /** `PROC_MARK_DISP`. */
        public const val PROC_MARK_DISP: Int = 1

        private const val VIEW_TREE = 4
        private const val BLEND_EFFECT_ALPHA = 1
        private const val VRAM_SQUARES = 0x5000
        private const val OPEN_IMAGES = 0x08A02C34 // Img_LimitViewSquares, 6 x 0x80 bytes
        private const val BASE_IMAGE = 0x08A02EB4 // gUnknown_08A02EB4
        private const val PAL_BLUE = 0x08A02F34 // gUnknown_08A02F34
        private const val PAL_RED = 0x08A02F94 // gUnknown_08A02F94
        private const val PAL_GREEN = 0x08A02FF4 // gUnknown_08A02FF4
    }
}
