package fe8.testing

import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** [PlaySt] and [BmSt] decode and re-encode every reference RAM dump byte-exactly. */
class PlayStBmStCodecTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun roundTrip(run: String): Int {
        val r = ref.run(run)
        val playSt = PlaySt()
        val bmSt = BmSt()
        var checks = 0
        for ((dump, _) in r.dumps) {
            if (!java.io.File(ref.root, "$run/$dump.ewram.bin").isFile) continue
            val ewram = r.region(dump, "ewram")
            val ps = PLAY_ST - EWRAM
            val bm = BM_ST - EWRAM
            val copy = ewram.copyOf()
            copy.fill(0, ps, ps + PlaySt.SIZE)
            copy.fill(0, bm, bm + BmSt.SIZE)
            playSt.decode(ewram, ps)
            playSt.encode(copy, ps)
            bmSt.decode(ewram, bm)
            bmSt.encode(copy, bm)
            assertArrayEquals(
                ewram.copyOfRange(ps, ps + PlaySt.SIZE),
                copy.copyOfRange(ps, ps + PlaySt.SIZE),
                "gPlaySt at $run/$dump",
            )
            assertArrayEquals(
                ewram.copyOfRange(bm, bm + BmSt.SIZE),
                copy.copyOfRange(bm, bm + BmSt.SIZE),
                "gBmSt at $run/$dump",
            )
            checks++
        }
        return checks
    }

    @Test
    fun `newgame dumps round-trip`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assertTrue(roundTrip("ram_trace_newgame") > 0)
    }

    @Test
    fun `first phase dumps round-trip`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assertTrue(roundTrip("ram_trace_first_phase") > 0)
    }

    @Test
    fun `first phase last dump holds chapter 0 turn 1 player phase`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run("ram_trace_first_phase")
        val last = r.dumps.keys.last {
            java.io.File(ref.root, "ram_trace_first_phase/$it.ewram.bin").isFile
        }
        val playSt = PlaySt()
        playSt.decode(r.region(last, "ewram"), PLAY_ST - EWRAM)
        assertEquals(0, playSt.chapterIndex, "chapterIndex at $last")
        assertEquals(0, playSt.faction, "faction at $last")
        assertEquals(1, playSt.chapterTurnNumber, "chapterTurnNumber at $last")
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
    }
}
