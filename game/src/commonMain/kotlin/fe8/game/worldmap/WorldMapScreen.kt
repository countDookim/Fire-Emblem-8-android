package fe8.game.worldmap

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
import gbafe.render.BgHardware

/**
 * The fields of `struct GmScreenProc` (include/worldmap.h), the state of `ProcScr_GMapScreen`: the
 * scrolling world map picture on BG3. The picture is a 60 x 40 tile image (derived from the
 * 0x3C and 0x28 wrap sizes and the 0x780-byte row pitch of src/worldmap_screen.c) shown through a
 * 32 x 32 tile BG3 window that is redrawn as the camera moves.
 */
public class GmScreenProc {
    /**
     * `flags_0` (bit 0 of the byte at +0x29): set by [WorldMapScreen.mapScreenInit]; while clear,
     * [WorldMapScreen.followCamera] does nothing and the screen stops following the camera.
     */
    public var flags0: Boolean = false

    /**
     * `unk_2a` (+0x2A): redraw requests for the next [WorldMapScreen.followCamera]: bit 0 redraw
     * the tilemap, bit 1 queue the tile data again, bit 2 reload the palettes. The world map main
     * proc sets 1 and 6 together after it changes the map (src/worldmap_main.c:1219-1220).
     */
    public var redrawRequests: Int = 0

    /**
     * `unk_2b` (+0x2B, `s8`): the tile column of the image the BG3 tile data has been
     * brought up to; trails [wantedColumn].
     */
    public var drawnColumn: Int = 0

    /** `unk_2c` (+0x2C, `s8`): the tile row the tile data has been brought up to ([wantedRow]). */
    public var drawnRow: Int = 0

    /** `unk_2d` (+0x2D, `s8`): the wanted tile column, [x] `/ 8`. */
    public var wantedColumn: Int = 0

    /** `unk_2e` (+0x2E, `s8`): the wanted tile row, [y] `/ 8`. */
    public var wantedRow: Int = 0

    /** `unk_2f` (+0x2F): only cleared by init; meaning unknown. */
    public var unk2f: Int = 0

    /** `unk_30` (+0x30): only cleared by init; meaning unknown. */
    public var unk30: Int = 0

    /** `unk_31` (+0x31): only cleared by init; meaning unknown. */
    public var unk31: Int = 0

    /** `unk_32` (+0x32): only cleared by init; meaning unknown. */
    public var unk32: Int = 0

    /**
     * `x` (+0x34, `s16`): the BG3 scroll x, the camera x clamped to 0..0xF0 (the world map is 480
     * pixels wide, the screen 240). Other world map procs read it to place sprites.
     */
    public var x: Int = 0

    /** `y` (+0x36, `s16`): the BG3 scroll y, the camera y with a lower clamp of 0 only. */
    public var y: Int = 0

    /** `unk_38` (+0x38, `u16`): [x] as of the end of the last frame; nothing reads it. */
    public var lastX: Int = 0

    /** `unk_3a` (+0x3A, `u16`): [y] as of the end of the last frame; nothing reads it. */
    public var lastY: Int = 0

    /** `unk_3c` (+0x3C): ROM address of the world map image (`gUnknown_08A83364`). */
    public var image: Int = 0

    /** `unk_40` (+0x40): the BG3 tilemap buffer (`gBG3TilemapBuffer`). */
    public var tilemap: ShortArray? = null

    /**
     * `unk_44` (+0x44): the decompressed palette map (`gUnknown_020087A0`), see
     * [WorldMapScreen.applyPaletteMap].
     */
    public var paletteMap: ByteArray? = null

    /** `unk_48` (+0x48): the `ProcScr_GMapScreenVSync` proc this screen ends with itself. */
    public var vsync: Proc? = null

    /** `gmroute` (+0x4C): the `ProcScr_GMapRoute` proc that draws the paths over the map. */
    public var gmRoute: Proc? = null
}

/**
 * The fields of `struct GMapScreenVSyncProc` (include/worldmap.h), the state of
 * `ProcScr_GMapScreenVSync`: the queue of picture blocks that still have to be copied into BG3
 * tile data.
 */
public class GMapScreenVSyncProc {
    /** `unk_2c` (+0x2C): ROM address of the world map image. */
    public var image: Int = 0

    /** `unk_30` (+0x30): the BG3 tilemap buffer; stored but not read by the VSync proc. */
    public var tilemap: ShortArray? = null

    /** `unk_34` (+0x34): the decompressed palette map; stored but not read by the VSync proc. */
    public var paletteMap: ByteArray? = null

    /** `unk_38` (+0x38, `s16`): the number of queued block copies. */
    public var count: Int = 0

    /**
     * `unk_3c` (+0x3C): the queue (`gUnknown_03001DA8`), [WorldMapScreen.QUEUE_ENTRIES] entries of
     * six `s8` (`struct Unknown_3001DA8`): source tile column, source tile row, VRAM tile column,
     * VRAM tile row, width in tiles, number of tile rows. This port (and the reference capture)
     * puts the entries 8 bytes apart, although include/worldmap.h gives the struct a size of 6.
     */
    public var queue: ByteArray = ByteArray(0)

    /** `unk_40` (+0x40, `u16`): only cleared; meaning unknown. */
    public var unk40: Int = 0

    /** `unk_44` (+0x44): `gUnknown_03001DE8`, stored but never read; meaning unknown. */
    public var unk44: ByteArray? = null
}

/**
 * The scrolling world map picture on BG3 (`src/worldmap_screen.c`): `ProcScr_GMapScreen` keeps the
 * BG3 tilemap and tile data in step with `gGMData.xCamera/yCamera`, and
 * `ProcScr_GMapScreenVSync` copies the queued picture blocks into VRAM every frame. BG3 tile data
 * (VRAM 0x06008000) is a 32 x 32 tile ring: the tilemap is the identity map and the picture
 * columns and rows wrap around it as the camera moves.
 *
 * @param engine the proc scheduler
 * @param hardware BG3 buffer, VRAM, palette buffer and BG scroll
 * @param rom the ROM (big map image, palettes, palette map)
 * @param gmData `gGMData`
 * @param hooks the unported callees
 */
public class WorldMapScreen(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val gmData: GmData,
    private val hooks: Hooks = Hooks(),
) {
    /**
     * `gUnknown_020087A0`: the decompressed palette map. One byte per two tiles of the picture,
     * 0x20 bytes per tile row: the low nibble is the palette bank offset of the even tile, the high
     * nibble that of the odd tile, added to bank 9 (see [applyPaletteMap]).
     */
    public val paletteMap: ByteArray = ByteArray(PALETTE_MAP_SIZE)

    /** `gUnknown_03001DA8`: the block copy queue, see [GMapScreenVSyncProc.queue]. */
    public val queue: ByteArray = ByteArray(QUEUE_ENTRIES * ENTRY_SIZE)

    /** `gUnknown_03001DE8`, only passed on to the VSync proc, which never reads it. */
    private val unk3001DE8: ByteArray = ByteArray(UNK_3001DE8_SIZE)

    /** `gUnknown_03001E30`, cleared by `sub_80BAB00`; nothing else uses it, meaning unknown. */
    public var unk3001E30: Int = 0

    /** The path drawing proc (`ProcScr_GMapRoute`, started by `StartGMapRoute`). */
    public val route: WorldMapRoute = WorldMapRoute(engine, hardware, rom, gmData)

    /** Callees of [newMapScreen] that are not ported yet. */
    public class Hooks(
        /**
         * `StartGMapRoute(proc, &gGMData.openPaths, 0x5000, 0xe)` (the last two arguments are the
         * route proc's `chr` and `pal`); the proc it returns becomes [GmScreenProc.gmRoute]. Null
         * runs the real [WorldMapRoute].
         */
        public val startGMapRoute: ((parent: Proc) -> Proc?)? = null,
    )

    /**
     * `ProcScr_GMapScreenVSync`: each frame copies the queued picture blocks into BG3 tile data.
     *
     * Transcribed from src/worldmap_screen.c: ProcScr_GMapScreenVSync (not verified: script order
     * checked against the `procs` capture in WorldMapScreenTest, which cannot tag a script)
     */
    public val vsyncScript: ProcScript = ProcScript(
        "ProcScr_GMapScreenVSync",
        listOf(
            name("GMapScreenVSync"),
            mark(PROC_MARK_WMSTUFF),
            call(ProcFunc { gmScreenVSyncInit(it.state as GMapScreenVSyncProc) }),
            repeat(ProcFunc { gmScreenVSyncLoop(it.state as GMapScreenVSyncProc) }),
            end(),
        ),
    ) { GMapScreenVSyncProc() }

    /**
     * `ProcScr_GMapScreen`: sets the world map picture up (palettes, palette map, first tile data)
     * and then follows the camera every frame.
     *
     * Transcribed from src/worldmap_screen.c: ProcScr_GMapScreen (not verified: script order
     * checked against the `procs` capture in WorldMapScreenTest, which cannot tag a script)
     */
    public val screenScript: ProcScript = ProcScript(
        "ProcScr_GMapScreen",
        listOf(
            name("GmapScreen"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { mapScreenOnDelete(it.state as GmScreenProc) }),
            call(ProcFunc { mapScreenInit(it.state as GmScreenProc) }),
            call(ProcFunc { loadInitialTileData(it.state as GmScreenProc) }),
            call(ProcFunc { applyPaletteMap(it.state as GmScreenProc) }),
            call(ProcFunc { sub80BAB00() }),
            repeat(ProcFunc { followCamera(it.state as GmScreenProc) }),
            end(),
        ),
    ) { GmScreenProc() }

    /**
     * Ported from src/worldmap_screen.c: NewMapScreen
     *
     * Starts the screen proc in root tree [rootIndex] (`PROC_TREE_5` in src/worldmap_main.c:527;
     * the decomp passes a parent proc) and its route child.
     */
    public fun newMapScreen(rootIndex: Int): Proc {
        val proc = engine.start(screenScript, rootIndex)
        val start = hooks.startGMapRoute
        (proc.state as GmScreenProc).gmRoute =
            if (start != null) start(proc) else route.startGMapRoute(proc, ROUTE_CHR, ROUTE_PAL)
        return proc
    }

    /**
     * Ported from src/worldmap_screen.c: NewMapScreenVSync
     *
     * Starts the VSync proc in root tree 0 (`PROC_TREE_VSYNC`, the procs run every VBlank).
     *
     * @param image ROM address of the world map image
     * @param tilemap the BG3 tilemap buffer
     * @param paletteMap the decompressed palette map
     * @param queue the block copy queue
     * @param unk44 `gUnknown_03001DE8`
     */
    public fun newMapScreenVSync(
        image: Int,
        tilemap: ShortArray,
        paletteMap: ByteArray,
        queue: ByteArray,
        unk44: ByteArray,
    ): Proc {
        val proc = engine.start(vsyncScript, 0)
        val state = proc.state as GMapScreenVSyncProc
        state.image = image
        state.tilemap = tilemap
        state.paletteMap = paletteMap
        state.count = 0
        state.queue = queue
        state.unk40 = 0
        state.unk44 = unk44
        return proc
    }

    /**
     * Ported from src/worldmap_screen.c: MapScreen_OnDelete
     *
     * End callback of the screen proc: ends its VSync proc.
     */
    public fun mapScreenOnDelete(proc: GmScreenProc) {
        engine.end(proc.vsync)
    }

    /**
     * Ported from src/worldmap_screen.c: MapScreen_Init
     *
     * Clears the fields, decompresses the palette map, loads the map palettes into BG palette
     * banks 9-12, clears the BG3 tilemap and starts the VSync proc.
     */
    public fun mapScreenInit(proc: GmScreenProc) {
        proc.flags0 = true
        proc.redrawRequests = 0
        proc.y = 0
        proc.x = 0
        proc.lastY = 0
        proc.lastX = 0
        proc.image = IMAGE
        val tilemap = hardware.mapBuffer(BG3)
        proc.tilemap = tilemap
        rom.decompressInto(PALETTE_MAP, paletteMap, 0)
        proc.paletteMap = paletteMap
        proc.drawnRow = 0
        proc.drawnColumn = 0
        proc.wantedRow = 0
        proc.wantedColumn = 0
        proc.unk30 = 0
        proc.unk2f = 0
        proc.unk32 = 0
        proc.unk31 = 0
        applyPalettes()
        hardware.fill(tilemap, 0)
        hardware.enableBgSyncByMask(BG3_SYNC_BIT)
        proc.vsync = newMapScreenVSync(IMAGE, tilemap, paletteMap, queue, unk3001DE8)
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA490
     *
     * Loads the first picture on screen: copies the top-left 32 x 32 tiles of the image (32 rows
     * of 0x400 bytes, the image rows being 0x780 bytes apart) to BG3 tile data at 0x06008000 and
     * fills the BG3 tilemap with the identity map.
     */
    public fun loadInitialTileData(proc: GmScreenProc) {
        for (i in 0 until 0x20) copyRom(proc.image + i * 0x780, VRAM_BG3 + i * 0x400, 0x400)
        fillIdentityTilemap()
        hardware.enableBgSyncByMask(BG3_SYNC_BIT)
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA458
     *
     * Fills the whole 32 x 32 BG3 tilemap with tile `i` of palette bank 9 (`0x9000 | i`), so
     * tilemap entry `i` shows BG3 tile data tile `i`. The decomp function has no return
     * statement ("BUG?") but returns `u32`; nothing uses it.
     */
    public fun fillIdentityTilemap() {
        val buffer = hardware.mapBuffer(BG3)
        var tile = 0
        for (i in 0 until 0x400) {
            buffer[i] = (tile or 0x9000).toShort()
            tile++
        }
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA4D0
     *
     * Rewrites the palette bank bits of the BG3 tilemap entries of the 32 x 22 tiles around the
     * camera from the palette map: each map byte gives the bank offsets (added to bank 9) of two
     * neighbouring tiles, low nibble first for an even [GmScreenProc.wantedColumn], swapped for an
     * odd one. The branch for an odd wanted column is not verified: the camera column is even in
     * the whole capture.
     */
    public fun applyPaletteMap(proc: GmScreenProc) {
        val tilemap = checkNotNull(proc.tilemap)
        val map = checkNotNull(proc.paletteMap)
        var row = proc.wantedRow * 0x20 + proc.wantedColumn / 2
        val odd = proc.wantedColumn and 1 != 0
        for (iy in 0 until 0x16) {
            var p = row
            val y = (iy + proc.wantedRow) and 0x1F
            row = p + 0x20
            for (ix in 0 until 0x10) {
                val x = (ix * 2 + proc.wantedColumn) and 0x1F
                val at = y * 0x20 + x
                val next = y * 0x20 + ((x + 1) and 0x1F)
                if (odd) {
                    retile(tilemap, at, ((map[p].toInt() and 0xF0) + 0x90) * 0x100)
                    p++
                    retile(tilemap, next, ((map[p].toInt() and 0xFF) + 9) * 0x1000)
                } else {
                    retile(tilemap, at, ((map[p].toInt() and 0xFF) + 9) * 0x1000)
                    retile(tilemap, next, ((map[p].toInt() and 0xF0) + 0x90) * 0x100)
                    p++
                }
            }
        }
        hardware.enableBgSyncByMask(BG3_SYNC_BIT)
    }

    /**
     * Replaces the palette bank bits (the top 4) of one tilemap entry, keeping its tile number:
     * `(entry & 0xFFF) | high`, stored as `u16`.
     */
    private fun retile(tilemap: ShortArray, index: Int, high: Int) {
        tilemap[index] = ((tilemap[index].toInt() and 0xFFF) or high).toShort()
    }

    /**
     * Init of the VSync proc: starts with an empty queue.
     *
     * Transcribed from src/worldmap_screen.c: GMScreenVSync_Init (not verified: it runs inside
     * `Proc_Start`, and `NewMapScreenVSync` writes the same two fields right after)
     */
    public fun gmScreenVSyncInit(proc: GMapScreenVSyncProc) {
        proc.count = 0
        proc.unk40 = 0
    }

    /**
     * Ported from src/worldmap_screen.c: GMScreenVSync_Loop
     *
     * Copies every queued block from the image to BG3 tile data and empties the queue. Each
     * entry copies `rows` tile rows of `width` tiles (32 bytes per 4bpp tile); the source row is
     * 0x780 bytes (60 tiles) long, the destination row 0x400 bytes (32 tiles), taken modulo 32
     * rows.
     */
    public fun gmScreenVSyncLoop(proc: GMapScreenVSyncProc) {
        if (proc.count == 0) return
        for (i in 0 until proc.count) {
            val base = i * ENTRY_SIZE
            val unk00 = proc.queue[base].toInt() * 8
            val unk02 = proc.queue[base + 2].toInt() and 0x1F
            val size = proc.queue[base + 4].toInt() * 0x20
            for (j in 0 until proc.queue[base + 5].toInt()) {
                val vram = VRAM_BG3 + unk02 * 0x20
                val unk03 = (proc.queue[base + 3] + j) and 0x1F
                val unk01 = proc.queue[base + 1] + j
                val src = proc.image + ((unk01 * 0x10 - unk01) * 0x80) + unk00 * 4
                copyRom(src, vram + (unk03 shl 10), size)
            }
        }
        proc.count = 0
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA6DC
     *
     * Queues a block copy for the VSync proc (see [GMapScreenVSyncProc.queue] for the six
     * arguments, in order); returns 1 and drops it if the queue (8 entries) is full, else 0.
     */
    public fun queueTileCopy(
        proc: GMapScreenVSyncProc,
        arg1: Int,
        arg2: Int,
        arg3: Int,
        arg4: Int,
        arg5: Int,
        arg6: Int,
    ): Int {
        if (proc.count > 7) return 1
        val base = proc.count * ENTRY_SIZE
        proc.queue[base] = arg1.toByte()
        proc.queue[base + 1] = arg2.toByte()
        proc.queue[base + 2] = arg3.toByte()
        proc.queue[base + 3] = arg4.toByte()
        proc.queue[base + 4] = arg5.toByte()
        proc.queue[base + 5] = arg6.toByte()
        proc.count++
        return 0
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA818
     *
     * Queues a full redraw of the 32 x 22 tiles at image tile position ([a], [b]). The source
     * column is [a] modulo 0x3C (the image is 60 tiles wide), the row [b] modulo 0x28 (40 tiles
     * high). The copy is split in two where the destination wraps around the 32 tile wide BG3
     * ring; when [a] modulo 0x3C is 0 it is one copy (that branch is not verified).
     */
    public fun queueFullRedraw(proc: GMapScreenVSyncProc, a: Int, b: Int) {
        val a1 = a % 0x3C
        val b1 = b % 0x28
        if (a1 + 0x20 > 0x20) {
            val c = 0x20 - a1
            queueTileCopy(proc, a1, b1, a, b, c, 0x16)
            queueTileCopy(proc, a1 + c, b1, a + c, b, 0x20 - c, 0x16)
        } else {
            queueTileCopy(proc, a1, b1, a, b, 0x20, 0x16)
        }
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA8A0
     *
     * Brings the drawn tile column and row up to the wanted ones: queues one new column or row
     * of the image just past the screen edge (the screen is 30 x 20 tiles, hence the offsets 0x1E
     * and 0x14) per call, or a full redraw if the camera moved more than one tile. The strip
     * lengths are 0x15 rows and 0x1F columns where [queueFullRedraw] uses 0x16 rows; the original
     * values are kept. Only the full-redraw branch runs in the capture; the one-tile column and
     * row branches are not verified.
     */
    public fun queueScrollEdge(proc: GmScreenProc) {
        val vsync = proc.vsync!!.state as GMapScreenVSyncProc
        if (proc.drawnColumn != proc.wantedColumn) {
            applyPaletteMap(proc)
            if (kotlin.math.abs(proc.wantedColumn - proc.drawnColumn) > 1) {
                queueFullRedraw(vsync, proc.wantedColumn, proc.wantedRow)
                proc.drawnColumn = proc.wantedColumn
                proc.drawnRow = proc.wantedRow
                return
            }
            val r4: Int
            val r6: Int
            if (proc.wantedColumn > proc.drawnColumn) {
                r4 = (proc.wantedColumn + 0x1E).toShort().toInt()
                r6 = (r4 % 0x3C).toShort().toInt()
            } else {
                r6 = proc.wantedColumn
                r4 = r6
            }
            val r5 = (proc.wantedRow % 0x28).toShort().toInt()
            val r9 = if (r5 + 0x15 < 0x28) 0x15 else 0x28 - r5
            queueTileCopy(vsync, r6, r5, r4, proc.wantedRow, 1, r9)
            proc.drawnColumn = proc.wantedColumn
        }
        if (proc.drawnRow != proc.wantedRow) {
            applyPaletteMap(proc)
            if (kotlin.math.abs(proc.wantedRow - proc.drawnRow) > 1) {
                queueFullRedraw(vsync, proc.wantedColumn, proc.wantedRow)
                proc.drawnColumn = proc.wantedColumn
                proc.drawnRow = proc.wantedRow
                return
            }
            val r9: Int
            val r5: Int
            if (proc.wantedRow > proc.drawnRow) {
                r9 = (proc.wantedRow + 0x14).toShort().toInt()
                r5 = (r9 % 0x28).toShort().toInt()
            } else {
                r5 = proc.wantedRow
                r9 = r5
            }
            val b = proc.wantedColumn - (proc.wantedColumn / 32) * 32
            val r4 = b.toByte().toInt()
            val r6 = (proc.wantedColumn % 0x3C).toShort().toInt()
            if (r4 < 1) {
                queueTileCopy(vsync, r6, r5, r4, r9, 0x1F, 1)
            } else {
                val iVar6 = (0x20 - r4).toShort().toInt()
                queueTileCopy(vsync, r6, r5, r4, r9, iVar6, 1)
                val tmp = (0x1F - iVar6).toShort().toInt()
                queueTileCopy(vsync, r6 + iVar6, r5, iVar6 + r4, r9, tmp, 1)
            }
            proc.drawnRow = proc.wantedRow
        }
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BAB00
     *
     * Clears `gUnknown_03001E30` (meaning unknown), the last init step of the screen proc.
     */
    public fun sub80BAB00() {
        unk3001E30 = 0
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BAB0C
     *
     * The per-frame loop of the screen proc: follows the camera by clamping it to the BG3 scroll
     * (x to 0..0xF0, y only to at least 0; `GetWMCenteredCameraPosition` already limits y), works
     * out the wanted tile position, runs the pending [GmScreenProc.redrawRequests], queues the
     * newly exposed picture strips ([queueScrollEdge]) and sets the BG3
     * scroll. The redraw request branches never run in the prologue capture and are not
     * verified.
     */
    public fun followCamera(proc: GmScreenProc) {
        if (!proc.flags0) return
        proc.x = gmData.xCamera
        proc.y = gmData.yCamera
        if (gmData.xCamera < 0) {
            proc.x = 0
        } else if (gmData.xCamera > 0xF0) {
            proc.x = 0xF0
        }
        if (proc.y < 0) proc.y = 0
        proc.wantedColumn = (proc.x / 8).toByte().toInt()
        proc.wantedRow = (proc.y / 8).toByte().toInt()
        if (proc.redrawRequests and 4 != 0) {
            applyPalettes()
            proc.redrawRequests = proc.redrawRequests and 4.inv()
        }
        if (proc.redrawRequests and 2 != 0) {
            queueFullRedraw(
                proc.vsync!!.state as GMapScreenVSyncProc, proc.wantedColumn, proc.wantedRow,
            )
            proc.redrawRequests = proc.redrawRequests and 2.inv()
        }
        if (proc.redrawRequests and 1 != 0) {
            fillIdentityTilemap()
            applyPaletteMap(proc)
            proc.redrawRequests = proc.redrawRequests and 1.inv()
        }
        queueScrollEdge(proc)
        hardware.lcd.setBgPosition(BG3, proc.x, proc.y)
        proc.lastX = proc.x and 0xFFFF
        proc.lastY = proc.y and 0xFFFF
    }

    /**
     * Loads the four world map palettes into BG palette banks 9-12 (`ApplyPalettes(
     * gUnknown_08A95FE4, 9, 4)`) and asks for a palette upload (`EnablePaletteSync`).
     */
    private fun applyPalettes() {
        rom.pal(PALETTES, 16 * 4).copyInto(hardware.palette, 9 * 16)
        hardware.enablePaletteSync()
    }

    /** `CpuFastCopy(ROM + src, VRAM + dst, size)`: copies [size] bytes of ROM into VRAM. */
    private fun copyRom(src: Int, dst: Int, size: Int) {
        for (i in 0 until size) hardware.vram[dst + i] = rom.u8(src + i).toByte()
    }

    /** ROM addresses and layout constants of `src/worldmap_screen.c`. */
    public companion object {
        /** `gUnknown_08A83364`: the world map image (4bpp tiles, 60 x 40 tiles, 76800 bytes). */
        public const val IMAGE: Int = 0x08A83364

        /** `gUnknown_08A95FE4`: the world map palettes (4 banks of 16 colours). */
        public const val PALETTES: Int = 0x08A95FE4

        /** `gUnknown_08A96064`: the compressed palette map (decompressed by `Decompress`). */
        public const val PALETTE_MAP: Int = 0x08A96064

        /** Entries of the VSync queue (`sub_80BA6DC` refuses a ninth). */
        public const val QUEUE_ENTRIES: Int = 8

        /** Bytes between queue entries, per the reference capture (see [GMapScreenVSyncProc]). */
        private const val ENTRY_SIZE = 8

        /** The `c` argument of `StartGMapRoute`: the route proc's `chr`. */
        private const val ROUTE_CHR = 0x5000

        /** The `d` argument of `StartGMapRoute`: the route proc's `pal`. */
        private const val ROUTE_PAL = 0xE

        /** Size of the palette map buffer; the decompressed data is smaller. */
        private const val PALETTE_MAP_SIZE = 0x800

        /** Size given to `gUnknown_03001DE8`; the decomp declares none, and it is never read. */
        private const val UNK_3001DE8_SIZE = 0x20

        /** `BG_3` (include/hardware.h). */
        private const val BG3 = 3

        /** `BG3_SYNC_BIT` (include/hardware.h): `1 << 3`, the BG3 upload request bit. */
        private const val BG3_SYNC_BIT = 8

        /** BG3 tile data offset in VRAM (`0x06008000` in the decomp). */
        private const val VRAM_BG3 = 0x8000

        /** `PROC_MARK_WMSTUFF` (include/proc.h): the proc mark of all world map procs. */
        private const val PROC_MARK_WMSTUFF = 8
    }
}
