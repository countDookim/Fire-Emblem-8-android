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

/** The fields of `struct GmScreenProc` (`ProcScr_GMapScreen`). */
public class GmScreenProc {
    /** `flags_0` (bit 0 of the byte at +0x29). */
    public var flags0: Boolean = false

    /** `unk_2a` (+0x2A): redraw requests (bit 0 tilemap, bit 1 tile data, bit 2 palette). */
    public var unk2a: Int = 0

    /** `unk_2b` (+0x2B, `s8`): the tile column drawn into the tilemap so far. */
    public var unk2b: Int = 0

    /** `unk_2c` (+0x2C, `s8`): the tile row drawn into the tilemap so far. */
    public var unk2c: Int = 0

    /** `unk_2d` (+0x2D, `s8`): the wanted tile column, `x / 8`. */
    public var unk2d: Int = 0

    /** `unk_2e` (+0x2E, `s8`): the wanted tile row, `y / 8`. */
    public var unk2e: Int = 0

    /** `unk_2f` (+0x2F). */
    public var unk2f: Int = 0

    /** `unk_30` (+0x30). */
    public var unk30: Int = 0

    /** `unk_31` (+0x31). */
    public var unk31: Int = 0

    /** `unk_32` (+0x32). */
    public var unk32: Int = 0

    /** `x` (+0x34, `s16`): the BG3 scroll x. */
    public var x: Int = 0

    /** `y` (+0x36, `s16`): the BG3 scroll y. */
    public var y: Int = 0

    /** `unk_38` (+0x38, `u16`): the last `x`. */
    public var unk38: Int = 0

    /** `unk_3a` (+0x3A, `u16`): the last `y`. */
    public var unk3a: Int = 0

    /** `unk_3c` (+0x3C): ROM address of the big map image (`gUnknown_08A83364`). */
    public var image: Int = 0

    /** `unk_40` (+0x40): the BG3 tilemap buffer. */
    public var tilemap: ShortArray? = null

    /** `unk_44` (+0x44): the decompressed palette map (`gUnknown_020087A0`). */
    public var paletteMap: ByteArray? = null

    /** `unk_48` (+0x48): the `ProcScr_GMapScreenVSync` proc. */
    public var vsync: Proc? = null

    /** `gmroute` (+0x4C): the `ProcScr_GMapRoute` proc (null while a stand-in until D11-wm3f). */
    public var gmRoute: Proc? = null
}

/** The fields of `struct GMapScreenVSyncProc` (`ProcScr_GMapScreenVSync`). */
public class GMapScreenVSyncProc {
    /** `unk_2c` (+0x2C): ROM address of the big map image. */
    public var image: Int = 0

    /** `unk_30` (+0x30): the BG3 tilemap buffer. */
    public var tilemap: ShortArray? = null

    /** `unk_34` (+0x34): the decompressed palette map. */
    public var paletteMap: ByteArray? = null

    /** `unk_38` (+0x38, `s16`): the number of queued tile rows. */
    public var count: Int = 0

    /**
     * `unk_3c` (+0x3C): the queue (`gUnknown_03001DA8`), [WorldMapScreen.QUEUE_ENTRIES] entries of
     * eight bytes (six `s8`: source column, source row, VRAM column, VRAM row, width, rows).
     */
    public var queue: ByteArray = ByteArray(0)

    /** `unk_40` (+0x40). */
    public var unk40: Int = 0

    /** `unk_44` (+0x44): `gUnknown_03001DE8`, unused here. */
    public var unk44: ByteArray? = null
}

/**
 * The scrolling BG3 world map image (`src/worldmap_screen.c`): `ProcScr_GMapScreen` keeps the
 * BG3 tilemap and tile data in step with `gGMData.xCamera/yCamera`, and
 * `ProcScr_GMapScreenVSync` copies the queued tile rows into VRAM.
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
    /** `gUnknown_020087A0`: the decompressed palette map. */
    public val paletteMap: ByteArray = ByteArray(PALETTE_MAP_SIZE)

    /** `gUnknown_03001DA8`: the VSync tile row queue (entries are 8 bytes apart in the capture). */
    public val queue: ByteArray = ByteArray(QUEUE_ENTRIES * ENTRY_SIZE)

    /** `gUnknown_03001DE8`, only passed on to the VSync proc. */
    private val unk3001DE8: ByteArray = ByteArray(UNK_3001DE8_SIZE)

    /** `gUnknown_03001E30`, cleared by `sub_80BAB00`. */
    public var unk3001E30: Int = 0

    /** Callees that are not ported yet. */
    public class Hooks(
        /**
         * `StartGMapRoute(proc, &gGMData.openPaths, 0x5000, 0xe)`: stand-in until D11-wm3f; the
         * proc it returns becomes [GmScreenProc.gmRoute].
         */
        public val startGMapRoute: (parent: Proc) -> Proc? = { null },
    )

    /**
     * `ProcScr_GMapScreenVSync`.
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
     * `ProcScr_GMapScreen`.
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
            call(ProcFunc { sub80BA490(it.state as GmScreenProc) }),
            call(ProcFunc { sub80BA4D0(it.state as GmScreenProc) }),
            call(ProcFunc { sub80BAB00() }),
            repeat(ProcFunc { sub80BAB0C(it.state as GmScreenProc) }),
            end(),
        ),
    ) { GmScreenProc() }

    /**
     * Ported from src/worldmap_screen.c: NewMapScreen
     *
     * Starts the screen proc in root tree [rootIndex] (`PROC_TREE_5` in `WorldMap_Init`) and its
     * route child.
     */
    public fun newMapScreen(rootIndex: Int): Proc {
        val proc = engine.start(screenScript, rootIndex)
        (proc.state as GmScreenProc).gmRoute = hooks.startGMapRoute(proc)
        return proc
    }

    /**
     * Ported from src/worldmap_screen.c: NewMapScreenVSync
     *
     * Starts the VSync proc in root tree 0.
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
     * Ends the VSync proc.
     */
    public fun mapScreenOnDelete(proc: GmScreenProc) {
        engine.end(proc.vsync)
    }

    /**
     * Ported from src/worldmap_screen.c: MapScreen_Init
     *
     * Clears the fields, decompresses the palette map, loads the map palettes (banks 9-12),
     * clears the BG3 tilemap and starts the VSync proc.
     */
    public fun mapScreenInit(proc: GmScreenProc) {
        proc.flags0 = true
        proc.unk2a = 0
        proc.y = 0
        proc.x = 0
        proc.unk3a = 0
        proc.unk38 = 0
        proc.image = IMAGE
        val tilemap = hardware.mapBuffer(BG3)
        proc.tilemap = tilemap
        rom.decompressInto(PALETTE_MAP, paletteMap, 0)
        proc.paletteMap = paletteMap
        proc.unk2c = 0
        proc.unk2b = 0
        proc.unk2e = 0
        proc.unk2d = 0
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
     * Copies the first 32 tile rows of the image to BG3 tile data and fills the BG3 tilemap with
     * the identity map.
     */
    public fun sub80BA490(proc: GmScreenProc) {
        for (i in 0 until 0x20) copyRom(proc.image + i * 0x780, VRAM_BG3 + i * 0x400, 0x400)
        sub80BA458()
        hardware.enableBgSyncByMask(BG3_SYNC_BIT)
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA458
     *
     * Fills the BG3 tilemap with tile `i` of palette bank 9, `i = 0 until 0x400`.
     */
    public fun sub80BA458() {
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
     * Rewrites the palette bank bits of the BG3 tilemap entries of the visible 32 x 22 tiles
     * from the palette map. The branch for an odd `unk_2d` is not verified: the camera column is
     * even in the whole capture.
     */
    public fun sub80BA4D0(proc: GmScreenProc) {
        val tilemap = checkNotNull(proc.tilemap)
        val map = checkNotNull(proc.paletteMap)
        var row = proc.unk2e * 0x20 + proc.unk2d / 2
        val odd = proc.unk2d and 1 != 0
        for (iy in 0 until 0x16) {
            var p = row
            val y = (iy + proc.unk2e) and 0x1F
            row = p + 0x20
            for (ix in 0 until 0x10) {
                val x = (ix * 2 + proc.unk2d) and 0x1F
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

    /** `(entry & 0xFFF) | high`, stored as `u16`. */
    private fun retile(tilemap: ShortArray, index: Int, high: Int) {
        tilemap[index] = ((tilemap[index].toInt() and 0xFFF) or high).toShort()
    }

    /**
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
     * Copies every queued tile row from the image to BG3 tile data and empties the queue.
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
     * Queues one tile row copy; returns 1 if the queue (8 entries) is full, else 0.
     */
    public fun sub80BA6DC(
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
     * Queues a full redraw of the 32 x 22 visible tiles at tile position ([a], [b]), split in
     * two where it wraps at the 0x3C column ring. The single-copy branch (column 0) is not
     * verified.
     */
    public fun sub80BA818(proc: GMapScreenVSyncProc, a: Int, b: Int) {
        val a1 = a % 0x3C
        val b1 = b % 0x28
        if (a1 + 0x20 > 0x20) {
            val c = 0x20 - a1
            sub80BA6DC(proc, a1, b1, a, b, c, 0x16)
            sub80BA6DC(proc, a1 + c, b1, a + c, b, 0x20 - c, 0x16)
        } else {
            sub80BA6DC(proc, a1, b1, a, b, 0x20, 0x16)
        }
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BA8A0
     *
     * Brings the drawn tile column (`unk_2b`) and row (`unk_2c`) up to the wanted ones
     * (`unk_2d`, `unk_2e`): one new column or row per call, or a full redraw if it moved more
     * than one tile. Only the full-redraw branch runs in the capture; the one-tile column and row
     * branches are not verified.
     */
    public fun sub80BA8A0(proc: GmScreenProc) {
        val vsync = proc.vsync!!.state as GMapScreenVSyncProc
        if (proc.unk2b != proc.unk2d) {
            sub80BA4D0(proc)
            if (kotlin.math.abs(proc.unk2d - proc.unk2b) > 1) {
                sub80BA818(vsync, proc.unk2d, proc.unk2e)
                proc.unk2b = proc.unk2d
                proc.unk2c = proc.unk2e
                return
            }
            val r4: Int
            val r6: Int
            if (proc.unk2d > proc.unk2b) {
                r4 = (proc.unk2d + 0x1E).toShort().toInt()
                r6 = (r4 % 0x3C).toShort().toInt()
            } else {
                r6 = proc.unk2d
                r4 = r6
            }
            val r5 = (proc.unk2e % 0x28).toShort().toInt()
            val r9 = if (r5 + 0x15 < 0x28) 0x15 else 0x28 - r5
            sub80BA6DC(vsync, r6, r5, r4, proc.unk2e, 1, r9)
            proc.unk2b = proc.unk2d
        }
        if (proc.unk2c != proc.unk2e) {
            sub80BA4D0(proc)
            if (kotlin.math.abs(proc.unk2e - proc.unk2c) > 1) {
                sub80BA818(vsync, proc.unk2d, proc.unk2e)
                proc.unk2b = proc.unk2d
                proc.unk2c = proc.unk2e
                return
            }
            val r9: Int
            val r5: Int
            if (proc.unk2e > proc.unk2c) {
                r9 = (proc.unk2e + 0x14).toShort().toInt()
                r5 = (r9 % 0x28).toShort().toInt()
            } else {
                r5 = proc.unk2e
                r9 = r5
            }
            val b = proc.unk2d - (proc.unk2d / 32) * 32
            val r4 = b.toByte().toInt()
            val r6 = (proc.unk2d % 0x3C).toShort().toInt()
            if (r4 < 1) {
                sub80BA6DC(vsync, r6, r5, r4, r9, 0x1F, 1)
            } else {
                val iVar6 = (0x20 - r4).toShort().toInt()
                sub80BA6DC(vsync, r6, r5, r4, r9, iVar6, 1)
                val tmp = (0x1F - iVar6).toShort().toInt()
                sub80BA6DC(vsync, r6 + iVar6, r5, iVar6 + r4, r9, tmp, 1)
            }
            proc.unk2c = proc.unk2e
        }
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BAB00
     */
    public fun sub80BAB00() {
        unk3001E30 = 0
    }

    /**
     * Ported from src/worldmap_screen.c: sub_80BAB0C
     *
     * Follows the camera: clamps it to the BG3 scroll, updates the wanted tile position, runs the
     * redraw requests of `unk_2a` and sets the BG3 position. The `unk_2a` branches never run in
     * the prologue capture and are not verified.
     */
    public fun sub80BAB0C(proc: GmScreenProc) {
        if (!proc.flags0) return
        proc.x = gmData.xCamera
        proc.y = gmData.yCamera
        if (gmData.xCamera < 0) {
            proc.x = 0
        } else if (gmData.xCamera > 0xF0) {
            proc.x = 0xF0
        }
        if (proc.y < 0) proc.y = 0
        proc.unk2d = (proc.x / 8).toByte().toInt()
        proc.unk2e = (proc.y / 8).toByte().toInt()
        if (proc.unk2a and 4 != 0) {
            applyPalettes()
            proc.unk2a = proc.unk2a and 4.inv()
        }
        if (proc.unk2a and 2 != 0) {
            sub80BA818(proc.vsync!!.state as GMapScreenVSyncProc, proc.unk2d, proc.unk2e)
            proc.unk2a = proc.unk2a and 2.inv()
        }
        if (proc.unk2a and 1 != 0) {
            sub80BA458()
            sub80BA4D0(proc)
            proc.unk2a = proc.unk2a and 1.inv()
        }
        sub80BA8A0(proc)
        hardware.lcd.setBgPosition(BG3, proc.x, proc.y)
        proc.unk38 = proc.x and 0xFFFF
        proc.unk3a = proc.y and 0xFFFF
    }

    /** `ApplyPalettes(gUnknown_08A95FE4, 9, 4)` and `EnablePaletteSync`. */
    private fun applyPalettes() {
        rom.pal(PALETTES, 16 * 4).copyInto(hardware.palette, 9 * 16)
        hardware.enablePaletteSync()
    }

    /** `CpuFastCopy(ROM + src, VRAM + dst, size)`. */
    private fun copyRom(src: Int, dst: Int, size: Int) {
        for (i in 0 until size) hardware.vram[dst + i] = rom.u8(src + i).toByte()
    }

    /** ROM addresses and layout constants. */
    public companion object {
        /** `gUnknown_08A83364`: the big map image. */
        public const val IMAGE: Int = 0x08A83364

        /** `gUnknown_08A95FE4`: the map palettes. */
        public const val PALETTES: Int = 0x08A95FE4

        /** `gUnknown_08A96064`: the compressed palette map. */
        public const val PALETTE_MAP: Int = 0x08A96064

        /** Entries of the VSync queue. */
        public const val QUEUE_ENTRIES: Int = 8

        private const val ENTRY_SIZE = 8
        private const val PALETTE_MAP_SIZE = 0x800
        private const val UNK_3001DE8_SIZE = 0x20
        private const val BG3 = 3
        private const val BG3_SYNC_BIT = 8
        private const val VRAM_BG3 = 0x8000
        private const val PROC_MARK_WMSTUFF = 8
    }
}
