package fe8.testing

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
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The player-phase goal window (src/player_interface.c) against `ram_trace_prologue_select_move`
 * and `ram_trace_prologue_cancel_end`, seeded and started like [TerrainDisplayDifferentialTest]
 * (the windows exist from f1172 on, before the first per-frame RAM, so each run restarts them).
 *
 * Every pass re-seeds `gBmSt` from the frame's dump, runs the proc trees once and compares with
 * the dump of the same frame: the goal proc (script position, cursor quadrant, hide / retract
 * flags, window quadrant, show / hide clock, `unitClock`, cursor and previous cursor once set,
 * both text strip headers), whether the green text proc exists, BG0 and BG1 tilemap columns
 * 19..30 of rows 0..5 and 14..19 (the goal box in either right-hand corner), BG palette banks 0
 * (the green text colour), 2 and 0x18. Every full dump also compares the text strip tiles.
 *
 * The Prologue's goal is to defeat the boss, so only the one-line box runs.
 */
class GoalDisplayDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "GoalDisplay_Init", "GoalDisplay_Loop_OnSideChange", "GoalDisplay_Loop_SlideIn",
        "GoalDisplay_Loop_Display", "sub_808D200", "sub_808D514", "StartGreenText",
        "GreenText_OnLoop",
    )
    fun `select_move goal window starts after the unit waits`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(SELECT_MOVE, seedFrame = 1362, vramDump = 1360)
        sim.pi.startPlayerPhaseSideWindows()
        for (f in 1364..1420) sim.pass(f)
        assertEquals(57, sim.compared, "frames compared")
        assertEquals(listOf(1370, 1380, 1390, 1400, 1410, 1420), sim.fullDumps, "full dumps")
        assertEquals(setOf(0, 1, 2, 3, 4), sim.slideSteps, "slide clock values seen")
        assertEquals(setOf(0), sim.goalClocks, "one-line box")
    }

    @Test
    @VerifiesPort(
        "GoalDisplay_Init", "GoalDisplay_Loop_OnSideChange", "GoalDisplay_Loop_SlideIn",
        "GoalDisplay_Loop_Display", "sub_808D200", "sub_808D514", "InitPlayerPhaseInterface",
        "StartGreenText", "GreenText_OnLoop",
    )
    fun `cancel_end goal window restarts after cancelling the selection`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(CANCEL_END, seedFrame = 1290, vramDump = 1290)
        sim.pi.initPlayerPhaseInterface()
        for (f in 1292..1310) sim.pass(f)
        assertEquals(19, sim.compared, "frames compared")
    }

    @Test
    @VerifiesPort("GoalDisplay_Loop_Display", "InitPlayerPhaseInterface")
    fun `cancel_end goal window stays while the cursor moves inside its quadrant`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(CANCEL_END, seedFrame = 1425, vramDump = 1420, firstPassClockSkew = -1)
        sim.pi.initPlayerPhaseInterface()
        for (f in 1427..1464) sim.pass(f)
        assertEquals(38, sim.compared, "frames compared")
        assertEquals(listOf(1430, 1440, 1450, 1460), sim.fullDumps, "full dumps")
    }

    @Test
    @VerifiesPort("EndPlayerPhaseSideWindows")
    fun `ending the windows ends the goal proc and its colour proc`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim(SELECT_MOVE, seedFrame = 1362, vramDump = 1360)
        sim.pi.startPlayerPhaseSideWindows()
        for (f in 1364..1380) sim.pass(f)
        assertNotNull(sim.engine.find(sim.pi.goalScript))
        assertNotNull(sim.engine.find(sim.green.script))
        sim.pi.endPlayerPhaseSideWindows()
        assertNull(sim.engine.find(sim.pi.goalScript), "goal proc ended")
        assertNull(sim.engine.find(sim.green.script), "green text proc ended")
        sim.compareEnded(1261)
    }

    private inner class Sim(
        val run: String,
        val seedFrame: Int,
        vramDump: Int,
        /**
         * Added to the dumped game clock on the first pass: the start pass of the window at
         * f1426 overran its frame, so the colour the first pass compares was set one frame
         * earlier than the dump's clock says.
         */
        val firstPassClockSkew: Int = 0,
    ) {
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
        val green = GreenText(engine, rom, hw) { clock }
        val pi: PlayerInterface
        var current = 0
        var previous = seedFrame
        var first = true
        var compared = 0
        var clock = 0
        var sawCursorMove = false
        val fullDumps = ArrayList<Int>()
        val slideSteps = HashSet<Int>()
        val goalClocks = HashSet<Int>()
        private val seedRam: ByteArray

        init {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            pi = PlayerInterface(
                engine, bm, playSt, units, rom, hw, oam, text, messages, camMove.script,
                green,
            )
            val e = ewram(seedFrame)
            playSt.decode(e, PLAY_ST - EWRAM)
            seedRam = e
            pi.hooks.terrainAt = { x, y -> terrainAt(x, y) }
            units.loadFrom(e)
            pi.hooks.unitIdAt = { x, y -> unitIdAt(x, y) }
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
            clock = GbaMem.u32(iwram(f), GbaMem.IWRAM, GAME_CLOCK) +
                if (f == seedFrame + 2) firstPassClockSkew else 0
            oam.syncHi()
            for (t in 0 until 8) engine.run(engine.root(t))
            compare(f)
        }

        private fun compareText(at: String, e: ByteArray, o: Int, t: Text) {
            assertEquals(u16(e, o), t.chrPosition, "$at text chrPosition")
            assertEquals(e[o + 4].toInt(), t.tileWidth, "$at text tileWidth")
            assertEquals(e[o + 5].toInt() != 0, t.dbEnabled, "$at text dbEnabled")
            assertEquals(e[o + 6].toInt(), t.dbId, "$at text dbId")
            assertEquals(e[o + 3].toInt(), t.colorId, "$at text colorId")
            assertEquals(e[o + 2].toInt() and 0xFF, t.x, "$at text x")
        }

        fun compare(f: Int) {
            val e = ewram(f)
            val at = "f$f"
            val slot = slotOf(e, GOAL_SCRIPT)
            val got = engine.find(pi.goalScript)
            assertEquals(slot >= 0, got != null, "$at goal live")
            val greenSlot = slotOf(e, GREEN_SCRIPT)
            assertEquals(greenSlot >= 0, engine.find(green.script) != null, "$at green live")
            if (slot >= 0 && got != null) {
                val o = PROC_ARRAY - EWRAM + slot * PROC_SIZE
                val state = got.state as PlayerInterfaceState
                assertEquals((u32(e, o + 4) - GOAL_SCRIPT) / 8, got.scrCur, "$at goal cmd")
                assertEquals(e[o + 0x50].toInt(), state.cursorQuadrant, "$at cursorQuadrant")
                assertEquals(e[o + 0x55].toInt() != 0, state.hideContents, "$at hideContents")
                assertEquals(e[o + 0x56].toInt() != 0, state.isRetracting, "$at isRetracting")
                assertEquals(e[o + 0x57].toInt(), state.windowQuadrant, "$at windowQuadrant")
                assertEquals(u32(e, o + 0x58), state.showHideClock, "$at showHideClock")
                assertEquals(u16(e, o + 0x44), state.unitClock, "$at unitClock")
                if (state.windowQuadrant != -1) {
                    assertEquals(e[o + 0x4C].toInt() and 0xFF, state.xCursorPrev, "$at xCursorPrev")
                    assertEquals(e[o + 0x4D].toInt() and 0xFF, state.yCursorPrev, "$at yCursorPrev")
                    assertEquals(e[o + 0x4E].toInt() and 0xFF, state.xCursor, "$at xCursor")
                    assertEquals(e[o + 0x4F].toInt() and 0xFF, state.yCursor, "$at yCursor")
                    compareText(at, e, o + 0x2C, state.text)
                    compareText(at, e, o + 0x34, state.text1)
                }
                slideSteps += state.showHideClock
                goalClocks += state.unitClock
            }
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (row in 0 until 20) {
                    for (col in 19 until 31) {
                        val i = row * 32 + col
                        if (row in 6..13) continue
                        assertEquals(u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                            "$at BG$bg tilemap[$row][$col]")
                    }
                }
            }
            for (i in 0 until 16) {
                for (bank in intArrayOf(0, 2, 0x18)) {
                    assertEquals(u16(e, PALETTE_BUFFER - EWRAM + 2 * (bank * 16 + i)),
                        hw.palette[bank * 16 + i].toInt() and 0xFFFF, "$at palette $bank/$i")
                }
            }
            compared++
            if (names.getValue(f).startsWith("f") && slot >= 0 && got != null) {
                val state = got.state as PlayerInterfaceState
                val vram = data.region(names.getValue(f), "vram")
                for (t in listOf(state.text, state.text1)) {
                    val from = text.getTextDrawDest(t) - 0x06000000
                    for (i in from until from + t.tileWidth * 2 * 0x20) {
                        assertEquals(vram[i], hw.vram[i], "$at text VRAM +${Integer.toHexString(i)}")
                    }
                }
                fullDumps += f
            }
        }

        /** After `EndPlayerPhaseSideWindows`: BG0 / BG1 clear, as at [frame]. */
        fun compareEnded(frame: Int) {
            val e = ewram(frame)
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "f$frame BG$bg tilemap[$i]")
                }
            }
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
        const val GAME_CLOCK = 0x03000014
        const val CLOCK_SKEW = -2
        const val PROC_ARRAY = 0x02024E68
        const val PROC_SIZE = 0x6C

        const val GOAL_SCRIPT = 0x08A019E4
        const val GREEN_SCRIPT = 0x08588284

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}
