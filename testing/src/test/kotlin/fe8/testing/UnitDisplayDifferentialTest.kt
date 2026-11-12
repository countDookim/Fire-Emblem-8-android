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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The player-phase unit mini-box (`gProcScr_UnitDisplay_MinimugBox`, its HP bar and mini portrait;
 * src/player_interface.c, face.c) against `ram_trace_prologue_select_move` and
 * `ram_trace_prologue_cancel_end`, with the same seeds, starts and frame alignment as
 * [TerrainDisplayDifferentialTest] (the terrain window runs beside it and decides, through its
 * corner, when the unit window may draw).
 *
 * Compared per frame: the unit window proc (script position, cursor quadrant, hide / retract
 * flags, window quadrant, show / hide clock, cursor and previous cursor, text strip header, unit
 * clock, HP digit position, the four HP digits); BG0 and BG1 tilemap rows 0..5 of columns 0..16
 * (the window is in the top left in these runs; the goal window is on the right) and rows 13..19;
 * BG palette banks 2, 3 (frame), 4 (portrait) and 0x18. Every full dump also compares the text
 * strip tiles, the mini portrait tiles (BG tile 0xF0) and the HP digit objects: the dump's OAM
 * holds the previous pass's objects, so the digits pushed by the previous pass are looked up in
 * order in it.
 *
 * Stand-ins as in the terrain test: the goal window's quadrant comes from the previous reference
 * dump and its 16 text tiles are reserved; the unit window's slot residue is 3.
 */
class UnitDisplayDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "InitPlayerPhaseInterface", "MMB_Init", "MMB_Loop_OnSideChange", "MMB_Loop_SlideIn",
        "MMB_Loop_Display", "DrawUnitMapUi", "UnitMapUiUpdate", "ClearUnitMapUiStatus",
        "DrawHpBar", "GetHpBarLeftTile", "GetHpBarMidTiles", "GetHpBarRightTile",
        "ApplyUnitMapUiFramePal", "PutFaceChibi", "UnpackFaceChibiGraphics", "PutFaceTm",
    )
    fun `select_move unit window draws Eirika and settles`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(SELECT_MOVE, seedFrame = 1362, vramDump = 1360)
        sim.pi.startPlayerPhaseSideWindows()
        for (f in 1364..1420) sim.pass(f)
        assertEquals(57, sim.compared, "frames compared")
        assertEquals(listOf(1370, 1380, 1390, 1400, 1410, 1420), sim.fullDumps, "full dumps")
        assertTrue(sim.digitFrames >= 6, "HP digit objects compared at ${sim.digitFrames} dumps")
    }

    @Test
    @VerifiesPort(
        "InitPlayerPhaseInterface", "MMB_Init", "MMB_Loop_OnSideChange", "MMB_Loop_SlideIn",
        "MMB_Loop_Display", "DrawUnitMapUi", "MMB_Loop_SlideOut", "UnitMapUiUpdate",
    )
    fun `cancel_end unit window slides out when the cursor leaves the unit`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(CANCEL_END, seedFrame = 1425, vramDump = 1420)
        sim.pi.initPlayerPhaseInterface()
        for (f in 1427..1464) sim.pass(f)
        assertEquals(38, sim.compared, "frames compared")
        assertEquals(setOf(0, 1, 2, 3), sim.slideSteps, "show / hide clock values seen")
        assertTrue(sim.sawRetract, "slide out compared")
    }

    @Test
    @VerifiesPort("InitPlayerPhaseInterface", "MMB_Loop_OnSideChange", "DrawUnitMapUi")
    fun `cancel_end unit window restarts after cancelling the selection`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(CANCEL_END, seedFrame = 1290, vramDump = 1290)
        sim.pi.initPlayerPhaseInterface()
        for (f in 1292..1310) sim.pass(f)
        assertEquals(19, sim.compared, "frames compared")
    }

    private inner class Sim(val run: String, val seedFrame: Int, vramDump: Int) {
        val data = ref.run(run)
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val text = TextSystem(romBytes, UnitVramHost(hw.vram), Fe8Text.CONFIG)
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
        var sawRetract = false
        var digitFrames = 0
        var prevOam = ShortArray(0)
        var prevObjects = 0
        var drawn = false
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
            prevOam = oam.buffer.copyOf()
            prevObjects = oam.hiPut / 4
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
            compareUnitWindow(at, e)
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

        fun compareUnitWindow(at: String, e: ByteArray) {
            val slot = slotOf(e, MINIMUG_SCRIPT)
            val got = engine.find(pi.minimugScript)
            assertEquals(slot >= 0, got != null, "$at unit window live")
            if (slot >= 0 && got != null) {
                val o = PROC_ARRAY - EWRAM + slot * PROC_SIZE
                val state = got.state as PlayerInterfaceState
                assertEquals((u32(e, o + 4) - MINIMUG_SCRIPT) / 8, got.scrCur, "$at unit cmd")
                assertEquals(e[o + 0x50].toInt(), state.cursorQuadrant, "$at unit cursorQuadrant")
                assertEquals(e[o + 0x55].toInt() != 0, state.hideContents, "$at unit hideContents")
                assertEquals(e[o + 0x56].toInt() != 0, state.isRetracting, "$at unit isRetracting")
                assertEquals(e[o + 0x57].toInt(), state.windowQuadrant, "$at unit windowQuadrant")
                assertEquals(u32(e, o + 0x58), state.showHideClock, "$at unit showHideClock")
                if (state.isRetracting) sawRetract = true
                if (state.windowQuadrant != -1) drawn = true
                if (drawn) {
                    assertEquals(e[o + 0x4E].toInt() and 0xFF, state.xCursor, "$at unit xCursor")
                    assertEquals(e[o + 0x4F].toInt() and 0xFF, state.yCursor, "$at unit yCursor")
                    assertEquals(u16(e, o + 0x44), state.unitClock, "$at unitClock")
                    assertEquals(u16(e, o + 0x46), state.xHp, "$at xHp")
                    assertEquals(u16(e, o + 0x48), state.yHp, "$at yHp")
                    assertEquals(e[o + 0x51].toInt() and 0xFF, state.hpCurHi, "$at hpCurHi")
                    assertEquals(e[o + 0x52].toInt() and 0xFF, state.hpCurLo, "$at hpCurLo")
                    assertEquals(e[o + 0x53].toInt() and 0xFF, state.hpMaxHi, "$at hpMaxHi")
                    assertEquals(e[o + 0x54].toInt() and 0xFF, state.hpMaxLo, "$at hpMaxLo")
                    val t = state.text
                    assertEquals(u16(e, o + 0x2C), t.chrPosition, "$at unit text chrPosition")
                    assertEquals(e[o + 0x30].toInt(), t.tileWidth, "$at unit text tileWidth")
                    assertEquals(e[o + 0x31].toInt() != 0, t.dbEnabled, "$at unit text dbEnabled")
                    assertEquals(e[o + 0x32].toInt(), t.dbId, "$at unit text dbId")
                    assertEquals(e[o + 0x2F].toInt(), t.colorId, "$at unit text colorId")
                    assertEquals(e[o + 0x2E].toInt() and 0xFF, t.x, "$at unit text x")
                }
                slideSteps += state.showHideClock
            }
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (row in 0 until 6) for (col in 0 until 17) {
                    val i = row * 32 + col
                    assertEquals(u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "$at BG$bg unit tilemap[$row][$col]")
                }
            }
            for (bank in intArrayOf(3, 4)) for (i in 0 until 16) {
                assertEquals(u16(e, PALETTE_BUFFER - EWRAM + 2 * (bank * 16 + i)),
                    hw.palette[bank * 16 + i].toInt() and 0xFFFF, "$at palette $bank/$i")
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
            val unitSlot = slotOf(e, MINIMUG_SCRIPT)
            if (unitSlot >= 0) {
                val o = PROC_ARRAY - EWRAM + unitSlot * PROC_SIZE
                val strip = 0x1000 + u16(e, o + 0x2C) * 2 * 0x20
                for (i in strip until strip + 7 * 2 * 2 * 0x20) {
                    assertEquals(vram[i], hw.vram[i], "$at unit text VRAM +${Integer.toHexString(i)}")
                }
                for (i in 0x1E00 until 0x1E00 + 0x200) {
                    assertEquals(vram[i], hw.vram[i], "$at portrait VRAM +${Integer.toHexString(i)}")
                }
                compareDigits(at, data.region(names.getValue(f), "oam"))
            }
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

        /** The HP digit objects of the previous pass, in order, somewhere in the dump's OAM. */
        fun compareDigits(at: String, dump: ByteArray) {
            if (prevObjects == 0) return
            val found = (0..128 - prevObjects).any { start ->
                (0 until prevObjects).all { obj ->
                    (0..2).all { attr ->
                        u16(dump, 2 * (4 * (start + obj) + attr)) ==
                            (prevOam[4 * obj + attr].toInt() and 0xFFFF)
                    }
                }
            }
            assertTrue(found, "$at HP digit objects ($prevObjects) in OAM")
            digitFrames++
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

private class UnitVramHost(val vram: ByteArray) : TextHost {
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
