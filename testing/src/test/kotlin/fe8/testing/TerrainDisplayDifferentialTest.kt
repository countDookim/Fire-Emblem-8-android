package fe8.testing

import fe8.game.scene.IntBinaryOperator
import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerInterface
import fe8.game.bm.PlayerInterfaceState
import fe8.game.bm.UnitTable
import fe8.game.text.Fe8Text
import fe8.game.ui.GreenText
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The player-phase terrain window and the side-window maker (src/player_interface.c) against
 * `ram_trace_prologue_select_move` and `ram_trace_prologue_cancel_end`.
 *
 * The windows exist from f1172 on, before the first per-frame RAM (f1255), so each run is seeded
 * from a frame where they are absent and started as the original does:
 * - select_move: `StartPlayerPhaseSideWindows` (SideWindowMaker at f1363, windows from f1364);
 * - cancel_end: `InitPlayerPhaseInterface` (no maker proc there) at f1291 and at f1426; the
 *   second window sees the cursor move (4,5) to (5,5) at f1445.
 *
 * Alignment: the start pass overruns its frame (it decompresses the interface graphics), so the
 * dump of the start frame S (f1363, f1291, f1426) caught it half way and S+1 holds the same
 * pass's end (the windows have run their first step; the terrain window has not drawn yet).
 * S is not compared; the start is the pass of S, each later frame runs the proc trees once and
 * is compared with the dump of the same frame.
 *
 * Seeds: BG0 and BG1 tilemap buffers, the palette buffer, the LCD buffer, `gPlaySt`, the terrain
 * map, text VRAM and the text font. Every pass re-seeds `gBmSt` (cursor and camera) from the
 * frame's dump, runs the proc trees once and compares with the dump of the same frame: the
 * terrain proc (script position, cursor quadrant, hide / retract flags, window quadrant, show /
 * hide clock, cursor and previous cursor once set, the text strip header) and the maker proc;
 * BG0 and BG1 tilemap rows 13..19 (the terrain window's strip; the unit window sits in rows 0..5
 * and the goal window in the top right in these runs); BG palette banks 2 and 0x18; the LCD
 * window, blend and scroll registers. Every full dump (each 10th frame) also compares the text
 * strip tiles, the interface font tiles in BG VRAM and the digit tiles in OBJ VRAM.
 *
 * Stand-ins: the unit window and the goal window are other ports (PP-15, PP-16). Their
 * `windowQuadrant` the terrain proc reads (they hold the window's corner against it) comes from
 * the reference dump of the previous frame, including the stale proc slot of a window that has
 * not initialised yet: the original's first pass sees that stale byte. They are not started.
 */
class TerrainDisplayDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "StartPlayerPhaseSideWindows", "InitPlayerPhaseInterface", "TerrainDisplay_Init",
        "TerrainDisplay_Loop_OnSideChange", "TerrainDisplay_Loop_SlideIn",
        "TerrainDisplay_Loop_Display", "DrawTerrainDisplayWindow", "GetWindowQuadrant",
        "GetCursorQuadrant",
    )
    fun `select_move windows start after the unit waits`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(SELECT_MOVE, seedFrame = 1362, vramDump = 1360)
        sim.pi.startPlayerPhaseSideWindows()
        for (f in 1364..1420) sim.pass(f)
        assertEquals(57, sim.compared, "frames compared")
        assertEquals(listOf(1370, 1380, 1390, 1400, 1410, 1420), sim.fullDumps, "full dumps")
        assertEquals(setOf(0, 1, 2), sim.slideSteps, "slide clock values seen")
    }

    @Test
    @VerifiesPort(
        "InitPlayerPhaseInterface", "TerrainDisplay_Init", "TerrainDisplay_Loop_OnSideChange",
        "TerrainDisplay_Loop_SlideIn", "TerrainDisplay_Loop_Display", "DrawTerrainDisplayWindow",
        "GetCursorQuadrant",
    )
    fun `cancel_end window restarts after cancelling the selection`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(CANCEL_END, seedFrame = 1290, vramDump = 1290)
        sim.pi.initPlayerPhaseInterface()
        for (f in 1292..1310) sim.pass(f)
        assertEquals(19, sim.compared, "frames compared")
    }

    @Test
    @VerifiesPort(
        "InitPlayerPhaseInterface", "TerrainDisplay_Loop_Display", "DrawTerrainDisplayWindow",
        "sub_808C288", "GetCursorQuadrant",
    )
    fun `cancel_end window follows the cursor inside its quadrant`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(CANCEL_END, seedFrame = 1425, vramDump = 1420)
        sim.pi.initPlayerPhaseInterface()
        for (f in 1427..1464) sim.pass(f)
        assertEquals(38, sim.compared, "frames compared")
        assertEquals(listOf(1430, 1440, 1450, 1460), sim.fullDumps, "full dumps")
        assertEquals(true, sim.sawCursorMove, "the cursor move at f1445 was compared")
    }

    @Test
    @VerifiesPort("EndPlayerPhaseSideWindows")
    fun `select_move ending the windows clears the BGs and the blend`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(SELECT_MOVE, seedFrame = 1362, vramDump = 1360)
        sim.pi.startPlayerPhaseSideWindows()
        for (f in 1364..1380) sim.pass(f)
        assertNotNull(sim.engine.find(sim.pi.terrainScript))
        sim.pi.endPlayerPhaseSideWindows()
        assertNull(sim.engine.find(sim.pi.terrainScript), "terrain proc ended")
        sim.compareEnded(1261)
    }

    private inner class Sim(val run: String, val seedFrame: Int, vramDump: Int) {
        val data = ref.run(run)
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val text = TextSystem(romBytes, HwVramHost(hw.vram), Fe8Text.CONFIG)
        val bm = BmSt()
        val units = UnitTable(rom)
        val oam = OamBuffer()
        val playSt = PlaySt()
        val camMove = CamMove(engine, bm)
        val pi: PlayerInterface
        var current = 0
        var previous = seedFrame
        var first = true
        var compared = 0
        var sawCursorMove = false
        val fullDumps = ArrayList<Int>()
        val slideSteps = HashSet<Int>()
        private val seedRam: ByteArray

        init {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            pi = PlayerInterface(
                engine, bm, playSt, units, rom, hw, oam, text, messages, camMove.script,
                GreenText(engine, rom, hw) { 0 },
            )
            val e = ewram(seedFrame)
            playSt.decode(e, PLAY_ST - EWRAM)
            seedRam = e
            pi.hooks.terrainAt = IntBinaryOperator { x, y -> terrainAt(x, y) }
            units.loadFrom(e)
            pi.hooks.unitIdAt = IntBinaryOperator { x, y -> unitIdAt(x, y) }
            pi.newUnitWindowResidue = 3
            pi.newGoalWindowResidue = staleGoalQuadrant(seedFrame + 2)
            for (bg in 0..1) {
                val at = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) hw.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            for (i in 0 until 0x200) {
                hw.palette[i] = u16(e, PALETTE_BUFFER - EWRAM + 2 * i).toShort()
            }
            hw.lcd.readFrom(iwram(seedFrame), LCD_BUFFER - IWRAM)
            data.region(names.getValue(vramDump), "vram").copyInto(hw.vram, 0, 0, 0x18000)
            text.initTextFont(null, 0x06001000, 0x80, 0)
        }

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        /** `gBmMapTerrain[y][x]` in the seed dump (the terrain map does not change). */
        private fun terrainAt(x: Int, y: Int): Int {
            val table = u32(seedRam, GBM_MAP_TERRAIN - EWRAM) - EWRAM
            val row = u32(seedRam, table + 4 * y) - EWRAM
            return seedRam[row + x].toInt() and 0xFF
        }

        /** `gBmMapUnit[y][x]` in this pass's dump. */
        private fun unitIdAt(x: Int, y: Int): Int {
            val e = ewram(current)
            val table = u32(e, GBM_MAP_UNIT - EWRAM) - EWRAM
            val row = u32(e, table + 4 * y) - EWRAM
            return e[row + x].toInt() and 0xFF
        }

        /** The `windowQuadrant` byte the goal proc's slot held in the seed dump ([first]'s slot). */
        private fun staleGoalQuadrant(first: Int): Int {
            val slot = slotOf(ewram(first), GOAL_SCRIPT)
            if (slot < 0) return -1
            return ewram(seedFrame)[PROC_ARRAY - EWRAM + slot * PROC_SIZE + 0x57].toInt()
        }

        private fun slotOf(e: ByteArray, script: Int): Int {
            for (slot in 0 until 64) {
                if (u32(e, PROC_ARRAY - EWRAM + slot * PROC_SIZE) == script) return slot
            }
            return -1
        }

        fun pass(f: Int) {
            current = f
            previous = if (first) seedFrame else f - 1
            first = false
            bm.decode(ewram(f), BM_ST - EWRAM)
            units.loadFrom(ewram(f))
            oam.syncHi()
            for (t in 0 until 8) engine.run(engine.root(t))
            compare(f)
        }

        fun compare(f: Int) {
            val e = ewram(f)
            val at = "f$f"
            val tiSlot = slotOf(e, TERRAIN_SCRIPT)
            val got = engine.find(pi.terrainScript)
            assertEquals(tiSlot >= 0, got != null, "$at terrain live")
            if (tiSlot >= 0 && got != null) {
                val o = PROC_ARRAY - EWRAM + tiSlot * PROC_SIZE
                val state = got.state as PlayerInterfaceState
                assertEquals((u32(e, o + 4) - TERRAIN_SCRIPT) / 8, got.scrCur, "$at terrain cmd")
                assertEquals(e[o + 0x50].toInt(), state.cursorQuadrant, "$at cursorQuadrant")
                assertEquals(e[o + 0x55].toInt() != 0, state.hideContents, "$at hideContents")
                assertEquals(e[o + 0x56].toInt() != 0, state.isRetracting, "$at isRetracting")
                assertEquals(e[o + 0x57].toInt(), state.windowQuadrant, "$at windowQuadrant")
                assertEquals(u32(e, o + 0x58), state.showHideClock, "$at showHideClock")
                if (state.windowQuadrant != -1) {
                    assertEquals(e[o + 0x4E].toInt() and 0xFF, state.xCursor, "$at xCursor")
                    assertEquals(e[o + 0x4F].toInt() and 0xFF, state.yCursor, "$at yCursor")
                }
                if (got.scrCur == DISPLAY_CMD) {
                    assertEquals(e[o + 0x4C].toInt() and 0xFF, state.xCursorPrev, "$at xCursorPrev")
                    assertEquals(e[o + 0x4D].toInt() and 0xFF, state.yCursorPrev, "$at yCursorPrev")
                    if (state.xCursorPrev != state.xCursor) sawCursorMove = true
                }
                if (state.windowQuadrant != -1) {
                    val t = state.text
                    assertEquals(u16(e, o + 0x2C), t.chrPosition, "$at text chrPosition")
                    assertEquals(e[o + 0x30].toInt(), t.tileWidth, "$at text tileWidth")
                    assertEquals(e[o + 0x31].toInt() != 0, t.dbEnabled, "$at text dbEnabled")
                    assertEquals(e[o + 0x32].toInt(), t.dbId, "$at text dbId")
                    assertEquals(e[o + 0x2F].toInt(), t.colorId, "$at text colorId")
                    assertEquals(e[o + 0x2E].toInt() and 0xFF, t.x, "$at text x")
                }
                slideSteps += state.showHideClock
            }
            val makerSlot = slotOf(e, MAKER_SCRIPT)
            val maker = engine.find(pi.sideWindowMakerScript)
            assertEquals(makerSlot >= 0, maker != null, "$at maker live")
            if (makerSlot >= 0 && maker != null) {
                val o = PROC_ARRAY - EWRAM + makerSlot * PROC_SIZE
                assertEquals((u32(e, o + 4) - MAKER_SCRIPT) / 8, maker.scrCur, "$at maker cmd")
            }

            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 13 * 32 until 20 * 32) {
                    assertEquals(u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "$at BG$bg tilemap[${i / 32}][${i % 32}]")
                }
            }
            for (i in 0 until 16) {
                for (bank in intArrayOf(2, 0x18)) {
                    assertEquals(u16(e, PALETTE_BUFFER - EWRAM + 2 * (bank * 16 + i)),
                        hw.palette[bank * 16 + i].toInt() and 0xFFFF, "$at palette $bank/$i")
                }
            }
            compareLcd(at, iwram(f))
            compared++
            if (names.getValue(f).startsWith("f")) {
                compareVram(at, f, e)
                fullDumps += f
            }
        }

        fun compareLcd(at: String, iw: ByteArray) {
            val lcd = LCD_BUFFER - IWRAM
            val lc = hw.lcd
            assertEquals(u16(iw, lcd), lc.dispcnt, "$at dispcnt")
            assertEquals(u16(iw, lcd + 0x34) or (u16(iw, lcd + 0x36) shl 16), lc.wincnt,
                "$at wincnt")
            assertEquals(u16(iw, lcd + 0x3C), lc.bldcnt, "$at bldcnt")
            assertEquals(iw[lcd + 0x44].toInt() and 0xFF, lc.blendCoeffA, "$at blendCoeffA")
            assertEquals(iw[lcd + 0x45].toInt() and 0xFF, lc.blendCoeffB, "$at blendCoeffB")
            assertEquals(iw[lcd + 0x46].toInt() and 0xFF, lc.blendY, "$at blendY")
            for (bg in 0..2) {
                assertEquals(u16(iw, lcd + 0x1C + 4 * bg), lc.bgHofs[bg], "$at BG$bg hofs")
                assertEquals(u16(iw, lcd + 0x1E + 4 * bg), lc.bgVofs[bg], "$at BG$bg vofs")
            }
        }

        fun compareVram(at: String, f: Int, e: ByteArray) {
            val vram = data.region(names.getValue(f), "vram")
            val o = PROC_ARRAY - EWRAM + slotOf(e, TERRAIN_SCRIPT) * PROC_SIZE
            val strip = 0x1000 + u16(e, o + 0x2C) * 2 * 0x20
            for (i in strip until strip + 5 * 2 * 2 * 0x20) {
                assertEquals(vram[i], hw.vram[i], "$at text VRAM +${Integer.toHexString(i)}")
            }
            val size = rom.decompressInto(GFX_FONT_TILES, ByteArray(0x8000), 0)
            for (i in 0x2000 until 0x2000 + size) {
                assertEquals(vram[i], hw.vram[i], "$at font VRAM +${Integer.toHexString(i)}")
            }
            val digits = rom.decompressInto(GFX_NUMBERS, ByteArray(0x8000), 0)
            for (i in 0x15C00 until 0x15C00 + digits) {
                assertEquals(vram[i], hw.vram[i], "$at digit VRAM +${Integer.toHexString(i)}")
            }
            for (i in 0x15D40 until 0x15D40 + 0x20) {
                assertEquals(vram[i], hw.vram[i], "$at copied tile VRAM +${Integer.toHexString(i)}")
            }
        }

        /** After `EndPlayerPhaseSideWindows`: BG0 / BG1 clear and the blend off, as at [frame]. */
        fun compareEnded(frame: Int) {
            val e = ewram(frame)
            val iw = iwram(frame)
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "f$frame BG$bg tilemap[$i]")
                }
            }
            val lcd = LCD_BUFFER - IWRAM
            // Bit 13 (backdrop as second target) is not touched by either: the seed's stays.
            assertEquals(u16(iw, lcd + 0x3C) and 0x1FFF, hw.lcd.bldcnt and 0x1FFF, "f$frame bldcnt")
            assertEquals(iw[lcd + 0x44].toInt() and 0xFF, hw.lcd.blendCoeffA, "f$frame blendCoeffA")
            assertEquals(iw[lcd + 0x45].toInt() and 0xFF, hw.lcd.blendCoeffB, "f$frame blendCoeffB")
            assertEquals(iw[lcd + 0x46].toInt() and 0xFF, hw.lcd.blendY, "f$frame blendY")
            assertEquals(0x3, hw.modifiedBgs and 0x3, "BG0 and BG1 flagged for sync")
        }
    }

    private companion object {
        const val SELECT_MOVE = "ram_trace_prologue_select_move"
        const val CANCEL_END = "ram_trace_prologue_cancel_end"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val ROM_BASE = 0x08000000
        const val BG0_BUFFER = 0x02022CA8
        const val BG1_BUFFER = 0x020234A8
        const val PALETTE_BUFFER = 0x020228A8
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val LCD_BUFFER = 0x03003080
        const val PROC_ARRAY = 0x02024E68
        const val PROC_SIZE = 0x6C

        const val TERRAIN_SCRIPT = 0x08A018AC
        const val MINIMUG_SCRIPT = 0x08A0190C
        const val MAKER_SCRIPT = 0x08A019C4
        const val GOAL_SCRIPT = 0x08A019E4
        const val GFX_FONT_TILES = 0x08A167C8
        const val GFX_NUMBERS = 0x08A16D6C
        const val DISPLAY_CMD = 9

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}

internal class HwVramHost(val vram: ByteArray) : TextHost {
    override fun language() = 1

    override fun applyTextPalette(talk: Boolean, palid: Int) = Unit

    override fun readVram32(addr: Int): Int {
        var v = 0
        for (k in 0..3) v = v or ((vram[addr - 0x06000000 + k].toInt() and 0xFF) shl (8 * k))
        return v
    }

    override fun writeVram32(addr: Int, value: Int) {
        for (k in 0..3) vram[addr - 0x06000000 + k] = (value shr (8 * k)).toByte()
    }

    override fun clearVram(dest: Int, byteCount: Int) {
        java.util.Arrays.fill(vram, dest - 0x06000000, dest - 0x06000000 + byteCount, 0)
    }
}
