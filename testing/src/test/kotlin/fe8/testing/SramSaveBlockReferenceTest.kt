package fe8.testing

import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Save block info routines on the SRAM dumps of mGBA reference runs. */
class SramSaveBlockReferenceTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun lib(run: String, dump: String): BmSaveLib {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run(run)
        assumeTrue(r.hasSram, "$run has no SRAM dumps")
        val sram = CartSram()
        sram.copyIn(r.sram(dump))
        return BmSaveLib(sram).also { it.sramInit() }
    }

    private fun image(l: BmSaveLib) = ByteArray(L.SRAM_SIZE).also { l.sram.copyOut(it) }

    @Test
    @VerifiesPort(
        "ReadSaveBlockInfo", "IsSaveValid", "SramChecksum32", "VerifySaveBlockChecksum",
        "PopulateSaveBlockChecksum", "WriteSaveBlockInfo", "GetSaveWriteAddr", "GetSaveReadAddr",
    )
    fun `newgame sram has a valid game slot 0 and block info is reproduced`() {
        val dump = "s01230"
        val lib = lib("ram_trace_newgame", dump)
        val orig = ref.run("ram_trace_newgame").sram(dump)
        assertTrue(lib.isSaveValid(0))
        for (i in 1..3) assertFalse(lib.isSaveValid(i), "slot $i")
        assertEquals(L.GAME0_OFFSET, lib.getSaveWriteAddr(0))
        assertEquals(L.GAME0_OFFSET, lib.getSaveReadAddr(0))

        val chunk = ByteArray(L.BLOCK_INFO_SIZE)
        System.arraycopy(orig, L.blockInfoOffset(0), chunk, 0, L.BLOCK_INFO_SIZE)
        // Clear what WriteSaveBlockInfo derives; magic32 and kind are the caller's.
        for (o in L.INFO_MAGIC16..0x0F) if (o != L.INFO_KIND) chunk[o] = 0
        // Erase slot 0's entry in SRAM too, so only WriteSaveBlockInfo can restore it.
        val wiped = image(lib)
        val at = L.blockInfoOffset(0)
        wiped.fill(0xFF.toByte(), at, at + L.BLOCK_INFO_SIZE)
        lib.sram.copyIn(wiped)
        assertFalse(lib.isSaveValid(0), "slot 0 entry not erased")
        lib.writeSaveBlockInfo(chunk, L.SAVE_ID_GAME0)
        val got = image(lib)
        assertArrayEquals(
            orig.copyOfRange(L.BLOCK_INFO_OFFSET, L.BLOCK_INFO_OFFSET + L.BLOCK_INFO_TABLE_SIZE),
            got.copyOfRange(L.BLOCK_INFO_OFFSET, L.BLOCK_INFO_OFFSET + L.BLOCK_INFO_TABLE_SIZE),
        )
    }

    @Test
    fun `loading a valid non-xmap suspend save keeps it`() {
        val run = "ram_trace_first_phase"
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run(run)
        assumeTrue(r.hasSram, "$run has no SRAM dumps")
        val dump = r.dumps.keys.last { java.io.File(ref.root, "$run/$it.sram.bin").isFile }
        val lib = lib(run, dump)
        val before = image(lib)
        lib.loadAndVerifySuspendSave()
        assertArrayEquals(before, image(lib))
    }

    @Test
    @VerifiesPort("IsSaveValid", "InvalidateSuspendSave", "EraseSaveBlockInfo")
    fun `first phase sram has a valid suspend save that can be invalidated`() {
        val run = "ram_trace_first_phase"
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run(run)
        assumeTrue(r.hasSram, "$run has no SRAM dumps")
        val dump = r.dumps.keys.last { java.io.File(ref.root, "$run/$it.sram.bin").isFile }
        val lib = lib(run, dump)
        assertTrue(lib.isSaveValid(L.SAVE_ID_SUSPEND) || lib.isSaveValid(L.SAVE_ID_SUSPEND_ALT))
        lib.invalidateSuspendSave(L.SAVE_ID_SUSPEND)
        val g = image(lib)
        for (i in intArrayOf(L.SAVE_ID_SUSPEND, L.SAVE_ID_SUSPEND_ALT)) {
            val o = L.blockInfoOffset(i)
            assertEquals(0xFF, g[o + L.INFO_KIND].toInt() and 0xFF)
            assertEquals(0, (g[o + L.INFO_SIZE].toInt() and 0xFF) or (g[o + L.INFO_SIZE + 1].toInt() shl 8))
            assertEquals(0, (g[o + L.INFO_MAGIC16].toInt() and 0xFF) or (g[o + L.INFO_MAGIC16 + 1].toInt() shl 8))
            assertFalse(lib.isSaveValid(i))
        }
        lib.eraseSaveBlockInfo(L.SAVE_ID_GAME1)
        assertEquals(0xFF, image(lib)[L.blockInfoOffset(1) + 5].toInt() and 0xFF)
    }

    @Test
    fun `last game save id round trips on a fresh boot`() {
        val sram = CartSram()
        val lib = BmSaveLib(sram)
        lib.boot()
        assertEquals(0, lib.readLastGameSaveId())
        lib.writeLastGameSaveId(2)
        assertEquals(2, lib.readLastGameSaveId())
    }
}
