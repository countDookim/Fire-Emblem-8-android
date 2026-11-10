package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapRm
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `src/worldmap_rm.c` (the big map BG of the prologue world map) against `ram_trace_prologue`: the
 * `procs` watch of `ProcScr_GmapRM` / `ProcScr_GmapRmUpdateDirect` / `ProcScr_GmapRmUpdateExt` for
 * steps
 * 4942-9285 and the full dump `f05100` (BG registers, tilemap, tile data, palette).
 */
class WorldMapRmTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun romFile() = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() =
        ref.available && romFile().isFile && ProcArrayDump.romNames().isNotEmpty()

    @Test
    @VerifiesPort(
        "GmapRm_StartUpdateDirect", "StartGmapRm", "GmapRm_Goto0IfFlag0", "GmapRm_80C2320",
        "GmapRm_RemoveUnits",
    )
    fun `starting the map loads its graphics, tilemap and BG registers like the original`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val run = ref.run("ram_trace_prologue")
        val iwram = run.region("r04920", "iwram").copyOf(0x8000)
        val gm = GmData(iwram, RngService())
        val hw = newHardware()
        val engine = ProcEngine()
        val removed = mutableListOf<Int>()
        val rm = WorldMapRm(
            engine, hw, RomView.ofRom(romFile().readBytes()), gm,
            WorldMapRm.Hooks(removeMuUnit = { removed += it }),
        )
        val names = ProcArrayDump.romNames()
        val start = ProcArrayDump.ofWatch(run.watch("procs"), FIRST_STEP, names)
        val expectedProc = checkNotNull(start.find("ProcScr_GmapRM"))
        val proc = rm.startGmapRm(0, 0, expectedProc.u8(0x29), null)
        var frames = 0
        while (engine.find(rm.updateDirectScript) == null && frames++ < 10) {
            engine.run(engine.root(3))
        }
        // The wait of `PROC_SLEEP(1)` then the rest of the script up to `GmapRm_Blocking`.
        engine.run(engine.root(3))
        hw.flushBackgrounds()

        val vram = run.region("f05100", "vram")
        // Bytes `Decompress(Img_EventGmap, BG_VRAM)` wrote; the rest of VRAM belongs to other
        // users.
        val tileBytes = RomView.ofRom(romFile().readBytes())
            .decompressInto(IMG_EVENT_GMAP, ByteArray(0x10000), 0)
        assertSame(vram, hw.vram, 0 until tileBytes, "BG tile data")
        // BG1 tilemap (screen block 13) and the cleared BG2 tilemap (screen block 14).
        assertSame(vram, hw.vram, 0x6800 until 0x7000, "BG1 tilemap")
        assertSame(vram, hw.vram, 0x7000 until 0x7800, "BG2 tilemap")
        val pal = run.region("f05100", "pal")
        for (i in 5 * 16 until 9 * 16) {
            val expected =
                (pal[2 * i].toInt() and 0xFF) or ((pal[2 * i + 1].toInt() and 0xFF) shl 8)
            assertEquals(expected, hw.paletteRam[i].toInt() and 0xFFFF, "palette entry $i")
        }
        val io = run.region("f05100", "io")
        val ours = ByteArray(0x100)
        hw.lcd.flushTo(ours)
        for (bg in 0 until 4) {
            assertEquals(io.u16(8 + 2 * bg) and 3, ours.u16(8 + 2 * bg) and 3, "BG$bg priority")
        }
        // BLDCNT/BLDALPHA are not compared: by step 5100 the face and text procs have set their
        // own blend.
        val blocked = checkNotNull(
            ProcArrayDump.ofWatch(run.watch("procs"), 4948, names).find("ProcScr_GmapRM"),
        )
        assertEquals(blocked.u8(0x29), (proc.state as fe8.game.worldmap.GmapRmProc).flag, "flag")
        assertEquals(
            run.region("f05100", "iwram")[GmData.BASE].toInt() and 0x08, gm.stateRaw and 0x08,
            "state_3",
        )
        assertEquals(emptyList<Int>(), removed, "no other unit shares the leader's node")
    }

    @Test
    @VerifiesPort(
        "GmapRm_Blocking", "GmapRmUpdateDirect_Loop", "GmapRmUpdateExt_InitPosition",
        "GmapRmUpdateExt_ScrollPosition", "StartGmapRmUpdateExt",
        "GmapRm_SetPosition", "sub_800A42C", "sub_8014CA4",
    )
    fun `map scroll procs follow the original proc fields and BG1 offsets every step`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val run = ref.run("ram_trace_prologue")
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)

        val gm = GmData(run.region("r04920", "iwram").copyOf(0x8000), RngService())
        val hw = newHardware()
        val engine = ProcEngine()
        val rm = WorldMapRm(engine, hw, RomView.ofRom(romFile().readBytes()), gm)
        val first = checkNotNull(dump(FIRST_STEP).find("ProcScr_GmapRM"))
        val rmProc = rm.startGmapRm(0, 0, first.u8(0x29), null)

        val bg1 = HashMap<Int, Pair<Int, Int>>()
        var ext = false
        var step = FIRST_STEP
        fun compare(at: Int) {
            val expected = dump(at)
            val rmExpected = checkNotNull(expected.find("ProcScr_GmapRM"), { "step $at" })
            val rmState = rmProc.state as fe8.game.worldmap.GmapRmProc
            assertEquals(rmExpected.scrCur, rmProc.scrCur, "RM script position at $at")
            assertEquals(rmExpected.u8(0x29), rmState.flag, "RM flag at $at")
            assertEquals(rmExpected.u16(0x2E).toShort().toInt(), rmState.x, "RM x at $at")
            assertEquals(rmExpected.u16(0x30).toShort().toInt(), rmState.y, "RM y at $at")
            val direct = expected.find("ProcScr_GmapRmUpdateDirect")
            val ourDirect = engine.find(rm.updateDirectScript)
            assertEquals(direct?.scrCur, ourDirect?.scrCur, "UpdateDirect position at $at")
            val extExpected = expected.find("ProcScr_GmapRmUpdateExt")
            val ourExt = engine.find(rm.updateExtScript)
            assertEquals(extExpected?.scrCur, ourExt?.scrCur, "UpdateExt position at $at")
            if (extExpected != null) {
                val s = ourExt!!.state as fe8.game.worldmap.GmapRmUpdateProc
                val fields = intArrayOf(s.x0, s.y0, s.x1, s.y1, s.delay, s.timer, s.speed)
                val expectedFields =
                    IntArray(7) { extExpected.u16(0x2A + 2 * it).toShort().toInt() }
                // The timer is only set by `GmapRmUpdateExt_InitPosition` (the slot's old value
                // stays).
                val keep = if (extExpected.scrCur < 6) listOf(0, 1, 2, 3, 4, 6) else (0..6).toList()
                assertEquals(
                    keep.map { expectedFields[it] }, keep.map { fields[it] },
                    "UpdateExt fields at $at",
                )
            }
        }

        // 4942-4943: the `PROC_SLEEP(1)`; the original then spends 4944-4947 inside the
        // `GmapRm_StartUpdateDirect` callback (decompressing the map), which is one lag-frame
        // run here, so the port's frame after the sleep lines up with step 4948.
        compare(step)
        engine.run(engine.root(3))
        compare(++step)
        engine.run(engine.root(3))
        step = 4948
        while (true) {
            compare(step)
            bg1[step] = hw.lcd.bgHofs[1] to hw.lcd.bgVofs[1]
            if (step == LAST_STEP) break
            step++
            engine.run(engine.root(3))
            val appears = dump(step).find("ProcScr_GmapRmUpdateExt")
            if (appears != null && !ext) {
                // The event engine creates the scroll after the frame's `Proc_Run` walked the
                // roots.
                rm.startGmapRmUpdateExt(
                    appears.u16(0x2A), appears.u16(0x2C), appears.u16(0x2E), appears.u16(0x30),
                    appears.u16(0x36), appears.u16(0x32), null,
                )
            }
            ext = appears != null
        }

        for (dumpStep in 5100..9000 step 300) {
            val io = run.region("f%05d".format(dumpStep), "io")
            val ours = bg1.getValue(dumpStep - 1)
            assertEquals(io.u16(0x14) and 0x1FF, ours.first and 0x1FF, "BG1HOFS at $dumpStep")
            assertEquals(io.u16(0x16) and 0x1FF, ours.second and 0x1FF, "BG1VOFS at $dumpStep")
        }
    }

    private fun assertSame(expected: ByteArray, actual: ByteArray, range: IntRange, what: String) {
        val bad = range.filter { expected[it] != actual[it] }
        val first = bad.firstOrNull()?.toString(16)
        assertEquals(0, bad.size, "$what: ${bad.size} bytes differ, first at 0x$first")
    }

    private fun newHardware(): BgHardware {
        val hw = BgHardware()
        hw.setMapDataOffset(1, 0x6800)
        hw.setMapDataOffset(2, 0x7000)
        hw.initBmBgLayers(false)
        return hw
    }

    private companion object {
        /** ROM address of `Img_EventGmap`. */
        const val IMG_EVENT_GMAP = 0x08A99140

        /** First step with a `ProcScr_GmapRM` proc. */
        const val FIRST_STEP = 4942

        /** Last step before the proc and its updater are ended (not by their own script). */
        const val LAST_STEP = 9285
    }
}

/** Little-endian halfword at [offset]. */
private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
