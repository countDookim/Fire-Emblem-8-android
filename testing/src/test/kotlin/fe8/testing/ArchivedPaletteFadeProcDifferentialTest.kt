package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
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

    private companion object {
        const val SCRIPT = 0x08A20DA4
        const val GPALETTEBUFFER = 0x228A8
        const val SPALFADEST = 0x2B6B0
        const val STRIDE = 0x30
        const val CLOCK_END = 0x2A

        fun u16(mem: ByteArray, at: Int) =
            (mem[at].toInt() and 0xFF) or ((mem[at + 1].toInt() and 0xFF) shl 8)
    }
}
