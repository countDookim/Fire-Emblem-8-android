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
        assertTrue(r.hasSram, "$run has no SRAM dumps")
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
    @VerifiesPort("IsSaveValid", "InvalidateSuspendSave")
    fun `invalidating the suspend save writes the entries the game wrote`() {
        // The game's own InvalidateSuspendSave (WriteNewGameSave, f1130): entries 3 and 4 go from
        // all-0xFF (s01128) to the invalid entry (s01130).
        val game = ref.run("ram_trace_newgame").sram("s01130")
        val cases = listOf("ram_trace_newgame" to "s01128", "ram_trace_first_phase" to "s19316")
        for ((run, dump) in cases) {
            val lib = lib(run, dump)
            val valid = lib.isSaveValid(L.SAVE_ID_SUSPEND_ALT)
            // first_phase s19316 holds a valid suspend save in entry 4; newgame has none.
            assertEquals(run == "ram_trace_first_phase", valid, "$run $dump entry 4 before")
            val before = image(lib)
            lib.invalidateSuspendSave(L.SAVE_ID_SUSPEND)
            val after = image(lib)
            for (i in intArrayOf(L.SAVE_ID_SUSPEND, L.SAVE_ID_SUSPEND_ALT)) {
                assertFalse(lib.isSaveValid(i), "$run entry $i")
                assertInvalidEntry(game, after, i)
            }
            // Nothing else changes (game save 0, header, aux blocks).
            val at = L.blockInfoOffset(L.SAVE_ID_SUSPEND)
            val end = L.blockInfoOffset(L.SAVE_ID_SUSPEND_ALT) + L.BLOCK_INFO_SIZE
            for (i in before.indices) {
                if (i in at until end) continue
                assertEquals(before[i], after[i], "$run byte 0x${i.toString(16)}")
            }
        }
    }

    /**
     * Compares entry [i] of [got] with the game's invalidated entry in [want]. Excluded: magic32
     * (stack garbage of the C `chunk` local, 1 in the dump, 0 here) and the exact checksum32:
     * `Checksum32` of the size-0 block still reads one halfword of `gGenericBuffer` (1F1F in the
     * dump, whatever this library's buffer held), so only its shape (low half == high half, as
     * sum == xor for one halfword) is checked.
     */
    private fun assertInvalidEntry(want: ByteArray, got: ByteArray, i: Int) {
        val o = L.blockInfoOffset(i)
        for (k in L.INFO_MAGIC16 until L.INFO_CHECKSUM32) {
            assertEquals(want[o + k], got[o + k], "entry $i byte $k")
        }
        assertEquals(0xFF, got[o + L.INFO_KIND].toInt() and 0xFF)
        for (k in 0 until 2) {
            assertEquals(got[o + L.INFO_CHECKSUM32 + k], got[o + L.INFO_CHECKSUM32 + 2 + k], "sum")
            assertEquals(want[o + L.INFO_CHECKSUM32 + k], want[o + L.INFO_CHECKSUM32 + 2 + k])
        }
    }

    @Test
    @VerifiesPort("IsSaveValid", "EraseSaveBlockInfo")
    fun `erasing a valid game slot leaves the 0xFF entry the dump has for unused slots`() {
        val lib = lib("ram_trace_newgame", "s01230")
        val dump = ref.run("ram_trace_newgame").sram("s01230")
        assertTrue(lib.isSaveValid(L.SAVE_ID_GAME0))
        val before = image(lib)
        lib.eraseSaveBlockInfo(7) // index >= BLOCK_INFO_COUNT: no write
        assertArrayEquals(before, image(lib))
        lib.eraseSaveBlockInfo(L.SAVE_ID_GAME0)
        assertFalse(lib.isSaveValid(L.SAVE_ID_GAME0))
        val after = image(lib)
        val e1 = L.blockInfoOffset(1)
        val unused = dump.copyOfRange(e1, e1 + L.BLOCK_INFO_SIZE)
        assertTrue(unused.all { it == 0xFF.toByte() })
        val at = L.blockInfoOffset(L.SAVE_ID_GAME0)
        assertArrayEquals(unused, after.copyOfRange(at, at + L.BLOCK_INFO_SIZE))
        for (i in before.indices) {
            if (i in at until at + L.BLOCK_INFO_SIZE) continue
            assertEquals(before[i], after[i], "byte 0x${i.toString(16)}")
        }
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
