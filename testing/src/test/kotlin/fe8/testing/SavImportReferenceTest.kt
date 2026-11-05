package fe8.testing

import fe8.game.save.Fe8SavException
import fe8.game.save.Fe8SavImporter
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Imports the post-New-Game SRAM of `ram_trace_newgame` (dump s01230, the bytes of the ROM repo's
 * `newgame_slot0.sav` fixture; read at runtime, never committed).
 */
class SavImportReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun bytes(): ByteArray {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_newgame")
        assumeTrue(run.hasSram, "ram_trace_newgame has no SRAM dumps")
        return run.sram("s01230")
    }

    @Test
    fun `import then export is byte identical and slot 0 only is valid`() {
        val sav = bytes().copyOf(0x8000)
        val r = Fe8SavImporter.import(sav)
        assertArrayEquals(sav, Fe8SavImporter.export(r.container))
        assertEquals(listOf(true, false, false), r.slotValid.toList())
    }

    @Test
    fun `padded to 128 KiB imports identically`() {
        val sav = bytes().copyOf(0x8000)
        val padded = ByteArray(0x20000) { 0xFF.toByte() }
        sav.copyInto(padded)
        val r = Fe8SavImporter.import(padded)
        assertArrayEquals(sav, Fe8SavImporter.export(r.container))
        assertEquals(listOf(true, false, false), r.slotValid.toList())
    }

    @Test
    fun `corrupted header raises CorruptedHeader`() {
        val sav = bytes().copyOf(0x8000)
        sav[0x20] = (sav[0x20].toInt() xor 0x01).toByte()
        assertThrows(Fe8SavException.CorruptedHeader::class.java) { Fe8SavImporter.import(sav) }
    }
}
