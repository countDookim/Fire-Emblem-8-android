package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmapMuEntryProc
import fe8.game.worldmap.WorldMapHblank
import fe8.game.worldmap.WorldMapMuEntry
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `gProcScr_GmapMuEntry2` (`src/worldmap_entry.c`) and the scanline helpers it uses
 * (`src/worldmap_hb.c`) against `ram_trace_prologue`: the script position, root tree and the
 * `struct GmapMuEntryProc` bytes +0x29..+0x43 of the `procs` watch at every step from 4907,
 * when `WorldMap_CallBeginningEvent` starts the proc, to 9285, its last step. At the dumps
 * 5070, 5100 and 5130 it also compares the scanline buffer `gUnknown_0201BE32`, the register
 * slots `gUnknown_0201C5B4`, the page `gUnknown_0201C5CF`, `gWmHblankStatus`, BG0's tilemap
 * buffer and (5070, 5100) the LCD blend registers, and at `f05100` the band tiles in VRAM and
 * palette bank 2.
 *
 * Inputs fed from the trace, because the code that produces them is not ported:
 * `GmMuEntryStartShow(speed, blend)` (called by an event) is called at the first step whose
 * traced `flags_0` is set, with `speed = unk_34 >> 12` and `blend = unk_30` read from that
 * record; `EndGmMuEntry` (world-map exit code) is called at the first step the proc is gone; and
 * at step 5040 the LCD buffer, BG0 tilemap, scanline buffer and its registers are loaded from
 * the `r05040` dump, since `GmapLineFade` and other unported procs write them before 5061.
 *
 * Limits: no capture calls `GmMuEntryStartHide`, so `GmMuEntry_80BFFD0`, `GmMuEntry_80C0144`
 * and `GmMuEntryStartHide` are not written. The scanline flags `gUnknown_0201C5CC` are 0 in
 * every dump, so `sub_80C1DE8` cannot be told from a no-op. The HBlank handler's own state
 * (`gUnknown_0201C5C0`, `5D0`, `5D1`) is not compared. `GmMuEntry_OnEnd_Null` is an empty
 * callback and not tagged.
 */
class WorldMapMuEntryTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort(
        "StartGmapMuEntry", "GmMuEntry_Init", "EndGmMuEntry", "GmMuEntryStartShow",
        "GmMuEntry_80BFEF8", "GmMuEntry_80C0080", "sub_80BFDA0", "sub_80BFD28",
        "sub_80C1DA0", "sub_80C1DC8", "sub_80C1DD8",
    )
    fun `mu entry proc follows the original every step`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available && romFile.isFile && names.isNotEmpty(), "data not present")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val addr = names.entries.first { it.value == "gProcScr_GmapMuEntry2" }.key
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        val hblank = WorldMapHblank()
        val engine = ProcEngine()
        engine.init()
        val entry = WorldMapMuEntry(engine, hardware, rom, hblank)
        val proc = checkNotNull(entry.startGmapMuEntry())
        val state = proc.state as GmapMuEntryProc

        fun compare(step: Int) {
            val t = checkNotNull(ProcArrayDump.ofWatch(trace, step, names).find(addr)) {
                "mu entry proc at $step"
            }
            assertEquals(t.scrCur, proc.scrCur, "script position at $step")
            assertEquals(t.u32(0x14), proc.rootIndex, "root tree at $step")
            assertEquals(
                listOf(
                    t.u8(0x29) and 1 != 0, t.u8(0x2A), t.u8(0x2B), t.u8(0x2C), t.u8(0x2D),
                    t.u8(0x2E), t.u8(0x2F), t.u16(0x30).toShort().toInt(), t.u32(0x34),
                    t.u32(0x38), t.u32(0x3C), t.u32(0x40),
                ),
                listOf(
                    state.flags0, state.unk2a, state.unk2b, state.unk2c, state.unk2d,
                    state.unk2e, state.unk2f, state.unk30, state.unk34, state.unk38,
                    state.unk3c, state.unk40,
                ),
                "fields at $step",
            )
        }

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

        fun loadState(dumpName: String) {
            val ewram = run.region(dumpName, "ewram")
            hardware.lcd.readFrom(run.region(dumpName, "iwram"), LCD_BUFFER)
            for (i in hardware.tilemaps[0].indices) {
                hardware.tilemaps[0][i] = u16(ewram, BG0_TILEMAP + 2 * i).toShort()
            }
            for (i in hblank.buffer.indices) {
                hblank.buffer[i] = u16(ewram, HB_BUFFER + 2 * i).toShort()
            }
            for (i in 0..2) hblank.registers[i] = u32(ewram, HB_REGISTERS + 4 * i)
            hblank.page = ewram[HB_PAGE].toInt() and 0xFF
            hblank.status = ewram[HB_STATUS].toInt() and 0xFF
        }

        fun compareDump(step: Int) {
            val name = dumpName(step)
            val ewram = run.region(name, "ewram")
            val bad = hblank.buffer.indices.filter {
                hblank.buffer[it].toInt() and 0xFFFF != u16(ewram, HB_BUFFER + 2 * it)
            }
            assertEquals(0, bad.size, "scanline buffer at $name, first bad ${bad.firstOrNull()}")
            assertEquals(
                listOf(
                    u32(ewram, HB_REGISTERS), u32(ewram, HB_REGISTERS + 4),
                    u32(ewram, HB_REGISTERS + 8), ewram[HB_PAGE].toInt() and 0xFF,
                    ewram[HB_STATUS].toInt() and 0xFF,
                    (0..2).map { ewram[HB_FLAGS + it].toInt() and 0xFF },
                ),
                listOf(
                    hblank.registers[0], hblank.registers[1], hblank.registers[2], hblank.page,
                    hblank.status, hblank.flags.toList(),
                ),
                "scanline registers, page, status and flags at $name",
            )
            val badMap = hardware.tilemaps[0].indices.filter {
                hardware.tilemaps[0][it].toInt() and 0xFFFF != u16(ewram, BG0_TILEMAP + 2 * it)
            }
            assertEquals(0, badMap.size, "BG0 tilemap at $name, first bad ${badMap.firstOrNull()}")
            if (step > LAST_BLEND_STEP) return
            val expected = BgHardware().lcd
            expected.readFrom(run.region(name, "iwram"), LCD_BUFFER)
            assertEquals(
                listOf(
                    expected.bldcnt, expected.blendCoeffA, expected.blendCoeffB, expected.blendY,
                ),
                listOf(
                    hardware.lcd.bldcnt, hardware.lcd.blendCoeffA, hardware.lcd.blendCoeffB,
                    hardware.lcd.blendY,
                ),
                "blend registers at $name",
            )
        }

        compare(4907)
        var step = 4907
        var ended = false
        var started = false
        while (step < 9330) {
            step++
            val live = ProcArrayDump.ofWatch(trace, step, names).find(addr)
            if (live == null) {
                entry.endGmMuEntry()
                ended = true
                break
            }
            if (!started && live.u8(0x29) and 1 != 0) {
                started = true
                val speed = live.u32(0x34) shr 12
                assertEquals(0, entry.gmMuEntryStartShow(speed, live.u16(0x30)), "start accepted")
                assertEquals(1, entry.gmMuEntryStartShow(speed, live.u16(0x30)), "start refused")
            }
            engine.run(engine.root(3))
            compare(step)
            if (step == LOAD_STEP) loadState(dumpName(step))
            if (step in DUMP_STEPS) compareDump(step)
        }
        assertEquals(true, started, "the capture starts a show transition")
        assertEquals(true, ended, "the capture ends the proc")
        assertEquals(9286, step, "first step without the proc")
        assertEquals(null, engine.find(entry.script), "EndGmMuEntry ends the proc")

        val pal = run.region("f05100", "pal")
        val vram = run.region("f05100", "vram")
        val scratch = ByteArray(0x2000)
        val tileBytes = rom.decompressInto(WorldMapMuEntry.IMG_BAND, scratch, 0)
        val badTiles = (0 until tileBytes).filter {
            hardware.vram[BAND_TILES + it] != vram[BAND_TILES + it]
        }
        assertEquals(0, badTiles.size, "band tiles at f05100, first bad ${badTiles.firstOrNull()}")
        // Color 10 of bank 2 is rewritten (0x7C00 to 0x4508) by a later unported proc.
        val colors = (32 until 48).filter { it != 42 }
        assertEquals(
            colors.map { u16(pal, 2 * it) },
            colors.map { hardware.palette[it].toInt() and 0xFFFF },
            "palette bank 2 at f05100",
        )
    }

    private fun dumpName(step: Int) = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)

    private companion object {
        /** `gLCDControlBuffer` in IWRAM. */
        const val LCD_BUFFER = 0x3080

        /** EWRAM offsets (address - 0x02000000): `gBG0TilemapBuffer`. */
        const val BG0_TILEMAP = 0x22CA8

        /** `gUnknown_0201BE32`. */
        const val HB_BUFFER = 0x1BE32

        /** `gUnknown_0201C5B4`. */
        const val HB_REGISTERS = 0x1C5B4

        /** `gUnknown_0201C5CC`. */
        const val HB_FLAGS = 0x1C5CC

        /** `gUnknown_0201C5CF`. */
        const val HB_PAGE = 0x1C5CF

        /** `gWmHblankStatus`. */
        const val HB_STATUS = 0x1BE30

        /** VRAM offset of the band tiles. */
        const val BAND_TILES = 0x4C00

        /** Step of the dump loaded as the starting state. */
        const val LOAD_STEP = 5040

        /** Dumps inside and after the transition (5061-5100). */
        val DUMP_STEPS = setOf(5070, 5100, 5130)

        /** Later dumps' blend registers belong to other procs. */
        const val LAST_BLEND_STEP = 5100
    }
}
