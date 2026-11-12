package fe8.testing

import fe8.game.RomView
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BmMap
import fe8.game.bm.BmPhase
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.PlaySt
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The attack target list (src/bmtarget.c, src/uiselecttarget.c) against the Prologue captures.
 * Units, `gPlaySt`, the terrain and unit maps come from the dump of the frame where the unit
 * menu is open; `MakeTargetListForWeapon` (as the weapon list's availability calls it) runs for
 * the acting unit's first item, and root, count and every target (tile, unit id, extra) must
 * equal the dump where the weapon list is open. Then `GetLinkedTargets` (the target selection's
 * start) runs, and the targets and their `next`/`prev` links must equal the dump where the
 * target select is open. `gTrapArray` in the dumps holds no trap.
 *
 * Cases: cap3 `ram_trace_prologue_attack` f1395 -> f1400 -> f1430, `..._attack_noanim` f1595 ->
 * f1630 -> f1660, `..._kill` Seth f1638 -> f1650 -> f1680 and Eirika f2210 -> f2230 (made and
 * linked in one step).
 */
class AttackTargetListDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "InitTargets", "AddTarget", "GetSelectTargetCount", "MakeTargetListForWeapon",
        "ForEachUnitInRange", "AddUnitToTargetListIfNotAllied", "TryAddTrapsToTargetList",
        "AreUnitsAllied",
    )
    fun `weapon target list matches the game`() {
        assumeInputs()
        val rom = RomView.ofRom(romFile.readBytes())
        var targets = 0
        for (case in CASES) {
            val sim = Sim(rom, case)
            sim.makeList()
            sim.compareList(case.list, linked = false)
            targets += sim.list.targets.getSelectTargetCount()
        }
        assertEquals(CASES.size, targets, "one target per case")
    }

    @Test
    @VerifiesPort(
        "GetLinkedTargets", "GetLinkedTargetsNear", "GetFurthestTargetDistance",
    )
    fun `linked targets match the game`() {
        assumeInputs()
        val rom = RomView.ofRom(romFile.readBytes())
        for (case in CASES) {
            val sim = Sim(rom, case)
            sim.makeList()
            assertEquals(1, sim.list.targets.getFurthestTargetDistance(), "${case.run} distance")
            val first = sim.list.targets.getLinkedTargets()
            assertEquals(0, first, "${case.run} first target")
            sim.compareList(case.linked, linked = true)
        }
    }

    @Test
    fun `same allegiance compares the faction bits`() {
        // `(left & 0xc0) == (right & 0xc0)`: blue 0x01-0x3F, green 0x40-0x7F, red 0x81-0xBF,
        // purple 0xC1-0xFF. (No capture has a trade or talk list, which call it.)
        assertTrue(BmPhase.isSameAllegiance(0x01, 0x3F))
        assertTrue(BmPhase.isSameAllegiance(0x82, 0xB2))
        assertTrue(!BmPhase.isSameAllegiance(0x3F, 0x40))
        assertTrue(!BmPhase.isSameAllegiance(0x82, 0xC1))
    }

    private fun assumeInputs() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
    }

    private inner class Sim(rom: RomView, val case: Case) {
        private val run = ref.run(case.run)
        private val names = run.dumps.entries.associate { it.value to it.key }

        private fun ewramOf(frame: Int): ByteArray = run.region(names.getValue(frame), "ewram")
        val units = UnitTable(rom)
        val list: Lists

        /** The acting unit's id and its first item. */
        var unit = 0
        var item = 0

        init {
            val ewram = ewramOf(case.seed)
            val iwram = run.region(names.getValue(case.seed), "iwram")
            val playSt = PlaySt()
            playSt.decode(ewram, PLAY_ST - EWRAM)
            val map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(playSt.chapterIndex)
            readPool(ewram, GBM_MAP_TERRAIN, map, map.terrain)
            readPool(ewram, GBM_MAP_UNIT, map, map.unit)
            units.loadFrom(ewram)
            val pointer = u32(iwram, GACTIVEUNIT - IWRAM)
            unit = units.bytes[pointer - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
            item = units.item(unit, 0) and 0xFFFF
            val traps = ByteArray(TRAP_POOL_SIZE)
            for (frame in listOf(case.seed, case.list, case.linked)) {
                val e = ewramOf(frame)
                for (t in 0 until TRAP_POOL_SIZE / 8) {
                    assertEquals(0, e[TRAP_POOL - EWRAM + 8 * t + 2].toInt(), "trap type, $frame")
                }
            }
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            val items = ItemInfo(rom)
            val range = AttackRange(map, units, items, EntityBmMaps(map, units, playSt), loader)
            list = Lists(AttackTargets(map, units, items, range, traps, SelectTargets()))
        }

        fun makeList() = list.attack.makeTargetListForWeapon(unit, item)

        fun compareList(frame: Int, linked: Boolean) {
            val e = ewramOf(frame)
            val t = list.targets
            val at = "${case.run} $frame"
            assertEquals(s16(e, ROOT - EWRAM), t.rootX, "$at rootX")
            assertEquals(s16(e, ROOT - EWRAM + 2), t.rootY, "$at rootY")
            assertEquals(u32(e, COUNT - EWRAM), t.getSelectTargetCount(), "$at count")
            for (i in 0 until t.getSelectTargetCount()) {
                val o = LIST - EWRAM + 12 * i
                assertEquals(e[o].toInt(), t.targetX(i), "$at target $i x")
                assertEquals(e[o + 1].toInt(), t.targetY(i), "$at target $i y")
                assertEquals(e[o + 2].toInt(), t.targetUid(i), "$at target $i uid")
                assertEquals(e[o + 3].toInt(), t.targetExtra(i), "$at target $i extra")
                if (linked) {
                    assertEquals(link(u32(e, o + 4)), t.targetNext(i), "$at target $i next")
                    assertEquals(link(u32(e, o + 8)), t.targetPrev(i), "$at target $i prev")
                }
            }
        }

        private fun link(pointer: Int): Int =
            if (pointer == 0) SelectTargets.NONE else (pointer - LIST) / 12

        private fun readPool(e: ByteArray, handle: Int, map: BmMap, pool: BmMap.Pool) {
            val rows = u32(e, handle - EWRAM) - EWRAM
            val first = u32(e, rows - 8) - EWRAM
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    pool[y, x] = e[first + (y + 2) * (map.sizeX + 2) + x].toInt() and 0xFF
                }
            }
        }
    }

    private class Lists(val attack: AttackTargets) {
        val targets: SelectTargets get() = attack.targets
    }

    private class Case(val run: String, val seed: Int, val list: Int, val linked: Int)

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val PLAY_ST = 0x0202BCF0
        const val GACTIVEUNIT = 0x03004E50
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val TRAP_POOL = 0x0203A614
        const val TRAP_POOL_SIZE = 0x200
        const val ROOT = 0x0203DDE8
        const val LIST = 0x0203DDEC
        const val COUNT = 0x0203E0EC
        val CASES = listOf(
            Case("ram_trace_prologue_attack", 1395, 1400, 1430),
            Case("ram_trace_prologue_attack_noanim", 1595, 1630, 1660),
            Case("ram_trace_prologue_kill", 1638, 1650, 1680),
            Case("ram_trace_prologue_kill", 2210, 2230, 2230),
        )

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(b: ByteArray, at: Int) = u16(b, at).toShort().toInt()

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}
