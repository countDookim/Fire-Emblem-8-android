package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The generic fade-in proc compared with the prologue capture (OpSubtitle's NewFadeIn(2)). */
class ArchivedPaletteFadeProcDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("NewFadeIn", "FadeInOut_Init", "FadeIn_Loop", "FadeInExists")
    fun `fade in timer lifetime and palette match the original`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val frames = (1200..2354).filter {
            ProcArrayDump.ofWatch(trace, it).find(SCRIPT) != null
        }
        assertTrue(frames.isNotEmpty(), "fade proc never observed")
        assertEquals(frames.size, frames.last() - frames.first() + 1, "contiguous lifetime")
        val original = frames.map { ProcArrayDump.ofWatch(trace, it).find(SCRIPT)!! }
        val first = original.first()
        assertEquals(2, first.u32(0x30), "speed")
        assertEquals(-1, first.u32(0x34), "mask")
        assertEquals(0, first.u8(0x29), "white_out")
        val timers = original.map { it.u32(0x2C) }

        val ewram = run.region("r01230", "ewram")
        val hardware = BgHardware()
        for (i in 0 until 512) {
            val at = SPALFADEST + STRIDE * (i / 16) + 2 * (i % 16)
            hardware.palette[i] = u16(ewram, at).toShort()
        }
        val engine = ProcEngine()
        val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val proc = fade.newFadeIn(2, null)
        assertTrue(fade.exists())
        val mine = ArrayList<Int>()
        val palettes = HashMap<Int, ShortArray>()
        while (fade.exists()) {
            engine.run(engine.root(4))
            if (fade.exists()) {
                val timer = fade.stateOf(proc).timer
                mine += timer
                palettes[timer] = hardware.palette.copyOf()
            }
            assertTrue(mine.size < 500, "fade never ended")
        }
        // Alignment is by timer value: the watch first shows the proc after its first loop step
        // (timer 2), so the sequences must be identical, including the number of frames the
        // proc exists after its first observed step.
        assertEquals(timers, mine, "timer sequence")

        // r01260 is mid-fade (clock end 0xB8 => timer 0x5C); compare the aligned palette.
        val snap = run.region("r01260", "ewram")
        val clockEnd = u16(snap, SPALFADEST + CLOCK_END)
        val expected = ShortArray(512) { u16(snap, GPALETTEBUFFER + 2 * it).toShort() }
        val got = palettes[clockEnd / 2] ?: error("no frame with timer ${clockEnd / 2}")
        assertEquals(expected.toList(), got.toList(), "palette at timer ${clockEnd / 2}")
    }

    @Test
    @VerifiesPort("FadeInOut_DisableGfx")
    fun `startup disables display and initializes the original palette archive`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_fade_start")
        val calls = File(ref.root, "ram_trace_fade_start/calls.txt").readLines().map {
            it.split(" ")
        }
        assertEquals(
            listOf(
                "call_newfade_1_entry", "call_disable_1_entry", "call_disable_1_exit",
                "call_newfade_1_exit", "call_init_1_entry", "call_init_1_exit",
            ),
            calls.map { it[0] },
        )
        assertTrue(calls.all { it[1] == "1215" }, "all calls occur in the same step")
        for ((entry, exit, address) in listOf(
            Triple(0, 3, "080AE2D0"), Triple(1, 2, "080AE248"),
            Triple(4, 5, "080AE18C"),
        )) {
            assertEquals(address, calls[entry][2], "entry PC")
            assertEquals(calls[entry][6], calls[exit][2], "return PC")
        }

        val disableEntry = run.region("call_disable_1_entry", "ewram")
        val disableEntryLcd = run.region("call_disable_1_entry", "iwram")
        val disableExit = run.region("call_disable_1_exit", "iwram")
        val newEntry = run.region("call_newfade_1_entry", "iwram")
        val initEntry = run.region("call_init_1_entry", "ewram")
        val initExit = run.region("call_init_1_exit", "ewram")
        val procOffset = calls[1][3].toLong(16).toInt() - 0x02000000
        assertTrue(u32(disableEntry, procOffset + 0x34) != 0xFFFF, "observed mask branch")
        assertEquals(calls[1][3], calls[4][3], "init uses the same proc")
        val hardware = BgHardware()
        hardware.lcd.dispcnt = u16(newEntry, LCD_CONTROL)
        assertEquals(u16(disableEntryLcd, LCD_CONTROL), hardware.lcd.dispcnt, "display at call")
        assertArrayEquals(
            run.region("call_disable_1_entry", "io"), run.region("call_disable_1_exit", "io"),
            "display registers do not flush during disable",
        )
        for (i in hardware.palette.indices) {
            hardware.palette[i] = u16(initEntry, GPALETTEBUFFER + i * 2).toShort()
        }
        val engine = ProcEngine()
        val palette = PaletteFade(hardware, engine)
        val fade = ArchivedPaletteFade(palette, hardware, engine)
        val proc = fade.newFadeIn(2, null)
        assertEquals(u16(disableExit, LCD_CONTROL), hardware.lcd.dispcnt, "disable display")
        val state = fade.stateOf(proc)
        assertEquals(u32(initEntry, procOffset + 0x30), state.speed, "speed")
        assertEquals(u32(initEntry, procOffset + 0x34), state.mask, "mask after start")
        assertEquals(initEntry[procOffset + 0x29].toInt() != 0, state.whiteOut, "white flag")
        state.timer = u32(initEntry, procOffset + 0x2C)
        assertTrue(state.timer != 0, "the original reused a nonzero timer")
        assertTrue((0 until 512).any {
            val at = SPALFADEST + STRIDE * (it / 16) + 2 * (it % 16)
            u16(initEntry, at) != u16(initExit, at)
        }, "the original archive changed during init")
        assertEquals(0, u32(initExit, procOffset + 0x2C), "original reset the old timer")
        engine.run(engine.root(4))
        assertEquals(2, state.timer, "reset timer before first loop")
        for (i in 0 until 512) {
            val at = SPALFADEST + STRIDE * (i / 16) + 2 * (i % 16)
            assertEquals(u16(initExit, at), palette.archivedColor(i / 16, i % 16), "color $i")
        }
        val frame = run.region("r01215", "ewram")
        val expected = ShortArray(512) { u16(frame, GPALETTEBUFFER + 2 * it).toShort() }
        assertArrayEquals(expected, hardware.palette, "first faded palette")
    }

    @Test
    fun `fade capture shares prologue inputs and sound state around the known divergence`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val old = ref.run("ram_trace_prologue")
        val fresh = ref.run("ram_trace_fade_start")
        assertArrayEquals(old.inputs.copyOf(fresh.inputs.size), fresh.inputs)
        for (step in listOf(960, 990, 1230, 1260)) {
            val dump = "r%05d".format(step)
            for (region in listOf("ewram", "iwram")) {
                assertArrayEquals(old.region(dump, region), fresh.region(dump, region), "$dump $region")
            }
        }
    }

    private companion object {
        const val LCD_CONTROL = 0x3080
        const val SCRIPT = 0x08A20DA4
        const val GPALETTEBUFFER = 0x228A8
        const val SPALFADEST = 0x2B6B0
        const val STRIDE = 0x30
        const val CLOCK_END = 0x2A

        fun u32(mem: ByteArray, at: Int) = u16(mem, at) or (u16(mem, at + 2) shl 16)

        fun u16(mem: ByteArray, at: Int) =
            (mem[at].toInt() and 0xFF) or ((mem[at + 1].toInt() and 0xFF) shl 8)
    }
}
