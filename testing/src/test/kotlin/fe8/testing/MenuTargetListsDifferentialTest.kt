package fe8.testing

import fe8.game.RomView
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.MenuTargets
import fe8.game.bm.PlaySt
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The rescue and take target lists (src/bmtarget.c) against `ram_trace_prologue_clear` f4181,
 * where the unit menu is built for Eirika at (8, 6) with one enemy next to her at (9, 6). The
 * menu's availability checks make both lists for her there. The dump of the frame holds the
 * outcome: `sSelectTargetRoot` is her tile, `sSelectTargetCount` is 0 (the enemy is skipped by
 * the first guard of each callback), `gSubjectUnit` is her and `gBmMapRange` holds the ring of
 * the four tiles next to her (what `ForEachAdjacentUnit` marks). Only that negative case is
 * verified: no capture rescues or takes a unit, so the later guards of the callbacks and
 * `CanUnitRescue`/`GetUnitAid` are covered by synthetic tests on edited units (and are not
 * claimed as ported).
 */
class MenuTargetListsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ForEachAdjacentUnit", "MakeRescueTargetList", "TryAddUnitToRescueTargetList",
        "MakeTakeTargetList", "TryAddRescuedUnitToTakeTargetList",
    )
    fun `rescue and take lists at f4181 are empty with the ring of the four tiles marked`() {
        assumeInputs()
        val rom = RomView.ofRom(romFile.readBytes())
        for (take in listOf(false, true)) {
            val sim = Sim(rom)
            val which = if (take) "take" else "rescue"
            assertEquals(1, sim.unit, "acting unit")
            assertEquals(listOf(129), sim.adjacentUnits(), "$which callback units")
            if (take) {
                sim.lists.makeTakeTargetList(sim.unit)
            } else {
                sim.lists.makeRescueTargetList(sim.unit)
            }
            val e = sim.ewram
            assertEquals(s16(e, ROOT - EWRAM), sim.targets.rootX, "$which rootX")
            assertEquals(s16(e, ROOT - EWRAM + 2), sim.targets.rootY, "$which rootY")
            assertEquals(u32(e, COUNT - EWRAM), sim.targets.getSelectTargetCount(), "$which count")
            assertEquals(0, sim.targets.getSelectTargetCount(), "$which count is 0")
            for (y in 0 until sim.map.sizeY) {
                for (x in 0 until sim.map.sizeX) {
                    assertEquals(sim.dumpRange[y][x], sim.map.range[y, x], "$which range ($x, $y)")
                }
            }
        }
    }

    @Test
    fun `rescue list adds an adjacent ally the actor can carry`() {
        assumeInputs()
        val sim = Sim(RomView.ofRom(romFile.readBytes()))
        val ally = sim.makeAllyBesideActor()
        sim.lists.makeRescueTargetList(sim.unit)
        assertEquals(1, sim.targets.getSelectTargetCount())
        assertEquals(sim.units.xPos(ally), sim.targets.targetX(0))
        assertEquals(sim.units.yPos(ally), sim.targets.targetY(0))
        assertEquals(sim.units.index(ally), sim.targets.targetUid(0))
        assertEquals(0, sim.targets.targetExtra(0))
        assertEquals(8 to 6, sim.targets.rootX to sim.targets.rootY)
    }

    @Test
    fun `rescue list guards each skip the ally`() {
        assumeInputs()
        val sim = Sim(RomView.ofRom(romFile.readBytes()))
        val ally = sim.makeAllyBesideActor()
        val units = sim.units
        val state = units.state(ally)
        fun count(): Int {
            sim.lists.makeRescueTargetList(sim.unit)
            return sim.targets.getSelectTargetCount()
        }
        assertEquals(1, count())
        units.setState(ally, state or UnitTable.US_RESCUING)
        assertEquals(0, count(), "rescuing")
        units.setState(ally, state or UnitTable.US_RESCUED)
        assertEquals(0, count(), "rescued")
        units.setState(ally, state)
        units.setUnitStatus(ally, UnitTable.STATUS_BERSERK)
        assertEquals(0, count(), "berserk")
        units.setUnitStatus(ally, 0)
        assertEquals(1, count())
        units.bytes[units.unitOffset(ally) + CON_BONUS] = 20
        assertEquals(0, count(), "too heavy")
        units.bytes[units.unitOffset(ally) + CON_BONUS] = 0
        assertEquals(1, count())
        val allyAt = units.unitOffset(ally) + 4
        val actorAt = units.unitOffset(sim.unit) + 4
        val allyClass = units.bytes.copyOfRange(allyAt, allyAt + 4)
        val actorClass = units.bytes.copyOfRange(actorAt, actorAt + 4)
        sim.setClassNumber(ally, CLASS_PHANTOM)
        assertEquals(0, count(), "phantom target")
        allyClass.copyInto(units.bytes, allyAt)
        sim.setClassNumber(sim.unit, CLASS_PHANTOM)
        assertEquals(0, count(), "phantom actor")
        actorClass.copyInto(units.bytes, actorAt)
        assertEquals(1, count())
        units.bytes[units.unitOffset(ally) + 0x0B] = 129.toByte()
        assertEquals(0, count(), "enemy side")
    }

    @Test
    fun `take list guards and adds an adjacent ally holding a unit the actor can carry`() {
        assumeInputs()
        val sim = Sim(RomView.ofRom(romFile.readBytes()))
        val ally = sim.makeAllyBesideActor()
        val units = sim.units
        val state = units.state(ally)
        val held = 129
        fun count(): Int {
            sim.lists.makeTakeTargetList(sim.unit)
            return sim.targets.getSelectTargetCount()
        }
        units.bytes[units.unitOffset(held) + CON_BONUS] = -20
        assertEquals(0, count(), "not rescuing")
        units.setState(ally, state or UnitTable.US_RESCUING)
        units.setRescue(ally, held)
        assertEquals(1, count())
        assertEquals(units.index(ally), sim.targets.targetUid(0))
        assertEquals(units.xPos(ally), sim.targets.targetX(0))
        assertEquals(0, sim.targets.targetExtra(0))
        units.bytes[units.unitOffset(held) + CON_BONUS] = 40
        assertEquals(0, count(), "held unit too heavy")
        units.bytes[units.unitOffset(held) + CON_BONUS] = -20
        assertEquals(1, count())
        val data = units.bytes.copyOfRange(units.unitOffset(ally), units.unitOffset(ally) + 8)
        sim.setWithSupply(ally)
        assertEquals(0, count(), "supply")
        data.copyInto(units.bytes, units.unitOffset(ally))
        assertEquals(1, count())
        units.bytes[units.unitOffset(ally) + 0x0B] = 129.toByte()
        assertEquals(0, count(), "enemy side")
    }

    @Test
    fun `aid is Con less 1 on foot and a mounted aid less Con otherwise`() {
        assumeInputs()
        val rom = RomView.ofRom(romFile.readBytes())
        val sim = Sim(rom)
        val units = sim.units
        val ally = sim.makeAllyBesideActor()
        units.bytes[units.unitOffset(ally) + CON_BONUS] = 3
        fun con(): Int {
            val o = units.unitOffset(ally)
            return rom.u8(u32(units.bytes, o + 4) + 0x11).toByte() +
                rom.u8(u32(units.bytes, o) + 0x13).toByte() + 3
        }
        assertEquals(con() - 1, sim.lists.getUnitAid(ally), "on foot")
        sim.setClassWithAttribute(ally, 1 shl 0, 1 shl 14, female = false)
        assertEquals(25 - con(), sim.lists.getUnitAid(ally), "mounted male")
        sim.setClassWithAttribute(ally, 1 shl 0, 1 shl 14, female = true)
        assertEquals(20 - con(), sim.lists.getUnitAid(ally), "mounted female")
        assertEquals(
            sim.lists.getUnitAid(ally) >= con(), sim.lists.canUnitRescue(ally, ally), "can rescue",
        )
    }

    private fun assumeInputs() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
    }

    private inner class Sim(val rom: RomView) {
        private val run = ref.run("ram_trace_prologue_clear")
        private val names = run.dumps.entries.associate { it.value to it.key }
        val ewram: ByteArray = run.region(names.getValue(4181), "ewram")
        private val iwram: ByteArray = run.region(names.getValue(4181), "iwram")
        val units = UnitTable(rom)
        val map: BmMap
        val unit: Int
        val lists: MenuTargets
        val targets: SelectTargets
        lateinit var dumpRange: Array<IntArray>

        init {
            val playSt = PlaySt()
            playSt.decode(ewram, PLAY_ST - EWRAM)
            map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(playSt.chapterIndex)
            readPool(GBM_MAP_TERRAIN, map.terrain)
            readPool(GBM_MAP_UNIT, map.unit)
            val dump = BmMap(rom, ChapterData(rom), BmSt())
            dump.initChapterMap(playSt.chapterIndex)
            readPool(GBM_MAP_RANGE, dump.range)
            dumpRange = Array(map.sizeY) { y -> IntArray(map.sizeX) { x -> dump.range[y, x] } }
            units.loadFrom(ewram)
            val pointer = u32(iwram, GACTIVEUNIT - IWRAM)
            unit = units.bytes[pointer - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            val items = ItemInfo(rom)
            val entityMaps = EntityBmMaps(map, units, playSt)
            val attack = AttackTargets(
                map, units, items, AttackRange(map, units, items, entityMaps, loader),
                ByteArray(0x200), SelectTargets(),
            )
            lists = MenuTargets(attack, entityMaps, units, rom)
            targets = attack.targets
        }

        /** The ids `ForEachAdjacentUnit` passes for the acting unit's tile, in call order. */
        fun adjacentUnits(): List<Int> {
            val seen = ArrayList<Int>()
            map.bmMapFill(map.range, 0)
            lists.forEachAdjacentUnit(units.xPos(unit), units.yPos(unit)) { seen.add(it) }
            return seen
        }

        /**
         * Puts the player unit 2 (Seth's slot, a blue unit) at (7, 6) so it is next to the
         * actor, and returns its id.
         */
        fun makeAllyBesideActor(): Int {
            val ally = 2
            units.setPosition(ally, 7, 6)
            map.unit[6, 7] = ally
            return ally
        }

        /** Points unit [id]'s class at the class with number [number]. */
        fun setClassNumber(id: Int, number: Int) {
            put32(units.bytes, units.unitOffset(id) + 4, CLASS_DATA + CLASS_SIZE * (number - 1))
        }

        /** Points unit [id]'s character or class at one with `CA_SUPPLY` set. */
        fun setWithSupply(id: Int) {
            val character = (0 until 0x100).map { CHARACTER_DATA + 0x34 * it }
                .firstOrNull { rom.u32(it + 0x28) and (1 shl 9) != 0 }
            if (character != null) {
                put32(units.bytes, units.unitOffset(id), character)
                return
            }
            val cls = (0 until 0x7F).map { CLASS_DATA + CLASS_SIZE * it }
                .first { rom.u32(it + 0x28) and (1 shl 9) != 0 }
            put32(units.bytes, units.unitOffset(id) + 4, cls)
        }

        /**
         * Points unit [id]'s class at one whose attributes include [need] and, when [female], the
         * character at one with the `CA_FEMALE` bit [femaleBit] set, else not.
         */
        fun setClassWithAttribute(id: Int, need: Int, femaleBit: Int, female: Boolean) {
            val cls = (0 until 0x7F).map { CLASS_DATA + CLASS_SIZE * it }
                .first { rom.u32(it + 0x28) and (need or femaleBit) == need }
            put32(units.bytes, units.unitOffset(id) + 4, cls)
            val ch = (0 until 0x100).map { CHARACTER_DATA + 0x34 * it }.first {
                (rom.u32(it + 0x28) and femaleBit != 0) == female &&
                    rom.u32(it + 0x28) and need == 0
            }
            put32(units.bytes, units.unitOffset(id), ch)
        }

        /** The byte image holding the address [at] and its offset in it (EWRAM or IWRAM). */
        private fun image(at: Int) = if (at >= IWRAM) iwram else ewram

        private fun offset(at: Int) = if (at >= IWRAM) at - IWRAM else at - EWRAM

        /** Reads a pool through its handle at [handle] (the pool's rows may be in IWRAM). */
        private fun readPool(handle: Int, pool: BmMap.Pool) {
            val rows = u32(ewram, handle - EWRAM)
            val first = u32(image(rows - 8), offset(rows - 8))
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    val at = first + (y + 2) * (map.sizeX + 2) + x
                    pool[y, x] = image(at)[offset(at)].toInt() and 0xFF
                }
            }
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val PLAY_ST = 0x0202BCF0
        const val GACTIVEUNIT = 0x03004E50
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_RANGE = 0x0202E4E4
        const val ROOT = 0x0203DDE8
        const val COUNT = 0x0203E0EC
        const val CON_BONUS = 0x1A
        const val CLASS_PHANTOM = 0x51
        const val CHARACTER_DATA = 0x08803D64
        const val CLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54

        fun put32(b: ByteArray, at: Int, v: Int) {
            for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
        }

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(b: ByteArray, at: Int) = u16(b, at).toShort().toInt()

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}
