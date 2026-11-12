package fe8.testing

import fe8.game.scene.IntSupplier
import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.MoveLimitView
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The range squares ([MoveLimitView]) against the per-frame RAM of the Prologue captures. Every
 * stretch in which the `procs` watch has `sProcScr_MoveLimitView` is replayed: seeded from the
 * dump of the frame before it, `DisplayMoveRangeGraphics` is called at the first frame with the
 * `LIMITVIEW_*` flags the dump's proc holds (the caller, `DisplayUnitEffectRange`, is PP-3) and
 * `BM_FLAG_1` set (also PP-3's), the tree 4 procs run one frame per dump, and on the frame after
 * the stretch `HideMoveRangeGraphics` is called (the callers are the cancel and move branches of
 * `PlayerPhase_RangeDisplayIdle`, PP-7). `gBmMapMovement` and `gBmMapRange` are seeded from the
 * first dump of the stretch (PP-1 and PP-2 verify them).
 *
 * Compared after every frame: both procs' existence, script positions, `flags` (+0x4A) and
 * `unk_4C`; `gBG2TilemapBuffer` and `gBG3TilemapBuffer` entry by entry; the 32 palette colours
 * from 0x40 the view animates; the LCD control buffer's window enable, BG control priorities, BG2
 * scroll, blend control and coefficients; `gameStateBits` bits 0 and 1; and, at the frames with a
 * full dump, VRAM 0x5000 to 0x50FF (the opening images; they are copied at once here and queued
 * until VBlank in the game; the stretch the attack run's unit menu starts, f1365, is not
 * compared: its VRAM at f1370 is still the old image).
 *
 * Not exercised: the attack run's second stretch (started by the unit menu after the move, f1365 on; the
 * driver supports it, but its palette at f1400 is overwritten by the weapon list and its VRAM
 * queue timing is unchecked), the view being redrawn while it exists (`DisplayMoveRangeGraphics` with a view
 * up, `MoveLimitViewChange_OnInit` with `BM_FLAG_0` set), the green and unknown palette tables,
 * and a flags value other than blue plus red.
 */
class MoveLimitViewDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "DisplayMoveRangeGraphics", "MoveLimitView_OnInit", "MoveLimitView_OnLoop",
        "MoveLimitView_OnEnd", "MoveLimitViewChange_OnInit", "MoveLimitViewChange_OnLoop",
        "HideMoveRangeGraphics", "DisplayMovementViewTile",
    )
    fun `select_move range squares match the game`() {
        val c = run("ram_trace_prologue_select_move", 1255, 1420)
        assertTrue(c.stretches == 1 && c.frames >= 30, "$c")
    }

    @Test
    @VerifiesPort(
        "DisplayMoveRangeGraphics", "MoveLimitView_OnInit", "MoveLimitView_OnLoop",
        "MoveLimitView_OnEnd", "MoveLimitViewChange_OnInit", "MoveLimitViewChange_OnLoop",
        "HideMoveRangeGraphics", "DisplayMovementViewTile",
    )
    fun `cancel_end range squares match the game`() {
        val c = run("ram_trace_prologue_cancel_end", 1255, 1650)
        assertTrue(c.stretches == 3 && c.frames >= 80, "$c")
    }

    @Test
    @VerifiesPort(
        "DisplayMoveRangeGraphics", "MoveLimitView_OnInit", "MoveLimitView_OnLoop",
        "MoveLimitView_OnEnd", "MoveLimitViewChange_OnInit", "MoveLimitViewChange_OnLoop",
        "HideMoveRangeGraphics", "DisplayMovementViewTile",
    )
    fun `attack range squares match the game`() {
        val c = run("ram_trace_prologue_attack", 1255, 1335)
        assertTrue(c.stretches == 1 && c.frames >= 30, "$c")
    }

    private class Checks {
        var stretches = 0
        var frames = 0
        var fullDumps = 0
        var redTiles = 0
        var blueTiles = 0

        override fun toString() =
            "stretches=$stretches frames=$frames fullDumps=$fullDumps blue=$blueTiles red=$redTiles"
    }

    private fun run(name: String, first: Int, last: Int): Checks {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(name)
        val trace = run.watch("procs")
        fun hasView(f: Int) =
            ProcArrayDump.ofWatch(trace, f, names).find("sProcScr_MoveLimitView") != null
        val c = Checks()
        var f = first + 1
        while (f <= last) {
            if (hasView(f) && !hasView(f - 1)) {
                var end = f
                while (end + 1 <= last && hasView(end + 1)) end++
                if (end + 1 <= last) {
                    replay(name, run, trace, names, f, end, c)
                    c.stretches++
                }
                f = end + 1
            } else {
                f++
            }
        }
        println("MoveLimitView $name: $c")
        return c
    }

    private fun dumpName(run: ReferenceData.Run, f: Int): String {
        val full = "f%05d".format(f)
        return if (run.dumps.containsKey(full)) full else "r%05d".format(f)
    }

    private fun replay(
        name: String,
        run: ReferenceData.Run,
        trace: gba.testkit.WatchTrace,
        names: Map<Int, String>,
        start: Int,
        end: Int,
        c: Checks,
    ) {
        val rom = RomView.ofRom(romFile.readBytes())
        fun ew(f: Int) = run.region(dumpName(run, f), "ewram")
        fun iw(f: Int) = run.region(dumpName(run, f), "iwram")
        val seedE = ew(start - 1)
        val seedI = iw(start - 1)
        val playSt = PlaySt().also { it.decode(seedE, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(seedE, BM_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
        val startE = ew(start)
        readPool(startE, iw(start), GBM_MAP_MOVEMENT, map, map.movement)
        readPool(startE, iw(start), GBM_MAP_RANGE, map, map.range)
        readPool(startE, iw(start), GBM_MAP_FOG, map, map.fog)
        val hardware = BgHardware()
        copyTilemap(seedE, BG2_BUFFER, hardware.mapBuffer(2))
        copyTilemap(seedE, BG3_BUFFER, hardware.mapBuffer(3))
        for (i in 0 until BgHardware.PALETTE_COLORS) {
            hardware.palette[i] = half(seedE, PALETTE - EWRAM + 2 * i).toShort()
        }
        hardware.lcd.readFrom(seedI, LCD_BUFFER - IWRAM)
        val engine = ProcEngine()
        val render = BmMapRender(bm, map, hardware)
        val view = MoveLimitView(engine, bm, map, hardware, rom, render)
        val wantLcd = LcdControl()

        for (f in start..end + 1) {
            val e = ew(f)
            val i = iw(f)
            val at = "$name f$f"
            view.gameClock = IntSupplier { word(i, GAME_CLOCK - IWRAM) }
            // The caller runs before tree 4 (PlayerPhase, tree 2) or after it (a tree 3 menu
            // proc, attack f1365); the change proc's unk_4C at the first frame tells which.
            val startWant = ProcArrayDump.ofWatch(trace, start, names)
            val lateStart = startWant.find("sProcScr_MoveLimitViewChange")?.u16(0x4C) == 2
            fun begin() {
                val flags = startWant.find("sProcScr_MoveLimitView")!!.u16(0x4A)
                // PlayerPhase_InitUnitMovementSelect sets BM_FLAG_1 for the select stretches.
                if (!lateStart) bm.gameStateBits = bm.gameStateBits or BM_FLAG_1
                view.displayMoveRangeGraphics(flags)
            }
            if (f == start && !lateStart) begin()
            if (f == end + 1) view.hideMoveRangeGraphics()
            engine.run(engine.root(4))
            if (f == start && lateStart) begin()

            // Procs: existence, script position, flags, unk_4C.
            val want = ProcArrayDump.ofWatch(trace, f, names)
            val wantView = want.find("sProcScr_MoveLimitView")
            val gotView = engine.find(view.viewScript)
            assertEquals(wantView != null, gotView != null, "$at view proc exists")
            if (wantView != null) {
                assertEquals(wantView.scrCur, gotView!!.scrCur, "$at view script position")
                assertEquals(
                    wantView.u16(0x4A), (gotView.state as MoveLimitView.State).flags,
                    "$at view flags",
                )
            }
            val wantChange = want.find("sProcScr_MoveLimitViewChange")
            val gotChange = engine.find(view.changeScript)
            assertEquals(wantChange != null, gotChange != null, "$at change proc exists")
            if (wantChange != null) {
                assertEquals(wantChange.scrCur, gotChange!!.scrCur, "$at change script position")
                assertEquals(
                    wantChange.u16(0x4C), (gotChange.state as MoveLimitView.State).unk4C,
                    "$at change unk_4C",
                )
            }

            // Tilemaps, palette, LCD, gameStateBits.
            compareTilemap(e, BG2_BUFFER, hardware.mapBuffer(2), "$at BG2")
            compareTilemap(e, BG3_BUFFER, hardware.mapBuffer(3), "$at BG3")
            for (k in 0x40 until 0x60) {
                assertEquals(
                    half(e, PALETTE - EWRAM + 2 * k), hardware.palette[k].toInt() and 0xFFFF,
                    "$at palette colour 0x${k.toString(16)}",
                )
            }
            wantLcd.readFrom(i, LCD_BUFFER - IWRAM)
            val lcd = hardware.lcd
            assertEquals(wantLcd.dispcnt and 0xE000, lcd.dispcnt and 0xE000, "$at window enable")
            for (bg in 0..3) {
                assertEquals(wantLcd.bgCnt[bg] and 3, lcd.bgCnt[bg] and 3, "$at BG$bg priority")
            }
            assertEquals(wantLcd.bgHofs[2], lcd.bgHofs[2], "$at BG2 hofs")
            assertEquals(wantLcd.bgVofs[2], lcd.bgVofs[2], "$at BG2 vofs")
            if (f != end + 1) {
                assertEquals(wantLcd.bldcnt, lcd.bldcnt, "$at BLDCNT")
                assertEquals(wantLcd.blendCoeffA, lcd.blendCoeffA, "$at EVA")
                assertEquals(wantLcd.blendCoeffB, lcd.blendCoeffB, "$at EVB")
            }
            assertEquals(
                e[BM_ST - EWRAM + 4].toInt() and 3, bm.gameStateBits and 3, "$at gameStateBits",
            )
            if (!lateStart && run.dumps.containsKey("f%05d".format(f))) {
                val vram = run.region("f%05d".format(f), "vram")
                for (k in 0 until 0x100) {
                    assertEquals(
                        vram[0x5000 + k], hardware.vram[0x5000 + k], "$at VRAM 0x${(0x5000 + k).toString(16)}",
                    )
                }
                c.fullDumps++
            }
            c.frames++
        }
        val bg2 = hardware.mapBuffer(2)
        val e = ew(start)
        for (k in 0 until 0x400) {
            val v = half(e, BG2_BUFFER - EWRAM + 2 * k)
            if (v == 0x4280) c.blueTiles++
            if (v == 0x5280) c.redTiles++
        }
        assertEquals(0, bg2.count { it.toInt() != 0 }, "$name BG2 cleared after hide")
    }

    private fun half(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun word(b: ByteArray, at: Int) = half(b, at) or (half(b, at + 2) shl 16)

    private fun copyTilemap(ewram: ByteArray, address: Int, out: ShortArray) {
        for (k in 0 until 0x400) out[k] = half(ewram, address - EWRAM + 2 * k).toShort()
    }

    private fun compareTilemap(ewram: ByteArray, address: Int, got: ShortArray, at: String) {
        for (k in 0 until 0x400) {
            assertEquals(half(ewram, address - EWRAM + 2 * k), got[k].toInt() and 0xFFFF, "$at[$k]")
        }
    }

    private fun readPool(ewram: ByteArray, iwram: ByteArray, handle: Int, map: BmMap, pool: BmMap.Pool) {
        fun byte(address: Int) =
            (if (address >= IWRAM) iwram[address - IWRAM] else ewram[address - EWRAM]).toInt() and 0xFF
        fun mem32(address: Int) = byte(address) or (byte(address + 1) shl 8) or
            (byte(address + 2) shl 16) or (byte(address + 3) shl 24)
        val first = mem32(mem32(handle) - 8)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = byte(first + (y + 2) * (map.sizeX + 2) + x)
            }
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val PALETTE = 0x020228A8
        const val BG2_BUFFER = 0x02023CA8
        const val BG3_BUFFER = 0x020244A8
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val GBM_MAP_RANGE = 0x0202E4E4
        const val GBM_MAP_FOG = 0x0202E4E8
        const val LCD_BUFFER = 0x03003080
        const val GAME_CLOCK = 0x03000014
        const val BM_FLAG_1 = 1 shl 1
    }
}
