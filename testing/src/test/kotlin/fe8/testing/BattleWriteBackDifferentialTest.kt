package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `BattleApplyGameStateUpdates` (src/bmbattle.c) against the Prologue captures: in the dump
 * before the write-back the unit table and both battle units hold the battle's result; the dump
 * after it must show the unit table entries of the actor and the target with level, exp, HP,
 * state, stats, weapon exp and items written back. The frame is found as the first dump in a
 * window whose unit entries differ from the previous dump.
 *
 * Cases: noanim f2074, kill run f1999 (an enemy dies) and f2549 (nobody dies), cap3 f2028. The
 * dump after the write-back also holds the death handling (`BATTLE_HandleCombatDeaths`, MC-16):
 * the test runs it on both units and compares the dead unit too (`UnitKill` clears its
 * character). The dead unit leaves the unit map and the sprite list a few frames later, at the
 * next `RefreshEntityBmMaps` and `RefreshUnitSprites` (kill run f2005 and f2008): the second
 * test runs both on the killed table and compares `gBmMapUnit` and the sprite handles with
 * those dumps.
 */
class BattleWriteBackDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "BattleApplyGameStateUpdates", "BattleApplyUnitUpdates", "UpdateUnitFromBattle",
        "GetBattleUnitUpdatedWeaponExp", "KillUnitOnCombatDeath", "UnitKill",
    )
    fun `the write-back reproduces the dumped unit table entries`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        var changed = 0
        var cleared = 0
        for (case in CASES) {
            val run = ref.run(case.run)
            val names = run.dumps.entries.associate { it.value to it.key }
            fun ewram(frame: Int) = run.region(names.getValue(frame), "ewram")
            val actorId = ewram(case.from)[GACTIONDATA - EWRAM + 0x0C].toInt() and 0xFF
            val targetId = ewram(case.from)[GACTIONDATA - EWRAM + 0x0D].toInt() and 0xFF
            var d = case.from + 1
            var found = -1
            while (d <= case.to) {
                val x = UnitTable(rom).also { it.loadFrom(ewram(d - 1)) }
                val y = UnitTable(rom).also { it.loadFrom(ewram(d)) }
                if (unitBytes(x, actorId) != unitBytes(y, actorId)) {
                    found = d
                    break
                }
                d++
            }
            assertTrue(found > 0, "${case.run}: no write-back in ${case.from}..${case.to}")
            val before = ewram(found - 1)
            val after = ewram(found)
            val tag = "${case.run} f${found - 1}"

            val table = UnitTable(rom).also { it.loadFrom(before) }
            val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
            val bmSt = BmSt().also { it.decode(before, GBMST - EWRAM) }
            val rng = RngService()
            val loader = UnitLoader(table, rom, { 0 }, rng, { false })
            val battle = BmBattle(rom, table, loader, playSt, bmSt) { _, _ -> 0 }
            val actorAt = GBATTLE_ACTOR - EWRAM
            val targetAt = GBATTLE_TARGET - EWRAM
            before.copyInto(battle.actor.bytes, 0, actorAt, actorAt + BattleUnit.SIZE)
            before.copyInto(battle.target.bytes, 0, targetAt, targetAt + BattleUnit.SIZE)
            battle.stats.decode(before, GBATTLESTATS - EWRAM)

            battle.battleApplyGameStateUpdates()
            // BATTLE_HandleCombatDeaths, as the combat proc runs it after the write-back.
            battle.dropRescueOnDeath(actorId)
            battle.dropRescueOnDeath(targetId)
            battle.killUnitOnCombatDeath(actorId, targetId)
            battle.killUnitOnCombatDeath(targetId, actorId)

            val want = UnitTable(rom).also { it.loadFrom(after) }
            for (id in intArrayOf(actorId, targetId)) {
                val o = table.unitOffset(id)
                if (want.bytes.copyOfRange(o, o + 4).all { it.toInt() == 0 }) cleared++
                for (i in 0 until UnitTable.UNIT_SIZE) {
                    assertEquals(
                        want.bytes[o + i], table.bytes[o + i],
                        "$tag unit $id byte 0x${i.toString(16)}",
                    )
                }
                if (unitBytes(UnitTable(rom).also { it.loadFrom(before) }, id) !=
                    unitBytes(want, id)
                ) {
                    changed++
                }
            }
        }
        assertTrue(changed >= 4, "the write-back must change units in the cases, got $changed")
        assertEquals(1, cleared, "the dead unit of the kill run is cleared")
    }

    @Test
    fun `the dead unit leaves the unit map and the sprite list`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val sym = RamSymbols.fromEnvironment()
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run("ram_trace_prologue_kill")
        val names = run.dumps.entries.associate { it.value to it.key }
        fun ewram(frame: Int) = run.region(names.getValue(frame), "ewram")
        val handlesAt = sym["gSMSHandleArray"].address - EWRAM
        val slotsAt = sym["gUnitSpriteSlots"].address - EWRAM
        for (kill in listOf(1999)) {
            val mapFrame = (kill + 1..kill + 12).first { f ->
                mapUnitPool(ewram(f - 1)) != mapUnitPool(ewram(f))
            }
            val handleFrame = (mapFrame + 1..mapFrame + 12).first { f ->
                !ewram(f - 1).copyOfRange(handlesAt, handlesAt + 0x4B0)
                    .contentEquals(ewram(f).copyOfRange(handlesAt, handlesAt + 0x4B0))
            }
            // The table the refresh sees, with the dead unit as it was before UnitKill: its
            // character is put back and `UnitKill` removes it again.
            val alive = UnitTable(rom).also { it.loadFrom(ewram(kill - 1)) }
            val dead = UnitTable(rom).also { it.loadFrom(ewram(kill)) }
            val deadId = (1..0xBF).single { alive.isValid(it) && !dead.isValid(it) }
            // The map animation's end shows Eirika again (her state changes at the refresh frame
            // too), so the unit states are the dump's after the refresh.
            val seed = ewram(mapFrame)
            val units = UnitTable(rom).also { it.loadFrom(seed) }
            val o = units.unitOffset(deadId)
            val from = alive.unitOffset(deadId)
            alive.bytes.copyInto(units.bytes, o, from, from + 4)
            units.unitKill(deadId)
            val playSt = PlaySt().also { it.decode(seed, GPLAYST - EWRAM) }
            val bmSt = BmSt().also { it.decode(seed, GBMST - EWRAM) }
            val map = BmMap(rom, ChapterData(rom), bmSt)
            map.initChapterMap(playSt.chapterIndex)
            EntityBmMaps(map, units, playSt).refreshEntityBmMaps()
            val want = ewram(mapFrame)
            val first = u32(want, u32(want, GBM_MAP_UNIT - EWRAM) - EWRAM - 8) - EWRAM
            var occupied = 0
            for (y in 0 until map.sizeY) {
                for (x in 0 until map.sizeX) {
                    val cell = want[first + (y + 2) * (map.sizeX + 2) + x].toInt() and 0xFF
                    assertEquals(cell, map.unit[y, x], "gBmMapUnit ($x,$y) kill f$kill")
                    if (cell != 0) occupied++
                }
            }
            assertTrue(occupied > 3, "units on the map: $occupied")

            // The sprite list after RefreshUnitSprites: the slots are the dump's.
            val before = ewram(handleFrame - 1)
            val sprites = UnitSprites(rom, BgHardware(), bmSt)
            sprites.resetUnitSprites()
            val slots = IntArray(0xD0) { before[slotsAt + it].toInt() and 0xFF }
            val used = (0 until 0xD0).filter { slots[it] != 0xFF }
            val small = used.filter { rom.u16(INFO + 8 * (it and 0x7F) + 2) == 0 }
            for (id in small.sortedByDescending { slotIndex(slots[it]) }) sprites.useUnitSprite(id)
            for (id in (used - small.toSet()).sortedBy { slotIndex(slots[it]) }) {
                sprites.useUnitSprite(id)
            }
            val handles = UnitSpriteHandles(rom, sprites, units)
            val traps = before.copyOfRange(
                sym["sTrapPool"].address - EWRAM, sym["sTrapPool"].address - EWRAM + 0x200,
            )
            handles.refreshUnitSprites({ x, y -> map.unit[y, x] }, traps)
            val dumped = ewram(handleFrame)
            assertEquals(
                (u32(dumped, sym["gSMSHandleIt"].address - EWRAM) - 0x0203A018) / 12,
                handles.handleIt, "handle count kill f$kill",
            )
            for (i in 0 until handles.handleIt * 12) {
                assertEquals(dumped[handlesAt + i], handles.handles[i], "handle byte $i f$kill")
            }
        }
    }

    private fun slotIndex(value: Int): Int {
        val lut = value * 2
        val row = lut / 32
        return (row / 4) * 32 + lut % 32 + (row % 4) / 2
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun mapUnitPool(ewram: ByteArray): List<Byte> {
        val first = u32(ewram, u32(ewram, GBM_MAP_UNIT - EWRAM) - EWRAM - 8) - EWRAM
        return ewram.copyOfRange(first, first + 0x400).toList()
    }

    private fun unitBytes(t: UnitTable, id: Int): List<Byte> {
        val o = t.unitOffset(id)
        return t.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE).toList()
    }

    private class Case(val run: String, val from: Int, val to: Int)

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val INFO = 0x088AF880
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GACTIONDATA = 0x0203A958
        val CASES = listOf(
            Case("ram_trace_prologue_attack_noanim", 2070, 2078),
            Case("ram_trace_prologue_kill", 1990, 2004),
            Case("ram_trace_prologue_kill", 2540, 2554),
            Case("ram_trace_prologue_attack", 2020, 2034),
        )
    }
}
