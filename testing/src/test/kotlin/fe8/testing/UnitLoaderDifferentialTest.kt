package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gbafe.core.rng.RngService
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [UnitLoader] against `ram_trace_newgame_bm`: the Prologue throne-room cutscene's `load_unit2` of
 * `UnitDef_Event_PrologueThroneRoomUnits` (ROM 0x088B3D18, eight blue level 1 units without items
 * or autolevel) takes `gUnitArrayBlue` from empty (`r09518`) to eight units (`r09521`). The event
 * layer's extras (`LoadUnit_800F704` with d == 1: `US_BIT22`, then the definition's x/y) are
 * applied here, outside [UnitLoader].
 */
class UnitLoaderDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_newgame_bm"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u8(addr: Int) = this[addr - UnitTable.EWRAM_BASE].toInt() and 0xFF
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    // Not @VerifiesPort: ClearUnit (the slots are already zero, a skipped clear survives),
    // FixROMUnitStructPtr (no unit has CA_BIT_23), CharStoreAI (all AI bytes are zero).
    @Test
    @VerifiesPort(
        "LoadUnit", "GetFreeUnit", "GetFreeBlueUnit", "UnitInitFromDefinition",
        "UnitLoadStatsFromChracter", "UnitLoadSupports",
        // UnitCheckStatCaps, UnitHideIfUnderRoof: run, but no cap clamps and no roof tile here.
        "GetCharacterData",
        "GetClassData", "GetUnitSupporterCount", "GetUnitSupporterInitialExp",
        // LoadUnit_800F704: b = 0 is a guess (ModifyMoveUnitFlag not ported), so untagged.
    )
    fun `loading the throne room units reproduces the dumped unit structs`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val r = ref.run(run)
        val before = r.region("r09518", "ewram")
        val after = r.region("r09521", "ewram")
        val table = UnitTable(rom).also { it.loadFrom(before) }
        for (id in 1..8) assertTrue(!table.isValid(id), "unit $id empty before")

        val rows = after.u32(GBM_MAP_TERRAIN)
        val terrainAt = { x: Int, y: Int -> after.u8(after.u32(rows + 4 * y) + x) }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        for (n in 0 until 8) {
            val def = DEF_ADDRESS + 20 * n
            // LoadUnit_800F704 as load_unit2 (d), non-silent, b = 0: US_BIT22, x/y := def x/y.
            val id = loader.loadUnitForEvent(def, 0, false, true, terrainAt)
            assertEquals(n + 1, id, "slot of def $n")
        }

        val expected = UnitTable(rom).also { it.loadFrom(after) }
        for (id in 1..8) {
            val o = table.unitOffset(id)
            val want = expected.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE)
            // +0x3C..0x3F is pMapSpriteHandle, set by RefreshUnitSprites (not LoadUnit) between
            // the dumps: LoadUnit leaves it 0 and the game has since put a handle there.
            assertTrue(want.copyOfRange(0x3C, 0x40).any { it.toInt() != 0 }, "unit $id handle")
            want.fill(0, 0x3C, 0x40)
            assertArrayEquals(want, table.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE), "unit $id")
        }
        // Everything after the eight units is untouched and empty.
        val end = table.unitOffset(9)
        assertArrayEquals(
            expected.bytes.copyOfRange(end, UnitTable.TOTAL_SIZE),
            table.bytes.copyOfRange(end, UnitTable.TOTAL_SIZE),
            "units 9.. unchanged",
        )
        for (id in 9..0x3E) assertTrue(!table.isValid(id), "unit $id empty")
        // The terminator entry is a unit with charIndex 0: nothing more to load from the list.
        assertEquals(0, rom.u8(DEF_ADDRESS + 20 * 8))
    }

    /**
     * Loads [defs] with the event layer's extras (`LoadUnit_800F704` with d == 0: x/y := the
     * definition's x/y) onto the units of the "before" dump of run [runName], and returns the
     * resulting unit bytes by slot together with the "after" dump's.
     */
    private fun loadAndDiff(
        runName: String,
        beforeDump: String,
        afterDump: String,
        defs: List<Int>,
        firstSlot: Int,
    ): List<Pair<ByteArray, ByteArray>> {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = RomView.ofRom(romFile.readBytes())
        val r = ref.run(runName)
        val before = r.region(beforeDump, "ewram")
        val after = r.region(afterDump, "ewram")
        val table = UnitTable(rom).also { it.loadFrom(before) }
        val expected = UnitTable(rom).also { it.loadFrom(after) }
        for (slot in firstSlot until firstSlot + defs.size) {
            assertTrue(!table.isValid(slot), "slot $slot free before")
            assertTrue(expected.isValid(slot), "slot $slot loaded after")
        }
        val rows = after.u32(GBM_MAP_TERRAIN)
        val terrainAt = { x: Int, y: Int -> after.u8(after.u32(rows + 4 * y) + x) }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val out = ArrayList<Pair<ByteArray, ByteArray>>()
        for ((n, def) in defs.withIndex()) {
            val id = loader.loadUnit(def, terrainAt)
            assertEquals(firstSlot + n, id, "slot of def $n")
            val pos = rom.u16(def + 4)
            table.bytes[table.unitOffset(id) + 0x10] = (pos and 0x3F).toByte()
            table.bytes[table.unitOffset(id) + 0x11] = ((pos shr 6) and 0x3F).toByte()
            val o = table.unitOffset(id)
            out += expected.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE) to
                table.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE)
        }
        return out
    }

    /**
     * `ram_trace_prologue`: Seth (3 items) and Eirika (1 item) are loaded by `load_unit1` between
     * `f15600` (no blue units) and `f15900`, 300 frames apart. Their `MU` walks them along the
     * REDAs in between, so exactly these bytes are masked:
     * - +0x0C bit 0 (`US_HIDDEN`): muctrl.c hides the unit while its `MU` walks it in.
     * - Seth's +0x11 (yPos): his MU has walked on from the definition's (13, 9) to (13, 8)
     *   (x and Eirika's position equal the definition's).
     * - +0x3C..0x3F (`pMapSpriteHandle`): set by `RefreshUnitSprites`, not by `LoadUnit`.
     * Items, `curHP` and every other byte are compared exactly.
     */
    // Not @VerifiesPort: GetItemIndex (all item ids < 0x80: a dropped mask survives),
    // CharStoreAI (zero AI bytes).
    @Test
    @VerifiesPort(
        "LoadUnit", "UnitInitFromDefinition", "MakeNewItem", "UnitAddItem", "GetItemMaxUses",
        "GetItemData", "UnitLoadStatsFromChracter",
        // GetItemAttributes, GetUnitMaxHp, GetItemHpBonus: no unbreakable item, HP bonus 0 here.
    )
    fun `loading the prologue allies reproduces units with items`() {
        val pairs = loadAndDiff(
            "ram_trace_prologue", "f15600", "f15900", listOf(ALLY_DEF, ALLY_DEF + 20), 1,
        )
        for ((n, p) in pairs.withIndex()) {
            val want = p.first.copyOf()
            val got = p.second.copyOf()
            for (b in intArrayOf(0x3C, 0x3D, 0x3E, 0x3F)) want[b] = 0
            want[0x0C] = (want[0x0C].toInt() and 1.inv()).toByte()
            got[0x0C] = (got[0x0C].toInt() and 1.inv()).toByte()
            if (n == 0) {
                want[0x11] = 0
                got[0x11] = 0
            }
            assertArrayEquals(want, got, "ally $n")
            val items = (0 until 5).map { (p.first[0x1E + 2 * it].toInt() and 0xFF) or
                ((p.first[0x1F + 2 * it].toInt() and 0xFF) shl 8) }
            assertTrue(items[0] != 0, "ally $n has an item")
        }
    }

    /**
     * `ram_trace_prologue`: the messenger (`UnitDef_Event_PrologueMessager`, 3 REDAs) goes into
     * slot 9 between `f09600` and `f09900`. Masked: +0x10/+0x11 (position: walked on by its MU from
     * the definition's (9, 15) to (13, 6)) and +0x3C..0x3F (`pMapSpriteHandle`, set by
     * `RefreshUnitSprites`).
     */
    @Test
    @VerifiesPort("LoadUnit", "UnitInitFromDefinition", "UnitLoadStatsFromChracter")
    fun `loading the prologue messenger reproduces the unit`() {
        val p = loadAndDiff("ram_trace_prologue", "f09600", "f09900", listOf(MESSENGER_DEF), 9)
            .single()
        val want = p.first.copyOf()
        val got = p.second.copyOf()
        for (b in intArrayOf(0x10, 0x11, 0x3C, 0x3D, 0x3E, 0x3F)) {
            want[b] = 0
            got[b] = 0
        }
        assertArrayEquals(want, got, "messenger")
    }

    /**
     * Red units with autolevel from `ram_trace_prologue`: the Grado shamans (`load_unit1`, four
     * units, the last two promoted-class autolevel units) between `r12390` and `r12420`, and the
     * Valter group (Valter plus two autolevel units, level 1, unpromoted: no
     * RNG) between `r13920` and `r13950`. LoadUnit's autolevel draws from the RNG, so the
     * whole list is loaded once per RNG state recorded in `rng.watch.bin` over the window
     * (the state before each step), each time with a fresh
     * [RngService]. The window holds more than one distinct state, so the stat growths have to
     * match for the states in which the load really happened and fail for the later ones.
     *
     * Masked: +0x0C bit 0 (`US_HIDDEN`: the MU hides a unit it walks in) and +0x3C..0x3F
     * (`pMapSpriteHandle`, `RefreshUnitSprites`). Every other byte is compared.
     */
    // Not @VerifiesPort: GetStatIncrease (`>= 100` and `roll1(g + 1)` breaks survive: any
    // state of the window may match, and only exact-boundary rolls differ).
    @Test
    @VerifiesPort(
        "LoadUnit", "UnitAutolevel", "UnitAutolevelCore", "GetAutoleveledStatIncrease",
        "GetCurrentPromotedLevelBonus", "UnitInitFromDefinition", "UnitLoadStatsFromChracter",
    )
    fun `autoleveled red units reproduce the dump for the RNG states of the window`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val r = ref.run("ram_trace_prologue")
        val rng = r.watch("rng")
        val cases = listOf(
            Triple("r12390", "r12420", SHAMANS_DEF),
            Triple("r13920", "r13950", 0x088B3FB8),
        )
        for ((beforeDump, afterDump, def) in cases) {
            val before = r.region(beforeDump, "ewram")
            val after = r.region(afterDump, "ewram")
            val count = generateSequence(0) { it + 1 }.first { rom.u8(def + 20 * it) == 0 }
            var matching = 0
            var failing = 0
            for (step in beforeDump.drop(1).toInt()..afterDump.drop(1).toInt()) {
                val table = UnitTable(rom).also { it.loadFrom(before) }
                val expected = UnitTable(rom).also { it.loadFrom(after) }
                val service = RngService()
                service.primary.load(rng.u16(step, 0), rng.u16(step, 2), rng.u16(step, 4))
                val loader = UnitLoader(table, rom, { 0 }, service, { false })
                var same = true
                for (k in 0 until count) {
                    val id = loader.loadUnit(def + 20 * k) { _, _ -> 0 }
                    val pos = rom.u16(def + 20 * k + 4)
                    table.bytes[table.unitOffset(id) + 0x10] = (pos and 0x3F).toByte()
                    table.bytes[table.unitOffset(id) + 0x11] = ((pos shr 6) and 0x3F).toByte()
                    val o = table.unitOffset(id)
                    for (b in 0 until UnitTable.UNIT_SIZE) {
                        val masked = b in 0x3C..0x3F
                        if (b == 0x0C) {
                            val got = table.bytes[o + b].toInt() or 1
                            if (got != (expected.bytes[o + b].toInt() or 1)) same = false
                        } else if (!masked && table.bytes[o + b] != expected.bytes[o + b]) {
                            same = false
                        }
                    }
                }
                if (same) matching++ else failing++
            }
            assertTrue(matching > 0, "$beforeDump..$afterDump: no RNG state reproduces the dump")
            // The Valter group's autolevel units are level 1 and not promoted: no RNG is drawn.
            if (def == SHAMANS_DEF) {
                assertTrue(failing > 0, "$beforeDump..$afterDump: RNG did not matter")
            }
        }
    }

    // Not @VerifiesPort: UnitAutolevelRealistic (no blue autolevel unit is loaded in the traced
    // runs; breaking it leaves every test here green), UnitApplyBonusLevels and
    // UnitAutolevelPenalty (the traced runs are chapter 0, whose easy / normal malus is 0 and
    // whose red units are not loaded in hard mode, so the level count is 0 and both return at
    // once; the other traces load no units), LoadUnit_800F704 (b is a guess, terrain stubbed).
    private companion object {
        const val DEF_ADDRESS = 0x088B3D18
        const val SHAMANS_DEF = 0x088B3E30
        const val ALLY_DEF = 0x088B3C14
        const val MESSENGER_DEF = 0x088B3DCC
        const val GBM_MAP_TERRAIN = 0x0202E4DC
    }
}
