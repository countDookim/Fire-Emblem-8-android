package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackRange
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.MuUnit
import fe8.game.bm.MovementMap
import fe8.game.bm.PathArrow
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.bm.generateBestMovementScript
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The unit's move after the destination is confirmed: `PlayerPhase_DisplayUnitMovement`
 * (`GetMovementScriptFromPath`, `SetAutoMuMoveScript`), the MU walk, `PlayerPhase_WaitForUnitMovement`
 * and `PlayerPhase_ApplyUnitMovement`, against the per-frame RAM of `ram_trace_prologue_select_move`
 * (A confirm f1295, walk f1296..f1314) and `ram_trace_prologue_attack` (confirm f1330, walk
 * f1331..f1365).
 *
 * Like [PlayerPhaseMoveSelectDifferentialTest] each case seeds from f1259, runs the real select
 * (A at f1260, with the [PathArrow] port) and goes on frame by frame. From the frame after the
 * confirm the MU ([Mu], started by `StartMu` at the seed from the dumped unit) runs after the
 * PlayerPhase tree each frame. Compared after every frame: the PlayerPhase proc position, sleep and
 * repeat callback, the unit action data, the 0x120 bytes of the units, from f1296 the MU proc
 * fields (state, facing, step sound clock, move clock, moveConfig, x_q4, y_q4, cam_b) and the
 * `sMuConfig[0]` script cursor, in the frame after the confirm the bytes of `gWorkingMovementScript`
 * up to its HALT and the MU's script, and the unit's x/y and state at the end.
 *
 * Not exercised: a trap or a unit on the path, the event of `StartAfterUnitMovedEvent` (a
 * recorded stand-in), the menu (the recorded hook).
 */
class PlayerPhaseMoveDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "PlayerPhase_DisplayUnitMovement", "PlayerPhase_WaitForUnitMovement",
        "PlayerPhase_ApplyUnitMovement", "GetMovementScriptFromPath", "SetAutoMuMoveScript",
        "SetMuMoveScript", "MuExistsActive",
    )
    fun `move after the confirm in select_move matches the game f1295 to f1314`() {
        val c = run(MvCase("ram_trace_prologue_select_move", 1295, 1314))
        assertTrue(c.muFrames == 19, "$c")
    }

    @Test
    @VerifiesPort(
        "PlayerPhase_DisplayUnitMovement", "PlayerPhase_WaitForUnitMovement",
        "PlayerPhase_ApplyUnitMovement", "GetMovementScriptFromPath", "SetAutoMuMoveScript",
        "SetMuMoveScript", "MuExistsActive",
    )
    fun `move after the confirm in attack matches the game f1330 to f1365`() {
        val c = run(MvCase("ram_trace_prologue_attack", 1330, 1365))
        assertTrue(c.muFrames == 35, "$c")
    }

    /**
     * Not a port verification: no frame calls `GenerateBestMovementScript`, so it is run on the
     * dumped working map (`gWorkingBmMap`) at the confirm frame to the cursor and compared with the
     * script the game made from the path in the frame after (a unique cheapest path, no RNG draw).
     */
    @Test
    fun `best movement script on the dumped working map equals the game script`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = RomView.ofRom(romFile.readBytes())
        for ((name, confirm, cursorX, cursorY) in
            listOf(
                Quad("ram_trace_prologue_select_move", 1295, 6, 5),
                Quad("ram_trace_prologue_attack", 1330, 8, 5),
            )) {
            val run = ref.run(name)
            val at = MvDump(
                run.region(dumpName(run, confirm), "ewram"), run.region(dumpName(run, confirm), "iwram"),
            )
            val next = MvDump(
                run.region(dumpName(run, confirm + 1), "ewram"),
                run.region(dumpName(run, confirm + 1), "iwram"),
            )
            val bm = BmSt().also { it.decode(at.ewram, BM_ST - EWRAM) }
            val playSt = PlaySt().also { it.decode(at.ewram, PLAY_ST - EWRAM) }
            val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
            readPool(at, GWORKING_BM_MAP, map, map.other)
            val out = IntArray(0x40)
            generateBestMovementScript(
                map.other, map.sizeX, map.sizeY, RngService(), cursorX, cursorY, out,
            )
            var n = 0
            while (out[n] != Mu.MOVE_CMD_HALT) n++
            assertTrue(n >= 2, "$name: script length $n")
            for (k in 0..n) {
                assertEquals(next.byte(WORKING_SCRIPT + k), out[k], "$name script+$k")
            }
        }
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: Int)

    private fun dumpName(run: ReferenceData.Run, f: Int): String {
        val full = "f%05d".format(f)
        return if (run.dumps.containsKey(full)) full else "r%05d".format(f)
    }

    private fun run(case: MvCase): MvChecks {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(case.run)
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procsTrace = run.watch("procs")
        fun ew(f: Int) = run.region(dumpName(run, f), "ewram")
        fun iw(f: Int) = run.region(dumpName(run, f), "iwram")
        fun keyStruct(f: Int) =
            ew(f).copyOfRange(KEY_STATUS - EWRAM, KEY_STATUS - EWRAM + KeyStatus.STRUCT_SIZE)

        val seed = 1259
        val seedEwram = ew(seed)
        val seedIwram = iw(seed)
        val playSt = PlaySt().also { it.decode(seedEwram, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(seedEwram, BM_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(seedEwram) }
        val seedDump = MvDump(seedEwram, seedIwram)
        readPool(seedDump, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(seedDump, GBM_MAP_UNIT, map, map.unit)
        val engine = ProcEngine()
        val keys = KeyStatus().also { it.readFrom(keyStruct(seed)) }
        val moves = MovementMap(rom, map, units, playSt)
        val active = ActiveUnit()
        val arrow = PathArrow(map, units, moves, active, bm)
        arrow.hooks.generateBestMovementScript = { _, _, _ ->
            error("${case.run}: the path was re-routed (GenerateBestMovementScript called)")
        }
        val camMove = CamMove(engine, bm)
        val phase = PlayerPhase(
            engine, bm, playSt, map, units, camMove,
            EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
            { false }, active = active, movementMap = moves, pathArrow = arrow,
        )
        phase.active.unit = unitIdAt(units, seedDump.word(GACTIVEUNIT))
        phase.active.unitId = seedDump.byte(GACTIVEUNIT_ID)
        phase.active.moveOriginX = seedDump.half(GACTIVEUNIT_ORIGIN)
        phase.active.moveOriginY = seedDump.half(GACTIVEUNIT_ORIGIN + 2)
        phase.active.action.subjectIndex = seedDump.byte(ACTION_DATA + 0x0C)
        phase.active.action.targetIndex = seedDump.byte(ACTION_DATA + 0x0D)
        phase.active.action.xMove = seedDump.byte(ACTION_DATA + 0x0E)
        phase.active.action.yMove = seedDump.byte(ACTION_DATA + 0x0F)
        phase.active.action.moveCount = seedDump.byte(ACTION_DATA + 0x10)
        phase.active.action.unitActionType = seedDump.byte(ACTION_DATA + 0x11)

        // The MU, as StartMu makes it from the unit (verified by MuStartDifferentialTest).
        val mu = Mu(engine, bm, playSt, keys, camMove.script, MvClassHooks(romBytes))
        val muUnitAddress = ProcArrayDump.ofEwram(seedEwram).find(PROC_SCR_MU)!!.u32(0x2C)
        val eirika = units.getUnitFromCharId(CHARACTER_EIRIKA)
        val eirikaMu = MuUnit(
            address = muUnitAddress,
            xPos = units.xPos(eirika),
            yPos = units.yPos(eirika),
            classId = units.classId(eirika),
            state = units.state(eirika),
            spritePalette = seedIwram[CFG - GbaMem.IWRAM + 1].toInt() and 0xFF,
        )
        val muProc = checkNotNull(mu.startMu(eirikaMu)) { "StartMu" }
        val muState = muProc.state as MuProc

        var frame = seed
        val log = ArrayList<String>()
        val h = phase.hooks
        h.unitSelectEnabled = true
        h.setAutoMuMoveScript = { script ->
            log += "f$frame setAutoMuMoveScript"
            mu.setAutoMuMoveScript(script)
        }
        h.muExistsActive = { mu.muExistsActive() }
        h.startAfterUnitMovedEvent = { log += "f$frame startAfterUnitMovedEvent"; false }
        h.startUnitActionMenu = { log += "f$frame startUnitActionMenu $it" }
        h.endSideWindows = ProcFunc {}
        h.endAllMus = ProcFunc {}
        h.refreshUnitSprites = ProcFunc {}
        h.muExists = ProcBoolFunc { true }
        h.doesBmxFadeExist = ProcBoolFunc { false }
        val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
        val attackRange =
            AttackRange(map, units, ItemInfo(rom), EntityBmMaps(map, units, playSt), loader)
        h.generateAttackRange = { unit ->
            map.bmMapFill(map.other, 0)
            map.bmMapFill(map.range, 0)
            attackRange.generateUnitCompleteAttackRange(unit)
            PlayerPhase.LIMITVIEW_RED or PlayerPhase.LIMITVIEW_BLUE
        }
        h.displayMoveRangeGraphics = { bm.gameStateBits = bm.gameStateBits or 1 }
        h.hideMoveRangeGraphics = ProcFunc {
            bm.gameStateBits = bm.gameStateBits and 3.inv()
        }
        ew(seed).copyInto(arrow.buffer, 0, ARROW - EWRAM + 0x29, ARROW - EWRAM + 0x6C)
        val proc = engine.start(phase.script, 2)
        engine.goto(proc, 9)
        engine.run(engine.root(2))

        val c = MvChecks()
        for (f in seed + 1..case.last) {
            frame = f
            keys.readFrom(keyStruct(f))
            engine.run(engine.root(2))
            val at = "${case.run} f$f"
            val e = ew(f)
            val d = MvDump(e, iw(f))
            val want = ProcArrayDump.ofWatch(procsTrace, f, names)

            if (f > case.confirm) {
                // The MU runs after the PlayerPhase tree (tree 5).
                engine.run(engine.root(5))
                val muDump = want.find("ProcScr_Mu")!!
                assertEquals(muDump.u8(0x3F), muState.state, "$at mu state")
                assertEquals(muDump.u8(0x42), muState.facing, "$at mu facing")
                assertEquals(muDump.u8(0x43), muState.stepSoundClock, "$at step_sound_clock")
                assertEquals(muDump.u16(0x48), muState.moveClockQ4, "$at move_clock_q4")
                assertEquals(muDump.u16(0x4A), muState.moveConfig and 0xFFFF, "$at moveConfig")
                assertEquals(muDump.u16(0x4C), muState.xQ4 and 0xFFFF, "$at x_q4")
                assertEquals(muDump.u16(0x4E), muState.yQ4 and 0xFFFF, "$at y_q4")
                assertEquals(
                    iw(f)[CFG - GbaMem.IWRAM + 4].toInt() and 0xFF, mu.configs[0].pc, "$at pc",
                )
                c.muFrames++
            }
            if (f == case.confirm + 1) {
                // gWorkingMovementScript (0x02033efc) up to its HALT, and the MU's copy.
                val script = arrow.workingMovementScript
                var n = 0
                while (script[n] != Mu.MOVE_CMD_HALT) n++
                for (k in 0..n) {
                    assertEquals(
                        d.byte(WORKING_SCRIPT + k), script[k] and 0xFF, "$at gWorkingMovementScript+$k",
                    )
                    assertEquals(
                        iw(f)[CFG - GbaMem.IWRAM + 5 + k].toInt() and 0xFF, mu.configs[0].movescr[k] and 0xFF,
                        "$at sMuConfig movescr+$k",
                    )
                }
                c.scriptLength = n
            }

            val wantProc = want.find("gProcScr_PlayerPhase")!!
            assertEquals(wantProc.scrCur, proc.scrCur, "$at PlayerPhase script position")
            assertEquals(wantProc.u16(0x24).toShort().toInt(), proc.sleepTime, "$at sleep")
            assertEquals(wantProc.u32(0x0C) != 0, proc.idleCb != null, "$at repeat callback")

            val a = phase.active.action
            assertEquals(d.byte(ACTION_DATA + 0x0E), a.xMove, "$at xMove")
            assertEquals(d.byte(ACTION_DATA + 0x0F), a.yMove, "$at yMove")
            assertEquals(d.byte(ACTION_DATA + 0x10), a.moveCount, "$at moveCount")
            assertEquals(d.byte(ACTION_DATA + 0x11), a.unitActionType, "$at unitActionType")
            for (k in 0 until UnitTable.TOTAL_SIZE) {
                // pMapSpriteHandle (0x3C): RefreshUnitSprites is a stand-in here.
                if (k % UnitTable.UNIT_SIZE in 0x3C..0x3F) continue
                assertEquals(
                    e[UnitTable.BLUE_ADDRESS - EWRAM + k], units.bytes[k],
                    "$at unit byte 0x${k.toString(16)}",
                )
            }
            c.frames++
        }
        // The destination: the unit stands there with the same state as in the dump.
        val unit = phase.active.unit
        val last = ew(case.last)
        val o = UnitTable.BLUE_ADDRESS - EWRAM + units.unitOffset(unit)
        assertEquals(last[o + 0x10].toInt(), units.xPos(unit), "unit x at the end")
        assertEquals(last[o + 0x11].toInt(), units.yPos(unit), "unit y at the end")
        assertEquals(
            GbaMem.u32(last, EWRAM, UnitTable.BLUE_ADDRESS + units.unitOffset(unit) + 0x0C),
            units.state(unit), "unit state at the end",
        )
        assertTrue(log.count { it.endsWith("startUnitActionMenu ${bm.cursorTargetX - bm.cameraX}") } == 1, "$log")
        println("PlayerPhaseMove ${case.run}: $c")
        return c
    }

    private fun unitIdAt(units: UnitTable, address: Int): Int {
        if (address == 0) return 0
        val offset = address - UnitTable.BLUE_ADDRESS
        return units.bytes[offset + 0x0B].toInt() and 0xFF
    }

    private fun readPool(dump: MvDump, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = dump.rowPointer(handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = dump.byte(first + (y + 2) * (map.sizeX + 2) + x)
            }
        }
    }

    private companion object {
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_STATUS = 0x02024CC0
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val GACTIVEUNIT_ID = 0x0202BE44
        const val GACTIVEUNIT_ORIGIN = 0x0202BE48
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val CHARACTER_EIRIKA = 0x01
        const val GWORKING_BM_MAP = 0x030049A0
        const val ARROW = 0x0203A990
        const val WORKING_SCRIPT = 0x02033EFC
        const val PROC_SCR_MU = 0x089A2C48
        const val CFG = 0x03001900
    }
}

private class MvCase(val run: String, val confirm: Int, val last: Int)

private class MvChecks {
    var frames = 0
    var muFrames = 0
    var scriptLength = 0

    override fun toString() = "frames=$frames muFrames=$muFrames scriptLength=$scriptLength"
}

private const val EWRAM = 0x02000000
private const val IWRAM = 0x03000000

/** The EWRAM and IWRAM of one dump, addressed with GBA addresses. */
private class MvDump(val ewram: ByteArray, val iwram: ByteArray) {
    fun byte(address: Int): Int =
        if (address >= IWRAM) {
            iwram[address - IWRAM].toInt() and 0xFF
        } else {
            ewram[address - EWRAM].toInt() and 0xFF
        }

    fun half(address: Int): Int = (byte(address) or (byte(address + 1) shl 8)).toShort().toInt()

    fun word(address: Int): Int = byte(address) or (byte(address + 1) shl 8) or
        (byte(address + 2) shl 16) or (byte(address + 3) shl 24)

    fun rowPointer(handle: Int, row: Int): Int = word(word(handle) + 4 * row)
}

/** Class data from the ROM (read at test time, not copied). */
private class MvClassHooks(private val rom: ByteArray) : MuHooks {
    private fun classByte(jid: Int, o: Int): Int =
        rom[GCLASS_DATA - ROM_BASE + (jid - 1) * CLASS_SIZE + o].toInt() and 0xFF

    override fun classAttributes(jid: Int): Int = classByte(jid, 0x28) or
        (classByte(jid, 0x29) shl 8) or (classByte(jid, 0x2A) shl 16) or
        (classByte(jid, 0x2B) shl 24)

    override fun classSlowWalking(jid: Int): Int = classByte(jid, 0x07)

    private companion object {
        const val GCLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val ROM_BASE = 0x08000000
    }
}
