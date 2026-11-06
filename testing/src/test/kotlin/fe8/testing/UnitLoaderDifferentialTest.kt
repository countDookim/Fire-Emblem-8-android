package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
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

    @Test
    @VerifiesPort(
        "LoadUnit", "ClearUnit", "GetFreeUnit", "GetFreeBlueUnit", "UnitInitFromDefinition",
        "UnitLoadStatsFromChracter", "FixROMUnitStructPtr", "UnitLoadSupports", "CharStoreAI",
        // UnitCheckStatCaps, UnitHideIfUnderRoof: run, but no cap clamps and no roof tile here.
        "GetCharacterData",
        "GetClassData", "GetUnitSupporterCount", "GetUnitSupporterInitialExp",
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
        val loader = UnitLoader(table, rom)
        for (n in 0 until 8) {
            val def = DEF_ADDRESS + 20 * n
            val id = loader.loadUnit(def, terrainAt)
            assertEquals(n + 1, id, "slot of def $n")
            // eventscr.c LoadUnit_800F704 (d == 1): US_BIT22, then the definition's position.
            table.setState(id, table.state(id) or (1 shl 22))
            val pos = rom.u16(def + 4)
            table.bytes[table.unitOffset(id) + 0x10] = (pos and 0x3F).toByte()
            table.bytes[table.unitOffset(id) + 0x11] = ((pos shr 6) and 0x3F).toByte()
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

    private companion object {
        const val DEF_ADDRESS = 0x088B3D18
        const val GBM_MAP_TERRAIN = 0x0202E4DC
    }
}
