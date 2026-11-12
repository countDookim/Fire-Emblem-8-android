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
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The player-phase unit windows against `ram_trace_prologue_status_ui` (CAP-status1): the
 * mini-box's unit-clock boundaries (HP and status refresh, `PutUnitMapUiStatus` for a unit
 * without a status), its direct unit-to-unit redraw (`Proc_Goto` label 1), and the Burst window
 * (`gProcScr_UnitDisplay_Burst`) with its Options-screen start.
 *
 * Each test seeds from one full dump (the terrain, goal and unit window procs with their
 * fields, both scratch tilemaps, BG0 and BG1, palette, LCD and VRAM), runs every frame to the
 * end frame and compares at every frame the capture dumped: the procs' script position and
 * fields, BG0 and BG1 (whole 32x32 buffers), both scratch tilemaps, palette banks 3 and 4 and,
 * at the full dumps, the text strips, the status icon tiles and the HP digit objects (the dump's
 * OAM holds the previous pass's objects). Frames between dumps use the cursor and unit state of
 * the last dump before them; the capture presses no key in those gaps.
 */
class UnitStatusDisplayDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("PutUnitMapUiStatus")
    fun `hover on a unit without a status passes the clock boundaries`() {
        val sim = sim() ?: return
        sim.seedProcs(1260)
        sim.run(1261..1450)
        assertTrue(sim.compared >= 30, "frames compared: ${sim.compared}")
        assertEquals(setOf(1318, 1382), sim.boundaries, "unit clock boundaries compared")
    }

    @Test
    @VerifiesPort("PutUnitMapUiStatus")
    fun `Seth boundary and slide out to an empty tile`() {
        val sim = sim() ?: return
        sim.seedProcs(1490)
        sim.run(1491..1532)
        assertEquals(setOf(1492), sim.boundaries, "unit clock boundaries compared")
        assertTrue(sim.sawRetract, "slide out compared")
    }

    @Test
    @VerifiesPort("MMB_CheckForUnit", "sub_808C234", "MMB_Loop_Display")
    fun `cursor hops from one soldier to the next inside one corner`() {
        val sim = sim() ?: return
        sim.seedProcs(1690)
        sim.run(1691..1740)
        assertEquals(setOf(1700, 1730), sim.hops, "label 1 jumps compared")
    }

    @Test
    @VerifiesPort(
        "InitPlayerPhaseInterface", "BurstDisplay_Init", "BurstDisplay_Loop_Display",
    )
    fun `Burst window starts after the Options screen and waits on empty tiles`() {
        val sim = sim() ?: return
        sim.seedStart(2031, 2030)
        // The capture clears scratch B rows 0..1, columns 20..24 (left over from the map menu)
        // in the same frame; no function of this window does (the writer is not identified).
        for (row in 0..1) for (col in 20..24) sim.pi.scratch.b[row * 32 + col] = 0
        sim.pi.initPlayerPhaseInterface()
        sim.run(2032..2058)
        assertEquals(7, sim.compared, "frames compared")
        assertTrue(sim.engine.find(sim.pi.burstScript) != null, "Burst proc live")
        sim.pi.endPlayerPhaseSideWindows()
        assertTrue(sim.engine.find(sim.pi.burstScript) == null, "Burst proc ended")
    }

    @Test
    @VerifiesPort(
        "BurstDisplay_Loop_Display", "DrawUnitBurstMapUi", "GetUnitBurstMapUiOrientationAt",
        "UnitMapUiUpdate",
    )
    fun `Burst window draws Eirika`() {
        val sim = sim() ?: return
        sim.seedProcs(2080)
        sim.run(2081..2100)
        assertTrue(sim.drawn, "box drawn")
    }

    @Test
    @VerifiesPort("BurstDisplay_Loop_Display", "UnitMapUiUpdate", "PutUnitMapUiStatus")
    fun `Burst window passes the clock boundaries`() {
        val sim = sim() ?: return
        sim.seedProcs(2150)
        sim.run(2151..2160)
        assertEquals(setOf(2155), sim.boundaries, "unit clock boundaries compared")
        val later = sim() ?: return
        later.seedProcs(2210)
        later.run(2211..2230)
        assertEquals(setOf(2219), later.boundaries, "unit clock boundaries compared")
    }

    @Test
    @VerifiesPort("BurstDisplay_Loop_Display", "ClearUnitBurstMapUi", "DrawUnitBurstMapUi")
    fun `Burst window moves from Eirika to Seth`() {
        val sim = sim() ?: return
        sim.seedProcs(2240)
        sim.run(2241..2260)
        assertTrue(sim.cleared, "box cleared")
        assertTrue(sim.drawn, "box redrawn")
        val later = sim() ?: return
        later.seedProcs(2320)
        later.run(2321..2330)
        assertEquals(setOf(2322), later.boundaries, "unit clock boundaries compared")
    }

    private fun sim(): Sim? {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return Sim()
    }

    private inner class Sim {
        val data = ref.run(RUN)
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val text = TextSystem(romBytes, StatusVramHost(hw.vram), Fe8Text.CONFIG)
        val bm = BmSt()
        val units = UnitTable(rom)
        val oam = OamBuffer()
        val playSt = PlaySt()
        val camMove = CamMove(engine, bm)
        val pi: PlayerInterface
        var lastDump = 0
        var compared = 0
        var sawRetract = false
        var drawn = false
        var cleared = false
        var prevWBurst = 0
        var prevOam = ShortArray(0)
        var prevObjects = 0
        var digitFrames = 0
        val boundaries = HashSet<Int>()
        val hops = HashSet<Int>()
        private var seedRam = ByteArray(0)

        init {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            pi = PlayerInterface(
                engine, bm, playSt, units, rom, hw, oam, text, messages, camMove.script,
                GreenText(engine, rom, hw) { 0 },
            )
            pi.hooks.terrainAt = { x, y -> terrainAt(x, y) }
            pi.hooks.unitIdAt = { x, y -> unitIdAt(x, y) }
        }

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        private fun terrainAt(x: Int, y: Int): Int {
            val table = u32(seedRam, GBM_MAP_TERRAIN - EWRAM) - EWRAM
            val row = u32(seedRam, table + 4 * y) - EWRAM
            return seedRam[row + x].toInt() and 0xFF
        }

        private fun unitIdAt(x: Int, y: Int): Int {
            val e = ewram(lastDump)
            val table = u32(e, GBM_MAP_UNIT - EWRAM) - EWRAM
            val row = u32(e, table + 4 * y) - EWRAM
            return e[row + x].toInt() and 0xFF
        }

        /** Loads hardware, tilemaps and VRAM of the dump at [frame] (VRAM from [vramFrame]). */
        fun seedStart(frame: Int, vramFrame: Int = frame) {
            val e = ewram(frame)
            seedRam = e
            lastDump = frame
            playSt.decode(e, PLAY_ST - EWRAM)
            bm.decode(e, BM_ST - EWRAM)
            units.loadFrom(e)
            for (bg in 0..1) {
                val at = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) hw.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            for (i in 0 until 0x280) {
                pi.scratch.a[i] = u16(e, SCRATCH_A - EWRAM + 2 * i).toShort()
                pi.scratch.b[i] = u16(e, SCRATCH_B - EWRAM + 2 * i).toShort()
            }
            for (i in 0 until 0x200) {
                hw.palette[i] = u16(e, PALETTE_BUFFER - EWRAM + 2 * i).toShort()
            }
            hw.lcd.readFrom(iwram(frame), LCD_BUFFER - IWRAM)
            data.region(names.getValue(vramFrame), "vram").copyInto(hw.vram, 0, 0, 0x18000)
            text.initTextFont(null, 0x06001000, 0x80, 0)
        }

        /** [seedStart] plus the side window procs of the dump at [frame], fields and position. */
        fun seedProcs(frame: Int) {
            seedStart(frame)
            val e = ewram(frame)
            seedProc(e, pi.terrainScript)
            seedProc(e, pi.goalScript)
            seedProc(e, pi.minimugScript)
            seedProc(e, pi.burstScript)
        }

        private fun seedProc(e: ByteArray, script: ProcScript) {
            val slot = slotOf(e, scriptAddress(script))
            if (slot < 0) return
            val o = PROC_ARRAY - EWRAM + slot * PROC_SIZE
            val proc = engine.start(script, 3)
            proc.scrCur = (u32(e, o + 4) - scriptAddress(script)) / 8
            proc.sleepTime = 0
            val prev = script.cmds[proc.scrCur - 1]
            proc.idleCb = if (prev.opcode == 0x03) prev.ptr as ProcFunc else null
            val s = proc.state as PlayerInterfaceState
            readText(e, o + 0x2C, s.text)
            readText(e, o + 0x34, s.text1)
            s.xBurst = e[o + 0x3C].toInt()
            s.yBurst = e[o + 0x3D].toInt()
            s.wBurst = e[o + 0x3E].toInt()
            s.hBurst = e[o + 0x3F].toInt()
            val statusTm = u32(e, o + 0x40)
            s.statusInBg0 = statusTm >= BG0_BUFFER && statusTm < BG0_BUFFER + 0x800
            s.statusTm = (statusTm - if (s.statusInBg0) BG0_BUFFER else SCRATCH_A) / 2
            s.unitClock = u16(e, o + 0x44).toShort().toInt()
            s.xHp = u16(e, o + 0x46)
            s.yHp = u16(e, o + 0x48)
            s.burstUnitIdPrev = e[o + 0x4A].toInt() and 0xFF
            s.burstUnitId = e[o + 0x4B].toInt() and 0xFF
            s.xCursorPrev = e[o + 0x4C].toInt() and 0xFF
            s.yCursorPrev = e[o + 0x4D].toInt() and 0xFF
            s.xCursor = e[o + 0x4E].toInt() and 0xFF
            s.yCursor = e[o + 0x4F].toInt() and 0xFF
            s.cursorQuadrant = e[o + 0x50].toInt()
            s.hpCurHi = e[o + 0x51].toInt() and 0xFF
            s.hpCurLo = e[o + 0x52].toInt() and 0xFF
            s.hpMaxHi = e[o + 0x53].toInt() and 0xFF
            s.hpMaxLo = e[o + 0x54].toInt() and 0xFF
            s.hideContents = e[o + 0x55].toInt() != 0
            s.isRetracting = e[o + 0x56].toInt() != 0
            s.windowQuadrant = e[o + 0x57].toInt()
            s.showHideClock = u32(e, o + 0x58)
        }

        private fun readText(e: ByteArray, o: Int, t: Text) {
            t.chrPosition = u16(e, o)
            t.x = e[o + 2].toInt() and 0xFF
            t.colorId = e[o + 3].toInt()
            t.tileWidth = e[o + 4].toInt()
            t.dbEnabled = e[o + 5].toInt() != 0
            t.dbId = e[o + 6].toInt()
            t.isPrinting = e[o + 7].toInt() != 0
        }

        private fun scriptAddress(script: ProcScript): Int = when (script) {
            pi.terrainScript -> TERRAIN_SCRIPT
            pi.goalScript -> GOAL_SCRIPT
            pi.minimugScript -> MINIMUG_SCRIPT
            else -> BURST_SCRIPT
        }

        fun slotOf(e: ByteArray, script: Int): Int {
            for (slot in 0 until 64) {
                if (u32(e, PROC_ARRAY - EWRAM + slot * PROC_SIZE) == script) return slot
            }
            return -1
        }

        fun run(frames: IntRange) {
            for (f in frames) {
                if (names.containsKey(f)) {
                    lastDump = f
                    bm.decode(ewram(f), BM_ST - EWRAM)
                    units.loadFrom(ewram(f))
                }
                prevOam = oam.buffer.copyOf()
                prevObjects = oam.hiPut / 4
                oam.syncHi()
                for (t in 0 until 8) engine.run(engine.root(t))
                if (names.containsKey(f)) compare(f)
            }
        }

        private fun compare(f: Int) {
            val e = ewram(f)
            val at = "f$f"
            compareProc(at, "terrain", e, pi.terrainScript, TERRAIN_SCRIPT, false)
            compareProc(at, "goal", e, pi.goalScript, GOAL_SCRIPT, false)
            compareProc(at, "unit", e, pi.minimugScript, MINIMUG_SCRIPT, true)
            compareProc(at, "burst", e, pi.burstScript, BURST_SCRIPT, true)
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "$at BG$bg tilemap[${i / 32}][${i % 32}]")
                }
            }
            for (i in 0 until 0x280) {
                assertEquals(u16(e, SCRATCH_A - EWRAM + 2 * i),
                    pi.scratch.a[i].toInt() and 0xFFFF, "$at scratch A[${i / 32}][${i % 32}]")
                assertEquals(u16(e, SCRATCH_B - EWRAM + 2 * i),
                    pi.scratch.b[i].toInt() and 0xFFFF, "$at scratch B[${i / 32}][${i % 32}]")
            }
            for (bank in intArrayOf(3, 4)) for (i in 0 until 16) {
                assertEquals(u16(e, PALETTE_BUFFER - EWRAM + 2 * (bank * 16 + i)),
                    hw.palette[bank * 16 + i].toInt() and 0xFFFF, "$at palette $bank/$i")
            }
            compared++
            if (names.getValue(f).startsWith("f")) compareVram(at, f, e)
        }

        private fun compareProc(
            at: String,
            label: String,
            e: ByteArray,
            script: ProcScript,
            address: Int,
            full: Boolean,
        ) {
            val slot = slotOf(e, address)
            val got = engine.find(script)
            assertEquals(slot >= 0, got != null, "$at $label live")
            if (slot < 0 || got == null) return
            val o = PROC_ARRAY - EWRAM + slot * PROC_SIZE
            val s = got.state as PlayerInterfaceState
            assertEquals((u32(e, o + 4) - address) / 8, got.scrCur, "$at $label cmd")
            assertEquals(e[o + 0x55].toInt() != 0, s.hideContents, "$at $label hideContents")
            assertEquals(u32(e, o + 0x58), s.showHideClock, "$at $label showHideClock")
            if (!full) return
            assertEquals(e[o + 0x56].toInt() != 0, s.isRetracting, "$at $label isRetracting")
            assertEquals(u16(e, o + 0x44).toShort().toInt(), s.unitClock, "$at $label unitClock")
            assertEquals(u16(e, o + 0x46), s.xHp, "$at $label xHp")
            assertEquals(u16(e, o + 0x48), s.yHp, "$at $label yHp")
            assertEquals(e[o + 0x51].toInt() and 0xFF, s.hpCurHi, "$at $label hpCurHi")
            assertEquals(e[o + 0x52].toInt() and 0xFF, s.hpCurLo, "$at $label hpCurLo")
            assertEquals(e[o + 0x53].toInt() and 0xFF, s.hpMaxHi, "$at $label hpMaxHi")
            assertEquals(e[o + 0x54].toInt() and 0xFF, s.hpMaxLo, "$at $label hpMaxLo")
            assertEquals(e[o + 0x4E].toInt() and 0xFF, s.xCursor, "$at $label xCursor")
            assertEquals(e[o + 0x4F].toInt() and 0xFF, s.yCursor, "$at $label yCursor")
            assertEquals(e[o + 0x4C].toInt() and 0xFF, s.xCursorPrev, "$at $label xCursorPrev")
            assertEquals(e[o + 0x4D].toInt() and 0xFF, s.yCursorPrev, "$at $label yCursorPrev")
            assertEquals(e[o + 0x57].toInt(), s.windowQuadrant, "$at $label windowQuadrant")
            assertEquals(e[o + 0x50].toInt(), s.cursorQuadrant, "$at $label cursorQuadrant")
            val t = s.text
            assertEquals(u16(e, o + 0x2C), t.chrPosition, "$at $label text chrPosition")
            assertEquals(e[o + 0x30].toInt(), t.tileWidth, "$at $label text tileWidth")
            assertEquals(e[o + 0x31].toInt() != 0, t.dbEnabled, "$at $label text dbEnabled")
            assertEquals(e[o + 0x32].toInt(), t.dbId, "$at $label text dbId")
            if (label == "burst") {
                assertEquals(e[o + 0x3C].toInt(), s.xBurst, "$at xBurst")
                assertEquals(e[o + 0x3D].toInt(), s.yBurst, "$at yBurst")
                assertEquals(e[o + 0x3E].toInt(), s.wBurst, "$at wBurst")
                assertEquals(e[o + 0x3F].toInt(), s.hBurst, "$at hBurst")
                assertEquals(e[o + 0x4A].toInt() and 0xFF, s.burstUnitIdPrev, "$at burstUnitIdPrev")
                assertEquals(e[o + 0x4B].toInt() and 0xFF, s.burstUnitId, "$at burstUnitId")
                if (s.wBurst != 0) {
                    assertEquals(u32(e, o + 0x40), BG0_BUFFER + 2 * s.statusTm, "$at statusTm")
                    drawn = true
                }
                if (prevWBurst != 0 && s.wBurst == 0) cleared = true
                prevWBurst = s.wBurst
            } else if (label == "unit") {
                assertEquals(u32(e, o + 0x40), SCRATCH_A + 2 * s.statusTm, "$at statusTm")
                if (s.isRetracting) sawRetract = true
            }
            if (s.unitClock and 63 == 0 && s.unitClock != 0 && s.windowQuadrant != -1) {
                boundaries += at.drop(1).toInt()
            }
            if (label == "unit" && got.scrCur == 13) hops += at.drop(1).toInt()
        }

        private fun compareVram(at: String, f: Int, e: ByteArray) {
            val vram = data.region(names.getValue(f), "vram")
            for (address in intArrayOf(MINIMUG_SCRIPT, BURST_SCRIPT)) {
                val slot = slotOf(e, address)
                if (slot < 0) continue
                val o = PROC_ARRAY - EWRAM + slot * PROC_SIZE
                val strip = 0x1000 + u16(e, o + 0x2C) * 2 * 0x20
                for (i in strip until strip + 7 * 2 * 2 * 0x20) {
                    assertEquals(vram[i], hw.vram[i], "$at text VRAM +${Integer.toHexString(i)}")
                }
                compareDigits(at, data.region(names.getValue(f), "oam"))
            }
            for (i in 0x16F * 0x20 until 0x174 * 0x20) {
                assertEquals(vram[i], hw.vram[i], "$at status icon VRAM +${Integer.toHexString(i)}")
            }
        }

        private fun compareDigits(at: String, dump: ByteArray) {
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
        const val RUN = "ram_trace_prologue_status_ui"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val ROM_BASE = 0x08000000
        const val BG0_BUFFER = 0x02022CA8
        const val BG1_BUFFER = 0x020234A8
        const val PALETTE_BUFFER = 0x020228A8
        const val SCRATCH_A = 0x02003D2C
        const val SCRATCH_B = 0x0200422C
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val LCD_BUFFER = 0x03003080
        const val PROC_ARRAY = 0x02024E68
        const val PROC_SIZE = 0x6C

        const val TERRAIN_SCRIPT = 0x08A018AC
        const val MINIMUG_SCRIPT = 0x08A0190C
        const val BURST_SCRIPT = 0x08A01994
        const val GOAL_SCRIPT = 0x08A019E4

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}

private class StatusVramHost(val vram: ByteArray) : TextHost {
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
