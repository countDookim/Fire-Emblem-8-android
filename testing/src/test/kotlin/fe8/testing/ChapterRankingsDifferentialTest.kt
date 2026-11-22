package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.save.ChapterRankings
import fe8.game.save.ChapterStats
import fe8.game.save.PidStats
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `PC-3c` (`r09443 -> f09444`) of `ram_trace_prologue_end` (`PC-cap1`): `ComputeChapterRankings`
 * and the chapter rank functions of `src/gamerankings.c`, run on `gPlaySt`, `gChapterStats`
 * (as at f9444, after `RegisterChapterTimeAndTurnCount`), `gPidStatsData` and the units of r09443
 * and compared with `gPlaySt` at f9444. `GetPartyTotalGoldValue` (PC-3d) is fed the total the
 * original left in `unk_30.total_gold`. Every rank is 4 at f9444, so no function is `Ported`:
 * break-checks showed most mutations of thresholds, operators and clamps survive.
 */
class ChapterRankingsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Harness(
        val rankings: ChapterRankings,
        val playSt: PlaySt,
        val want: PlaySt,
        val wantBytes: ByteArray,
        val before: PlaySt,
    )

    private fun harness(): Harness {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(RUN_PROLOGUE_END)
        val ew9443 = run.region("r09443", "ewram")
        val ew9444 = run.region("f09444", "ewram")
        val playSt = PlaySt().apply { decode(ew9443, ADDR_PLAY_ST - EWRAM_BASE) }
        val before = PlaySt().apply { decode(ew9443, ADDR_PLAY_ST - EWRAM_BASE) }
        val want = PlaySt().apply { decode(ew9444, ADDR_PLAY_ST - EWRAM_BASE) }
        val chOff = ADDR_CHAPTER_STATS - EWRAM_BASE
        val stats = ChapterStats(ew9444.copyOfRange(chOff, chOff + ChapterStats.SIZE))
        val pidOff = ADDR_PID_STATS - EWRAM_BASE
        val pid = PidStats(rom, ew9443.copyOfRange(pidOff, pidOff + PidStats.SIZE))
        val units = UnitTable(rom).apply { loadFrom(ew9443) }
        val rankings = ChapterRankings(
            playSt, stats, pid, units, ChapterData(rom),
            getPartyTotalGoldValue = { want.totalGold },
        )
        val psOff = ADDR_PLAY_ST - EWRAM_BASE
        return Harness(
            rankings, playSt, want, ew9444.copyOfRange(psOff, psOff + PlaySt.SIZE), before,
        )
    }

    private fun PlaySt.ranking(): ByteArray =
        ByteArray(PlaySt.SIZE).also { encode(it, 0) }.copyOfRange(0x2C, 0x40)

    @Test
    fun `f9443 to f9444 ComputeChapterRankings fills the ranking fields of gPlaySt`() {
        val h = harness()
        h.rankings.computeChapterRankings()
        assertArrayEquals(
            h.wantBytes.copyOfRange(0x2C, 0x40),
            h.playSt.ranking(),
            "gPlaySt 0x2C..0x3F at f9444",
        )
        assertEquals(h.want.tacticsRank, h.playSt.tacticsRank)
        assertEquals(h.want.survivalRank, h.playSt.survivalRank)
        assertEquals(h.want.expRank, h.playSt.expRank)
    }

    @Test
    fun `f9444 individual ranks and their side effects match gPlaySt`() {
        val h = harness()
        assertEquals(h.want.tacticsRank, h.rankings.getChapterTacticsRank())
        assertEquals(h.want.survivalRank, h.rankings.getChapterSurvivalRank())
        assertEquals(h.want.expRank, h.rankings.getChapterExpRank())
        assertEquals(h.want.unk30Unk82, h.playSt.unk30Unk82)
        assertEquals(h.want.combatRank, h.rankings.getChapterCombatRank())
        assertEquals(h.want.unk30Unk400, h.playSt.unk30Unk400)
        assertEquals(h.want.unk30Unk414, h.playSt.unk30Unk414)
        assertEquals(h.want.unk30Unk81, h.playSt.unk30Unk81)
        assertEquals(h.want.fundsRank, h.rankings.getChapterFundsRank())
        assertEquals(h.want.totalGold, h.playSt.totalGold)
        assertEquals(
            h.want.unk2C04 - h.before.unk2C04,
            h.rankings.getOverallRank(
                h.want.tacticsRank, h.want.survivalRank, h.want.fundsRank,
                h.want.expRank, h.want.combatRank,
            ),
        )
    }

    private companion object {
        const val RUN_PROLOGUE_END = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val ADDR_PLAY_ST = 0x0202BCF0
        const val ADDR_CHAPTER_STATS = 0x0203ECF4
        const val ADDR_PID_STATS = 0x0203E894
    }
}
