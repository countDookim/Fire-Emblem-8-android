package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Original calls in frames 4181..4274. Each helper has only one observed return value, so
 * constant-return implementations survive and these checks do not verify the ports.
 */
class BattleItemHelpersDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    @Test
    fun `tracing preserves the original input and RAM`() {
        assumeTrue(ref.available, "reference data not present")
        val traced = ref.run("ram_trace_battle_item_helpers")
        val original = ref.run("ram_trace_prologue_clear")
        assertArrayEquals(original.inputs.copyOf(traced.inputs.size), traced.inputs)
        for ((checkpoint, originalDump) in listOf(
            "checkpoint4180" to "f04180",
            "checkpoint4274" to "r04274",
        )) {
            for (region in listOf("ewram", "iwram")) {
                assertArrayEquals(
                    original.region(originalDump, region), traced.region(checkpoint, region),
                )
            }
        }
    }

    @Test
    fun `item helper returns match captured calls`() {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val runName = "ram_trace_battle_item_helpers"
        val calls = File(ref.root, "$runName/calls.txt").readLines().map { it.split(" ") }
        val entries = calls.filter { it[0].endsWith("_entry") }
        assertEquals(27, entries.size)
        val rom = RomView.ofRom(romFile.readBytes())
        val table = UnitTable(rom)
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val battle = BmBattle(rom, table, loader, PlaySt(), BmSt()) { _, _ -> 0 }
        val run = ref.run(runName)
        for (entry in entries) {
            val name = entry[0]
            val exitName = name.removeSuffix("_entry") + "_exit"
            val exit = calls.single { it[0] == exitName }
            val expected = exit[3].toLong(16).toInt()
            val arg0 = entry[3].toLong(16).toInt()
            val arg1 = entry[4].toLong(16).toInt()
            val bytes = run.region(name, "ewram")
            val actual = when {
                name.startsWith("call_slot_") ->
                    battle.getUnitItemSlot(bytes, arg0 - EWRAM, arg1)
                name.startsWith("call_itemeff_") ->
                    if (battle.isItemEffectiveAgainst(arg0, bytes, arg1 - EWRAM)) 1 else 0
                name.startsWith("call_uniteff_") ->
                    if (battle.isUnitEffectiveAgainst(bytes, arg0 - EWRAM, bytes, arg1 - EWRAM)) {
                        1
                    } else {
                        0
                    }
                name.startsWith("call_range_") ->
                    if (battle.isItemCoveringRange(arg0, arg1)) 1 else 0
                else -> error("Unexpected call: $name")
            }
            assertEquals(expected, actual, name)
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
    }
}
