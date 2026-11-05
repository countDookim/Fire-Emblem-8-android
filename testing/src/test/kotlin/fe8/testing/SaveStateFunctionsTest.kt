package fe8.testing

import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PlaySt
import fe8.game.bm.initPlayConfig
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Plain unit tests of the transcribed (not yet ground-truth verified) save state functions. */
class SaveStateFunctionsTest {
    @Test
    fun `fresh sram has no completions and id 1 is the first free id`() {
        val lib = BmSaveLib(CartSram())
        lib.boot()
        assertEquals(0, lib.getGlobalCompletionCount())
        assertTrue(lib.isPlaythroughIdUnique(1))
        assertEquals(1, lib.getNewPlaythroughId())
    }

    @Test
    fun `cleared playthroughs are counted and their ids are taken`() {
        val sram = CartSram()
        val lib = BmSaveLib(sram)
        lib.boot()
        sram.writeByte(L.HEADER_CLEARED_PLAYTHROUGHS, 1)
        sram.writeByte(L.HEADER_CLEARED_PLAYTHROUGHS + 5, 2)
        val header = ByteArray(L.HEADER_SIZE).also { sram.read(0, it, 0, L.HEADER_SIZE) }
        lib.writeGlobalSaveInfo(header)
        assertEquals(2, lib.getGlobalCompletionCount())
        assertFalse(lib.isPlaythroughIdUnique(1))
        assertEquals(3, lib.getNewPlaythroughId())
    }

    @Test
    fun `first phase sram completion count and play state read`() {
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run("ram_trace_first_phase")
        assumeTrue(r.hasSram, "no SRAM dumps")
        val dump = r.dumps.keys.last { java.io.File(ref.root, "ram_trace_first_phase/$it.sram.bin").isFile }
        val sram = CartSram().also { it.copyIn(r.sram(dump)) }
        val lib = BmSaveLib(sram).also { it.sramInit() }
        assertEquals(0, lib.getGlobalCompletionCount())
        val ps = PlaySt()
        lib.readGameSavePlaySt(L.SAVE_ID_SUSPEND, ps)
        assertTrue(ps.chapterIndex != 0x7F)
    }

    @Test
    fun `init play config sets the defaults`() {
        val ps = PlaySt()
        ps.partyGoldAmount = 5
        ps.chapterIndex = 9
        initPlayConfig(ps, true, 1)
        assertEquals(0, ps.partyGoldAmount)
        assertEquals(0, ps.chapterIndex)
        assertEquals(PLAY_FLAG_HARD, ps.chapterStateBits)
        assertEquals(1, ps.controller)
        assertEquals(1, ps.textSpeed)
        initPlayConfig(ps, false, 0)
        assertEquals(0, ps.chapterStateBits)
    }
}
