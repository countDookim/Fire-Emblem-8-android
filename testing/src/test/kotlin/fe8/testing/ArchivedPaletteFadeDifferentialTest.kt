package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Archived palette fade functions compared with the prologue capture during NewFadeIn. */
class ArchivedPaletteFadeDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("WriteFadedPaletteFromArchive")
    fun `darkening write matches gPaletteBuffer mid fade`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_prologue")
        for (snapshot in listOf("r01230", "r01260")) {
            val ewram = run.region(snapshot, "ewram")
            val hardware = BgHardware()
            for (i in 0 until BANKS * 16) {
                hardware.palette[i] = u16(ewram, archiveAt(i / 16, i % 16)).toShort()
            }
            val fade = PaletteFade(hardware, ProcEngine())
            fade.archiveCurrentPalettes()
            val ends = IntArray(3) { u16(ewram, SPALFADEST + STRIDE * it + CLOCK_END) }
            assertTrue(ends.all { it < 0x100 }, "$snapshot clock ends ${ends.toList()}")
            hardware.palette.fill(0x5A5A)
            fade.writeFadedPaletteFromArchive(ends[0], ends[1], ends[2], -1)
            for (i in 0 until BANKS * 16) {
                assertEquals(
                    u16(ewram, GPALETTEBUFFER + 2 * i),
                    hardware.palette[i].toInt() and 0xFFFF,
                    "$snapshot color $i (bank ${i / 16})",
                )
            }
        }
    }

    @Test
    @VerifiesPort("ArchiveCurrentPalettes")
    fun `archive copies all banks and resets clock ends`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_prologue")
        val before = run.region("r01230", "ewram")
        val settled = run.region("r01440", "ewram")
        val hardware = BgHardware()
        for (i in 0 until BANKS * 16) {
            hardware.palette[i] = u16(settled, GPALETTEBUFFER + 2 * i).toShort()
        }
        val fade = PaletteFade(hardware, ProcEngine())
        fade.archiveCurrentPalettes()
        // The game's own NewFadeIn only archived banks 0..2 (the rest of the r01230 archive is
        // still zero, while the settled buffer holds other banks), so compare those banks.
        for (i in 0 until 3 * 16) {
            assertEquals(
                u16(before, archiveAt(i / 16, i % 16)),
                fade.archivedColor(i / 16, i % 16),
                "archived color $i (bank ${i / 16})",
            )
        }
        for (bank in 0..2) assertEquals(0x100, fade.clockEnd(bank), "clock end $bank")
    }

    private companion object {
        const val BANKS = 32
        const val GPALETTEBUFFER = 0x228A8
        const val SPALFADEST = 0x2B6B0
        const val STRIDE = 0x30
        const val CLOCK_END = 0x2A

        /** from_colors is the first member of each PalFadeSt entry. */
        fun archiveAt(bank: Int, index: Int) = SPALFADEST + STRIDE * bank + 2 * index

        fun u16(mem: ByteArray, at: Int) =
            (mem[at].toInt() and 0xFF) or ((mem[at + 1].toInt() and 0xFF) shl 8)
    }
}
