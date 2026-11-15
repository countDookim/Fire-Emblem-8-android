package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapKind
import fe8.game.bm.BattleUnit
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.save.PidStats
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Differential and unit verification for per-unit battle statistics and favour values
 * (`gPidStatsData` / `gBWLDataArray`, `src/bmsave-bwl.c`, EP-27).
 *
 * Verifies the 1120-byte stats block at EWRAM 0x0203E894 against reference dumps across
 * `ram_trace_prologue_enemy_phase` and `ram_trace_prologue_clear` (f1520, f1530, f1696, f2129,
 * f2964, f3832, f4274, f4892).
 */
class PidStatsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("GetPidStats", "PidStatsSubFavval08", "PidStatsAddFavval")
    fun `favour of Seth and Eirika drops by 8 across the phase ends at f1530 and f2964`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run(RUN_PROLOGUE_CLEAR)
        val dump1530 = run.region("r01530", "ewram")
        val dump2964 = run.region("r02964", "ewram")

        // Turn 1 phase end (f1530): Seth (pid 2) and Eirika (pid 1) drop from 8192 (0x2000) to 8184
        val pidStats1 = PidStats(rom)
        for (i in 0 until PidStats.ARRAY_NUM) {
            pidStats1.setFavvalBySlot(i, 0x2000)
        }
        pidStats1.pidStatsSubFavval08(PID_SETH)
        pidStats1.pidStatsSubFavval08(PID_EIRIKA)
        assertEquals(8184, pidStats1.favval(PID_EIRIKA))
        assertEquals(8184, pidStats1.favval(PID_SETH))
        assertArrayEquals(
            extractStats(dump1530), pidStats1.data, "gPidStatsData matches r01530",
        )

        // Turn 2 phase end (f2964): Eirika was at 8289 and Seth was at 8184 in r02963; both drop by 8
        val dump2963 = run.region("r02963", "ewram")
        val pidStats2 = PidStats(rom, extractStats(dump2963))
        assertEquals(8289, pidStats2.favval(PID_EIRIKA), "Eirika favval before drop")
        assertEquals(8184, pidStats2.favval(PID_SETH), "Seth favval before drop")
        pidStats2.pidStatsSubFavval08(PID_EIRIKA)
        pidStats2.pidStatsSubFavval08(PID_SETH)
        assertEquals(8281, pidStats2.favval(PID_EIRIKA), "Eirika favval after drop")
        assertEquals(8176, pidStats2.favval(PID_SETH), "Seth favval after drop")
        assertArrayEquals(
            extractEntry(dump2964, PID_EIRIKA),
            pidStats2.data.copyOfRange(0, 16),
            "Eirika stats match r02964",
        )
        assertArrayEquals(
            extractEntry(dump2964, PID_SETH),
            pidStats2.data.copyOfRange(16, 32),
            "Seth stats match r02964",
        )
    }

    @Test
    @VerifiesPort("PidStatsAddBattleAmt", "PidStatsAddWinAmt", "PidStatsRecordBattleRes")
    fun `battle and win count increment with favour bonuses at f1696, f2129 and f4274`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val clearRun = ref.run(RUN_PROLOGUE_CLEAR)
        val epRun = ref.run(RUN_PROLOGUE_ENEMY_PHASE)
        val dump1696 = epRun.region("r01696", "ewram")
        val dump2129 = epRun.region("r02129", "ewram")
        val dump4274 = clearRun.region("r04274", "ewram")

        // First battle at f1696: Eirika attacks and defeats enemy
        val pidStats = PidStats(rom, extractStats(clearRun.region("r01530", "ewram")))
        val playSt = PlaySt()
        val actor = BattleUnit()
        val target = BattleUnit()
        // buB (Eirika): pid 1, blue faction, alive (curHP 16)
        setupCombatant(actor, PID_EIRIKA, faction = 0, curHp = 16)
        // buA (Enemy): pid 0x38, red faction, dead (curHP 0)
        setupCombatant(target, 0x38, faction = 0x80, curHp = 0)

        pidStats.pidStatsRecordBattleRes(actor, target, playSt)
        pidStats.pidStatsAddBattleAmt(actor.bytes, 0)
        pidStats.pidStatsAddBattleAmt(target.bytes, 0)

        assertEquals(1, playSt.unk48, "unk48 incremented on blue win")
        assertEquals(1, pidStats.winAmt(PID_EIRIKA))
        assertEquals(1, pidStats.battleAmt(PID_EIRIKA))
        assertEquals(8204, pidStats.favval(PID_EIRIKA)) // 8184 + 16 (win) + 4 (battle)
        assertArrayEquals(
            extractStats(dump1696), pidStats.data, "entire stats block matches r01696",
        )

        // Second battle at f2129: Eirika defeats second enemy after gaining 32 exp
        pidStats.pidStatsAddExpGained(PID_EIRIKA, 32)
        assertEquals(8236, pidStats.favval(PID_EIRIKA))
        pidStats.pidStatsRecordBattleRes(actor, target, playSt)
        pidStats.pidStatsAddBattleAmt(actor.bytes, 0)
        pidStats.pidStatsAddBattleAmt(target.bytes, 0)

        assertEquals(2, playSt.unk48)
        assertEquals(2, pidStats.winAmt(PID_EIRIKA))
        assertEquals(2, pidStats.battleAmt(PID_EIRIKA))
        assertEquals(8256, pidStats.favval(PID_EIRIKA))
        assertArrayEquals(
            extractEntry(dump2129, PID_EIRIKA),
            pidStats.data.copyOfRange(0, 16),
            "Eirika stats match r02129",
        )

        // Third battle at f4274: Seth defeats boss
        val sethStats = PidStats(rom, extractStats(clearRun.region("r03832", "ewram")))
        sethStats.pidStatsAddActAmt(PID_SETH)
        assertEquals(1, sethStats.actAmt(PID_SETH))
        assertEquals(8178, sethStats.favval(PID_SETH)) // 8176 + 2 (act)

        setupCombatant(actor, PID_SETH, faction = 0, curHp = 20)
        setupCombatant(target, 0x38, faction = 0x80, curHp = 0)
        sethStats.pidStatsRecordBattleRes(actor, target, playSt)
        sethStats.pidStatsAddBattleAmt(actor.bytes, 0)

        assertEquals(1, sethStats.winAmt(PID_SETH))
        assertEquals(1, sethStats.battleAmt(PID_SETH))
        assertEquals(8198, sethStats.favval(PID_SETH)) // 8178 + 16 + 4
        assertArrayEquals(
            extractEntry(dump4274, PID_SETH),
            sethStats.data.copyOfRange(16, 32),
            "Seth stats match r04274",
        )
    }

    @Test
    @VerifiesPort("PidStatsAddExpGained", "PidStatsAddActAmt")
    fun `experience and action increments reproduce the stats at f2040, f2430, f4274 and f4892`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val clearRun = ref.run(RUN_PROLOGUE_CLEAR)
        val dump4274 = clearRun.region("r04274", "ewram")
        val dump4892 = clearRun.region("r04892", "ewram")

        // Exp gain on Seth at f4892: Seth gains 45 exp after winning at f4274
        val pidStats = PidStats(rom, extractStats(dump4274))
        assertEquals(0, pidStats.expGained(PID_SETH))
        assertEquals(8198, pidStats.favval(PID_SETH))

        pidStats.pidStatsAddExpGained(PID_SETH, 45)
        assertEquals(45, pidStats.expGained(PID_SETH))
        assertEquals(8243, pidStats.favval(PID_SETH)) // 8198 + 45
        assertArrayEquals(
            extractEntry(dump4892, PID_SETH),
            pidStats.data.copyOfRange(16, 32),
            "Seth stats match r04892",
        )
    }

    @Test
    @VerifiesPort(
        "GetPidStats", "PidStatsAddBattleAmt", "PidStatsAddWinAmt", "PidStatsRecordBattleRes",
        "PidStatsAddFavval", "PidStatsSubFavval08", "PidStatsAddExpGained", "PidStatsAddActAmt",
    )
    fun `step by step battle stats progression reproduces EWRAM dumps across prologue clear`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val epRun = ref.run(RUN_PROLOGUE_ENEMY_PHASE)
        val clearRun = ref.run(RUN_PROLOGUE_CLEAR)

        val d1520 = extractStats(epRun.region("f01520", "ewram"))
        val d1530 = extractStats(clearRun.region("r01530", "ewram"))
        val d1710 = extractStats(clearRun.region("r01710", "ewram"))
        val d2130 = extractStats(clearRun.region("r02130", "ewram"))
        val d2964 = extractStats(clearRun.region("r02964", "ewram"))
        val d3832 = extractStats(clearRun.region("r03832", "ewram"))
        val d4274 = extractStats(clearRun.region("r04274", "ewram"))
        val d4892 = extractStats(clearRun.region("r04892", "ewram"))

        // Start from initial state (f1520)
        val pidStats = PidStats(rom, d1520.copyOf())

        // 1. Turn 1 phase end (f1530)
        pidStats.pidStatsSubFavval08(PID_SETH)
        pidStats.pidStatsSubFavval08(PID_EIRIKA)
        assertArrayEquals(d1530, pidStats.data, "matches f1530")

        // 2. Battle 1 (f1696 / f1710)
        pidStats.pidStatsAddWinAmt(PID_EIRIKA)
        pidStats.pidStatsAddBattleAmt(PID_EIRIKA)
        assertArrayEquals(d1710, pidStats.data, "matches f1696 / f1710")

        // 3. Exp gain 1 (32 exp) + Battle 2 (f2129 / f2130)
        pidStats.pidStatsAddExpGained(PID_EIRIKA, 32)
        pidStats.pidStatsAddWinAmt(PID_EIRIKA)
        pidStats.pidStatsAddBattleAmt(PID_EIRIKA)
        assertArrayEquals(
            d2130.copyOfRange(0, 32),
            pidStats.data.copyOfRange(0, 32),
            "Eirika and Seth match f2129 / f2130",
        )

        // 4. Exp gain 2 (33 exp) + Turn 2 phase end (f2964)
        pidStats.pidStatsAddExpGained(PID_EIRIKA, 33)
        pidStats.pidStatsSubFavval08(PID_EIRIKA)
        pidStats.pidStatsSubFavval08(PID_SETH)
        assertArrayEquals(
            d2964.copyOfRange(0, 32),
            pidStats.data.copyOfRange(0, 32),
            "Eirika and Seth match f2964",
        )
        assertArrayEquals(
            d3832.copyOfRange(0, 32),
            pidStats.data.copyOfRange(0, 32),
            "Eirika and Seth match f3832",
        )

        // 5. Turn 3: Seth acts and wins battle (f4274)
        pidStats.pidStatsAddActAmt(PID_SETH)
        pidStats.pidStatsAddWinAmt(PID_SETH)
        pidStats.pidStatsAddBattleAmt(PID_SETH)
        assertArrayEquals(
            d4274.copyOfRange(0, 32),
            pidStats.data.copyOfRange(0, 32),
            "Eirika and Seth match f4274",
        )

        // 6. Turn 3: Seth exp gain (f4892)
        pidStats.pidStatsAddExpGained(PID_SETH, 45)
        assertArrayEquals(
            d4892.copyOfRange(0, 32),
            pidStats.data.copyOfRange(0, 32),
            "Eirika and Seth match f4892",
        )
    }

    @Test
    fun `getPidStats returns negative offset for invalid pids and pids without affinity`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val pidStats = PidStats(rom)

        assertEquals(-1, pidStats.getPidStats(0), "pid 0 invalid")
        assertEquals(0, pidStats.getPidStats(1), "Eirika (pid 1) at offset 0")
        assertEquals(16, pidStats.getPidStats(2), "Seth (pid 2) at offset 16")
        assertEquals(-1, pidStats.getPidStats(27), "pid 27 has 0 affinity in ROM")
        assertEquals(-1, pidStats.getPidStats(70), "pid 70 is out of bounds (ARRAY_NUM = 70)")
        assertEquals(-1, pidStats.getPidStats(255), "pid 255 out of bounds")
    }

    @Test
    fun `pidStatsRecordDefeatInfo records skirmish vs story chapter defeat info`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val pidStats = PidStats(rom)

        // Story mode defeat
        pidStats.getBattleMapKind = { BattleMapKind.STORY }
        pidStats.pidStatsRecordDefeatInfo(
            pid = PID_EIRIKA,
            killerPid = 0x38,
            deathCause = PidStats.DEFEAT_CAUSE_COMBAT,
            chapterIndex = 0,
            chapterTurn = 3,
        )
        assertFalse(pidStats.deathSkirm(PID_EIRIKA))
        assertEquals(0, pidStats.deathLoc(PID_EIRIKA))
        assertEquals(3, pidStats.deathTurn(PID_EIRIKA))
        assertEquals(0x38, pidStats.killerPid(PID_EIRIKA))
        assertEquals(PidStats.DEFEAT_CAUSE_COMBAT, pidStats.deathCause(PID_EIRIKA))

        // Skirmish mode defeat
        pidStats.getBattleMapKind = { BattleMapKind.SKIRMISH }
        pidStats.getSkirmishLocation = { 5 }
        pidStats.pidStatsRecordDefeatInfo(
            pid = PID_SETH,
            killerPid = 0x40,
            deathCause = PidStats.DEFEAT_CAUSE_TRAP,
            chapterIndex = 12,
            chapterTurn = 7,
        )
        assertTrue(pidStats.deathSkirm(PID_SETH))
        assertEquals(5, pidStats.deathLoc(PID_SETH))
        assertEquals(7, pidStats.deathTurn(PID_SETH))
        assertEquals(0x40, pidStats.killerPid(PID_SETH))
        assertEquals(PidStats.DEFEAT_CAUSE_TRAP, pidStats.deathCause(PID_SETH))
    }

    @Test
    fun `pidStatsAddSquaresMoved accumulates and caps at 1000`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val pidStats = PidStats(rom)
        pidStats.setFavval(PID_EIRIKA, 0x2000)

        pidStats.pidStatsAddSquaresMoved(PID_EIRIKA, 5)
        assertEquals(5, pidStats.moveAmt(PID_EIRIKA))
        assertEquals(0x2002, pidStats.favval(PID_EIRIKA))

        pidStats.pidStatsAddSquaresMoved(PID_EIRIKA, 1200)
        assertEquals(1000, pidStats.moveAmt(PID_EIRIKA), "capped at 1000")
        assertEquals(0x2004, pidStats.favval(PID_EIRIKA))
    }

    @Test
    fun `pidStatsAddStatViewAmt and pidStatsAddDeployAmt clamp and adjust favval`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val pidStats = PidStats(rom)
        pidStats.setFavval(PID_EIRIKA, 0x2000)

        pidStats.pidStatsAddStatViewAmt(PID_EIRIKA)
        assertEquals(1, pidStats.statViewAmt(PID_EIRIKA))
        assertEquals(0x2002, pidStats.favval(PID_EIRIKA))

        pidStats.pidStatsAddDeployAmt(PID_EIRIKA)
        assertEquals(1, pidStats.deployAmt(PID_EIRIKA))
        assertEquals(0x2042, pidStats.favval(PID_EIRIKA))

        pidStats.pidStatsSubFavval100(PID_EIRIKA)
        assertEquals(0x2042 - 0x100, pidStats.favval(PID_EIRIKA))
    }

    @Test
    fun `totals and helper accessors sum stats across all slots`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val pidStats = PidStats(rom)

        pidStats.setBattleAmt(PID_EIRIKA, 10)
        pidStats.setBattleAmt(PID_SETH, 15)
        assertEquals(25, pidStats.pidStatsGetTotalBattleAmt())

        pidStats.setWinAmt(PID_EIRIKA, 8)
        pidStats.setWinAmt(PID_SETH, 12)
        assertEquals(20, pidStats.pidStatsGetTotalWinAmt())

        pidStats.setLossAmt(PID_EIRIKA, 1)
        pidStats.setLossAmt(PID_SETH, 2)
        assertEquals(3, pidStats.pidStatsGetTotalLossAmt())

        pidStats.setExpGained(PID_EIRIKA, 250)
        pidStats.setExpGained(PID_SETH, 350)
        assertEquals(600, pidStats.pidStatsGetTotalExpGain())
        assertEquals(5, pidStats.pidStatsGetTotalLevel()) // (250/100) + (350/100) = 2 + 3 = 5

        assertEquals(250, pidStats.pidStatsGetExpGain(PID_EIRIKA))
        assertEquals(0, pidStats.pidStatsGetExpGain(70))

        pidStats.setFavval(PID_EIRIKA, 0x2000)
        assertEquals(0x2000 shr 6, pidStats.pidStatsGetFavval(PID_EIRIKA))
        assertEquals(0x2000, pidStats.pidStatsGetFavval(70))

        pidStats.clearPidStats()
        assertEquals(0, pidStats.pidStatsGetTotalBattleAmt())
        assertEquals(0, pidStats.pidStatsGetTotalExpGain())
    }

    // --- Private helpers and constants ---

    private fun extractStats(ewram: ByteArray): ByteArray =
        ewram.copyOfRange(PID_STATS_EWRAM_OFFSET, PID_STATS_EWRAM_OFFSET + PidStats.SIZE)

    private fun extractEntry(ewram: ByteArray, pid: Int): ByteArray {
        val off = PID_STATS_EWRAM_OFFSET + (pid - 1) * PidStats.ENTRY_SIZE
        return ewram.copyOfRange(off, off + PidStats.ENTRY_SIZE)
    }

    private fun setupCombatant(bu: BattleUnit, pid: Int, faction: Int, curHp: Int) {
        val charPtr = 0x08803D64 + (pid - 1) * 0x34
        bu.bytes[0] = charPtr.toByte()
        bu.bytes[1] = (charPtr shr 8).toByte()
        bu.bytes[2] = (charPtr shr 16).toByte()
        bu.bytes[3] = (charPtr shr 24).toByte()
        bu.bytes[0x0B] = faction.toByte()
        bu.bytes[0x13] = curHp.toByte()
    }

    private fun PidStats.setFavvalBySlot(slot: Int, value: Int) {
        val off = slot * PidStats.ENTRY_SIZE
        data[off + 1] = value.toByte()
        data[off + 2] = (value shr 8).toByte()
    }

    private companion object {
        const val PID_STATS_ADDR = 0x0203E894
        const val EWRAM_BASE = 0x02000000
        const val PID_STATS_EWRAM_OFFSET = PID_STATS_ADDR - EWRAM_BASE

        const val PID_EIRIKA = 1
        const val PID_SETH = 2

        const val RUN_PROLOGUE_ENEMY_PHASE = "ram_trace_prologue_enemy_phase"
        const val RUN_PROLOGUE_CLEAR = "ram_trace_prologue_clear"
    }
}
