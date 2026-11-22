package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmContainer
import fe8.game.bm.ItemInfo
import fe8.game.bm.PartyGoldValue
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `PC-3d` of `ram_trace_prologue_end` (`PC-cap1`): `GetPartyTotalGoldValue`, `GetConvoyItemCostSum`
 * and `GetUnitItemCostSum` (`src/bmitem.c`) run on `gPlaySt`, the units and `gConvoyItemArray` of
 * r09443 and compared with the `unk_30.total_gold` that `GetChapterFundsRank` stored in `gPlaySt`
 * at f9444.
 */
class PartyGoldValueDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Harness(val value: PartyGoldValue, val playSt: PlaySt, val want: PlaySt)

    private fun harness(): Harness {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(RUN_PROLOGUE_END)
        val ew9443 = run.region("r09443", "ewram")
        val ew9444 = run.region("f09444", "ewram")
        val playSt = PlaySt().apply { decode(ew9443, ADDR_PLAY_ST - EWRAM_BASE) }
        val want = PlaySt().apply { decode(ew9444, ADDR_PLAY_ST - EWRAM_BASE) }
        val units = UnitTable(rom).apply { loadFrom(ew9443) }
        val convoyOff = ADDR_CONVOY - EWRAM_BASE
        val convoy = BmContainer(ew9443.copyOfRange(convoyOff, convoyOff + CONVOY_BYTES), playSt)
        return Harness(PartyGoldValue(ItemInfo(rom), units, convoy, playSt), playSt, want)
    }

    @Test
    fun `f9443 to f9444 party total gold value matches the stored total_gold`() {
        val h = harness()
        val convoy = h.value.getConvoyItemCostSum()
        val unitItems = h.value.getUnitItemCostSum()
        println("gold=${h.playSt.partyGoldAmount} convoy=$convoy units=$unitItems " +
            "want=${h.want.totalGold} chapter=${h.playSt.chapterIndex}")
        assertEquals(h.want.totalGold, h.value.getPartyTotalGoldValue())
        assertTrue(h.want.totalGold > 0, "capture must make the sum observable")
    }

    private companion object {
        const val RUN_PROLOGUE_END = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val ADDR_PLAY_ST = 0x0202BCF0
        const val ADDR_CONVOY = 0x0203A81C
        const val CONVOY_BYTES = 200
    }
}
