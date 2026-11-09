package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitLoader.UnitDef
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_0040
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventConst.EV_STATE_UNITCAM
import fe8.game.event.EventEngineProc
import fe8.game.event.EventState
import fe8.game.event.EventUnitLoads
import fe8.game.event.EventUnitLoadsHost
import fe8.game.event.ScriptMemory
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** [EventUnitLoads] against `ram_trace_newgame_bm`, `ram_trace_prologue` and ROM ground truth. */
class EventUnitLoadsTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u8(addr: Int) = this[addr - UnitTable.EWRAM_BASE].toInt() and 0xFF
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    private fun setup(
        before: ByteArray?,
        terrainAt: (Int, Int) -> Int = { _, _ -> 0 },
        unitAt: (Int, Int) -> Int = { _, _ -> 0 },
        host: EventUnitLoadsHost = object : EventUnitLoadsHost {},
    ): Setup {
        val rom = RomView.ofRom(romFile.readBytes())
        val table = UnitTable(rom)
        if (before != null) table.loadFrom(before)
        val state = EventState()
        val words = ShortArray(8)
        val memory = ScriptMemory { a -> words[(a - SCRIPT) / 2].toInt() and 0xFFFF }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false }).also {
            it.chapterLevelBonus = { 0 }
            it.startDefinedMove = { _, _, _, _ -> }
        }
        val loads = EventUnitLoads(
            state, memory, rom, table, loader, RngService(), terrainAt, unitAt, {}, host,
        )
        return Setup(rom, table, state, words, loads, loader)
    }

    private fun Setup.command(code: Int, arg: Int, ptr: Int = 0) {
        words[0] = code.toShort()
        words[1] = arg.toShort()
        words[2] = ptr.toShort()
        words[3] = (ptr ushr 16).toShort()
    }

    @Test
    @VerifiesPort("Event2C_LoadUnits", "EventLoadUnitSliently")
    fun `skipped load_unit2 of the throne room units reproduces the dumped unit structs`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = ref.run("ram_trace_newgame_bm")
        val before = r.region("r09518", "ewram")
        val after = r.region("r09521", "ewram")
        // The throne room is a cutscene without a chapter map: the dumped gBmMapTerrain is stale
        // (non-zero under the units, which would need AiGetUnitClosestValidPosition), so the
        // terrain is taken as empty. No unit stands on a roof tile (see
        // UnitLoaderDifferentialTest).
        val s = setup(before)
        // load_unit2 1 UnitDef_Event_PrologueThroneRoomUnits = EVT2(0x2C, 1), 1, ptr32
        s.command(0x2C41, 1, DEF_ADDRESS)
        val proc = EventEngineProc()
        proc.pEventCurrent = SCRIPT
        proc.evStateBits = EV_STATE_SKIPPING
        proc.unitLoadCount = 0 // 0: count the definitions (GetLoadUnitsAmount)
        // No definition has sumFlag or REDAs, so a non-zero chance and disableReda load the same
        // units and make the resets below observable.
        proc.chance = 50
        proc.disableReda = true
        assertEquals(EVC_ADVANCE_YIELD, s.loads.event2cLoadUnits.run(proc))
        assertEquals(0, proc.chance)
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
    @VerifiesPort("GetLoadUnitsAmount")
    fun `GetLoadUnitsAmount matches definition block counts across the ROM`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(null)
        // (address, expectedCount) from fireemblem8.nm.txt
        val cases = listOf(
            0x088B3C14 to 2, // UnitDef_Event_PrologueAlly
            0x088B3C50 to 3, // UnitDef_Event_PrologueEnemy
            0x088B3D18 to 8, // UnitDef_Event_PrologueThroneRoomUnits
            0x088B3DCC to 1, // UnitDef_Event_PrologueMessager
            0x088B3DF4 to 2, // UnitDef_Event_PrologueGradoRoyals
            0x088B3E30 to 4, // UnitDef_Event_PrologueGradoShamans
            0x088B3E94 to 6, // UnitDef_Event_PrologueGradoCavalry
            0x088B3FB8 to 3, // UnitDef_Event_PrologueValterGroup
        )
        for ((addr, want) in cases) {
            val count = s.loads.getLoadUnitsAmount(addr)
            assertEquals(want, count, "unit count at 0x${addr.toString(16)}")
            // Ground truth check: each of the want entries has non-zero charIndex, and the
            // terminator entry at +20*want has charIndex == 0.
            for (i in 0 until want) {
                assertTrue(
                    s.rom.u8(addr + 20 * i) != 0,
                    "entry $i at 0x${addr.toString(16)} non-zero",
                )
            }
            assertEquals(0, s.rom.u8(addr + 20 * want), "terminator at 0x${addr.toString(16)}")
        }
    }

    @Test
    fun `Event2B_ConfigLoadUnit executes real ROM event script commands`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val table = UnitTable(rom)
        val state = EventState()
        val memory = ScriptMemory { a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false }).also {
            it.chapterLevelBonus = { 0 }
            it.startDefinedMove = { _, _, _, _ -> }
        }
        val loads = EventUnitLoads(state, memory, rom, table, loader, RngService(), { _, _ -> 0 })

        // 1. EventScr_ConfigHardModeLoadUnitHard at 0x089EE828:
        //    offset 6 words (0x089EE834) is 0x2B20, 0xFFFF (-1, uses slot 2).
        val proc = EventEngineProc()
        proc.pEventCurrent = 0x089EE834
        state.slots[2] = 7
        assertEquals(EVC_ADVANCE_CONTINUE, loads.event2bConfigLoadUnit.run(proc))
        assertEquals(7, proc.unitLoadCount)

        //    offset 12 words (0x089EE840) is 0x2B20, 0x0000 (sets count to 0).
        proc.pEventCurrent = 0x089EE840
        assertEquals(EVC_ADVANCE_CONTINUE, loads.event2bConfigLoadUnit.run(proc))
        assertEquals(0, proc.unitLoadCount)

        // 2. EventScr_9EE84C at 0x089EE84C:
        //    offset 74 words (0x089EE8E0) is 0x2B21, 0xFFFF (sets chance from slot 2).
        proc.pEventCurrent = 0x089EE8E0
        state.slots[2] = 42
        assertEquals(EVC_ADVANCE_CONTINUE, loads.event2bConfigLoadUnit.run(proc))
        assertEquals(42, proc.chance)

        // Clamping to 0..100
        state.slots[2] = 250
        assertEquals(EVC_ADVANCE_CONTINUE, loads.event2bConfigLoadUnit.run(proc))
        assertEquals(100, proc.chance)

        state.slots[2] = -15
        assertEquals(EVC_ADVANCE_CONTINUE, loads.event2bConfigLoadUnit.run(proc))
        assertEquals(0, proc.chance)

        // 3. Subcode 2 (disableReda)
        val s = setup(null)
        s.command(0x2B02, 0)
        proc.pEventCurrent = SCRIPT
        proc.disableReda = false
        assertEquals(EVC_ADVANCE_CONTINUE, s.loads.event2bConfigLoadUnit.run(proc))
        assertTrue(proc.disableReda)
    }

    @Test
    fun `ModifyMoveUnitFlag matches bit combinations across trace proc states`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val trace = run.watch("procs")
        val s = setup(null)

        // Check real proc states in ram_trace_prologue:
        // Step 12403 has StdEventEngine with evStateBits == 0x0002.
        val dump12403 = ProcArrayDump.ofWatch(trace, 12403)
        val engine12403 = dump12403.find(0x08591AC0)
        assertNotNull(engine12403, "engine proc at step 12403")
        val proc = EventEngineProc()
        proc.evStateBits = engine12403!!.u16(0x3C)
        assertEquals(2, proc.evStateBits)
        assertEquals(1, s.loads.modifyMoveUnitFlag(proc, true))
        assertEquals(0, s.loads.modifyMoveUnitFlag(proc, false))

        // State with EV_STATE_0040 (e.g. 0x0072): bit 3 (8) is set
        proc.evStateBits = 0x0072
        assertEquals(9, s.loads.modifyMoveUnitFlag(proc, true))
        assertEquals(8, s.loads.modifyMoveUnitFlag(proc, false))

        // State with EV_STATE_UNITCAM (0x0200): bit 2 (4) is set
        proc.evStateBits = EV_STATE_UNITCAM
        assertEquals(5, s.loads.modifyMoveUnitFlag(proc, true))
        assertEquals(4, s.loads.modifyMoveUnitFlag(proc, false))

        // Both EV_STATE_UNITCAM and EV_STATE_0040
        proc.evStateBits = EV_STATE_UNITCAM or EV_STATE_0040
        assertEquals(13, s.loads.modifyMoveUnitFlag(proc, true))
        assertEquals(12, s.loads.modifyMoveUnitFlag(proc, false))
    }

    @Test
    @VerifiesPort("IsSethLArachelMyrrhInnes")
    fun `IsSethLArachelMyrrhInnes matches the ROM character list table`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val s = setup(null)
        // gPidList_SethLArachelMyrrhInnes is at ROM 0x089ED674
        val romAddr = 0x089ED674
        val romList = mutableListOf<Int>()
        var offset = 0
        while (true) {
            val pid = s.rom.u8(romAddr + offset++)
            if (pid == 0) break
            romList.add(pid)
        }
        assertEquals(listOf(0x02, 0x19, 0x0B, 0x1E), romList)

        for (pid in 0..255) {
            val expected = pid in romList
            val actual = s.loads.isSethLArachelMyrrhInnes(pid)
            assertEquals(expected, actual, "pid 0x${pid.toString(16)}")
        }
    }

    @Test
    fun `IsUnitSlotAvailable reflects slot occupancy in UnitTable from trace`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = ref.run("ram_trace_newgame_bm")
        val before = r.region("r09518", "ewram")
        val after = r.region("r09521", "ewram")

        val sBefore = setup(before)
        assertTrue(sBefore.loads.isUnitSlotAvailable(0), "blue slots available before load")
        assertTrue(sBefore.loads.isUnitSlotAvailable(0x80), "red slots available before load")

        val sAfter = setup(after)
        // 8 blue units loaded into slots 1..8; slots 9..0x3F still available
        assertTrue(sAfter.loads.isUnitSlotAvailable(0), "blue slots still available after 8 loads")
        assertTrue(sAfter.loads.isUnitSlotAvailable(0x80), "red slots available")

        // Fill all allocated red slots (0x81..0xB2 in UnitTable)
        for (slot in 0x81..0xB2) {
            val o = sAfter.table.unitOffset(slot)
            sAfter.table.bytes[o] = 1
        }
        assertFalse(sAfter.loads.isUnitSlotAvailable(0x80), "red slots full")
    }

    @Test
    fun `ShouldUNITBeLoaded respects allegiance, dead units, Seth exemption and red slots`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        var mapUnit = 0
        val s = setup(
            ref.run("ram_trace_newgame_bm").region("r09521", "ewram"),
            unitAt = { _, _ -> mapUnit },
        )

        val eirikaDef = UnitDef(s.rom, DEF_ADDRESS + 20) // Eirika (charIndex = 1, blue)
        val sethDef = UnitDef(s.rom, DEF_ADDRESS + 40)   // Seth (charIndex = 2, blue)
        assertEquals(1, eirikaDef.charIndex)
        assertEquals(2, sethDef.charIndex)

        // Alive blue units are loaded for all parameters
        assertTrue(s.loads.shouldUnitBeLoaded(eirikaDef, 0))
        assertTrue(s.loads.shouldUnitBeLoaded(eirikaDef, 1))
        assertTrue(s.loads.shouldUnitBeLoaded(eirikaDef, 2))

        // Mark Eirika dead (US_DEAD = 4)
        val eirikaId = s.table.getUnitFromCharId(1)
        s.table.setState(eirikaId, s.table.state(eirikaId) or 4)
        assertFalse(s.loads.shouldUnitBeLoaded(eirikaDef, 0), "dead Eirika skipped for param 0")
        assertTrue(s.loads.shouldUnitBeLoaded(eirikaDef, 1), "dead Eirika loaded for param 1")
        assertFalse(s.loads.shouldUnitBeLoaded(eirikaDef, 2), "dead Eirika skipped for param 2")

        // Mark Seth dead (US_DEAD = 4)
        val sethId = s.table.getUnitFromCharId(2)
        s.table.setState(sethId, s.table.state(sethId) or 4)
        assertFalse(s.loads.shouldUnitBeLoaded(sethDef, 0), "dead Seth skipped for param 0")
        assertTrue(s.loads.shouldUnitBeLoaded(sethDef, 1), "dead Seth loaded for param 1")
        assertTrue(s.loads.shouldUnitBeLoaded(sethDef, 2), "dead Seth kept for param 2 (exemption)")

        // Green unit (allegiance 1) is always loaded
        val greenDef = eirikaDef.copy(allegiance = 1)
        assertTrue(s.loads.shouldUnitBeLoaded(greenDef, 0))

        // Red unit (allegiance 2)
        val redDef = eirikaDef.copy(allegiance = 2, sumFlag = false, redaCount = 0)
        mapUnit = 0
        assertTrue(s.loads.shouldUnitBeLoaded(redDef, 0), "red on empty tile with free red slots")

        mapUnit = 5 // occupied tile
        assertFalse(
            s.loads.shouldUnitBeLoaded(redDef, 0),
            "red on occupied tile without sumFlag/redas",
        )
        assertTrue(
            s.loads.shouldUnitBeLoaded(redDef.copy(sumFlag = true), 0),
            "sumFlag ignores tile",
        )
        assertTrue(
            s.loads.shouldUnitBeLoaded(redDef.copy(redaCount = 1), 0),
            "redaCount ignores tile",
        )

        // When red slots are full
        mapUnit = 0
        for (slot in 0x81..0xB2) {
            val o = s.table.unitOffset(slot)
            s.table.bytes[o] = 1
        }
        assertFalse(s.loads.shouldUnitBeLoaded(redDef, 0), "red rejected when no red slot free")
    }

    @Test
    fun `TryPrepareEventUnitMovement checks unitcam, camera moving and MU capacity`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        var camMoving = false
        var cameraEnsured = false
        var muAvailable = true
        val host = object : EventUnitLoadsHost {
            override fun camMoveExists(): Boolean = camMoving
            override fun ensureCameraOntoPosition(
                proc: EventEngineProc,
                x: Int,
                y: Int,
            ): Boolean = cameraEnsured
            override fun canStartMu(): Boolean = muAvailable
        }
        val s = setup(null, host = host)
        val proc = EventEngineProc()

        // Without EV_STATE_UNITCAM: only canStartMu matters
        proc.evStateBits = 2
        assertTrue(s.loads.tryPrepareEventUnitMovement(proc, 10, 10))
        muAvailable = false
        assertFalse(s.loads.tryPrepareEventUnitMovement(proc, 10, 10))

        // With EV_STATE_UNITCAM
        proc.evStateBits = EV_STATE_UNITCAM
        muAvailable = true
        camMoving = true
        assertFalse(s.loads.tryPrepareEventUnitMovement(proc, 10, 10), "camera moving blocks")

        camMoving = false
        cameraEnsured = true
        assertFalse(s.loads.tryPrepareEventUnitMovement(proc, 10, 10), "camera ensure blocks")

        cameraEnsured = false
        assertTrue(s.loads.tryPrepareEventUnitMovement(proc, 10, 10), "all clear allows movement")
    }

    @Test
    fun `EventLoadUnitWithMovement pauses and resumes walking load across trace frame sequence`() {
        assumeTrue(romFile.isFile, "ROM not present")
        var muCapacity = 4
        var activeMus = 0
        val host = object : EventUnitLoadsHost {
            override fun canStartMu(): Boolean = activeMus < muCapacity
        }
        val s = setup(null, host = host)
        s.loader.startDefinedMove = { _, _, _, _ -> activeMus++ }
        // Grado Cavalry at 0x088B3E94 has 6 units
        val cavalryAddr = 0x088B3E94
        val totalUnits = s.loads.getLoadUnitsAmount(cavalryAddr)
        assertEquals(6, totalUnits)

        s.command(0x2C40, 1, cavalryAddr)
        val proc = EventEngineProc().also { it.pEventCurrent = SCRIPT }

        // Start walking load
        s.loads.event2cLoadUnits.run(proc)
        // 4 units started walking, reaching muCapacity=4; 2 units remain
        assertEquals(2, proc.unitLoadCount)
        assertNotNull(proc.callback)

        // Repeated tick while MUs are still busy keeps pausing
        s.loads.eventLoadUnitWithMovement(proc)
        assertEquals(2, proc.unitLoadCount)
        assertNotNull(proc.callback)

        // MUs finish walking; host has capacity again
        activeMus = 0
        muCapacity = 10
        s.loads.eventLoadUnitWithMovement(proc)
        assertEquals(0, proc.unitLoadCount)
        assertEquals(null, proc.callback)
    }

    @Test
    @VerifiesPort("GetUnitDefinitionFromEventScr")
    fun `GetUnitDefinitionFromEventScr matches dumped gLoadUnitBuffer in trace`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = setup(null)
        val source = List(8) { UnitDef(s.rom, DEF_ADDRESS + 20 * it) }
        val out = s.loads.getUnitDefinitionFromEventScr(source, 8, 0, false, false)
        assertEquals(8, out.size)

        // Compare against gLoadUnitBuffer dumped in r09521 at EWRAM 0x0203EFB8
        val ewram = ref.run("ram_trace_newgame_bm").region("r09521", "ewram")
        val bufferOffset = 0x0203EFB8 - 0x02000000
        for (i in 0 until 8) {
            val uDef = out[i]
            val off = bufferOffset + 20 * i
            val wantChar = ewram[off].toInt() and 0xFF
            val wantClass = ewram[off + 1].toInt() and 0xFF
            assertEquals(wantChar, uDef.charIndex, "unit $i charIndex")
            assertEquals(wantClass, uDef.classIndex, "unit $i classIndex")
            assertEquals(false, uDef.sumFlag, "unit $i sumFlag false")
        }

        // Test disableReda clears redaCount and redas
        val srcWithRedas = listOf(
            source[0].copy(redaCount = 3, redas = 0x08800000),
            source[1],
        )
        val stripped = s.loads.getUnitDefinitionFromEventScr(srcWithRedas, 2, 0, false, true)
        assertEquals(0, stripped[0].redaCount)
        assertEquals(0, stripped[0].redas)

        // Test summon probability partitioning
        val rng = RngService()
        val summonLoads = EventUnitLoads(
            s.state, ScriptMemory { 0 }, s.rom, s.table,
            UnitLoader(s.table, s.rom, { 0 }, rng, { false }), rng, { _, _ -> 0 },
        )
        val mixedSource = listOf(
            source[0].copy(sumFlag = true),
            source[1].copy(sumFlag = false),
            source[2].copy(sumFlag = true),
            source[3].copy(sumFlag = false),
        )
        val partitioned =
            summonLoads.getUnitDefinitionFromEventScr(mixedSource, 4, 50, false, false)
        assertEquals(4, partitioned.size)
        // With chance=50, (2 * 50 + 50) / 100 = 1 chosen summon unit moved to the end with
        // sumFlag=true
        val sumCount = partitioned.count { it.sumFlag }
        assertEquals(1, sumCount)
        assertTrue(partitioned.last().sumFlag, "chosen summon flag placed at the end")
    }

    private class Setup(
        val rom: RomView,
        val table: UnitTable,
        val state: EventState,
        val words: ShortArray,
        val loads: EventUnitLoads,
        val loader: UnitLoader,
    )

    private companion object {
        const val SCRIPT = 0x0A000000
        const val DEF_ADDRESS = 0x088B3D18
    }
}
