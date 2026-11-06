package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitLoader.UnitDef
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventConst.EV_STATE_UNITCAM
import fe8.game.event.EventEngineProc
import fe8.game.event.EventState
import fe8.game.event.EventUnitLoads
import fe8.game.event.ScriptMemory
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** [EventUnitLoads] against `ram_trace_newgame_bm` and on small hand-made cases. */
class EventUnitLoadsTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u8(addr: Int) = this[addr - UnitTable.EWRAM_BASE].toInt() and 0xFF
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    private class Setup(
        val rom: RomView,
        val table: UnitTable,
        val state: EventState,
        val words: ShortArray,
        val loads: EventUnitLoads,
    )

    private fun setup(before: ByteArray?, terrainAt: (Int, Int) -> Int = { _, _ -> 0 }): Setup {
        val rom = RomView.ofRom(romFile.readBytes())
        val table = UnitTable(rom)
        if (before != null) table.loadFrom(before)
        val state = EventState()
        val words = ShortArray(8)
        val memory = ScriptMemory { a -> words[(a - SCRIPT) / 2].toInt() and 0xFFFF }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })
        val loads = EventUnitLoads(state, memory, rom, table, loader, RngService(), terrainAt)
        return Setup(rom, table, state, words, loads)
    }

    private fun Setup.command(code: Int, arg: Int, ptr: Int = 0) {
        words[0] = code.toShort()
        words[1] = arg.toShort()
        words[2] = ptr.toShort()
        words[3] = (ptr ushr 16).toShort()
    }

    @Test
    @VerifiesPort(
        "Event2C_LoadUnits", "EventLoadUnitSliently", "GetLoadUnitsAmount",
        // Untagged (one branch only): ShouldUNITBeLoaded (blue, no dead unit),
        // GetUnitDefinitionFromEventScr (chance 0, REDAs kept), LoadUnit_800F704 (terrain
        // stubbed to 0, so the b bit 0 AiGetUnitClosestValidPosition check never runs).
    )
    fun `skipped load_unit2 of the throne room units reproduces the dumped unit structs`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = ref.run("ram_trace_newgame_bm")
        val before = r.region("r09518", "ewram")
        val after = r.region("r09521", "ewram")
        // The throne room is a cutscene without a chapter map: the dumped gBmMapTerrain is stale
        // (non-zero under the units, which would need AiGetUnitClosestValidPosition), so the
        // terrain is taken as empty. No unit stands on a roof tile (see UnitLoaderDifferentialTest).
        val s = setup(before)
        // load_unit2 1 UnitDef_Event_PrologueThroneRoomUnits = EVT2(0x2C, 1), 1, ptr32
        s.command(0x2C41, 1, DEF_ADDRESS)
        val proc = EventEngineProc()
        proc.pEventCurrent = SCRIPT
        proc.evStateBits = EV_STATE_SKIPPING
        proc.unitLoadCount = 0
        proc.chance = 0
        assertEquals(EVC_ADVANCE_YIELD, s.loads.event2cLoadUnits.run(proc))
        assertEquals(0, proc.unitLoadCount)
        assertFalse(proc.disableReda)

        val expected = UnitTable(s.rom).also { it.loadFrom(after) }
        for (id in 1..8) {
            val o = s.table.unitOffset(id)
            val want = expected.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE)
            want.fill(0, 0x3C, 0x40) // pMapSpriteHandle: RefreshUnitSprites, not LoadUnit
            assertArrayEquals(want, s.table.bytes.copyOfRange(o, o + UnitTable.UNIT_SIZE), "$id")
        }
        val end = s.table.unitOffset(9)
        assertArrayEquals(
            expected.bytes.copyOfRange(end, UnitTable.TOTAL_SIZE),
            s.table.bytes.copyOfRange(end, UnitTable.TOTAL_SIZE),
        )
    }

    @Test
    fun `walking load is unsupported`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(null)
        s.command(0x2C40, 1, DEF_ADDRESS)
        val proc = EventEngineProc().also { it.pEventCurrent = SCRIPT }
        assertThrows(UnsupportedOperationException::class.java) {
            s.loads.event2cLoadUnits.run(proc)
        }
    }

    @Test
    fun `config load unit sets count, clamped chance and reda disabling`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(null)
        val proc = EventEngineProc().also { it.pEventCurrent = SCRIPT }
        s.command(0x2B00, 5)
        s.loads.event2bConfigLoadUnit.run(proc)
        assertEquals(5, proc.unitLoadCount)
        s.command(0x2B01, 300)
        s.loads.event2bConfigLoadUnit.run(proc)
        assertEquals(100, proc.chance)
        s.state.slots[2] = -4
        s.command(0x2B01, -1)
        s.loads.event2bConfigLoadUnit.run(proc)
        assertEquals(0, proc.chance)
        s.command(0x2B02, 0)
        s.loads.event2bConfigLoadUnit.run(proc)
        assertTrue(proc.disableReda)
    }

    @Test
    fun `ModifyMoveUnitFlag combines unit camera and 0040 bits`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(null)
        val proc = EventEngineProc()
        assertEquals(0, s.loads.modifyMoveUnitFlag(proc, false))
        assertEquals(1, s.loads.modifyMoveUnitFlag(proc, true))
        proc.evStateBits = EV_STATE_UNITCAM or 0x40
        assertEquals(13, s.loads.modifyMoveUnitFlag(proc, true))
    }

    @Test
    fun `dead blue unit is skipped for parameter 0 and kept for 1`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val after = ref.run("ram_trace_newgame_bm").region("r09521", "ewram")
        val s = setup(after)
        val def = UnitDef(s.rom, DEF_ADDRESS)
        assertTrue(s.loads.shouldUnitBeLoaded(def, 0))
        val id = s.table.getUnitFromCharId(def.charIndex)
        s.table.setState(id, s.table.state(id) or 4)
        assertFalse(s.loads.shouldUnitBeLoaded(def, 0))
        assertTrue(s.loads.shouldUnitBeLoaded(def, 1))
        assertFalse(s.loads.shouldUnitBeLoaded(def, 2))
    }

    @Test
    fun `chance 0 clears every sumFlag keeping order and disableReda drops REDAs`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(null)
        val src = listOf(
            UnitDef(s.rom, DEF_ADDRESS).copy(sumFlag = true, redaCount = 2, redas = 0x08000000),
            UnitDef(s.rom, DEF_ADDRESS + 20),
        )
        val out = s.loads.getUnitDefinitionFromEventScr(src, 2, 0, false, true)
        assertEquals(listOf(src[0].charIndex, src[1].charIndex), out.map { it.charIndex })
        assertTrue(out.all { !it.sumFlag && it.redaCount == 0 && it.redas == 0 })
        val kept = s.loads.getUnitDefinitionFromEventScr(src, 2, 0, false, false)
        assertEquals(2, kept[0].redaCount)
    }

    private companion object {
        const val SCRIPT = 0x0A000000
        const val DEF_ADDRESS = 0x088B3D18
    }
}
