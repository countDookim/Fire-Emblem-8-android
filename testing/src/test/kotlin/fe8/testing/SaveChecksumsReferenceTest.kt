package fe8.testing

import fe8.game.save.Fe8SramLayout as L
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.save.SaveChecksums
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Checks the save checksums and the SRAM layout against the SRAM dumps of mGBA reference runs. */
class SaveChecksumsReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `layout constants are consistent`() {
        assertEquals(0x64, L.BLOCK_INFO_OFFSET)
        assertEquals(L.HEADER_SIZE, L.BLOCK_INFO_OFFSET)
        assertEquals(0xD4, L.BLOCK_INFO_OFFSET + L.BLOCK_INFO_TABLE_SIZE)
        assertEquals(0x204C, L.SUSPEND1_OFFSET)
        assertEquals(0x3FC4, L.GAME0_OFFSET)
        assertEquals(0x691C, L.ARENA_OFFSET)
        assertEquals(0x7190, L.END_OFFSET)
        assertEquals(L.SRAM_SIZE - L.XMAP_SIZE, L.XMAP_OFFSET)
        assertEquals(
            L.BLOCK_INFO_OFFSET + 3 * L.BLOCK_INFO_SIZE, L.blockInfoOffset(L.SAVE_ID_SUSPEND),
        )
    }

    @Test
    @VerifiesPort("Checksum16")
    fun `checksum16 matches the stored global save info checksum`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("sram_boot")
        assumeTrue(run.hasSram, "sram_boot has no SRAM dumps")
        val names = run.dumps.keys.filter { File(ref.root, "sram_boot/$it.sram.bin").isFile }
        val first = names.indexOfFirst { hasMarker(run.sram(it)) }
        assumeTrue(first >= 0, "no initialised SRAM header in sram_boot")
        // Before initialisation the SRAM is erased (0xFF): the checksum of that is not 0xFFFF.
        val erased = SaveChecksums.checksum16(run.sram(names[0]), 0, L.HEADER_CHECKSUM_SIZE)
        assertNotEquals(0xFFFF, erased)
        for (name in names.drop(first)) {
            val sram = run.sram(name)
            assertEquals(L.SRAM_SIZE, sram.size)
            assertEquals(L.SAVEMAGIC32, u32(sram, L.HEADER_MAGIC32), name)
            assertEquals(L.SAVEMAGIC16, u16(sram, L.HEADER_MAGIC16), name)
            assertEquals(
                u16(sram, L.HEADER_CHECKSUM),
                SaveChecksums.checksum16(sram, L.HEADER_OFFSET, L.HEADER_CHECKSUM_SIZE),
                "header checksum at $name",
            )
        }
    }

    @Test
    @VerifiesPort("Checksum32", "ComputeChecksum32")
    fun `checksum32 matches the game block info after new game`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_newgame")
        assumeTrue(run.hasSram, "ram_trace_newgame has no SRAM dumps")
        val last = run.dumps.keys.last {
            File(ref.root, "ram_trace_newgame/$it.sram.bin").isFile
        }
        val sram = run.sram(last)
        val info = L.blockInfoOffset(L.SAVE_ID_GAME0)
        assertEquals(L.SAVEMAGIC32, u32(sram, info + L.INFO_MAGIC32))
        assertEquals(L.SAVEMAGIC16, u16(sram, info + L.INFO_MAGIC16))
        assertEquals(L.SAVEBLOCK_KIND_GAME, u8(sram, info + L.INFO_KIND))
        assertEquals(L.GAME0_OFFSET, u16(sram, info + L.INFO_OFFSET))
        assertEquals(L.GAME_SIZE, u16(sram, info + L.INFO_SIZE))
        val stored = u32(sram, info + L.INFO_CHECKSUM32)
        assertEquals(stored, SaveChecksums.checksum32(sram, L.GAME0_OFFSET, L.GAME_SIZE))
        assertEquals(stored, SaveChecksums.computeChecksum32(sram, L.GAME0_OFFSET, L.GAME_SIZE))
    }

    @Test
    @VerifiesPort("Checksum32", "ComputeChecksum32")
    fun `checksum32 matches the suspend block info in first phase`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_first_phase")
        assumeTrue(run.hasSram, "ram_trace_first_phase has no SRAM dumps")
        val last = run.dumps.keys.last {
            File(ref.root, "ram_trace_first_phase/$it.sram.bin").isFile
        }
        val sram = run.sram(last)
        // The first suspend save is written to the alternate slot (SAVE_ID_SUSPEND_ALT).
        val info = L.blockInfoOffset(L.SAVE_ID_SUSPEND_ALT)
        assertEquals(L.SAVEMAGIC32, u32(sram, info + L.INFO_MAGIC32))
        assertEquals(L.SAVEMAGIC16, u16(sram, info + L.INFO_MAGIC16))
        assertEquals(L.SAVEBLOCK_KIND_SUSPEND, u8(sram, info + L.INFO_KIND))
        assertEquals(L.SUSPEND1_OFFSET, u16(sram, info + L.INFO_OFFSET))
        assertEquals(L.SUSPEND_SIZE, u16(sram, info + L.INFO_SIZE))
        val stored = u32(sram, info + L.INFO_CHECKSUM32)
        assertTrue(stored != 0)
        assertEquals(stored, SaveChecksums.checksum32(sram, L.SUSPEND1_OFFSET, L.SUSPEND_SIZE))
        assertEquals(
            stored, SaveChecksums.computeChecksum32(sram, L.SUSPEND1_OFFSET, L.SUSPEND_SIZE),
        )
        // The game slot written earlier is still valid in the same image.
        val game = L.blockInfoOffset(L.SAVE_ID_GAME0)
        assertEquals(
            u32(sram, game + L.INFO_CHECKSUM32),
            SaveChecksums.checksum32(sram, L.GAME0_OFFSET, L.GAME_SIZE),
        )
    }

    private fun hasMarker(sram: ByteArray): Boolean =
        String(sram, 0, L.SAVE_MARKER.length, Charsets.US_ASCII) == L.SAVE_MARKER

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    private fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

    private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
}
