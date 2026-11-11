package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.MovementMap
import fe8.game.bm.PathArrow
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.RamSymbols
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The drawing of the path arrow ([PathArrow.drawPathArrow], [PathArrow.drawUpdatedPathArrow], the
 * graphics load of [PathArrow.init]) against `ram_trace_prologue_select_move` (init f1262, the arrow
 * grows at f1275 and f1283) and `ram_trace_prologue_cancel_end` (f1401..f1410).
 *
 * The setup is that of [PathArrowDifferentialTest]. Each frame clears the sprite layers (as the
 * main loop does) and runs `DrawUpdatedPathArrow` with the cursor and camera of the dump; the chain
 * of sprite layer 11 (`sSpriteLayers[11]`, with the pool entries' oam1, oam0, oam2 and object) is
 * compared with the dump's. In the select case the init frame also compares the arrow tiles in OBJ
 * VRAM (0x5E00) and palette 0x13 with the dump. At each full dump (every 10th frame) the arrow's
 * objects, as `PutOamHi` expands them, are looked for in the hardware OAM as one run.
 *
 * Not exercised: points outside the camera bounds, a path of length 0, `PathArrowDisp_Init(1)`.
 */
class PathArrowDrawDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "DrawPathArrow", "DrawUpdatedPathArrow", "GetDirectionOfPathBeforeIndex",
        "GetDirectionOfPathAfterIndex", "PointInCameraBounds", "PathArrowDisp_Init",
    )
    fun `select_move path arrow drawing matches the game`() {
        val c = run("ram_trace_prologue_select_move", 1262, 1296, init = true, 1263..1294)
        assertTrue(c.frames >= 30 && c.oamChecks == 2 && c.maxPoints == 2, "$c")
    }

    @Test
    @VerifiesPort(
        "DrawPathArrow", "DrawUpdatedPathArrow", "GetDirectionOfPathBeforeIndex",
        "GetDirectionOfPathAfterIndex", "PointInCameraBounds",
    )
    fun `cancel_end path arrow drawing matches the game`() {
        val c = run("ram_trace_prologue_cancel_end", 1401, 1410, init = false, 1404..1410)
        assertTrue(c.frames == 10 && c.maxPoints >= 1 && c.oamChecks == 1, "$c")
    }

    private class Checks {
        var frames = 0
        var oamChecks = 0
        var maxPoints = 0

        override fun toString() = "frames=$frames oamChecks=$oamChecks maxPoints=$maxPoints"
    }

    private fun dumpName(run: ReferenceData.Run, f: Int): String {
        val full = "f%05d".format(f)
        return if (run.dumps.containsKey(full)) full else "r%05d".format(f)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private fun dumpChain(ew: ByteArray, layer: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var p = sym["sSpriteLayers"].address + 16 * layer
        while (p != 0) {
            val o = p - EWRAM
            out += listOf(u16(ew, o + 4), u16(ew, o + 6), u16(ew, o + 8), u32(ew, o + 0x0C))
            p = u32(ew, o)
        }
        return out
    }

    private fun ourChain(s: SpriteLayers, layer: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var it: SpriteLayers.Entry? = s.layers[layer]
        while (it != null) {
            out += listOf(it.oam1 and 0xFFFF, it.oam0 and 0xFFFF, it.oam2, it.obj?.id ?: 0)
            it = it.next
        }
        return out
    }

    private fun run(name: String, initFrame: Int, last: Int, init: Boolean, draws: IntRange): Checks {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(name)
        val rom = RomView.ofRom(romFile.readBytes())
        fun dump(f: Int) = DrawDump(
            run.region(dumpName(run, f), "ewram"), run.region(dumpName(run, f), "iwram"),
        )
        val seed = dump(initFrame - 1)
        val playSt = PlaySt().also { it.decode(seed.ewram, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(seed.ewram, BM_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(dump(initFrame).ewram) }
        readPool(seed, GBM_MAP_TERRAIN, map, map.terrain)
        val moves = MovementMap(rom, map, units, playSt)
        val active = fe8.game.bm.ActiveUnit()
        val activeAddress = dump(initFrame).word(GACTIVEUNIT)
        active.unit = units.bytes[activeAddress - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
        active.action.moveCount = dump(initFrame).byte(ACTION_DATA + 0x10)
        moves.generateUnitMovementMapExt(active.unit, units.movement(active.unit))
        readPool(dump(initFrame), GBM_MAP_MOVEMENT, map, map.movement)
        val sprites = SpriteLayers()
        val hardware = BgHardware()
        val arrow = PathArrow(
            map, units, moves, active, bm, sprites = sprites, hardware = hardware, rom = rom,
        )
        arrow.hooks.generateBestMovementScript = { _, _, _ ->
            error("$name: the path was re-routed (GenerateBestMovementScript called)")
        }
        seed.ewram.copyInto(arrow.buffer, 0, ARROW - EWRAM + 0x29, ARROW - EWRAM + 0x6C)

        val c = Checks()
        for (f in initFrame..last) {
            val d = dump(f)
            bm.decode(d.ewram, BM_ST - EWRAM)
            sprites.clear()
            if (f == initFrame && init) {
                arrow.init(0)
                // The init frame has no VRAM dump: the next full dump does (the tiles stay).
                val vram = run.region(dumpName(run, f - f % 10 + 10), "vram")
                for (k in 0 until (rom.u32(0x08A03054) ushr 8)) {
                    assertEquals(
                        vram[OBJ_VRAM + 0x5E00 + k], hardware.vram[OBJ_VRAM + 0x5E00 + k],
                        "$name f$f arrow tile byte $k",
                    )
                }
                for (k in 0 until 16) {
                    assertEquals(
                        u16(d.ewram, sym["gPaletteBuffer"].address - EWRAM + 2 * (0x13 * 16 + k)),
                        hardware.palette[0x13 * 16 + k].toInt() and 0xFFFF,
                        "$name f$f palette 0x13 colour $k",
                    )
                }
            } else if (f in draws) {
                arrow.drawUpdatedPathArrow()
            }
            val at = "$name f$f"
            // Outside [draws] no idle runs (the init frame, the confirm, the frames after it, the
            // frames before the cancelled select is back): the layer is empty in the game too.
            assertEquals(dumpChain(d.ewram, 11), ourChain(sprites, 11), "$at sprite layer 11")
            c.maxPoints = maxOf(c.maxPoints, arrow.pathLen)
            if (f % 10 == 0 && f in draws && arrow.pathLen > 0) {
                val hw = run.region(dumpName(run, f), "oam")
                val buf = OamBuffer()
                var it = sprites.layers[11].next
                while (it != null) {
                    it.obj?.let { o -> buf.putHi(it!!.oam1, it!!.oam0, o.data, it!!.oam2) }
                    it = it.next
                }
                val n = buf.hiPut / 4
                var found = false
                for (start in 0..OamBuffer.OBJ_COUNT - n) {
                    var ok = true
                    for (i in 0 until n) {
                        for (h in 0 until 3) {
                            if (u16(hw, (start + i) * 8 + 2 * h) !=
                                (buf.buffer[i * 4 + h].toInt() and 0xFFFF)
                            ) {
                                ok = false
                            }
                        }
                    }
                    if (ok) { found = true; break }
                }
                assertTrue(n > 0 && found, "$at: the arrow's $n objects are not one run in OAM")
                c.oamChecks++
            }
            c.frames++
        }
        println("PathArrowDraw $name: $c")
        return c
    }

    private fun readPool(dump: DrawDump, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = dump.rowPointer(handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = dump.byte(first + (y + 2) * (map.sizeX + 2) + x)
            }
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val ACTION_DATA = 0x0203A958
        const val ARROW = 0x0203A990
        const val GACTIVEUNIT = 0x03004E50
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val OBJ_VRAM = 0x10000
    }
}

/** The EWRAM and IWRAM of one dump, addressed with GBA addresses. */
private class DrawDump(val ewram: ByteArray, val iwram: ByteArray) {
    fun byte(address: Int): Int =
        if (address >= 0x03000000) {
            iwram[address - 0x03000000].toInt() and 0xFF
        } else {
            ewram[address - 0x02000000].toInt() and 0xFF
        }

    fun half(address: Int): Int = (byte(address) or (byte(address + 1) shl 8)).toShort().toInt()

    fun word(address: Int): Int = byte(address) or (byte(address + 1) shl 8) or
        (byte(address + 2) shl 16) or (byte(address + 3) shl 24)

    /** The address of the data of row [row] (the handle points at the row 0 pointer). */
    fun rowPointer(handle: Int, row: Int): Int = word(word(handle) + 4 * row)
}
