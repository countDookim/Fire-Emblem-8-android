package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerPhase
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
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

    // Not @VerifiesPort: sUnknown_0203A60C is already 0 before and after f3804 (UnitBeginAction)
    // in ram_trace_prologue_clear and is never written non-zero in the decomp, so a no-op
    // survives against the capture.
    @Test
    fun `sub802C334 zeroes battleScratch at prologue_clear r03804 and in unitBeginAction`() {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val clear = ref.run("ram_trace_prologue_clear")
        val r3804 = clear.region("r03804", "ewram")
        val scrOff = SUNKNOWN_0203A60C - EWRAM
        assertEquals(0, r3804[scrOff].toInt() and 0xFF)
        assertEquals(0, r3804[scrOff + 1].toInt() and 0xFF)
        assertEquals(0, r3804[scrOff + 2].toInt() and 0xFF)

        val rom = RomView.ofRom(romFile.readBytes())
        val table = UnitTable(rom)
        val playSt = PlaySt()
        val bmSt = BmSt()
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt) { _, _ -> 0 }
        battle.battleScratch.unk00 = 0x12
        battle.battleScratch.unk01 = 0x34
        battle.battleScratch.unk02 = 0x56

        val map = BmMap(rom, ChapterData(rom), bmSt).also { it.initChapterMap(0) }
        val active = ActiveUnit()
        val phase = PlayerPhase(
            ProcEngine(), bmSt, playSt, map, table,
            fe8.game.bm.CamMove(ProcEngine(), bmSt),
            EntityBmMaps(map, table, playSt),
            fe8.game.bm.BmMapRender(bmSt, map, gbafe.render.BgHardware()),
            KeyStatus(), { false }, active = active,
        )
        phase.hooks.resetBattleScratch = { battle.sub802C334() }
        // Seed a minimal valid red unit at slot 0x81 (first red slot) as in f3804.
        r3804.copyInto(table.bytes, 0, UNIT_BLUE - EWRAM, UNIT_BLUE - EWRAM + UnitTable.TOTAL_SIZE)
        phase.unitBeginAction(0x81)

        assertEquals(r3804[scrOff].toInt() and 0xFF, battle.battleScratch.unk00)
        assertEquals(r3804[scrOff + 1].toInt() and 0xFF, battle.battleScratch.unk01)
        assertEquals(r3804[scrOff + 2].toInt() and 0xFF, battle.battleScratch.unk02)
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val SUNKNOWN_0203A60C = 0x0203A60C
        const val UNIT_BLUE = 0x0202BE4C
    }
}
