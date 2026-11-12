package fe8.testing

import fe8.game.worldmap.WorldMapHblank
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The world map's HBlank handler (`src/worldmap_hb.c`) against `ram_trace_prologue` and
 * `ram_trace_prologue_wm_lcd`.
 *
 * `WorldMapHblank`: at every `ewram` dump from 5500 to 8450 (the text box is shown from 5102;
 * 98 dumps) the handler runs over one frame of calls starting with its latched state cleared.
 * The latch it ends with must be the dump's (`gUnknown_0201C5C0`, `5D0`, `5D1`), every palette
 * write must be the buffer line of the latched page and line, and the last one (line 159, the
 * colour 42 of bank 2 the register 0x05000054 points at) must be the `pal` watch's colour of the
 * same step. The write to an I/O register is checked with a synthetic slot (BG0VOFS), since no
 * dump has an I/O register in a slot after the line fade ends.
 *
 * `SetWorldMapHblank` / `ClearWmHblank`: from a dirty state they must produce the state of
 * `r04920` (the first dump after `WorldMap_Init`) and `r09330` (after the exit): the zeroed or
 * kept slots, the buffer, `gWmHblankStatus` and DISPSTAT's HBlank IRQ bit of `gLCDControlBuffer`.
 *
 * Limits: the `REG_WIN0H` branch (status bit 1) is not written: no world map code sets that bit.
 * The `displayio` watch's BG0VOFS and BLDY are 0 in every step of the text phase (no slot points
 * at an I/O register after the line fade), so the I/O write path is not trace-verified.
 */
class WorldMapHblankTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Sink : fe8.game.worldmap.PaletteLineWriter {
        val lines = ArrayList<Int>()
        val values = ArrayList<Int>()
        val indexes = ArrayList<Int>()

        override fun write(line: Int, index: Int, color: Int) {
            lines.add(line)
            indexes.add(index)
            values.add(color)
        }
    }

    private fun u16(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

    private fun load(h: WorldMapHblank, ewram: ByteArray) {
        for (i in h.buffer.indices) h.buffer[i] = u16(ewram, HB_BUFFER + 2 * i).toShort()
        for (i in 0..2) h.registers[i] = u32(ewram, HB_REGISTERS + 4 * i)
        h.page = ewram[HB_PAGE].toInt() and 0xFF
        h.status = ewram[HB_STATUS].toInt() and 0xFF
    }

    private fun dumpName(step: Int) = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)

    private fun lcdDispstat(run: ReferenceData.Run, name: String): Int {
        val hw = BgHardware()
        hw.lcd.readFrom(run.region(name, "iwram"), LCD_BUFFER)
        return hw.lcd.dispstat
    }

    @Test
    @VerifiesPort("WorldMapHblank")
    fun `handler writes the latched buffer lines and ends on the captured palette colour`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        val pal = ref.run("ram_trace_prologue_wm_lcd").watch("pal")
        var compared = 0
        for (step in 5500..8450) {
            val name = dumpName(step)
            val ewram = try {
                run.region(name, "ewram")
            } catch (e: java.io.IOException) {
                continue
            }
            val sink = Sink()
            val h = WorldMapHblank(sink)
            load(h, ewram)
            val io = ByteArray(0x400)
            // One frame of the renderer's call order: lines 159..227, then 0..158.
            for (v in 159 until 228) h.onHBlank(v, io)
            for (v in 0 until 159) h.onHBlank(v, io)
            assertEquals(
                listOf(u32(ewram, HB_ACTIVE), u32(ewram, HB_ACTIVE + 4), u32(ewram, HB_ACTIVE + 8)),
                h.activeRegisters.toList(), "latched registers at $name",
            )
            assertEquals(ewram[HB_ACTIVE_PAGE].toInt(), h.activePage, "latched page at $name")
            assertEquals(ewram[HB_ACTIVE_ON].toInt() != 0, h.copyEnabled, "copy flag at $name")
            assertEquals((0..159).toList(), sink.lines, "palette write lines at $name")
            val page = ewram[HB_ACTIVE_PAGE].toInt() * 480
            assertEquals(
                (0..159).map { u16(ewram, HB_BUFFER + 2 * (page + 160 + it)) },
                sink.values, "palette colours at $name",
            )
            assertEquals(List(160) { 42 }, sink.indexes, "palette index at $name")
            assertEquals(pal.u16(step, 0x54), sink.values.last(), "last colour vs pal watch at $name")
            compared++
        }
        assertTrue(compared >= 90, "dumps compared: $compared")
        // The I/O path (synthetic): slots on BG0VOFS and BLDY get the line's value, applied by
        // the call of the line before; slot 2 is empty in every dump.
        val h = WorldMapHblank()
        for (i in 0 until 160) {
            h.buffer[i] = (i + 0x101).toShort()
            h.buffer[320 + i] = (i + 0x202).toShort()
        }
        h.registers[0] = 0x04000012
        h.registers[2] = 0x04000054
        h.status = 4
        h.page = 0
        val io = ByteArray(0x400)
        h.onHBlank(159, io)
        h.onHBlank(227, io)
        assertEquals(listOf(0x101, 0x202), listOf(u16(io, 0x12), u16(io, 0x54)), "line 0 values")
        h.onHBlank(36, io)
        assertEquals(
            listOf(0x101 + 37, 0x202 + 37), listOf(u16(io, 0x12), u16(io, 0x54)),
            "values of line 37 after the call at line 36",
        )
    }

    @Test
    @VerifiesPort("SetWorldMapHblank", "ClearWmHblank")
    fun `set and clear match the dumps after init and after the exit`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        // Set: from the state of r09270 (everything dirty) to the state of r04920.
        val dirty = run.region("r09270", "ewram")
        val after = run.region("r04920", "ewram")
        val hw = BgHardware()
        hw.lcd.readFrom(run.region("r04890", "iwram"), LCD_BUFFER)
        val h = WorldMapHblank()
        load(h, dirty)
        for (i in 0..2) h.activeRegisters[i] = 7
        h.activePage = 1
        h.copyEnabled = true
        h.flags[1] = 3
        h.setWorldMapHblank(hw.hblank)
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, false, 0, listOf(0, 0, 0)),
            listOf(
                h.registers[0], h.registers[1], h.registers[2], h.page, h.activePage,
                h.activeRegisters[0], h.activeRegisters[1], h.copyEnabled, h.status, h.flags.toList(),
            ),
            "slots after SetWorldMapHblank",
        )
        assertEquals(after[HB_ACTIVE_ON].toInt() != 0, h.copyEnabled, "r04920 copy flag")
        assertEquals(lcdDispstat(run, "r04920"), hw.lcd.dispstat, "DISPSTAT after set")
        assertTrue(hw.hblank.active === h.handler, "primary handler installed")
        // Clear: from the state of r09270 to r09330.
        val cleared = run.region("r09330", "ewram")
        val hw2 = BgHardware()
        hw2.lcd.readFrom(run.region("r09270", "iwram"), LCD_BUFFER)
        val c = WorldMapHblank()
        load(c, dirty)
        hw2.hblank.setPrimary(c.handler)
        c.clearWmHblank(hw2.hblank)
        assertEquals(List(960) { 0 }, c.buffer.map { it.toInt() }, "buffer zeroed")
        assertEquals(cleared[HB_STATUS].toInt(), c.status, "status")
        assertEquals(
            listOf(u32(cleared, HB_REGISTERS + 4), cleared[HB_PAGE].toInt()),
            listOf(c.registers[1], c.page),
            "slots kept",
        )
        assertEquals(lcdDispstat(run, "r09330"), hw2.lcd.dispstat, "DISPSTAT after clear")
    }

    private companion object {
        const val LCD_BUFFER = 0x3080
        const val HB_BUFFER = 0x1BE32
        const val HB_REGISTERS = 0x1C5B4
        const val HB_ACTIVE = 0x1C5C0
        const val HB_PAGE = 0x1C5CF
        const val HB_ACTIVE_PAGE = 0x1C5D0
        const val HB_ACTIVE_ON = 0x1C5D1
        const val HB_STATUS = 0x1BE30
    }
}
