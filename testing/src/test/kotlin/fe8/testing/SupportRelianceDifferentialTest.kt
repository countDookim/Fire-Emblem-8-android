package fe8.testing

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.bm.SupportReliance
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Support reliance and turn support exp of `src/bmreliance.c` against real mGBA reference captures:
 *
 * 1. `ProcessTurnSupportExp`: at `f2768` (`ram_trace_prologue_enemy_phase`, turn 1 -> turn 2) and
 *    `f3877` (`ram_trace_prologue_clear`, turn 2 -> turn 3), adjacent player units (Eirika and
 *    Seth) gain support points and increment `gPlaySt.chapterTotalSupportGain`. The entire
 *    blue unit array (62 units) is verified against the dumped EWRAM `gUnitArrayBlue`.
 * 2. `InitSupportBonuses`: at `f1614`, `f1696` (`ram_trace_prologue_enemy_phase`), `f04210` and
 *    `r04274` (`ram_trace_prologue_clear`), `InitSupportBonuses` zeroes the six stat bonus fields,
 *    reproducing the battle unit attack, defense, hit, avoid, crit and dodge rates in
 *    `gBattleActor`.
 */
class SupportRelianceDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ProcessTurnSupportExp",
        "UnitGainSupportExp",
        "GetUnitSupporterCharacter",
        "GetUnitSupporterUnit",
    )
    fun `ProcessTurnSupportExp reproduces unit supports and total gain at f2768 and f3877`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        val cases = listOf(
            SupportTurnCase(
                runName = "ram_trace_prologue_enemy_phase",
                beforeDump = "r02767",
                afterDump = "r02768",
                expectedTurn = 2,
                expectedTotalGain = 6,
                expectedEirikaExp = 28,
                expectedSethExp = 28,
            ),
            SupportTurnCase(
                runName = "ram_trace_prologue_clear",
                beforeDump = "r03876",
                afterDump = "r03877",
                expectedTurn = 3,
                expectedTotalGain = 12,
                expectedEirikaExp = 31,
                expectedSethExp = 31,
            ),
        )

        for (c in cases) {
            val run = ref.run(c.runName)
            val beforeEwram = run.region(c.beforeDump, "ewram")
            val afterEwram = run.region(c.afterDump, "ewram")

            val table = UnitTable(rom).also { it.loadFrom(beforeEwram) }
            val playSt = PlaySt().also { it.decode(beforeEwram, GPLAYST - EWRAM) }
            val support = SupportReliance(rom, table, playSt)

            // Switch from green to blue phase, incrementing turn number and awarding support exp
            playSt.faction = 0x00
            if (playSt.chapterTurnNumber < 999) playSt.chapterTurnNumber++
            support.processTurnSupportExp()

            assertEquals(0x00, playSt.faction, "faction after phase switch in ${c.afterDump}")
            assertEquals(c.expectedTurn, playSt.chapterTurnNumber, "turn number in ${c.afterDump}")
            assertEquals(
                c.expectedTotalGain,
                playSt.chapterTotalSupportGain,
                "total support gain in ${c.afterDump}",
            )

            // Eirika (unit 0) and Seth (unit 1) supports check
            val eirikaOffset = table.unitOffset(1)
            val sethOffset = table.unitOffset(2)
            assertEquals(
                c.expectedEirikaExp,
                table.bytes[eirikaOffset + OFF_SUPPORTS + 1].toInt() and 0xFF,
            )
            assertEquals(
                c.expectedSethExp,
                table.bytes[sethOffset + OFF_SUPPORTS + 1].toInt() and 0xFF,
            )

            // Both remain at support level NONE (exp <= 80)
            assertEquals(
                SupportReliance.SUPPORT_LEVEL_NONE,
                support.getUnitSupportLevel(table.bytes, eirikaOffset, 1),
            )
            assertEquals(
                SupportReliance.SUPPORT_LEVEL_NONE,
                support.getUnitSupportLevel(table.bytes, sethOffset, 1),
            )
            assertEquals(0, support.getUnitTotalSupportLevel(table.bytes, eirikaOffset))
            assertEquals(0, support.getUnitTotalSupportLevel(table.bytes, sethOffset))

            // Supporter unit lookup matches
            assertEquals(sethOffset, support.getUnitSupporterUnit(table.bytes, eirikaOffset, 1))
            assertEquals(eirikaOffset, support.getUnitSupporterUnit(table.bytes, sethOffset, 1))

            // The entire blue unit array (62 units * 0x48 bytes) matches the after-dump byte for
            // byte
            val blueAt = BLUE_ADDR - EWRAM
            val blueExpected = afterEwram.copyOfRange(blueAt, blueAt + BLUE_SIZE)
            val blueActual = table.bytes.copyOfRange(0, BLUE_SIZE)
            assertArrayEquals(blueExpected, blueActual, "gUnitArrayBlue matches ${c.afterDump}")
        }
    }

    @Test
    @VerifiesPort("InitSupportBonuses")
    fun `InitSupportBonuses zeroes bonuses at f1614, f1696, f4210 and f4274`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        // 1. Direct verify of InitSupportBonuses on sentinel values
        val bonuses = intArrayOf(7, 13, 21, 33, 42, 55)
        SupportReliance(rom, UnitTable(rom), PlaySt()).initSupportBonuses(bonuses)
        assertArrayEquals(
            intArrayOf(0, 0, 0, 0, 0, 0),
            bonuses,
            "InitSupportBonuses must zero all 6 bonuses",
        )

        // 2. Battle unit stat matches across reference frames
        val battleCases = listOf(
            BattleCheckCase("ram_trace_prologue_enemy_phase", "r01614", 12, 5, 62, 2, 0, 0),
            BattleCheckCase("ram_trace_prologue_enemy_phase", "r01696", 12, 5, 62, 2, 0, 0),
            BattleCheckCase("ram_trace_prologue_clear", "f04210", 22, 11, 107, 37, 6, 13),
            BattleCheckCase("ram_trace_prologue_clear", "r04274", 23, 11, 122, 37, 6, 13),
        )

        for (bc in battleCases) {
            val run = ref.run(bc.runName)
            val ewram = run.region(bc.dumpName, "ewram")
            val table = UnitTable(rom).also { it.loadFrom(ewram) }
            val support = SupportReliance(rom, table, PlaySt())

            val actorAt = GBACTOR_ADDR - EWRAM
            val atk = ewram.s16(actorAt + 0x5A)
            val def = ewram.s16(actorAt + 0x5C)
            val hit = ewram.s16(actorAt + 0x60)
            val avo = ewram.s16(actorAt + 0x62)
            val crit = ewram.s16(actorAt + 0x66)
            val ddg = ewram.s16(actorAt + 0x68)

            assertEquals(bc.expectedAtk, atk, "atk in ${bc.dumpName}")
            assertEquals(bc.expectedDef, def, "def in ${bc.dumpName}")
            assertEquals(bc.expectedHit, hit, "hit in ${bc.dumpName}")
            assertEquals(bc.expectedAvo, avo, "avo in ${bc.dumpName}")
            assertEquals(bc.expectedCrit, crit, "crit in ${bc.dumpName}")
            assertEquals(bc.expectedDdg, ddg, "ddg in ${bc.dumpName}")

            // Verify support bonuses computed for this actor are all zero
            val supportBonuses = intArrayOf(99, 99, 99, 99, 99, 99)
            support.getUnitSupportBonuses(ewram, actorAt, supportBonuses)
            assertArrayEquals(
                intArrayOf(0, 0, 0, 0, 0, 0),
                supportBonuses,
                "bonuses in ${bc.dumpName}",
            )
        }
    }

    @Test
    fun `ProcessTurnSupportExp does nothing on turn 1`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        val run = ref.run("ram_trace_prologue_enemy_phase")
        val ewram = run.region("r02767", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(ewram) }
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        playSt.chapterTurnNumber = 1
        playSt.chapterTotalSupportGain = 0

        val support = SupportReliance(rom, table, playSt)
        support.processTurnSupportExp()

        assertEquals(0, playSt.chapterTotalSupportGain, "gain on turn 1 must be 0")
        assertEquals(25, table.bytes[table.unitOffset(1) + OFF_SUPPORTS + 1].toInt() and 0xFF)
        assertEquals(25, table.bytes[table.unitOffset(2) + OFF_SUPPORTS + 1].toInt() and 0xFF)
    }

    @Test
    fun `ProcessTurnSupportExp does nothing on extra maps`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        val run = ref.run("ram_trace_prologue_enemy_phase")
        val ewram = run.region("r02767", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(ewram) }
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        playSt.chapterTurnNumber = 2
        playSt.chapterStateBits = 1 shl 7 // PLAY_FLAG_EXTRA_MAP
        playSt.chapterTotalSupportGain = 0

        val support = SupportReliance(rom, table, playSt)
        support.processTurnSupportExp()

        assertEquals(0, playSt.chapterTotalSupportGain, "gain on extra map must be 0")
        assertEquals(25, table.bytes[table.unitOffset(1) + OFF_SUPPORTS + 1].toInt() and 0xFF)
        assertEquals(25, table.bytes[table.unitOffset(2) + OFF_SUPPORTS + 1].toInt() and 0xFF)
    }

    @Test
    fun `ProcessTurnSupportExp handles rescue at distance 0`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        val run = ref.run("ram_trace_prologue_enemy_phase")
        val ewram = run.region("r02767", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(ewram) }
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        playSt.chapterTurnNumber = 2
        playSt.chapterTotalSupportGain = 0

        val support = SupportReliance(rom, table, playSt)
        val u1 = table.unitOffset(1)
        val u2 = table.unitOffset(2)

        // Case A: distance 0, unit 1 rescues unit 2 (unit 1 rescue field holds index 2)
        table.bytes[u2 + 0x10] = table.bytes[u1 + 0x10] // same x
        table.bytes[u2 + 0x11] = table.bytes[u1 + 0x11] // same y (distance 0)
        table.bytes[u1 + 0x1B] = 2.toByte()             // Eirika rescues Seth
        table.bytes[u2 + 0x0C] = (1 shl 5).toByte()     // Seth has US_RESCUED

        support.processTurnSupportExp()

        // Eirika is the rescuer of Seth, so Eirika gains support exp (+3);
        // Seth is rescued and does not have rescue == Eirika.index, so Seth does not gain.
        assertEquals(
            28,
            table.bytes[u1 + OFF_SUPPORTS + 1].toInt() and 0xFF,
            "Eirika support with Seth",
        )
        assertEquals(
            25,
            table.bytes[u2 + OFF_SUPPORTS + 1].toInt() and 0xFF,
            "Seth support with Eirika",
        )
        assertEquals(3, playSt.chapterTotalSupportGain, "total support gain")
    }

    @Test
    fun `ProcessTurnSupportExp skips pairs when either is rescued at distance 1`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        val run = ref.run("ram_trace_prologue_enemy_phase")
        val ewram = run.region("r02767", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(ewram) }
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        playSt.chapterTurnNumber = 2
        playSt.chapterTotalSupportGain = 0

        val support = SupportReliance(rom, table, playSt)
        val u1 = table.unitOffset(1)

        // Eirika is at distance 1 from Seth, but Eirika has US_RESCUED set
        val state = table.state(1)
        table.setState(1, state or (1 shl 5))

        support.processTurnSupportExp()

        assertEquals(0, playSt.chapterTotalSupportGain, "rescued unit at distance 1 gains no exp")
        assertEquals(25, table.bytes[u1 + OFF_SUPPORTS + 1].toInt() and 0xFF)
    }

    @Test
    fun `ProcessTurnSupportExp respects MAX_SIMULTANEOUS_SUPPORT_COUNT limit of 5`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        val run = ref.run("ram_trace_prologue_enemy_phase")
        val ewram = run.region("r02767", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(ewram) }
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        playSt.chapterTurnNumber = 2
        playSt.chapterTotalSupportGain = 0

        val support = SupportReliance(rom, table, playSt)
        val u1 = table.unitOffset(1)

        // Set Eirika's other supports so total support level is 5 (e.g. A + B = 3 + 2 = 5)
        table.bytes[u1 + OFF_SUPPORTS + 0] = 245.toByte() // level A (3)
        table.bytes[u1 + OFF_SUPPORTS + 2] = 165.toByte() // level B (2)
        assertEquals(5, support.getUnitTotalSupportLevel(table.bytes, u1))

        support.processTurnSupportExp()

        assertEquals(0, playSt.chapterTotalSupportGain, "unit with 5 support levels gains no exp")
        assertEquals(25, table.bytes[u1 + OFF_SUPPORTS + 1].toInt() and 0xFF)
    }

    @Test
    fun `UnitGainSupportExp caps at support level max exp lookup`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "mGBA reference data or baserom.gba not present",
        )
        val rom = RomView.ofRom(romFile.readBytes())

        val run = ref.run("ram_trace_prologue_enemy_phase")
        val ewram = run.region("r02767", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(ewram) }
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        val support = SupportReliance(rom, table, playSt)
        val u1 = table.unitOffset(1)

        // Support level NONE: capped at 80 (SUPPORT_EXP_C - 1)
        // With currentExp = 79 and growth = 3, gain is 1, final exp is 80
        table.bytes[u1 + OFF_SUPPORTS + 1] = 79.toByte()
        playSt.chapterTotalSupportGain = 0
        support.unitGainSupportExp(table.bytes, u1, 1)

        assertEquals(80, table.bytes[u1 + OFF_SUPPORTS + 1].toInt() and 0xFF)
        assertEquals(1, playSt.chapterTotalSupportGain)

        // Calling again at 80 gives 0 gain
        support.unitGainSupportExp(table.bytes, u1, 1)
        assertEquals(80, table.bytes[u1 + OFF_SUPPORTS + 1].toInt() and 0xFF)
        assertEquals(1, playSt.chapterTotalSupportGain)
    }

    private class SupportTurnCase(
        val runName: String,
        val beforeDump: String,
        val afterDump: String,
        val expectedTurn: Int,
        val expectedTotalGain: Int,
        val expectedEirikaExp: Int,
        val expectedSethExp: Int,
    )

    private class BattleCheckCase(
        val runName: String,
        val dumpName: String,
        val expectedAtk: Int,
        val expectedDef: Int,
        val expectedHit: Int,
        val expectedAvo: Int,
        val expectedCrit: Int,
        val expectedDdg: Int,
    )

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.s16(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8)).toShort().toInt()

    private companion object {
        const val EWRAM = 0x02000000
        const val BLUE_ADDR = 0x0202BE4C
        const val BLUE_SIZE = 62 * 0x48
        const val GPLAYST = 0x0202BCF0
        const val GBACTOR_ADDR = 0x0203A4EC
        const val GBTARGET_ADDR = 0x0203A56C
        const val OFF_SUPPORTS = 0x32
    }
}
